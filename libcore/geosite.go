package libcore

import (
	"fmt"
	"io"
	"libcore/device"
	"os"

	geosites "github.com/sagernet/sing-box/common/geosite"
	C "github.com/sagernet/sing-box/constant"
	"github.com/sagernet/sing-box/nekoutils"
	"github.com/sagernet/sing-box/option"
)

// openGeoSite opens a sing-geosite database. The reader keeps reading from
// the file, so the file is what invalidation closes.
func openGeoSite(path string) (reader *geosites.Reader, closer io.Closer, err error) {
	defer device.DeferPanicToError("openGeoSite", func(err_ error) { err = err_ })

	file, err := os.Open(path)
	if err != nil {
		return nil, nil, err
	}
	reader, _, err = geosites.NewReader(file)
	if err != nil {
		file.Close()
		return nil, nil, err
	}
	return reader, file, nil
}

func geositeRulesFrom(reader *geosites.Reader, code string) (rules []option.HeadlessRule, err error) {
	defer device.DeferPanicToError("geositeRulesFrom", func(err_ error) { err = err_ })

	sourceSet, err := reader.Read(code)
	if err != nil {
		return nil, fmt.Errorf("failed to read geosite code %s :%w", code, err)
	}

	var headlessRule option.DefaultHeadlessRule

	defaultRule := geosites.Compile(sourceSet)

	headlessRule.Domain = defaultRule.Domain
	headlessRule.DomainSuffix = defaultRule.DomainSuffix
	headlessRule.DomainKeyword = defaultRule.DomainKeyword
	headlessRule.DomainRegex = defaultRule.DomainRegex

	return []option.HeadlessRule{
		{
			Type:           C.RuleTypeDefault,
			DefaultOptions: headlessRule,
		},
	}, nil
}

// See geoCache for the caching rationale. Codes are matched exactly.
var geositeCache = geoCache[*geosites.Reader]{
	dbName: geositeDat,
	open:   openGeoSite,
	load:   geositeRulesFrom,
}

func geositeRules(code string) ([]option.HeadlessRule, error) {
	return geositeCache.rules(code)
}

func init() {
	nekoutils.GetGeoSiteHeadlessRules = geositeRules
}
