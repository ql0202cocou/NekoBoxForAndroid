//go:build with_utls

package tls

import (
	"slices"
	"testing"

	utls "github.com/metacubex/utls"
)

func TestRandomizedHybridKeyShare(t *testing.T) {
	for i := range 64 {
		id := randomizedFingerprint
		seed := utls.PRNGSeed{byte(i)}
		id.Seed = &seed
		spec, err := utls.UTLSIdToSpec(id)
		if err != nil {
			t.Fatal(err)
		}
		var group, share bool
		for _, ext := range spec.Extensions {
			switch e := ext.(type) {
			case *utls.SupportedCurvesExtension:
				group = slices.Contains(e.Curves, utls.X25519MLKEM768)
			case *utls.KeyShareExtension:
				share = slices.ContainsFunc(e.KeyShares, func(k utls.KeyShare) bool {
					return k.Group == utls.X25519MLKEM768
				})
			}
		}
		if !group || !share {
			t.Fatalf("seed %d: hybrid group %v, key share %v", i, group, share)
		}
	}
}
