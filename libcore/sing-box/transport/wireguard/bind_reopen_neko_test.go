package wireguard

import (
	"bytes"
	"context"
	"encoding/binary"
	"errors"
	"net"
	"net/netip"
	"runtime"
	"sync"
	"testing"
	"time"

	"github.com/sagernet/sing/common/logger"
	M "github.com/sagernet/sing/common/metadata"
	"github.com/sagernet/wireguard-go/conn"
	"github.com/sagernet/wireguard-go/device"
	"golang.org/x/crypto/curve25519"
)

// 关闭必须取消旧拨号，下一轮则能用新上下文重新收发；覆盖单对端 Dial 与多对端 Listen。
func TestNekoClientBindReopenResumesTraffic(t *testing.T) {
	for _, isConnect := range []bool{true, false} {
		name := "listen"
		if isConnect {
			name = "connect"
		}
		t.Run(name, func(t *testing.T) {
			server, err := net.ListenPacket("udp", "127.0.0.1:0")
			if err != nil {
				t.Fatal(err)
			}
			defer server.Close()
			serverAddr := server.LocalAddr().(*net.UDPAddr).AddrPort()
			dialer := &nekoReopenDialer{started: make(chan struct{})}
			bind := NewClientBind(nekoPauseContext(t, false), logger.NOP(), dialer, isConnect, serverAddr, [3]uint8{})
			if _, _, err := bind.Open(0); err != nil {
				t.Fatal(err)
			}
			defer bind.Close()
			payload := []byte{0, 0, 0, 0, 5, 6}
			pending := make(chan error, 1)
			go func() { pending <- bind.Send([][]byte{payload}, remoteEndpoint(serverAddr), 0) }()
			nekoWaitReopenSignal(t, dialer.started, "旧拨号未开始")
			closed := make(chan struct{})
			go func() {
				bind.Close()
				close(closed)
			}()
			nekoWaitReopenSignal(t, closed, "Close 未取消在途拨号")
			if err := <-pending; !errors.Is(err, context.Canceled) {
				t.Fatalf("旧拨号应被取消，实际：%v", err)
			}
			for round := 0; round < 2; round++ {
				fns, _, err := bind.Open(0)
				if err != nil {
					t.Fatal(err)
				}
				if err := bind.Send([][]byte{payload}, remoteEndpoint(serverAddr), 0); err != nil {
					t.Fatalf("重开后发送失败：%v", err)
				}
				if err := server.SetDeadline(time.Now().Add(3 * time.Second)); err != nil {
					t.Fatal(err)
				}
				packet := make([]byte, 64)
				n, addr, err := server.ReadFrom(packet)
				if err != nil || !bytes.Equal(packet[:n], payload) {
					t.Fatalf("重开后的发送数据不符：%v，%v", packet[:n], err)
				}
				if _, err := server.WriteTo(payload, addr); err != nil {
					t.Fatal(err)
				}
				received := make(chan error, 1)
				sizes := make([]int, 1)
				eps := make([]conn.Endpoint, 1)
				go func() {
					_, err := fns[0]([][]byte{packet}, sizes, eps)
					received <- err
				}()
				select {
				case err := <-received:
					if err != nil || !bytes.Equal(packet[:sizes[0]], payload) || eps[0].DstToString() != serverAddr.String() {
						t.Fatalf("重开后的接收数据或来源不符：%v，%v，%v", packet[:sizes[0]], eps[0], err)
					}
				case <-time.After(3 * time.Second):
					t.Fatal("重开后未收到回复")
				}
				bind.Close()
			}
		})
	}
}

type nekoReopenDialer struct {
	started chan struct{}
	first   sync.Once
}

func (d *nekoReopenDialer) waitFirst(ctx context.Context) error {
	d.first.Do(func() {
		close(d.started)
		<-ctx.Done()
	})
	return ctx.Err()
}

func (d *nekoReopenDialer) DialContext(ctx context.Context, network string, destination M.Socksaddr) (net.Conn, error) {
	if err := d.waitFirst(ctx); err != nil {
		return nil, err
	}
	return (nekoUDPDialer{}).DialContext(ctx, network, destination)
}

func (d *nekoReopenDialer) ListenPacket(ctx context.Context, destination M.Socksaddr) (net.PacketConn, error) {
	if err := d.waitFirst(ctx); err != nil {
		return nil, err
	}
	return (nekoUDPDialer{}).ListenPacket(ctx, destination)
}

