package libcore

import (
	"os"
	"path/filepath"
	"reflect"
	"sort"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/oschwald/maxminddb-golang"
	"github.com/sagernet/sing-box/option"
)

func TestGeoIPCountryRules(t *testing.T) {
	path := os.Getenv("NEKO_TEST_GEOIP_DB")
	if path == "" {
		t.Skip("set NEKO_TEST_GEOIP_DB to a sing-geoip database")
	}
	g := new(geoip)
	if err := g.Open(path); err != nil {
		t.Fatal(err)
	}
	defer g.geoipReader.Close()
	// Reproduce the previous all-country grouping independently, then compare
	// the optimized path against it, including its case-insensitive input.
	want := make(map[string][]string)
	networks := g.geoipReader.Networks(maxminddb.SkipAliasedNetworks)
	for networks.Next() {
		var country string
		ipnet, err := networks.Network(&country)
		if err != nil {
			t.Fatal(err)
		}
		want[country] = append(want[country], ipnet.String())
	}
	if err := networks.Err(); err != nil {
		t.Fatal(err)
	}
	checked := 0
	countries := make([]string, 0, len(want))
	for country := range want {
		countries = append(countries, country)
	}
	sort.Strings(countries)
	for _, country := range countries {
		cidrs := want[country]
		rules, err := g.Rules(strings.ToUpper(country))
		if err != nil {
			t.Fatal(err)
		}
		if len(rules) != 1 || !reflect.DeepEqual([]string(rules[0].DefaultOptions.IPCIDR), cidrs) {
			t.Fatalf("mismatched country %q", country)
		}
		checked++
		if checked == 3 {
			break
		}
	}
	if checked == 0 {
		t.Fatal("empty fixture")
	}
	if _, err := g.Rules("not-a-country"); err == nil {
		t.Fatal("missing country accepted")
	}
}

// Sets up the cache against a temp copy of the fixture and restores all
// package state afterwards.
func setupGeoIPCacheTest(t *testing.T) string {
	t.Helper()
	fixture := os.Getenv("NEKO_TEST_GEOIP_DB")
	if fixture == "" {
		t.Skip("set NEKO_TEST_GEOIP_DB to a sing-geoip database")
	}
	data, err := os.ReadFile(fixture)
	if err != nil {
		t.Fatal(err)
	}
	dir := t.TempDir()
	if err := os.WriteFile(filepath.Join(dir, "geoip.db"), data, 0o644); err != nil {
		t.Fatal(err)
	}

	oldAssetsPath := externalAssetsPath
	geoipCache.Lock()
	oldCache := geoipCacheSnapshot{
		path: geoipCache.path, size: geoipCache.size, modTime: geoipCache.modTime,
		reader: geoipCache.reader, countries: geoipCache.countries,
	}
	geoipCache.path = ""
	geoipCache.reader = nil
	geoipCache.countries = nil
	geoipCache.Unlock()
	externalAssetsPath = dir
	t.Cleanup(func() {
		externalAssetsPath = oldAssetsPath
		geoipCache.Lock()
		defer geoipCache.Unlock()
		if geoipCache.reader != nil && geoipCache.reader != oldCache.reader {
			geoipCache.reader.Close()
		}
		geoipCache.path = oldCache.path
		geoipCache.size = oldCache.size
		geoipCache.modTime = oldCache.modTime
		geoipCache.reader = oldCache.reader
		geoipCache.countries = oldCache.countries
	})
	return dir
}

type geoipCacheSnapshot struct {
	path      string
	size      int64
	modTime   time.Time
	reader    *maxminddb.Reader
	countries map[string][]option.HeadlessRule
}

func firstCountry(t *testing.T) string {
	t.Helper()
	fixture := os.Getenv("NEKO_TEST_GEOIP_DB")
	reader, err := maxminddb.Open(fixture)
	if err != nil {
		t.Fatal(err)
	}
	defer reader.Close()
	networks := reader.Networks(maxminddb.SkipAliasedNetworks)
	if !networks.Next() {
		t.Fatal("empty fixture")
	}
	var country string
	if _, err := networks.Network(&country); err != nil {
		t.Fatal(err)
	}
	return country
}

func TestGeoIPCacheHitAndCase(t *testing.T) {
	setupGeoIPCacheTest(t)
	country := firstCountry(t)

	rules, err := geoipRules(country)
	if err != nil {
		t.Fatal(err)
	}
	if len(rules) != 1 || len(rules[0].DefaultOptions.IPCIDR) == 0 {
		t.Fatal("empty rules")
	}
	// same country, different case: served from the cache (identical slice)
	again, err := geoipRules(strings.ToUpper(country))
	if err != nil {
		t.Fatal(err)
	}
	if &rules[0] != &again[0] {
		t.Fatal("second load did not hit the cache")
	}
	geoipCache.Lock()
	cached := len(geoipCache.countries)
	geoipCache.Unlock()
	if cached != 1 {
		t.Fatalf("expected 1 cached country, got %d", cached)
	}
}

func TestGeoIPCacheUnknownCountryNotCached(t *testing.T) {
	setupGeoIPCacheTest(t)
	if _, err := geoipRules("not-a-country"); err == nil {
		t.Fatal("missing country accepted")
	}
	geoipCache.Lock()
	cached := len(geoipCache.countries)
	geoipCache.Unlock()
	if cached != 0 {
		t.Fatalf("unknown country cached %d entries", cached)
	}
}

func TestGeoIPCacheInvalidation(t *testing.T) {
	dir := setupGeoIPCacheTest(t)
	country := firstCountry(t)

	before, err := geoipRules(country)
	if err != nil {
		t.Fatal(err)
	}
	geoipCache.Lock()
	readerBefore := geoipCache.reader
	geoipCache.Unlock()

	// an assets update replaces the file: same content, new mtime
	newTime := time.Now().Add(time.Hour)
	if err := os.Chtimes(filepath.Join(dir, "geoip.db"), newTime, newTime); err != nil {
		t.Fatal(err)
	}
	after, err := geoipRules(country)
	if err != nil {
		t.Fatal(err)
	}
	geoipCache.Lock()
	readerAfter := geoipCache.reader
	geoipCache.Unlock()
	if readerBefore == readerAfter {
		t.Fatal("file replacement did not reopen the reader")
	}
	if &before[0] == &after[0] {
		t.Fatal("file replacement did not drop cached countries")
	}
	if !reflect.DeepEqual(before, after) {
		t.Fatal("rules changed after identical-file replacement")
	}
}

func TestGeoIPCacheConcurrent(t *testing.T) {
	setupGeoIPCacheTest(t)
	country := firstCountry(t)
	var wg sync.WaitGroup
	errs := make(chan error, 8)
	for i := 0; i < 8; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			if _, err := geoipRules(country); err != nil {
				errs <- err
			}
		}()
	}
	wg.Wait()
	close(errs)
	for err := range errs {
		t.Fatal(err)
	}
	geoipCache.Lock()
	cached := len(geoipCache.countries)
	geoipCache.Unlock()
	if cached != 1 {
		t.Fatalf("expected 1 cached country after concurrent loads, got %d", cached)
	}
}
