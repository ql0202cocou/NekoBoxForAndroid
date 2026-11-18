package libcore

import (
	"io"
	"os"
	"path/filepath"
	"sync"

	"github.com/sagernet/sing-box/option"
)

// geo 规则缓存共享 reader 和已查询键的结果，避免切换节点或测速时反复扫描数据库。
// 资产发布会原子替换文件；除路径、大小、完整修改时间外，还比较文件身份，避免保留元数据
// 的替换继续使用旧 reader。锁同时合并首次并发加载，避免重复扫描。
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
	fileInfo os.FileInfo // 大小、修改时间与文件身份都从这次 Stat 比较
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
	if cached := c.state.fileInfo; c.state.closer == nil || c.state.path != path ||
		cached.Size() != stat.Size() || !cached.ModTime().Equal(stat.ModTime()) ||
		!os.SameFile(cached, stat) {
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
			fileInfo: stat,
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
