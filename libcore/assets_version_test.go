package libcore

import "testing"

func TestShouldUpdateAssetVersion(t *testing.T) {
	cases := []struct {
		name         string
		localVersion string
		assetVersion string
		want         bool
	}{
		// numeric (timestamp) versions
		{"older local", "20250901", "20250919", true},
		{"newer local", "20250919", "20250901", false},
		{"same version", "20250919", "20250919", false},
		// user-imported files are never overwritten
		{"custom local", "Custom", "20250919", false},
		{"custom against custom asset", "Custom", "Custom", false},
		// non-numeric asset version: plain string comparison
		{"string fallback differs", "20250901", "dev", true},
		{"string fallback equal", "dev", "dev", false},
		// unreadable local version counts as stale
		{"local not numeric", "dev", "20250919", true},
		{"local missing", "", "20250919", true},
		{"both not numeric", "dev", "dev2", true},
	}
	for _, c := range cases {
		if got := shouldUpdateAsset(c.localVersion, c.assetVersion); got != c.want {
			t.Errorf("%s: shouldUpdateAsset(%q, %q) = %v, want %v",
				c.name, c.localVersion, c.assetVersion, got, c.want)
		}
	}
}
