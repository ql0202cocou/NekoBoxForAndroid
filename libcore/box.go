package libcore

import (
	"context"
	"errors"
	"fmt"
	"libcore/device"
	"log"
	"net/http"
	"strings"
	"sync"

	"github.com/matsuridayo/libneko/speedtest"
	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/boxapi"
	"github.com/sagernet/sing-box/protocol/group"

	box "github.com/sagernet/sing-box"
	"github.com/sagernet/sing-box/common/dialer"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing/service"
	"github.com/sagernet/sing/service/pause"
)

func init() {
	dialer.DoNotSelectInterface = true
}

var mainInstanceAccess sync.Mutex
var mainInstance *BoxInstance

func getMainInstance() *BoxInstance {
	mainInstanceAccess.Lock()
	defer mainInstanceAccess.Unlock()
	return mainInstance
}

func ResetAllConnections() {
	defer device.DeferPanicToError("ResetAllConnections", nil)

	// sing-box 1.13 移除了 conntrack，改由 ResetNetwork 关闭所有连接
	if main := getMainInstance(); main != nil {
		// getMainInstance 已释放 mainInstanceAccess，此处再取 main.access
		// 不会与 Close 的 b.access -> mainInstanceAccess 加锁顺序死锁（同
		// UrlTest）。ResetNetwork 期间持锁：对已关闭的 box 调用会在已关闭的
		// 端点上执行 InterfaceUpdated，行为无保证
		if main.lockIfOpen() {
			main.Network().ResetNetwork(context.Background())
			main.access.Unlock()
		}
	}
	log.Println("Reset system connections done")
}

// BoxInstance lifecycle states, in the order they are reached.
const (
	boxNew = iota
	boxStarted
	boxClosed
)

type BoxInstance struct {
	access sync.Mutex

	*box.Box
	cancel context.CancelFunc
	state  int

	v2api        *boxapi.SbV2rayServer
	selector     *group.Selector
	pauseManager pause.Manager
}

func NewSingBoxInstance(config string, localTransport LocalDNSTransport) (b *BoxInstance, err error) {
	defer device.DeferPanicToError("NewSingBoxInstance", func(err_ error) { err = err_ })

	// custom CA and (in :bg) the geo assets must be in place before the box
	// opens rule-sets and dials; see assetsReady
	waitAssetsReady()

	// create box context
	ctx, cancel := context.WithCancel(context.Background())
	// Cancel unless we hand the context off to a BoxInstance; covers every error return.
	defer func() {
		if b == nil {
			cancel()
		}
	}()
	ctx = box.Context(ctx,
		nekoboxAndroidInboundRegistry(), nekoboxAndroidOutboundRegistry(), nekoboxAndroidEndpointRegistry(),
		nekoboxAndroidDNSTransportRegistry(localTransport), nekoboxAndroidServiceRegistry(),
		nekoboxAndroidCertificateProviderRegistry(),
	)
	ctx = service.ContextWithDefaultRegistry(ctx)
	service.MustRegister[adapter.PlatformInterface](ctx, boxPlatformInterfaceInstance)

	// parse options
	var options option.Options
	err = options.UnmarshalJSONContext(ctx, []byte(config))
	if err != nil {
		return nil, fmt.Errorf("decode config: %w", err)
	}

	// create box
	instance, err := box.New(box.Options{
		Options:           options,
		Context:           ctx,
		PlatformLogWriter: boxPlatformLogWriter,
	})
	if err != nil {
		return nil, fmt.Errorf("create service: %w", err)
	}

	b = &BoxInstance{
		Box:          instance,
		cancel:       cancel,
		pauseManager: service.FromContext[pause.Manager](ctx),
	}

	// selector
	if proxy, ok := b.Outbound().Outbound("proxy"); ok {
		if selector, ok := proxy.(*group.Selector); ok {
			b.selector = selector
		}
	}

	return b, nil
}

// lockIfOpen takes b.access unless the instance is already closed; on false
// the lock is not held and the caller has nothing to do. The state check has
// to happen under the lock, otherwise a concurrent Close can tear the box
// down between the check and whatever the caller does with it.
func (b *BoxInstance) lockIfOpen() bool {
	b.access.Lock()
	if b.state == boxClosed {
		b.access.Unlock()
		return false
	}
	return true
}

func (b *BoxInstance) Start() (err error) {
	b.access.Lock()
	defer b.access.Unlock()

	defer device.DeferPanicToError("box.Start", func(err_ error) { err = err_ })

	if b.state == boxNew {
		b.state = boxStarted
		// Box.Start closes the box itself when it fails, so a retry on this
		// instance is pointless; the state stays boxStarted on purpose so that
		// Close()
		// still cancels the context and drops the main-instance reference.
		return b.Box.Start()
	}
	return errors.New("already started")
}

