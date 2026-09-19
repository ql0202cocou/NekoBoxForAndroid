package libcore

import (
	"io"
	"os"
	"path/filepath"
	"sync"
	"time"

	"github.com/sagernet/sing-box/option"
)

// Loading one geo rule-set walks the whole database, so repeated loads of the
// same key (several rule-sets referencing it, box rebuilds on profile switch
// or URL tests) each paid a full scan plus file open. geoCache keeps one
// shared resource and the per-key results of keys actually used; only a newly
// referenced key costs a load. Assets updates replace the database while the
// process is alive, so everything is keyed to the file's path/size/mtime and
// rebuilt when it changes. The mutex doubles as singleflight: concurrent
// first loads of different keys serialize instead of scanning in parallel.
//
// The resource is whatever a query needs (a reader); the closer is the handle
// closed on invalidation, which may be the resource itself or a separate
// underlying file. A nil closer marks the cache as empty.
type geoCache[T any] struct {
	sync.Mutex
	state geoState[T]

	dbName    string
	open      func(path string) (resource T, closer io.Closer, err error)
	normalize func(key string) string // nil keeps the key as-is
	load      func(resource T, key string) ([]option.HeadlessRule, error)
}

// geoState is everything a database swap invalidates, grouped so that
// dropping it is one assignment instead of a field-by-field reset.
type geoState[T any] struct {
	path     string
	size     int64
	modTime  time.Time
	resource T
	closer   io.Closer
	results  map[string][]option.HeadlessRule
}

func (c *geoCache[T]) rules(key string) ([]option.HeadlessRule, error) {
	c.Lock()
	defer c.Unlock()

	path := filepath.Join(externalAssetsDir(), c.dbName)
	stat, err := os.Stat(path)
	if err != nil {
		return nil, err
	}
	if c.state.closer == nil || c.state.path != path ||
		c.state.size != stat.Size() || !c.state.modTime.Equal(stat.ModTime()) {
		if c.state.closer != nil {
			c.state.closer.Close()
		}
		c.state = geoState[T]{}
		resource, closer, err := c.open(path)
		if err != nil {
			return nil, err
		}
		c.state = geoState[T]{
			path:     path,
			size:     stat.Size(),
			modTime:  stat.ModTime(),
			resource: resource,
			closer:   closer,
			results:  make(map[string][]option.HeadlessRule),
		}
	}

	if c.normalize != nil {
		key = c.normalize(key)
	}
	if rules, loaded := c.state.results[key]; loaded {
		return rules, nil
	}
	rules, err := c.load(c.state.resource, key)
	if err != nil {
		// Failures (unknown key) are not cached: they stay errors on every
		// call, like before.
		return nil, err
	}
	c.state.results[key] = rules
	return rules, nil
}
