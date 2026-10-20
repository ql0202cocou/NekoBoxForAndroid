// 本仓库新增的测试（1.14.3-neko-3 补丁「ClientBind 的退避与竞争」，见 libcore/sing-box/NEKO.md）。
//   - ClientBind：连不上时 Send 立即报错；接收协程的退避在 bind 关闭后立即返回（暂停期间也是）。
//     把两处改回上游的 WaitActive 加 time.Sleep(time.Second) 时相应的子测试失败。
//   - 并发收发与关闭：只在 -race 下有意义（CI 的 race 步骤），c.conn 改回普通指针时报数据竞争。
//
// 都不需要 root，也不需要 with_gvisor 标签。
package wireguard

import (
	"context"
	"errors"
	"net"
	"net/netip"
	"sync"
	"testing"
	"time"

	"github.com/sagernet/sing/common/logger"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
	"github.com/sagernet/sing/service"
	"github.com/sagernet/sing/service/pause"
	"github.com/sagernet/wireguard-go/conn"
)

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
