package libcore

import (
	"os"
	"reflect"
	"sort"
	"strings"
	"testing"

	"github.com/oschwald/maxminddb-golang"
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
