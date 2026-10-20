// 本仓库新增的测试（1.14.3-neko-3 补丁「WireGuard 只在固定 listen_port 时用 StdNetBind」与「ClientBind 的
// 退避与竞争」，见 libcore/sing-box/NEKO.md）。
//   - 选 bind：没有 listen_port 的端点不走 StdNetBind。StdNetBind 的批量为 128，wireguard-go 的读 TUN 协程一启动
//     就预占 128 个 64 KB 缓冲；这里构造几个端点、Start 后量堆的增量（假 TUN 不发 EventUp，设备不 Up、不开 socket、
//     不发包），把 Start 里选 bind 的那行改回上游写法时增量约为每个端点 8 MB，测试失败。
//   - ClientBind：连不上时 Send 立即报错；接收协程的退避在 bind 关闭后立即返回（暂停期间也是）。
//     把两处改回上游的 WaitActive 加 time.Sleep(time.Second) 时相应的子测试失败。
//   - 并发收发与关闭：只在 -race 下有意义（CI 的 race 步骤），c.conn 改回普通指针时报数据竞争。
//
// 都不需要 root，也不需要 with_gvisor 标签。
package wireguard

import (
	"context"
	"encoding/base64"
	"errors"
	"net"
	"net/netip"
	"runtime"
	"sync"
	"testing"
	"time"

	"github.com/sagernet/sing/common/control"
	"github.com/sagernet/sing/common/logger"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
	"github.com/sagernet/sing/service"
	"github.com/sagernet/sing/service/pause"
	"github.com/sagernet/wireguard-go/conn"
)

// 实现 dialer.UDPListener 的拨号器（上游凭这个接口选 StdNetBind），拨号一律报错
type nekoListenerDialer struct {
	nekoNopDialer
}

func (nekoListenerDialer) UDPListenerControl() (control.Func, bool) { return nil, false }

func TestNekoStdNetBindOnlyWithListenPort(t *testing.T) {
	if _, ok := stdNetBindListener(nekoListenerDialer{}, 0); ok {
		t.Fatal("没有 listen_port 时不应使用 StdNetBind")
	}
	if _, ok := stdNetBindListener(nekoListenerDialer{}, 51820); !ok {
		t.Fatal("固定 listen_port 时应使用 StdNetBind")
	}
	if _, ok := stdNetBindListener(nekoNopDialer{}, 51820); ok {
		t.Fatal("拨号器不是 UDPListener（设了 detour）时不能使用 StdNetBind")
	}
}

func nekoHeapInuse() uint64 {
	runtime.GC()
	runtime.GC()
	var stats runtime.MemStats
	runtime.ReadMemStats(&stats)
	return stats.HeapInuse
}

func TestNekoEndpointWithoutListenPortSkipsBatchBuffers(t *testing.T) {
	const endpointCount = 8
	before := nekoHeapInuse()
	var endpoints []*Endpoint
	for i := 0; i < endpointCount; i++ {
		endpoint, err := NewEndpoint(EndpointOptions{
			Context:      context.Background(),
			Logger:       logger.NOP(),
			System:       true,
			Dialer:       nekoListenerDialer{},
			CreateDialer: func(string) N.Dialer { return nekoNopDialer{} },
			Name:         "neko-test",
			Address:      []netip.Prefix{netip.MustParsePrefix("172.16.0.2/32")},
			PrivateKey:   base64.StdEncoding.EncodeToString(nekoKey(0x1a, 32)),
			Peers: []PeerOptions{{
				Endpoint:   M.ParseSocksaddrHostPort("127.0.0.1", 9),
				PublicKey:  base64.StdEncoding.EncodeToString(nekoKey(0x2b, 32)),
				AllowedIPs: []netip.Prefix{netip.MustParsePrefix("0.0.0.0/0")},
			}},
		})
		if err != nil {
			t.Fatalf("NewEndpoint: %v", err)
		}
		fakeTun := newNekoFakeTun()
		endpoint.tunDevice = fakeTun
		endpoint.returnDevice = &returnDeviceWrapper{Device: fakeTun}
		t.Cleanup(func() { endpoint.Close() })
		if err := endpoint.Start(false); err != nil {
			t.Fatalf("Start: %v", err)
		}
		endpoints = append(endpoints, endpoint)
	}
	// 读 TUN 协程在 NewDevice 里异步启动，给它时间把缓冲拿到手
	time.Sleep(500 * time.Millisecond)
	after := nekoHeapInuse()
	runtime.KeepAlive(endpoints)
	var grown uint64
	if after > before {
		grown = after - before
	}
	if limit := uint64(endpointCount) << 20; grown > limit {
		t.Fatalf("%d 个端点 Start 后堆增长 %d MB，超过每个端点 1 MB（StdNetBind 的批量缓冲每个约 8 MB）",
			endpointCount, grown>>20)
	}
}

