package libcore

import (
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/common/trafficcontrol"
	"github.com/sagernet/sing/service"
)

// Minimal config that starts on desktop: no inbounds (nothing to bind), a
// single direct outbound, and panic log level to keep test output quiet.
const lifecycleBoxConfig = `{
	"log": {"level": "panic"},
	"outbounds": [{"type": "direct", "tag": "direct"}]
}`

func newTestBox(t *testing.T) *BoxInstance {
	t.Helper()
	instance, err := NewSingBoxInstance(lifecycleBoxConfig, nil)
	if err != nil {
		t.Fatal(err)
	}
	return instance
}

func newStartedTestBox(t *testing.T) *BoxInstance {
	t.Helper()
	instance := newTestBox(t)
	if err := instance.Start(); err != nil {
		t.Fatal(err)
	}
	return instance
}

func TestBoxInstanceLifecycle(t *testing.T) {
	instance := newTestBox(t)

	// before SetV2rayStats every query reads as zero
	if got := instance.QueryStats("direct", "uplink"); got != 0 {
		t.Fatalf("QueryStats before SetV2rayStats = %d", got)
	}
	// no "proxy" selector outbound in this config
	if instance.SelectOutbound("direct") {
		t.Fatal("SelectOutbound without selector = true")
	}

	if err := instance.Start(); err != nil {
		t.Fatal(err)
	}
	// the state machine moves to boxStarted on the first start and never back
	if err := instance.Start(); err == nil || !strings.Contains(err.Error(), "already started") {
		t.Fatalf("second Start = %v", err)
	}

	// pause manager is installed by the box; both calls must be no-ops here
	instance.Sleep()
	instance.Wake()

	if err := instance.Close(); err != nil {
		t.Fatal(err)
	}
	// double close is a no-op
	if err := instance.Close(); err != nil {
		t.Fatalf("second Close = %v", err)
	}
	// boxClosed is not boxNew either, so Start keeps reporting "already started"
	if err := instance.Start(); err == nil || !strings.Contains(err.Error(), "already started") {
		t.Fatalf("Start after Close = %v", err)
	}
}

// interfaceMonitorStub 的 DefaultInterface 恒为 nil，sing-box 在 PostStart 会因此
// NetworkPause；Start 返回后网络必须处于未暂停状态，否则 WireGuard 永久 Down、
// 其定时器全部卡在 WaitActive
func TestBoxInstanceStartLeavesNetworkAwake(t *testing.T) {
	instance := newStartedTestBox(t)
	defer instance.Close()
	if instance.pauseManager == nil {
		t.Fatal("pause manager not installed")
	}
	if instance.pauseManager.IsNetworkPaused() {
		t.Fatal("network still paused after Start")
	}
}

func TestBoxInstanceCloseBeforeStart(t *testing.T) {
	instance := newTestBox(t)
	if err := instance.Close(); err != nil {
		t.Fatalf("Close before Start = %v", err)
	}
	if err := instance.Close(); err != nil {
		t.Fatalf("second Close = %v", err)
	}
}

func TestBoxInstanceSetAsMainClearsOnClose(t *testing.T) {
	instance := newTestBox(t)
	instance.SetAsMain()
	if getMainInstance() != instance {
		t.Fatal("SetAsMain did not install the instance")
	}
	if err := instance.Close(); err != nil {
		t.Fatal(err)
	}
	if getMainInstance() != nil {
		t.Fatal("Close did not clear the main instance")
	}
}

// SetV2rayStats 在 b.access 下安装 tracker，QueryStats 在同一把锁下读取；
// 由 race detector 校验。安装只允许在 Start 之前，所以用未启动的实例
func TestBoxInstanceSetV2rayStatsRace(t *testing.T) {
	instance := newTestBox(t)

	var wg sync.WaitGroup
	for i := 0; i < 8; i++ {
		wg.Add(1)
		go func(i int) {
			defer wg.Done()
			for j := 0; j < 5; j++ {
				if i%2 == 0 {
					// only the first call installs; the rest log a duplicate warning
					instance.SetV2rayStats("direct")
				} else {
					for k := 0; k < 10; k++ {
						instance.QueryStats("direct", "uplink")
					}
				}
			}
		}(i)
	}
	wg.Wait()

	if instance.v2api == nil {
		t.Fatal("SetV2rayStats before Start did not install a tracker")
	}
	// the winner installed exactly one tracker serving both directions
	if got := instance.QueryStats("direct", "uplink"); got != 0 {
		t.Fatalf("QueryStats = %d, want 0 (no traffic)", got)
	}
	if err := instance.Close(); err != nil {
		t.Fatal(err)
	}
}

// Start 之后的 SetV2rayStats 必须忽略：上游 Router.trackers 无锁，
// 此时 AppendTracker 会与路由并发读 trackers
func TestBoxInstanceSetV2rayStatsAfterStart(t *testing.T) {
	instance := newStartedTestBox(t)
	instance.SetV2rayStats("direct")
	if instance.v2api != nil {
		t.Fatal("SetV2rayStats installed a tracker on a started instance")
	}
	if err := instance.Close(); err != nil {
		t.Fatal(err)
	}
}

