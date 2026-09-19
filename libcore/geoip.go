package libcore

import (
	"fmt"
	"io"
	"libcore/device"
	"strings"

	"github.com/oschwald/maxminddb-golang"
	C "github.com/sagernet/sing-box/constant"
	"github.com/sagernet/sing-box/nekoutils"
	"github.com/sagernet/sing-box/option"
)

type geoip struct {
	geoipReader *maxminddb.Reader
}

func (g *geoip) Open(path string) (err error) {
	defer device.DeferPanicToError("geoip.Open", func(err_ error) { err = err_ })

	geoipReader, err := maxminddb.Open(path)
	if err != nil {
		return err
	}
	g.geoipReader = geoipReader
	return nil
}

func (g *geoip) Rules(countryCode string) (rules []option.HeadlessRule, err error) {
	defer device.DeferPanicToError("geoip.Rules", func(err_ error) { err = err_ })

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

// See geoCache for the caching rationale. The reader doubles as the closer,
// and country codes are matched case-insensitively.
var geoipCache = geoCache[*maxminddb.Reader]{
	dbName: "geoip.db",
	open: func(path string) (*maxminddb.Reader, io.Closer, error) {
		g := new(geoip)
		if err := g.Open(path); err != nil {
			return nil, nil, err
		}
		return g.geoipReader, g.geoipReader, nil
	},
	normalize: strings.ToLower,
	load: func(reader *maxminddb.Reader, countryCode string) ([]option.HeadlessRule, error) {
		return (&geoip{geoipReader: reader}).Rules(countryCode)
	},
}

func geoipRules(countryCode string) ([]option.HeadlessRule, error) {
	return geoipCache.rules(countryCode)
}

func init() {
	nekoutils.GetGeoIPHeadlessRules = geoipRules
}
