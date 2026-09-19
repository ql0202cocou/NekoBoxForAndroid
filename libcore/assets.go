package libcore

import "sync/atomic"

const (
	geoipDat       = "geoip.db"
	geositeDat     = "geosite.db"
	geoipVersion   = "geoip.version.txt"
	geositeVersion = "geosite.version.txt"

	yacdDstFolder = "yacd"
	yacdVersion   = "yacd.version.txt"
)

const apkAssetPrefixSingBox = "sing-box/"

// 资产目录由 InitCore 在进程启动时写入一次，之后被 box 的任意 goroutine
// 读取（geo_cache.go 等），故用 atomic.Value 发布。读取一律经
// internalAssetsDir / externalAssetsDir；未初始化时返回空串，与原来的
// 零值行为一致。
var internalAssetsPath atomic.Value // string
var externalAssetsPath atomic.Value // string

func internalAssetsDir() string {
	s, _ := internalAssetsPath.Load().(string)
	return s
}

func externalAssetsDir() string {
	s, _ := externalAssetsPath.Load().(string)
	return s
}