// SetV2rayStats/QueryStats after Close must be no-ops: AppendTracker on a
// closed box would panic, and a late UI call must not crash the process.
func TestBoxInstanceStatsAfterClose(t *testing.T) {
	instance := newStartedTestBox(t)
	if err := instance.Close(); err != nil {
		t.Fatal(err)
	}

	instance.SetV2rayStats("direct")
	if instance.v2api != nil {
		t.Fatal("SetV2rayStats installed a tracker on a closed instance")
	}
	if got := instance.QueryStats("direct", "uplink"); got != 0 {
		t.Fatalf("QueryStats after Close = %d", got)
	}
}

// SetAsMain on a closed instance must not install it: UrlTest and
// ResetAllConnections would otherwise run against a torn-down box.
func TestBoxInstanceSetAsMainAfterClose(t *testing.T) {
	instance := newTestBox(t)
	if err := instance.Close(); err != nil {
		t.Fatal(err)
	}

	instance.SetAsMain()
	if getMainInstance() == instance {
		t.Fatal("SetAsMain installed a closed instance as main")
	}
}

// ResetAllConnections must skip a closed main instance: ResetNetwork on a
// torn-down box calls InterfaceUpdated on closed endpoints with no
// guaranteed behavior.
func TestBoxInstanceResetAllConnectionsAfterClose(t *testing.T) {
	instance := newStartedTestBox(t)
	if err := instance.Close(); err != nil {
		t.Fatal(err)
	}
	// Close normally clears the reference; reinstall the closed instance so
	// that the state check inside ResetAllConnections is the thing under test
	mainInstanceAccess.Lock()
	mainInstance = instance
	mainInstanceAccess.Unlock()
	defer func() {
		mainInstanceAccess.Lock()
		mainInstance = nil
		mainInstanceAccess.Unlock()
	}()

	ResetAllConnections()
}

// 钉住 libcore/sing-box/NEKO.md 里 needCacheFile 的补丁：libcore 总是传 PlatformLogWriter，
// 上游在 needCacheFile 与「创建 trafficcontrol.Manager + clashmode.Manager」两个条件里都有
// `|| options.PlatformLogWriter != nil`，补丁去掉了这两处。条件被改回上游写法时，任何配置都会常驻
// cache file 服务（工作目录多出 cache.db，选择器启动时用缓存的选择覆盖应用指定的默认项）和
// traffic manager（每条连接多一层 tracker）。traffic manager 用 MustRegisterPtr 注册，读取要用 PtrFromContext。
//
// 阳性对照先证明断言测得到「有」，下面最小配置的「没有」才是补丁的效果。
func TestBoxInstanceOptionalServicesNeedExplicitConfig(t *testing.T) {
	// 工作目录换成临时目录：cache file 的默认路径是进程当前目录下的 cache.db
	dir := t.TempDir()
	t.Chdir(dir)

	startClose := func(t *testing.T, config string) (*BoxInstance, func()) {
		t.Helper()
		instance, err := NewSingBoxInstance(config, nil)
		if err != nil {
			t.Fatal(err)
		}
		if err := instance.Start(); err != nil {
			t.Fatal(err)
		}
		return instance, func() {
			if err := instance.Close(); err != nil {
				t.Fatal(err)
			}
		}
	}

	t.Run("positive cache_file", func(t *testing.T) {
		path := filepath.Join(dir, "explicit-cache.db")
		config := fmt.Sprintf(`{
			"log": {"level": "panic"},
			"outbounds": [{"type": "direct", "tag": "direct"}],
			"experimental": {"cache_file": {"enabled": true, "path": %q}}
		}`, path)
		instance, closeBox := startClose(t, config)
		defer closeBox()
		if service.FromContext[adapter.CacheFile](instance.ctx) == nil {
			t.Fatal("cache_file enabled but no CacheFile registered")
		}
		if _, err := os.Stat(path); err != nil {
			t.Fatalf("cache file not created: %v", err)
		}
	})

	t.Run("positive clash_api", func(t *testing.T) {
		// external_controller 留空：不监听端口，只让 traffic manager 被创建
		instance, closeBox := startClose(t, `{
			"log": {"level": "panic"},
			"outbounds": [{"type": "direct", "tag": "direct"}],
			"experimental": {"clash_api": {}}
		}`)
		defer closeBox()
		if service.PtrFromContext[trafficcontrol.Manager](instance.ctx) == nil {
			t.Fatal("clash_api configured but no traffic manager registered")
		}
	})

	t.Run("minimal config registers neither", func(t *testing.T) {
		instance, closeBox := startClose(t, lifecycleBoxConfig)
		if service.FromContext[adapter.CacheFile](instance.ctx) != nil {
			t.Error("CacheFile registered without experimental.cache_file")
		}
		if service.PtrFromContext[trafficcontrol.Manager](instance.ctx) != nil {
			t.Error("traffic manager registered without clash_api / v2ray_api")
		}
		closeBox()
		if _, err := os.Stat(filepath.Join(dir, "cache.db")); !os.IsNotExist(err) {
			t.Errorf("cache.db left in working directory (stat err = %v)", err)
		}
	})
}
