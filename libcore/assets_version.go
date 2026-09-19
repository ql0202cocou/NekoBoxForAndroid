package libcore

import "strconv"

// shouldUpdateAsset reports whether the extracted asset at localVersion must be
// replaced by the version bundled in the APK (assetVersion). A "Custom" local
// version marks a file imported by the user and is never overwritten. Versions
// are timestamps and normally compare numerically; a non-numeric asset version
// falls back to plain string inequality, and an unreadable local version is
// treated as stale so a broken file self-heals.
func shouldUpdateAsset(localVersion, assetVersion string) bool {
	if localVersion == "Custom" {
		return false
	}
	av, err := strconv.ParseUint(assetVersion, 10, 64)
	if err != nil {
		return assetVersion != localVersion
	}
	lv, err := strconv.ParseUint(localVersion, 10, 64)
	return err != nil || av > lv
}
