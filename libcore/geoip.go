package libcore

import (
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"

	"github.com/oschwald/maxminddb-golang"
	C "github.com/sagernet/sing-box/constant"
	"github.com/sagernet/sing-box/nekoutils"
	"github.com/sagernet/sing-box/option"
)

type geoip struct {
	geoipReader *maxminddb.Reader
}

func (g *geoip) Open(path string) error {
	geoipReader, err := maxminddb.Open(path)
	g.geoipReader = geoipReader
	return err
}

func (g *geoip) Rules(countryCode string) ([]option.HeadlessRule, error) {
	networks := g.geoipReader.Networks(maxminddb.SkipAliasedNetworks)
	countryCode = strings.ToLower(countryCode)
	var cidrs []string
	var nextCountryCode string
	for networks.Next() {
		ipNet, err := networks.Network(&nextCountryCode)
		if err != nil {
			return nil, fmt.Errorf("failed to get network: %w", err)
		}
		// A rule only needs its own country. Do not retain all other
		// networks in a map on every rule-set load.
		if nextCountryCode == countryCode {
			cidrs = append(cidrs, ipNet.String())
		}
	}
	if err := networks.Err(); err != nil {
		return nil, fmt.Errorf("failed to iterate networks: %w", err)
	}
	if len(cidrs) == 0 {
		return nil, fmt.Errorf("no networks found for country code: %s", countryCode)
	}
	headlessRule := option.DefaultHeadlessRule{IPCIDR: cidrs}

	return []option.HeadlessRule{
		{
			Type:           C.RuleTypeDefault,
			DefaultOptions: headlessRule,
		},
	}, nil
}

// Loading one geoip rule-set walks the whole mmdb tree, so repeated loads of
// the same country (several rule-sets referencing it, box rebuilds on profile
// switch or URL tests) each paid a full scan plus file open. The cache keeps
// one shared reader and the per-country results of countries actually used;
// only a newly referenced country costs a scan. Assets updates replace
// geoip.db while the process is alive, so everything is keyed to the file's
// path/size/mtime and rebuilt when it changes. The mutex doubles as
// singleflight: concurrent first loads of different countries serialize
// instead of scanning in parallel.
var geoipCache = struct {
	sync.Mutex
	path      string
	size      int64
	modTime   time.Time
	reader    *maxminddb.Reader
	countries map[string][]option.HeadlessRule
}{}

func geoipRules(countryCode string) ([]option.HeadlessRule, error) {
	geoipCache.Lock()
	defer geoipCache.Unlock()

	path := filepath.Join(externalAssetsPath, "geoip.db")
	stat, err := os.Stat(path)
	if err != nil {
		return nil, err
	}
	if geoipCache.reader == nil || geoipCache.path != path ||
		geoipCache.size != stat.Size() || !geoipCache.modTime.Equal(stat.ModTime()) {
		if geoipCache.reader != nil {
			geoipCache.reader.Close()
		}
		reader, err := maxminddb.Open(path)
		if err != nil {
			geoipCache.reader = nil
			geoipCache.countries = nil
			return nil, err
		}
		geoipCache.path = path
		geoipCache.size = stat.Size()
		geoipCache.modTime = stat.ModTime()
		geoipCache.reader = reader
		geoipCache.countries = make(map[string][]option.HeadlessRule)
	}

	countryCode = strings.ToLower(countryCode)
	if rules, loaded := geoipCache.countries[countryCode]; loaded {
		return rules, nil
	}
	rules, err := (&geoip{geoipReader: geoipCache.reader}).Rules(countryCode)
	if err != nil {
		// Failures (unknown country) are not cached: they stay errors on
		// every call, like before.
		return nil, err
	}
	geoipCache.countries[countryCode] = rules
	return rules, nil
}

func init() {
	nekoutils.GetGeoIPHeadlessRules = geoipRules
}
