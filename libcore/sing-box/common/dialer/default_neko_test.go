package dialer

import (
	"context"
	"io"
	"net"
	"testing"

	C "github.com/sagernet/sing-box/constant"
	M "github.com/sagernet/sing/common/metadata"
)

// 构造带非 nil networkStrategy、但没有 networkManager 的 dialer：一旦走进接口选择
// 路径就会解引用 nil networkManager 而 panic，走普通拨号 / 监听则正常返回。
// 以此断言 DoNotSelectInterface 让四个入口都绕开接口选择（Android VPN protect 依赖它）
func newStrategyDialer() *DefaultDialer {
	strategy := C.NetworkStrategyDefault
	return &DefaultDialer{networkStrategy: &strategy}
}

func withDoNotSelectInterface(t *testing.T) {
	t.Helper()
	previous := DoNotSelectInterface
	DoNotSelectInterface = true
	t.Cleanup(func() { DoNotSelectInterface = previous })
}

// 把接口选择路径的 panic 转成测试失败，报出是哪个入口
func noSelection(t *testing.T, entry string, fn func() error) {
	t.Helper()
	defer func() {
		if r := recover(); r != nil {
			t.Fatalf("%s took the interface selection path: %v", entry, r)
		}
	}()
	if err := fn(); err != nil {
		t.Fatalf("%s: %v", entry, err)
	}
}

// 调用成功时关掉返回的连接，只把错误交给 noSelection
func closeOK(c io.Closer, err error) error {
	if err == nil {
		c.Close()
	}
	return err
}

func TestDoNotSelectInterfaceDial(t *testing.T) {
	withDoNotSelectInterface(t)
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer listener.Close()
	go func() {
		for {
			conn, err := listener.Accept()
			if err != nil {
				return
			}
			conn.Close()
		}
	}()
	address := M.SocksaddrFromNet(listener.Addr())
	strategy := C.NetworkStrategyFallback
	d := newStrategyDialer()

	noSelection(t, "DialContext", func() error {
		return closeOK(d.DialContext(context.Background(), "tcp", address))
	})
	// 调用方显式传入 strategy 也必须忽略
	noSelection(t, "DialParallelInterface", func() error {
		return closeOK(d.DialParallelInterface(context.Background(), "tcp", address, &strategy, nil, nil, 0))
	})
}

func TestDoNotSelectInterfaceListenPacket(t *testing.T) {
	withDoNotSelectInterface(t)
	destination := M.ParseSocksaddrHostPort("127.0.0.1", 53)
	strategy := C.NetworkStrategyFallback
	d := newStrategyDialer()

	noSelection(t, "ListenPacket", func() error {
		return closeOK(d.ListenPacket(context.Background(), destination))
	})
	noSelection(t, "ListenSerialInterfacePacket", func() error {
		return closeOK(d.ListenSerialInterfacePacket(context.Background(), destination, &strategy, nil, nil, 0))
	})
}
