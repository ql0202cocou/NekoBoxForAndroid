package libcore

import (
	"os"
	"reflect"
	"sort"
	"strings"
	"testing"

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

var geoipCacheTests = geoCacheTestDriver[*maxminddb.Reader]{
	cache:       &geoipCache,
	load:        geoipRules,
	noun:        "country",
	plural:      "countries",
	envVar:      "NEKO_TEST_GEOIP_DB",
	dbName:      "geoip.db",
	skipMessage: "set NEKO_TEST_GEOIP_DB to a sing-geoip database",
	badKey:      "not-a-country",
	// country codes match case-insensitively: an upper-case reload must hit
	// the cache entry of the lower-case first load
	reloadKey: strings.ToUpper,
	checkRules: func(t *testing.T, rules []option.HeadlessRule) {
		if len(rules[0].DefaultOptions.IPCIDR) == 0 {
			t.Fatal("empty rules")
		}
	},
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
	geoipCacheTests.setup(t)
	geoipCacheTests.testHit(t, firstCountry(t))
}

func TestGeoIPCacheUnknownCountryNotCached(t *testing.T) {
	geoipCacheTests.setup(t)
	geoipCacheTests.testMissNotCached(t)
}

func TestGeoIPCacheInvalidation(t *testing.T) {
	dir := geoipCacheTests.setup(t)
	geoipCacheTests.testInvalidation(t, dir, firstCountry(t))
}

func TestGeoIPCacheConcurrent(t *testing.T) {
	geoipCacheTests.setup(t)
	geoipCacheTests.testConcurrent(t, firstCountry(t))
}