// 有暂停管理器的上下文；paused 时让设备处于暂停状态（WaitActive 会一直等）
func nekoPauseContext(t *testing.T, paused bool) context.Context {
	ctx, cancel := context.WithCancel(context.Background())
	t.Cleanup(cancel)
	ctx = pause.WithDefaultManager(ctx)
	if paused {
		service.FromContext[pause.Manager](ctx).DevicePause()
	}
	return ctx
}

func TestNekoClientBindSendFailsFast(t *testing.T) {
	for _, paused := range []bool{false, true} {
		name := "active"
		if paused {
			name = "device paused"
		}
		t.Run(name, func(t *testing.T) {
			bind := NewClientBind(nekoPauseContext(t, paused), logger.NOP(), nekoNopDialer{}, true,
				netip.MustParseAddrPort("127.0.0.1:9"), [3]uint8{})
			if _, _, err := bind.Open(0); err != nil {
				t.Fatal(err)
			}
			defer bind.Close()
			result := make(chan error, 1)
			go func() {
				result <- bind.Send([][]byte{make([]byte, 32)}, remoteEndpoint(netip.MustParseAddrPort("127.0.0.1:9")), 0)
			}()
			select {
			case err := <-result:
				if err == nil {
					t.Fatal("拨号失败时 Send 应当报错")
				}
			case <-time.After(300 * time.Millisecond):
				t.Fatal("拨号失败时 Send 没有立即返回")
			}
		})
	}
}

func TestNekoClientBindReceiveStopsOnClose(t *testing.T) {
	for _, paused := range []bool{false, true} {
		name := "active"
		if paused {
			name = "device paused"
		}
		t.Run(name, func(t *testing.T) {
			bind := NewClientBind(nekoPauseContext(t, paused), logger.NOP(), nekoNopDialer{}, true,
				netip.MustParseAddrPort("127.0.0.1:9"), [3]uint8{})
			fns, _, err := bind.Open(0)
			if err != nil {
				t.Fatal(err)
			}
			stopped := make(chan struct{})
			// 照 wireguard-go 的 RoutineReceiveIncoming：报错才退出，连不上时（count 0、无错）继续调
			go func() {
				defer close(stopped)
				packets := [][]byte{make([]byte, 1500)}
				sizes := make([]int, 1)
				eps := make([]conn.Endpoint, 1)
				for {
					if _, err := fns[0](packets, sizes, eps); err != nil {
						return
					}
				}
			}()
			// 让接收协程进入退避
			time.Sleep(100 * time.Millisecond)
			closedAt := time.Now()
			bind.Close()
			select {
			case <-stopped:
			case <-time.After(300 * time.Millisecond):
				t.Fatal("bind 关闭后接收协程仍在退避")
			}
			if elapsed := time.Since(closedAt); elapsed > 300*time.Millisecond {
				t.Fatalf("接收协程在关闭后 %v 才退出", elapsed)
			}
		})
	}
}

// 真实拨号到本机 UDP 的拨号器
type nekoUDPDialer struct{}

func (nekoUDPDialer) DialContext(ctx context.Context, network string, destination M.Socksaddr) (net.Conn, error) {
	var dialer net.Dialer
	return dialer.DialContext(ctx, N.NetworkUDP, destination.String())
}

func (nekoUDPDialer) ListenPacket(ctx context.Context, destination M.Socksaddr) (net.PacketConn, error) {
	return net.ListenPacket(N.NetworkUDP, "127.0.0.1:0")
}

func TestNekoClientBindConcurrentSendReceiveClose(t *testing.T) {
	server, err := net.ListenPacket(N.NetworkUDP, "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer server.Close()
	serverAddr := server.LocalAddr().(*net.UDPAddr).AddrPort()
	bind := NewClientBind(nekoPauseContext(t, false), logger.NOP(), nekoUDPDialer{}, true, serverAddr, [3]uint8{})
	fns, _, err := bind.Open(0)
	if err != nil {
		t.Fatal(err)
	}
	var wg sync.WaitGroup
	wg.Add(1)
	go func() {
		defer wg.Done()
		packets := [][]byte{make([]byte, 1500)}
		sizes := make([]int, 1)
		eps := make([]conn.Endpoint, 1)
		for {
			if _, err := fns[0](packets, sizes, eps); err != nil {
				return
			}
		}
	}()
	for i := 0; i < 8; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for j := 0; j < 50; j++ {
				err := bind.Send([][]byte{make([]byte, 32)}, remoteEndpoint(serverAddr), 0)
				if err != nil && !errors.Is(err, net.ErrClosed) {
					return
				}
			}
		}()
	}
	time.Sleep(50 * time.Millisecond)
	bind.Close()
	done := make(chan struct{})
	go func() {
		wg.Wait()
		close(done)
	}()
	select {
	case <-done:
	case <-time.After(3 * time.Second):
		t.Fatal("关闭后收发协程没有退出")
	}
}
