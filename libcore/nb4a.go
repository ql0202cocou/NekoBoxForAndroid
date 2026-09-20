package libcore

import (
	"libcore/device"
	"os"
	"path/filepath"
	"runtime"
	"runtime/debug"
	"slices"
	"strings"
	"sync/atomic"
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

// assetsReady 在 InitCore 的后台初始化完成（加载自定义 CA，:bg 进程解压
// APK 资产）后关闭；NewSingBoxInstance 会等待它，否则首次安装时
// geoip.db/geosite.db 还在解压，建出来的 box 打不开 geo rule-set。
//
// InitCore 在应用主线程写入，NewSingBoxInstance 在 Kotlin 协程线程读取
// （bg/proto/BoxInstance.kt），两侧没有共同的锁，故用 atomic.Pointer
// 发布，不能靠调用顺序提供可见性。
var assetsReady atomic.Pointer[chan struct{}]

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
	isBg := strings.HasSuffix(process, ":bg")
	isBgProcess.Store(isBg)

	neko_common.RunMode = neko_common.RunMode_NekoBoxForAndroid
	intfNB4A.Store(if1)
	intfBox.Store(if2)
	useProcfs.Store(if2.UseProcFS())
	gLocalDNSTransport.Store(newPlatformTransport(if3, "", option.LocalDNSServerOptions{}))

	// Working dir
	tmp := filepath.Join(cachePath, "../no_backup")
	if err := os.MkdirAll(tmp, 0755); err != nil {
		log.Println("failed to create working dir:", err)
	}
	if err := os.Chdir(tmp); err != nil {
		log.Println("failed to chdir to working dir:", err)
	}
	// protect socket 用绝对路径发布，protect.go / platform_box.go 不再依赖进程 CWD
	protectPath := filepath.Join(tmp, "protect_path")
	protectSocketPath.Store(&protectPath)

	// sing-box fs
	if !slices.Contains(resourcePaths, externalAssets) {
		resourcePaths = append(resourcePaths, externalAssets)
	}
	externalAssetsPath.Store(externalAssets)
	internalAssetsPath.Store(internalAssets)

	// Set up log
	if maxLogSizeKb < 50 {
		maxLogSizeKb = 50
	}
	neko_log.LogWriterDisable = !logEnable
	neko_log.TruncateOnStart = isBg
	// 日志文件打不开时 SetupLog 退化为仅 stdout/GUI 输出（见 neko_log），
	// 记一条日志后继续，不能让进程起不来
	if err := neko_log.SetupLog(int(maxLogSizeKb)*1024, filepath.Join(cachePath, "neko.log")); err != nil {
		log.Println("SetupLog:", err)
	}

	// nekoutils
	nekoutils.Selector_OnProxySelected = if1.Selector_OnProxySelected

	// Set up some component
	ready := make(chan struct{})
	assetsReady.Store(&ready)
	go func() {
		defer close(ready)
		defer device.DeferPanicToError("InitCore-go", nil)

		// certs
		pem, err := os.ReadFile(filepath.Join(externalAssetsDir(), "ca.pem"))
		if err == nil {
			updateRootCACerts(pem)
		}

		// bg
		if isBg {
			extractAssets()
		}
	}()
}
