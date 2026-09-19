package libcore

import (
	"libcore/device"
	"os"
	"path/filepath"
	"runtime"
	"runtime/debug"
	"slices"
	"strings"
	_ "unsafe"

	"log"

	"github.com/matsuridayo/libneko/neko_common"
	"github.com/matsuridayo/libneko/neko_log"
	"github.com/sagernet/sing-box/constant"
	"github.com/sagernet/sing-box/nekoutils"
	"github.com/sagernet/sing-box/option"
)

//go:linkname resourcePaths github.com/sagernet/sing-box/constant.resourcePaths
var resourcePaths []string

// assetsReady is closed once InitCore's background setup has finished: the
// custom CA load and, in :bg, the APK asset extraction. NewSingBoxInstance
// waits on it, because a box created while geoip.db/geosite.db are still being
// extracted (fresh install) fails to open its geo rule-sets.
//
// The variable itself is written and read without synchronization; the
// happens-before comes from the call order instead: Application.onCreate
// calls InitCore before any box is created, so the assignment above is
// visible to every NewSingBoxInstance call.
var assetsReady chan struct{}

func VersionBox() string {
	defer device.DeferPanicToError("VersionBox", nil)

	version := []string{
		"sing-box: " + constant.Version,
		runtime.Version() + "@" + runtime.GOOS + "/" + runtime.GOARCH,
	}

	var tags string
	debugInfo, loaded := debug.ReadBuildInfo()
	if loaded {
		for _, setting := range debugInfo.Settings {
			switch setting.Key {
			case "-tags":
				tags = setting.Value
			}
		}
	}

	if tags != "" {
		version = append(version, tags)
	}

	return strings.Join(version, "\n")
}

func NekoLogPrintln(s string) {
	defer device.DeferPanicToError("NekoLogPrintln", nil)

	log.Println(s)
}

func NekoLogClear() {
	defer device.DeferPanicToError("NekoLogClear", nil)

	neko_log.LogWriter.Truncate()
}

func ForceGc() {
	defer device.DeferPanicToError("ForceGc", nil)

	go debug.FreeOSMemory()
}

func InitCore(process, cachePath, internalAssets, externalAssets string,
	maxLogSizeKb int32, logEnable bool,
	if1 NB4AInterface, if2 BoxPlatformInterface, if3 LocalDNSTransport,
) {
	defer device.DeferPanicToError("InitCore", nil)
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
		defer device.DeferPanicToError("InitCore-go", nil)

		// certs
		pem, err := os.ReadFile(filepath.Join(externalAssetsPath, "ca.pem"))
		if err == nil {
			updateRootCACerts(pem)
		}

		// bg
		if isBgProcess {
			extractAssets()
		}
	}()
}
