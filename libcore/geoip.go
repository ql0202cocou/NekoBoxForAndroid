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

// openGeoIP opens a sing-geoip database; the reader is its own closer.
func openGeoIP(path string) (reader *maxminddb.Reader, closer io.Closer, err error) {
	defer device.DeferPanicToError("openGeoIP", func(err_ error) { err = err_ })

	reader, err = maxminddb.Open(path)
	if err != nil {
		return nil, nil, err
	}
	return reader, reader, nil
}

func geoipRulesFrom(reader *maxminddb.Reader, countryCode string) (rules []option.HeadlessRule, err error) {
	defer device.DeferPanicToError("geoipRulesFrom", func(err_ error) { err = err_ })

	networks := reader.Networks(maxminddb.SkipAliasedNetworks)
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

// See geoCache for the caching rationale. Country codes are matched
// case-insensitively.
var geoipCache = geoCache[*maxminddb.Reader]{
	dbName:    geoipDat,
	open:      openGeoIP,
	normalize: strings.ToLower,
	load:      geoipRulesFrom,
}

func geoipRules(countryCode string) ([]option.HeadlessRule, error) {
	return geoipCache.rules(countryCode)
}

func init() {
	nekoutils.GetGeoIPHeadlessRules = geoipRules
}
