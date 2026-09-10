package libcore

import (
	"libcore/device"
	"os"
	"path/filepath"
	"runtime/debug"
	"slices"
	"strings"
	_ "unsafe"

	"log"

	"github.com/matsuridayo/libneko/neko_common"
	"github.com/matsuridayo/libneko/neko_log"
	"github.com/sagernet/sing-box/nekoutils"
	"github.com/sagernet/sing-box/option"
)

//go:linkname resourcePaths github.com/sagernet/sing-box/constant.resourcePaths
var resourcePaths []string

// assetsReady is closed once InitCore's background setup has finished: the
// custom CA load and, in :bg, the APK asset extraction. NewSingBoxInstance
// waits on it, because a box created while geoip.db/geosite.db are still being
// extracted (fresh install) fails to open its geo rule-sets.
var assetsReady chan struct{}

func NekoLogPrintln(s string) {
	log.Println(s)
}

func NekoLogClear() {
	neko_log.LogWriter.Truncate()
}

func ForceGc() {
	go debug.FreeOSMemory()
}

func InitCore(process, cachePath, internalAssets, externalAssets string,
	maxLogSizeKb int32, logEnable bool,
	if1 NB4AInterface, if2 BoxPlatformInterface, if3 LocalDNSTransport,
) {
	defer device.DeferPanicToError("InitCore", func(err error) { log.Println(err) })
	isBgProcess = strings.HasSuffix(process, ":bg")

	neko_common.RunMode = neko_common.RunMode_NekoBoxForAndroid
	intfNB4A = if1
	intfBox = if2
	useProcfs = intfBox.UseProcFS()
	gLocalDNSTransport = newPlatformTransport(if3, "", option.LocalDNSServerOptions{})

	// Working dir
	tmp := filepath.Join(cachePath, "../no_backup")
	if err := os.MkdirAll(tmp, 0755); err != nil {
		log.Println("failed to create working dir:", err)
	}
	if err := os.Chdir(tmp); err != nil {
		log.Println("failed to chdir to working dir:", err)
	}

	// sing-box fs
	if !slices.Contains(resourcePaths, externalAssets) {
		resourcePaths = append(resourcePaths, externalAssets)
	}
	externalAssetsPath = externalAssets
	internalAssetsPath = internalAssets

	// Set up log
	if maxLogSizeKb < 50 {
		maxLogSizeKb = 50
	}
	neko_log.LogWriterDisable = !logEnable
	neko_log.TruncateOnStart = isBgProcess
	neko_log.SetupLog(int(maxLogSizeKb)*1024, filepath.Join(cachePath, "neko.log"))

	// nekoutils
	nekoutils.Selector_OnProxySelected = intfNB4A.Selector_OnProxySelected

	// Set up some component
	ready := make(chan struct{})
	assetsReady = ready
	go func() {
		defer close(ready)
		defer device.DeferPanicToError("InitCore-go", func(err error) { log.Println(err) })

		// certs
		pem, err := os.ReadFile(externalAssetsPath + "ca.pem")
		if err == nil {
			updateRootCACerts(pem)
		}

		// bg
		if isBgProcess {
			extractAssets()
		}
	}()
}
