// 本仓库新增的测试（1.14.3-neko-2 补丁「WireGuard 启动报错不带密钥」，见 libcore/sing-box/NEKO.md）。
// 端点 Start 时 IpcSet 失败，上游把整段 ipcConf（私钥、公钥、预共享密钥的十六进制）拼进报错；这条报错会经
// libcore 进应用的日志和界面。这里让三种密钥分别长度不对（31 字节）使 IpcSet 失败，断言 Start 的报错和端点
// 写出的日志都不含任何一把密钥（十六进制与 base64），同时报错仍指明是哪个字段失败。
// 把补丁改回上游写法时三个子测试都会失败。
// 端点用真的 NewEndpoint 构造（System 模式不带 gVisor 时构造期不碰系统），Start 前换成假 TUN 设备，
// 不需要 root，也不需要 with_gvisor 标签，CI 的 race 步骤能跑到。
package wireguard

import (
	"bytes"
	"context"
	"encoding/base64"
	"encoding/hex"
	"fmt"
	"net"
	"net/netip"
	"os"
	"strings"
	"sync"
	"testing"

	"github.com/sagernet/sing/common/logger"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
	"github.com/sagernet/wireguard-go/device"
	wgTun "github.com/sagernet/wireguard-go/tun"
)

// 假 TUN 设备：不收发任何包，Close 之后 Read 报错，wireguard-go 的读循环随之退出
type nekoFakeTun struct {
	events    chan wgTun.Event
	closed    chan struct{}
	closeOnce sync.Once
}

func newNekoFakeTun() *nekoFakeTun {
	return &nekoFakeTun{events: make(chan wgTun.Event), closed: make(chan struct{})}
}

func (d *nekoFakeTun) File() *os.File { return nil }

func (d *nekoFakeTun) Read(bufs [][]byte, sizes []int, offset int) (int, error) {
	<-d.closed
	return 0, os.ErrClosed
}

func (d *nekoFakeTun) Write(bufs [][]byte, offset int) (int, error) { return len(bufs), nil }
func (d *nekoFakeTun) MTU() (int, error)                            { return 1408, nil }
func (d *nekoFakeTun) Name() (string, error)                        { return "neko-fake", nil }
func (d *nekoFakeTun) Events() <-chan wgTun.Event                   { return d.events }
func (d *nekoFakeTun) BatchSize() int                               { return 1 }

func (d *nekoFakeTun) Close() error {
	d.closeOnce.Do(func() {
		close(d.closed)
		close(d.events)
	})
	return nil
}

func (d *nekoFakeTun) DialContext(ctx context.Context, network string, destination M.Socksaddr) (net.Conn, error) {
	return nil, os.ErrInvalid
}

func (d *nekoFakeTun) ListenPacket(ctx context.Context, destination M.Socksaddr) (net.PacketConn, error) {
	return nil, os.ErrInvalid
}

func (d *nekoFakeTun) Start() error                    { return nil }
func (d *nekoFakeTun) SetDevice(device *device.Device) {}
func (d *nekoFakeTun) Inet4Address() netip.Addr        { return netip.Addr{} }
func (d *nekoFakeTun) Inet6Address() netip.Addr        { return netip.Addr{} }

// 端点的拨号器：测试走不到真正拨号，一律报错
type nekoNopDialer struct{}

func (nekoNopDialer) DialContext(ctx context.Context, network string, destination M.Socksaddr) (net.Conn, error) {
	return nil, os.ErrInvalid
}

func (nekoNopDialer) ListenPacket(ctx context.Context, destination M.Socksaddr) (net.PacketConn, error) {
	return nil, os.ErrInvalid
}

var _ N.Dialer = nekoNopDialer{}

// 记下端点写出的日志（wireguard-go 的 Verbosef / Errorf 经端点转到 Debug / Error）
type nekoRecordLogger struct {
	logger.ContextLogger
	mu    sync.Mutex
	lines []string
}

func (l *nekoRecordLogger) record(args ...any) {
	l.mu.Lock()
	defer l.mu.Unlock()
	l.lines = append(l.lines, fmt.Sprint(args...))
}

func (l *nekoRecordLogger) Trace(args ...any) { l.record(args...) }
func (l *nekoRecordLogger) Debug(args ...any) { l.record(args...) }
func (l *nekoRecordLogger) Info(args ...any)  { l.record(args...) }
func (l *nekoRecordLogger) Warn(args ...any)  { l.record(args...) }
func (l *nekoRecordLogger) Error(args ...any) { l.record(args...) }

func (l *nekoRecordLogger) text() string {
	l.mu.Lock()
	defer l.mu.Unlock()
	return strings.Join(l.lines, "\n")
}

// 每把密钥用不同的字节填充，便于在报错里精确查找
func nekoKey(fill byte, size int) []byte {
	return bytes.Repeat([]byte{fill}, size)
}

func TestNekoStartErrorHidesKeys(t *testing.T) {
	cases := []struct {
		name      string
		private   []byte
		public    []byte
		preShared []byte
		reason    string
	}{
		{"private key", nekoKey(0x1a, 31), nekoKey(0x2b, 32), nekoKey(0x3c, 32), "failed to set private_key"},
		{"public key", nekoKey(0x1a, 32), nekoKey(0x2b, 31), nekoKey(0x3c, 32), "failed to get peer by public key"},
		{"preshared key", nekoKey(0x1a, 32), nekoKey(0x2b, 32), nekoKey(0x3c, 31), "failed to set preshared key"},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			recorder := &nekoRecordLogger{ContextLogger: logger.NOP()}
			endpoint, err := NewEndpoint(EndpointOptions{
				Context:      context.Background(),
				Logger:       recorder,
				System:       true,
				Dialer:       nekoNopDialer{},
				CreateDialer: func(string) N.Dialer { return nekoNopDialer{} },
				Name:         "neko-test",
				Address:      []netip.Prefix{netip.MustParsePrefix("172.16.0.2/32")},
				PrivateKey:   base64.StdEncoding.EncodeToString(c.private),
				Peers: []PeerOptions{{
					Endpoint:     M.ParseSocksaddrHostPort("198.51.100.1", 51820),
					PublicKey:    base64.StdEncoding.EncodeToString(c.public),
					PreSharedKey: base64.StdEncoding.EncodeToString(c.preShared),
					AllowedIPs:   []netip.Prefix{netip.MustParsePrefix("0.0.0.0/0")},
				}},
			})
			if err != nil {
				t.Fatalf("NewEndpoint: %v", err)
			}
			fakeTun := newNekoFakeTun()
			endpoint.tunDevice = fakeTun
			endpoint.returnDevice = &returnDeviceWrapper{Device: fakeTun}
			t.Cleanup(func() { endpoint.Close() })

			err = endpoint.Start(false)
			if err == nil {
				t.Fatal("Start 应当因密钥长度不对而失败")
			}
			msg := err.Error()
			if !strings.Contains(msg, "setup wireguard") || !strings.Contains(msg, c.reason) {
				t.Fatalf("报错应指明 setup wireguard 与失败字段 %q，实际：%q", c.reason, msg)
			}
			logs := recorder.text()
			for _, key := range [][]byte{c.private, c.public, c.preShared} {
				for _, form := range []string{hex.EncodeToString(key), base64.StdEncoding.EncodeToString(key)} {
					if strings.Contains(msg, form) {
						t.Errorf("Start 的报错含密钥（%d 字符）", len(form))
					}
					if strings.Contains(logs, form) {
						t.Errorf("端点日志含密钥（%d 字符）", len(form))
					}
				}
			}
		})
	}
}
