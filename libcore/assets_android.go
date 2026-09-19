//go:build android

package libcore

import (
	"fmt"
	"io"
	"log"
	"os"
	"path/filepath"

	"golang.org/x/mobile/asset"
)

func extractAssets() {
	useOfficialAssets := intfNB4A.UseOfficialAssets()

	extract := func(name string) {
		err := extractAssetName(name, useOfficialAssets)
		if err != nil {
			log.Println("Extract", name, "failed:", err)
		}
	}

	extract(geoipDat)
	extract(geositeDat)
	extract(yacdDstFolder)
}

// 这里解压的是 apk 里面的
func extractAssetName(name string, useOfficialAssets bool) error {
	// The main process may be importing or downloading the same file from
	// AssetsActivity right now: version check, extraction and publish run under
	// the shared record lock (see assets_lock.go).
	return withAssetsLock(internalAssetsPath+"assets.lock", func() error {
		return extractAssetNameLocked(name, useOfficialAssets)
	})
}

func extractAssetNameLocked(name string, useOfficialAssets bool) error {
	// Replaceable assets also live in app-internal storage; the external
	// path name is retained for compatibility with the native interface.
	replaceable := true

	var version string
	var apkPrefix string
	switch name {
	case geoipDat:
		version = geoipVersion
		apkPrefix = apkAssetPrefixSingBox
	case geositeDat:
		version = geositeVersion
		apkPrefix = apkAssetPrefixSingBox
	case yacdDstFolder:
		version = yacdVersion
		replaceable = false
	}

	var dir string
	if !replaceable {
		dir = internalAssetsPath
	} else {
		dir = externalAssetsPath
	}
	dstName := dir + name

	var localVersion string
	var assetVersion string

	// loadAssetVersion from APK
	loadAssetVersion := func() error {
		av, err := asset.Open(apkPrefix + version)
		if err != nil {
			return fmt.Errorf("open version in assets: %v", err)
		}
		b, err := io.ReadAll(av)
		av.Close()
		if err != nil {
			return fmt.Errorf("read internal version: %v", err)
		}
		assetVersion = string(b)
		return nil
	}
	if err := loadAssetVersion(); err != nil {
		return err
	}

	var doExtract bool

	if _, err := os.Stat(dstName); err != nil {
		// assetFileMissing
		doExtract = true
	} else if useOfficialAssets || !replaceable {
		// 官方源升级
		b, err := os.ReadFile(dir + version)
		if err != nil {
			// versionFileMissing: the extracted file may be stale or partial
			doExtract = true
			_ = os.RemoveAll(dstName)
		} else {
			localVersion = string(b)
			doExtract = shouldUpdateAsset(localVersion, assetVersion)
		}
	} else {
		//非官方源不升级
	}

	if !doExtract {
		return nil
	}

	extractXz := func(f asset.File) error {
		tmpXzName := tempName(dstName, "xz")
		tmpName := tempName(dstName, "tmp")
		defer os.Remove(tmpXzName)
		defer os.Remove(tmpName)
		err := extractAsset(f, tmpXzName)
		if err == nil {
			// decompress to a temp file and rename atomically, so a
			// concurrent box start never reads a truncated asset
			err = Unxz(tmpXzName, tmpName)
			os.Remove(tmpXzName)
		}
		if err == nil {
			err = os.Rename(tmpName, dstName)
		}
		if err != nil {
			os.Remove(tmpName)
			return fmt.Errorf("extract xz: %v", err)
		}
		return nil
	}

	extractZip := func(f asset.File, outDir string) error {
		tmpZipName := tempName(dstName, "zip")
		defer os.Remove(tmpZipName)
		err := extractAsset(f, tmpZipName)
		if err == nil {
			err = Unzip(tmpZipName, outDir)
			os.Remove(tmpZipName)
		}
		if err != nil {
			return fmt.Errorf("extract zip: %v", err)
		}
		return nil
	}

	if f, err := asset.Open(apkPrefix + name + ".xz"); err == nil {
		if err := extractXz(f); err != nil {
			return err
		}
	} else if name == yacdDstFolder {
		f, err := asset.Open("yacd.zip")
		if err != nil {
			return fmt.Errorf("open yacd asset: %w", err)
		}
		// Remove leftovers of a previous extraction killed midway (the zip's
		// Yacd-* top directory, or the old panel awaiting deletion), so the
		// glob below can succeed again.
		for _, pattern := range []string{"/Yacd-*", "/" + yacdDstFolder + ".old.*"} {
			if leftovers, _ := filepath.Glob(internalAssetsPath + pattern); len(leftovers) > 0 {
				for _, leftover := range leftovers {
					os.RemoveAll(leftover)
				}
			}
		}
		if err := extractZip(f, internalAssetsPath); err != nil {
			return err
		}
		m, err := filepath.Glob(internalAssetsPath + "/Yacd-*")
		if err != nil {
			return fmt.Errorf("glob Yacd: %v", err)
		}
		if len(m) != 1 {
			// Clean up the wreckage so the next extraction self-heals
			// instead of failing on the same leftover forever.
			for _, dir := range m {
				os.RemoveAll(dir)
			}
			return fmt.Errorf("glob Yacd found %d result, expect 1", len(m))
		}
		// Keep the current panel until the new one is complete: the directory is
		// missing only between the two renames, not for the whole extraction.
		old := tempName(dstName, "old")
		if _, err := os.Stat(dstName); err == nil {
			if err := os.Rename(dstName, old); err != nil {
				return fmt.Errorf("move old Yacd: %v", err)
			}
		}
		if err := os.Rename(m[0], dstName); err != nil {
			os.Rename(old, dstName)
			return fmt.Errorf("rename Yacd: %v", err)
		}
		os.RemoveAll(old)
	} else {
		// TODO normal file
		return fmt.Errorf("no asset found for %s", name)
	}

	// extraction succeeded, only now bump the version file,
	// otherwise a broken file would be kept forever
	return os.WriteFile(dir+version, []byte(assetVersion), 0600)
}

func extractAsset(i asset.File, path string) error {
	defer i.Close()
	o, err := os.Create(path)
	if err != nil {
		return err
	}
	err = copyAndClose(o, i)
	if err == nil {
		log.Println("Extract >>", path)
	}
	return err
}
