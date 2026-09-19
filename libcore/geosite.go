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

type geosite struct {
	geositeReader *geosites.Reader
	file          *os.File
}

func (g *geosite) Open(path string) (err error) {
	defer device.DeferPanicToError("geosite.Open", func(err_ error) { err = err_ })

	file, err := os.Open(path)
	if err != nil {
		return err
	}
	geositeReader, _, err := geosites.NewReader(file)
	if err != nil {
		file.Close()
		return err
	}
	g.geositeReader = geositeReader
	g.file = file
	return nil
}

func (g *geosite) Rules(code string) (rules []option.HeadlessRule, err error) {
	defer device.DeferPanicToError("geosite.Rules", func(err_ error) { err = err_ })

	sourceSet, err := g.geositeReader.Read(code)
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

// See geoCache for the caching rationale. The reader needs its underlying
// file as the closer, and codes are matched exactly.
var geositeCache = geoCache[*geosites.Reader]{
	dbName: "geosite.db",
	open: func(path string) (*geosites.Reader, io.Closer, error) {
		g := new(geosite)
		if err := g.Open(path); err != nil {
			return nil, nil, err
		}
		return g.geositeReader, g.file, nil
	},
	load: func(reader *geosites.Reader, code string) ([]option.HeadlessRule, error) {
		return (&geosite{geositeReader: reader}).Rules(code)
	},
}

func geositeRules(code string) ([]option.HeadlessRule, error) {
	return geositeCache.rules(code)
}

func init() {
	nekoutils.GetGeoSiteHeadlessRules = geositeRules
}
