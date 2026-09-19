package libcore

import (
	"os"
	"path/filepath"
	"reflect"
	"sync"
	"testing"
	"time"

	"github.com/sagernet/sing-box/option"
)

// geoCacheTestDriver holds the per-database specifics the shared cache test
// bodies are parameterized over.
type geoCacheTestDriver[T comparable] struct {
	cache       *geoCache[T]
	load        func(string) ([]option.HeadlessRule, error)
	noun        string // "country" / "code", used in failure messages
	plural      string
	envVar      string
	dbName      string
	skipMessage string
	badKey      string
	reloadKey   func(key string) string // key variant that must hit the cache; nil reloads the key as-is
	checkRules  func(t *testing.T, rules []option.HeadlessRule)
}

// setup points the cache at a temp copy of the fixture and restores all
// package state afterwards.
func (d *geoCacheTestDriver[T]) setup(t *testing.T) string {
	t.Helper()
	fixture := os.Getenv(d.envVar)
	if fixture == "" {
		t.Skip(d.skipMessage)
	}
	data, err := os.ReadFile(fixture)
	if err != nil {
		t.Fatal(err)
	}
	dir := t.TempDir()
	if err := os.WriteFile(filepath.Join(dir, d.dbName), data, 0o644); err != nil {
		t.Fatal(err)
	}

	oldAssetsPath := externalAssetsDir()
	d.cache.Lock()
	old := d.cache.state
	d.cache.state = geoState[T]{}
	d.cache.Unlock()
	externalAssetsPath.Store(dir)
	t.Cleanup(func() {
		externalAssetsPath.Store(oldAssetsPath)
		d.cache.Lock()
		defer d.cache.Unlock()
		if d.cache.state.closer != nil && d.cache.state.closer != old.closer {
			d.cache.state.closer.Close()
		}
		d.cache.state = old
	})
	return dir
}

// testHit loads key, then a variant of it, and checks the second load is
// served from the cache (identical slice) and only one entry is cached.
func (d *geoCacheTestDriver[T]) testHit(t *testing.T, key string) {
	t.Helper()
	rules, err := d.load(key)
	if err != nil {
		t.Fatal(err)
	}
	if len(rules) != 1 {
		t.Fatal("empty rules")
	}
	d.checkRules(t, rules)
	reloadKey := key
	if d.reloadKey != nil {
		reloadKey = d.reloadKey(key)
	}
	again, err := d.load(reloadKey)
	if err != nil {
		t.Fatal(err)
	}
	if &rules[0] != &again[0] {
		t.Fatal("second load did not hit the cache")
	}
	d.cache.Lock()
	cached := len(d.cache.state.results)
	d.cache.Unlock()
	if cached != 1 {
		t.Fatalf("expected 1 cached %s, got %d", d.noun, cached)
	}
}

// testMissNotCached checks that an unknown key fails and is not cached.
func (d *geoCacheTestDriver[T]) testMissNotCached(t *testing.T) {
	t.Helper()
	if _, err := d.load(d.badKey); err == nil {
		t.Fatalf("missing %s accepted", d.noun)
	}
	d.cache.Lock()
	cached := len(d.cache.state.results)
	d.cache.Unlock()
	if cached != 0 {
		t.Fatalf("unknown %s cached %d entries", d.noun, cached)
	}
}

// testInvalidation replaces the database file (same content, new mtime) and
// checks the resource is reopened and cached results are dropped.
func (d *geoCacheTestDriver[T]) testInvalidation(t *testing.T, dir, key string) {
	t.Helper()
	before, err := d.load(key)
	if err != nil {
		t.Fatal(err)
	}
	d.cache.Lock()
	resourceBefore := d.cache.state.resource
	d.cache.Unlock()

	// an assets update replaces the file: same content, new mtime
	newTime := time.Now().Add(time.Hour)
	if err := os.Chtimes(filepath.Join(dir, d.dbName), newTime, newTime); err != nil {
		t.Fatal(err)
	}
	after, err := d.load(key)
	if err != nil {
		t.Fatal(err)
	}
	d.cache.Lock()
	resourceAfter := d.cache.state.resource
	d.cache.Unlock()
	if resourceBefore == resourceAfter {
		t.Fatal("file replacement did not reopen the reader")
	}
	if &before[0] == &after[0] {
		t.Fatalf("file replacement did not drop cached %s", d.plural)
	}
	if !reflect.DeepEqual(before, after) {
		t.Fatal("rules changed after identical-file replacement")
	}
}

// testConcurrent loads the same key from 8 goroutines and checks they all
// succeed and only one entry is cached.
func (d *geoCacheTestDriver[T]) testConcurrent(t *testing.T, key string) {
	t.Helper()
	var wg sync.WaitGroup
	errs := make(chan error, 8)
	for i := 0; i < 8; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			if _, err := d.load(key); err != nil {
				errs <- err
			}
		}()
	}
	wg.Wait()
	close(errs)
	for err := range errs {
		t.Fatal(err)
	}
	d.cache.Lock()
	cached := len(d.cache.state.results)
	d.cache.Unlock()
	if cached != 1 {
		t.Fatalf("expected 1 cached %s after concurrent loads, got %d", d.noun, cached)
	}
}