func (b *BoxInstance) Close() (err error) {
	b.access.Lock()
	defer b.access.Unlock()

	defer device.DeferPanicToError("box.Close", func(err_ error) { err = err_ })

	// no double close
	if b.state == boxClosed {
		return nil
	}
	b.state = boxClosed

	// clear main instance
	mainInstanceAccess.Lock()
	if mainInstance == b {
		mainInstance = nil
		goServeProtect(false)
	}
	mainInstanceAccess.Unlock()

	// close box
	b.cancel()
	return b.Box.Close()
}

func (b *BoxInstance) Sleep() {
	defer device.DeferPanicToError("box.Sleep", nil)

	// 与 SetV2rayStats 等兄弟方法一致：box 已关闭时不再 emit pause 事件
	if !b.lockIfOpen() {
		return
	}
	defer b.access.Unlock()

	if b.pauseManager != nil {
		b.pauseManager.DevicePause()
	}
}

func (b *BoxInstance) Wake() {
	defer device.DeferPanicToError("box.Wake", nil)

	// 同 Sleep：box 已关闭时不再 emit pause 事件
	if !b.lockIfOpen() {
		return
	}
	defer b.access.Unlock()

	if b.pauseManager != nil {
		b.pauseManager.DeviceWake()
	}
}

func (b *BoxInstance) SetAsMain() {
	defer device.DeferPanicToError("box.SetAsMain", nil)

	// b.access -> mainInstanceAccess is the same lock order as Close.
	if !b.lockIfOpen() {
		return
	}
	defer b.access.Unlock()
	mainInstanceAccess.Lock()
	defer mainInstanceAccess.Unlock()
	mainInstance = b
	goServeProtect(true)
}

// SetV2rayStats 装上流量统计服务。app 在 Start 之前调用（ProxyInstance.launch）：
// 启动后再 AppendTracker 会与路由并发读 trackers，只能靠 sing-box 补丁
// "router: lock trackers" 兜住
func (b *BoxInstance) SetV2rayStats(outbounds string) {
	defer device.DeferPanicToError("box.SetV2rayStats", nil)

	if !b.lockIfOpen() {
		return
	}
	defer b.access.Unlock()

	if b.v2api != nil {
		log.Println("duplicate call of SetV2rayStats")
		return
	}
	b.v2api = boxapi.NewSbV2rayServer(option.V2RayStatsServiceOptions{
		Enabled:   true,
		Outbounds: strings.Split(outbounds, "\n"),
	})
	// StatsService 在服务未启用时返回 nil；AppendTracker 不拒绝 nil，
	// 装进去后第一条路由的连接就会 panic
	if tracker := b.v2api.StatsService(); tracker != nil {
		b.Box.Router().AppendTracker(tracker)
	}
}

func (b *BoxInstance) QueryStats(tag, direct string) int64 {
	defer device.DeferPanicToError("box.QueryStats", nil)

	if !b.lockIfOpen() {
		return 0
	}
	defer b.access.Unlock()

	if b.v2api == nil {
		return 0
	}
	return b.v2api.QueryStats(fmt.Sprintf("outbound>>>%s>>>traffic>>>%s", tag, direct))
}

func (b *BoxInstance) SelectOutbound(tag string) bool {
	defer device.DeferPanicToError("box.SelectOutbound", nil)

	if b.selector != nil {
		return b.selector.SelectOutbound(tag)
	}
	return false
}

// newProxyHttpClient builds a client dialing through b. It holds b.access across the
// whole build: a concurrent Close could otherwise tear the box down between the state
// check and CreateProxyHttpClient, panicking inside it.
func (b *BoxInstance) newProxyHttpClient() (*http.Client, error) {
	if !b.lockIfOpen() {
		return nil, errors.New("instance is closed")
	}
	defer b.access.Unlock()
	var connectionTracker adapter.ConnectionTracker
	if b.v2api != nil {
		connectionTracker = b.v2api.StatsService()
	}
	return boxapi.CreateProxyHttpClient(b.Box, connectionTracker), nil
}

func UrlTest(i *BoxInstance, link string, timeout int32) (latency int32, err error) {
	defer device.DeferPanicToError("box.UrlTest", func(err_ error) { err = err_ })
	if i == nil {
		// test mainInstance, or direct when there is none (getMainInstance released
		// mainInstanceAccess already, so taking i.access below cannot deadlock
		// against Close's b.access -> mainInstanceAccess order)
		if i = getMainInstance(); i == nil {
			return speedtest.UrlTest(boxapi.CreateProxyHttpClient(nil, nil), link, timeout, speedtest.UrlTestStandard_RTT)
		}
	}
	httpClient, err := i.newProxyHttpClient()
	if err != nil {
		return 0, err
	}
	return speedtest.UrlTest(httpClient, link, timeout, speedtest.UrlTestStandard_RTT)
}
