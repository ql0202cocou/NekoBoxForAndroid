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

// assetSpec 描述 APK 内打包的一个资产。可替换资产（geo 数据库）可在
// AssetsActivity 里由用户导入或下载，位于 externalAssetsPath；其余资产位于
// internalAssetsPath，始终跟随 APK 自带版本。
type assetSpec struct {
	name        string // 资产文件名
	version     string // 版本文件名，位于资产旁及 APK 内
	apkPrefix   string // 资产在 APK 内的目录
	replaceable bool
}

// assetSpecs 是资产清单的唯一数据源（原来是 map 加 extractAssets 里的
// 硬编码列表双份维护）；extractAssets 按此 slice 的顺序解压。
var assetSpecs = []assetSpec{
	{name: geoipDat, version: geoipVersion, apkPrefix: apkAssetPrefixSingBox, replaceable: true},
	{name: geositeDat, version: geositeVersion, apkPrefix: apkAssetPrefixSingBox, replaceable: true},
	{name: yacdDstFolder, version: yacdVersion},
}

func extractAssets() {
	useOfficialAssets := nb4aIntf().UseOfficialAssets()
	for _, spec := range assetSpecs {
		if err := extractAssetName(spec.name, useOfficialAssets); err != nil {
			log.Println("Extract", spec.name, "failed:", err)
		}
	}
}

// extractAssetName extracts one asset bundled in the APK.
func extractAssetName(name string, useOfficialAssets bool) error {
	// The main process may be importing or downloading the same file from
	// AssetsActivity right now: version check, extraction and publish run under
	// the shared record lock (see assets_lock.go).
	return withAssetsLock(internalAssetsDir()+"assets.lock", func() error {
		return extractAssetNameLocked(name, useOfficialAssets)
	})
}

func extractAssetNameLocked(name string, useOfficialAssets bool) error {
	var spec assetSpec
	known := false
	for _, s := range assetSpecs {
		if s.name == name {
			spec, known = s, true
			break
		}
	}
	if !known {
		return fmt.Errorf("unknown asset %s", name)
	}
	// Replaceable assets also live in app-internal storage; the external
	// path name is retained for compatibility with the native interface.
	dir := internalAssetsDir()
	if spec.replaceable {
		dir = externalAssetsDir()
	}
	dstName := dir + name

	assetVersion, err := readAPKAssetVersion(spec.apkPrefix + spec.version)
	if err != nil {
		return err
	}

	var doExtract bool
	if _, err := os.Stat(dstName); err != nil {
		// assetFileMissing
		doExtract = true
	} else if useOfficialAssets || !spec.replaceable {
		// 官方源升级
		b, err := os.ReadFile(dir + spec.version)
		if err != nil {
			// versionFileMissing: the extracted file may be stale or partial
			doExtract = true
			_ = os.RemoveAll(dstName)
		} else {
			doExtract = shouldUpdateAsset(string(b), assetVersion)
		}
	} else {
		//非官方源不升级
	}
	if !doExtract {
		return nil
	}

	if f, err := asset.Open(spec.apkPrefix + name + ".xz"); err == nil {
		if err := extractXz(f, dstName); err != nil {
			return err
		}
	} else if name == yacdDstFolder {
		if err := extractYacd(dstName); err != nil {
			return err
		}
	} else {
		return fmt.Errorf("no asset found for %s", name)
	}

	// extraction succeeded, only now bump the version file,
	// otherwise a broken file would be kept forever
	return os.WriteFile(dir+spec.version, []byte(assetVersion), 0600)
}

// readAPKAssetVersion reads a version file bundled in the APK.
func readAPKAssetVersion(name string) (string, error) {
	av, err := asset.Open(name)
	if err != nil {
		return "", fmt.Errorf("open version in assets: %w", err)
	}
	b, err := io.ReadAll(av)
	av.Close()
	if err != nil {
		return "", fmt.Errorf("read internal version: %w", err)
	}
	return string(b), nil
}

// extractXz decompresses f to a temp file and renames it into place
// atomically, so a concurrent box start never reads a truncated asset.
func extractXz(f asset.File, dstName string) error {
	tmpXzName := tempName(dstName, "xz")
	tmpName := tempName(dstName, "tmp")
	defer os.Remove(tmpXzName)
	defer os.Remove(tmpName)
	err := extractAsset(f, tmpXzName)
	if err == nil {
		err = unxz(tmpXzName, tmpName)
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

func extractZip(f asset.File, dstName string, outDir string) error {
	tmpZipName := tempName(dstName, "zip")
	defer os.Remove(tmpZipName)
	err := extractAsset(f, tmpZipName)
	if err == nil {
		err = unzip(tmpZipName, outDir)
		os.Remove(tmpZipName)
	}
	if err != nil {
		return fmt.Errorf("extract zip: %v", err)
	}
	return nil
}

// extractYacd unpacks the yacd.zip dashboard (a single Yacd-* top directory)
// into internalAssetsPath and swaps it into dstName.
func extractYacd(dstName string) error {
	f, err := asset.Open("yacd.zip")
	if err != nil {
		return fmt.Errorf("open yacd asset: %w", err)
	}
	// Remove leftovers of a previous extraction killed midway (the zip's
	// Yacd-* top directory, or the old panel awaiting deletion), so the
	// glob below can succeed again.
	for _, pattern := range []string{"/Yacd-*", "/" + yacdDstFolder + ".old.*"} {
		if leftovers, _ := filepath.Glob(internalAssetsDir() + pattern); len(leftovers) > 0 {
			for _, leftover := range leftovers {
				os.RemoveAll(leftover)
			}
		}
	}
	if err := extractZip(f, dstName, internalAssetsDir()); err != nil {
		return err
	}
	m, err := filepath.Glob(internalAssetsDir() + "/Yacd-*")
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
	return nil
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
