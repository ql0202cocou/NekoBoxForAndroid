package libcore

import (
	"os"
	"sort"
	"testing"

	geosites "github.com/sagernet/sing-box/common/geosite"
	"github.com/sagernet/sing-box/option"
)

var geositeCacheTests = geoCacheTestDriver[*geosites.Reader]{
	cache:       &geositeCache,
	load:        geositeRules,
	noun:        "code",
	plural:      "codes",
	envVar:      "NEKO_TEST_GEOSITE_DB",
	dbName:      "geosite.db",
	skipMessage: "set NEKO_TEST_GEOSITE_DB to a sing-geosite database",
	badKey:      "not-a-code",
	// codes match exactly: reload the code as-is
	checkRules: func(t *testing.T, rules []option.HeadlessRule) {
		options := rules[0].DefaultOptions
		if len(options.Domain) == 0 && len(options.DomainSuffix) == 0 &&
			len(options.DomainKeyword) == 0 && len(options.DomainRegex) == 0 {
			t.Fatal("empty rules")
		}
	},
}

func firstGeositeCode(t *testing.T) string {
	t.Helper()
	fixture := os.Getenv("NEKO_TEST_GEOSITE_DB")
	reader, codes, err := geosites.Open(fixture)
	if err != nil {
		t.Fatal(err)
	}
	defer reader.Upstream().(*os.File).Close()
	if len(codes) == 0 {
		t.Fatal("empty fixture")
	}
	sort.Strings(codes)
	return codes[0]
}

func TestGeoSiteCacheHit(t *testing.T) {
	geositeCacheTests.setup(t)
	geositeCacheTests.testHit(t, firstGeositeCode(t))
}

func TestGeoSiteCacheUnknownCodeNotCached(t *testing.T) {
	geositeCacheTests.setup(t)
	geositeCacheTests.testMissNotCached(t)
}

func TestGeoSiteCacheInvalidation(t *testing.T) {
	dir := geositeCacheTests.setup(t)
	geositeCacheTests.testInvalidation(t, dir, firstGeositeCode(t))
}

func TestGeoSiteCacheConcurrent(t *testing.T) {
	geositeCacheTests.setup(t)
	geositeCacheTests.testConcurrent(t, firstGeositeCode(t))
}