// 用真实 wireguard-go 的握手队列触发 Cookie 回复。普通发送持有设备的 net 读锁，
// Cookie 回复却不持锁，且 BindUpdate 只等待接收协程，不等待握手工作协程。
// 因而 Cookie 的 Send 可以跨过 Close，与下一次 Open 交错；直接并发调用裸 Bind
// 无法证明这个交错在实际调用链中可达。
func TestNekoClientBindReopenDuringCookieReply(t *testing.T) {
	workerGate := make(chan struct{})
	var releaseWorker sync.Once
	bind := &nekoCookieReopenBind{
		ClientBind: NewClientBind(nekoPauseContext(t, false), logger.NOP(), nekoNopDialer{}, true,
			netip.MustParseAddrPort("127.0.0.1:9"), [3]uint8{}),
		queued:       make(chan struct{}),
		cookieReady:  make(chan struct{}),
		cookieGate:   make(chan struct{}),
		cookieResult: make(chan error, 1),
	}
	dev := device.NewDevice(bind.ctx, newNekoFakeTun(), bind, &device.Logger{
		Verbosef: func(format string, args ...any) {
			if format == "Routine: handshake worker %d - started" {
				<-workerGate
			}
		},
		Errorf: func(string, ...any) {},
	}, 1)
	t.Cleanup(func() {
		releaseWorker.Do(func() { close(workerGate) })
		bind.releaseCookie.Do(func() { close(bind.cookieGate) })
		dev.Close()
	})
	// 固定测试密钥；只需有效 MAC1 即可触发负载保护，不需要配置对端或完成握手。
	privateKey := device.NoisePrivateKey{1}
	if err := dev.SetPrivateKey(privateKey); err != nil {
		t.Fatal(err)
	}
	var publicKey device.NoisePublicKey
	curve25519.ScalarBaseMult((*[32]byte)(&publicKey), (*[32]byte)(&privateKey))
	var generator device.CookieGenerator
	generator.Init(publicKey)
	bind.packet = make([]byte, device.MessageInitiationSize)
	binary.LittleEndian.PutUint32(bind.packet, device.MessageInitiationType)
	generator.AddMacs(bind.packet)
	if err := dev.Up(); err != nil {
		t.Fatal(err)
	}
	nekoWaitReopenSignal(t, bind.queued, "握手队列没有填满")
	releaseWorker.Do(func() { close(workerGate) })
	nekoWaitReopenSignal(t, bind.cookieReady, "真实握手工作协程未发送 Cookie 回复")
	updated := make(chan error, 1)
	go func() { updated <- dev.BindUpdate() }()
	select {
	case err := <-updated:
		if err != nil {
			t.Fatal(err)
		}
	case <-time.After(3 * time.Second):
		t.Fatal("Cookie 回复在途时 BindUpdate 未完成")
	}
	select {
	case err := <-bind.cookieResult:
		if err == nil {
			t.Fatal("测试拨号器始终拒绝拨号，Send 不应成功")
		}
	case <-time.After(3 * time.Second):
		t.Fatal("重开后 Cookie 回复未退出")
	}
}

func nekoWaitReopenSignal(t *testing.T, signal <-chan struct{}, message string) {
	t.Helper()
	select {
	case <-signal:
	case <-time.After(3 * time.Second):
		t.Fatal(message)
	}
}

type nekoCookieReopenBind struct {
	*ClientBind
	packet        []byte
	opens         int
	queued        chan struct{}
	cookieReady   chan struct{}
	cookieGate    chan struct{}
	cookieResult  chan error
	releaseCookie sync.Once
}

func (b *nekoCookieReopenBind) Open(port uint16) ([]conn.ReceiveFunc, uint16, error) {
	b.opens++
	if b.opens > 1 {
		// 此时真实 BindUpdate 已关闭旧 bind 并等完旧接收协程。
		// 只释放 Cookie 发送，不等它完成；与 Open 之间没有人为的先后保证。
		b.releaseCookie.Do(func() { close(b.cookieGate) })
		runtime.Gosched()
	}
	fns, actualPort, err := b.ClientBind.Open(port)
	if err != nil || b.opens > 1 {
		return fns, actualPort, err
	}
	remaining := device.QueueHandshakeSize/8 + 1
	receive := fns[0]
	fns[0] = func(packets [][]byte, sizes []int, eps []conn.Endpoint) (int, error) {
		if remaining > 0 {
			remaining--
			sizes[0] = copy(packets[0], b.packet)
			eps[0] = remoteEndpoint(netip.MustParseAddrPort("127.0.0.1:9"))
			return 1, nil
		}
		if remaining == 0 {
			remaining--
			close(b.queued)
		}
		return receive(packets, sizes, eps)
	}
	return fns, actualPort, nil
}

func (b *nekoCookieReopenBind) Send(bufs [][]byte, ep conn.Endpoint, offset int) error {
	if binary.LittleEndian.Uint32(bufs[0][offset:]) != device.MessageCookieReplyType {
		panic("测试应只发送 Cookie 回复")
	}
	// 只放行第一个回复，其余队列项无需重复发送。
	select {
	case <-b.cookieReady:
		return nil
	default:
		close(b.cookieReady)
	}
	<-b.cookieGate
	err := b.ClientBind.Send(bufs, ep, offset)
	b.cookieResult <- err
	return err
}
