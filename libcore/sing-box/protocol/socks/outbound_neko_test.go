// 本仓库新增的测试（neko-2 补丁「SOCKS5 出站在 UDP ASSOCIATE 里声明未指定来源」，见 libcore/sing-box/NEKO.md）。
// 用假 SOCKS5 服务端抓 UDP ASSOCIATE 请求的原始字节，断言声明的 DST 一律是 [::]:0，且数据报头仍是原目标；
// 去掉补丁（ListenPacket / DialContext 改回交给 sing 的 Client）时，私有 IPv4 / IPv6 与公网 IPv4 目标的
// 10 个子测试会失败（sing 分别声明 [::1]:0、127.0.0.1:0、0.0.0.0:0）。
// 另有两个错误路径的测试：认证失败、ctx 到期时 associate 都要关掉 TCP 控制连接（与 sing 原实现相同），
// 删掉握手失败时的 Close 或整段 ctx 处理时它们会失败。
package socks

import (
	"bytes"
	"context"
	"encoding/binary"
	"encoding/hex"
	"errors"
	"io"
	"net"
	"testing"
	"time"

	"github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing-box/option"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
)

// 假 SOCKS5 服务端：只认用户名密码认证，记下 UDP ASSOCIATE 请求的原始字节，回一个本机 UDP 端口作 BND，
// 再把收到的第一个数据报交给测试
type fakeSocksServer struct {
	listener net.Listener
	requests chan []byte
	packets  chan []byte
}

func newFakeSocksServer(t *testing.T) *fakeSocksServer {
	t.Helper()
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	s := &fakeSocksServer{listener: listener, requests: make(chan []byte, 16), packets: make(chan []byte, 16)}
	t.Cleanup(func() { listener.Close() })
	go func() {
		for {
			conn, err := listener.Accept()
			if err != nil {
				return
			}
			go s.serve(conn)
		}
	}()
	return s
}

// readFull 读满 n 字节，出错返回 nil（假服务端遇到异常就直接放弃这条连接）
func readFull(r io.Reader, n int) []byte {
	b := make([]byte, n)
	if _, err := io.ReadFull(r, b); err != nil {
		return nil
	}
	return b
}

// serve 依次处理：方法协商（固定选 02 用户名密码）→ RFC 1929 认证（不校验，回成功）→ 请求（按 ATYP 读完整个 DST）
func (s *fakeSocksServer) serve(conn net.Conn) {
	defer conn.Close()
	greeting := readFull(conn, 2)
	if greeting == nil || readFull(conn, int(greeting[1])) == nil {
		return
	}
	conn.Write([]byte{5, 2})
	authHeader := readFull(conn, 2)
	if authHeader == nil || readFull(conn, int(authHeader[1])) == nil {
		return
	}
	passwordLength := readFull(conn, 1)
	if passwordLength == nil || readFull(conn, int(passwordLength[0])) == nil {
		return
	}
	conn.Write([]byte{1, 0})
	request := readFull(conn, 4)
	if request == nil {
		return
	}
	var addressLength int
	switch request[3] {
	case 1:
		addressLength = 4
	case 4:
		addressLength = 16
	case 3:
		l := readFull(conn, 1)
		if l == nil {
			return
		}
		request = append(request, l...)
		addressLength = int(l[0])
	}
	rest := readFull(conn, addressLength+2)
	if rest == nil {
		return
	}
	s.requests <- append(request, rest...)
	packetConn, err := net.ListenUDP("udp", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		return
	}
	defer packetConn.Close()
	reply := []byte{5, 0, 0, 1, 127, 0, 0, 1}
	reply = binary.BigEndian.AppendUint16(reply, uint16(packetConn.LocalAddr().(*net.UDPAddr).Port))
	conn.Write(reply)
	packetConn.SetReadDeadline(time.Now().Add(5 * time.Second))
	buffer := make([]byte, 2048)
	n, _, err := packetConn.ReadFromUDP(buffer)
	if err == nil {
		s.packets <- append([]byte(nil), buffer[:n]...)
	}
}

func (s *fakeSocksServer) port() uint16 {
	return uint16(s.listener.Addr().(*net.TCPAddr).Port)
}

// newTestOutbound 建一个连假服务端的 SOCKS5 出站（缺省版本 5，带用户名密码，与应用连外核本机入站的形态相同）
func newTestOutbound(t *testing.T, server *fakeSocksServer) *Outbound {
	t.Helper()
	return newTestOutboundPort(t, server.port())
}

// newTestOutboundPort 同 newTestOutbound，直接给本机端口
func newTestOutboundPort(t *testing.T, port uint16) *Outbound {
	t.Helper()
	outbound, err := NewOutbound(context.Background(), nil, log.NewNOPFactory().NewLogger("socks"), "test", option.SOCKSOutboundOptions{
		ServerOptions: option.ServerOptions{Server: "127.0.0.1", ServerPort: port},
		Username:      "user",
		Password:      "pass",
	})
	if err != nil {
		t.Fatal(err)
	}
	return outbound.(*Outbound)
}

// receive 等假服务端交来的一项数据，5 秒内没有就判失败
func receive(t *testing.T, ch chan []byte, what string) []byte {
	t.Helper()
	select {
	case b := <-ch:
		return b
	case <-time.After(5 * time.Second):
		t.Fatalf("no %s", what)
		return nil
	}
}

// UDP ASSOCIATE 一律声明 [::]:0（sing 会把私有 IPv4 写成 [::1]:0、私有 IPv6 写成 127.0.0.1:0、
// 公网 IPv4 写成 0.0.0.0:0），数据报头仍是原目标：ListenPacket 按每个包的目标写，
// DialContext 的 Write 写到拨号时的目标（AssociatePacketConn 的 remoteAddr）。
// 目标覆盖三段私有 IPv4、私有 IPv6、公网 IPv4 / IPv6、回环与域名；域名目标只查声明，不发包
// （WriteTo 拿不到域名的 UDPAddr）
func TestUDPAssociateDeclaresUnspecified(t *testing.T) {
	// VER 05、CMD 03、RSV 00、ATYP 04，16 字节全零地址，端口 0
	declared := "0503000400000000000000000000000000000000" + "0000"
	for _, target := range []string{
		"10.0.0.5:9999", "172.16.1.5:9999", "192.168.1.5:9999", "[fd00::5]:9999",
		"203.0.113.5:9999", "[2001:db8::5]:9999", "127.0.0.1:9999", "node.example.com:9999",
	} {
		destination := M.ParseSocksaddr(target)
		// SOCKS5 UDP 数据报头：RSV 两字节、FRAG 一字节，再接原目标
		var header bytes.Buffer
		header.Write([]byte{0, 0, 0})
		if err := M.SocksaddrSerializer.WriteAddrPort(&header, destination); err != nil {
			t.Fatal(err)
		}
		t.Run("ListenPacket "+target, func(t *testing.T) {
			server := newFakeSocksServer(t)
			packetConn, err := newTestOutbound(t, server).ListenPacket(context.Background(), destination)
			if err != nil {
				t.Fatal(err)
			}
			defer packetConn.Close()
			if got := hex.EncodeToString(receive(t, server.requests, "request")); got != declared {
				t.Fatalf("ASSOCIATE request %s, want %s", got, declared)
			}
			if _, err := packetConn.WriteTo([]byte("x"), destination.UDPAddr()); destination.IsIP() && err != nil {
				t.Fatal(err)
			}
			if !destination.IsIP() {
				return
			}
			if got := receive(t, server.packets, "datagram"); !bytes.Equal(got, append(header.Bytes(), 'x')) {
				t.Fatalf("datagram %x, want header %x", got, header.Bytes())
			}
		})
		t.Run("DialContext "+target, func(t *testing.T) {
			server := newFakeSocksServer(t)
			conn, err := newTestOutbound(t, server).DialContext(context.Background(), N.NetworkUDP, destination)
			if err != nil {
				t.Fatal(err)
			}
			defer conn.Close()
			if got := hex.EncodeToString(receive(t, server.requests, "request")); got != declared {
				t.Fatalf("ASSOCIATE request %s, want %s", got, declared)
			}
			if _, err := conn.Write([]byte("x")); err != nil {
				t.Fatal(err)
			}
			if got := receive(t, server.packets, "datagram"); !bytes.Equal(got, append(header.Bytes(), 'x')) {
				t.Fatalf("datagram %x, want header %x", got, header.Bytes())
			}
		})
	}
}

// 错误路径用的假服务端：认证前与 fakeSocksServer 相同；authFail 时认证回 01 01（失败），
// 否则认证成功、读完 [::]:0 的 UDP ASSOCIATE 请求后不再回复。之后等客户端关连接：
// 3 秒内读到 EOF 交出 nil，否则交出读错误
func newErrorPathServer(t *testing.T, authFail bool) (uint16, chan error) {
	t.Helper()
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { listener.Close() })
	closed := make(chan error, 1)
	go func() {
		conn, err := listener.Accept()
		if err != nil {
			return
		}
		defer conn.Close()
		greeting := readFull(conn, 2)
		if greeting == nil || readFull(conn, int(greeting[1])) == nil {
			closed <- errors.New("bad greeting")
			return
		}
		conn.Write([]byte{5, 2})
		authHeader := readFull(conn, 2)
		if authHeader == nil || readFull(conn, int(authHeader[1])) == nil {
			closed <- errors.New("bad auth")
			return
		}
		passwordLength := readFull(conn, 1)
		if passwordLength == nil || readFull(conn, int(passwordLength[0])) == nil {
			closed <- errors.New("bad auth")
			return
		}
		if authFail {
			conn.Write([]byte{1, 1})
		} else {
			conn.Write([]byte{1, 0})
			// VER CMD RSV ATYP(04)、16 字节地址、2 字节端口
			if readFull(conn, 4+16+2) == nil {
				closed <- errors.New("bad request")
				return
			}
		}
		conn.SetReadDeadline(time.Now().Add(3 * time.Second))
		_, err = io.ReadAll(conn)
		closed <- err
	}()
	return uint16(listener.Addr().(*net.TCPAddr).Port), closed
}

// 认证失败：两个入口都返回错误，并且关掉 TCP 控制连接
func TestUDPAssociateAuthFailureClosesTCP(t *testing.T) {
	destination := M.ParseSocksaddr("10.0.0.5:9999")
	for name, open := range map[string]func(*Outbound) error{
		"ListenPacket": func(o *Outbound) error {
			_, err := o.ListenPacket(context.Background(), destination)
			return err
		},
		"DialContext": func(o *Outbound) error {
			_, err := o.DialContext(context.Background(), N.NetworkUDP, destination)
			return err
		},
	} {
		t.Run(name, func(t *testing.T) {
			port, closed := newErrorPathServer(t, true)
			if err := open(newTestOutboundPort(t, port)); err == nil {
				t.Fatal("authentication failure accepted")
			}
			if err := <-closed; err != nil {
				t.Fatalf("tcp not closed: %v", err)
			}
		})
	}
}

// ctx 在服务端回复前到期：两个入口都返回 ctx.Err()，并且关掉 TCP 控制连接
func TestUDPAssociateContextDoneClosesTCP(t *testing.T) {
	destination := M.ParseSocksaddr("10.0.0.5:9999")
	for name, open := range map[string]func(context.Context, *Outbound) error{
		"ListenPacket": func(ctx context.Context, o *Outbound) error {
			_, err := o.ListenPacket(ctx, destination)
			return err
		},
		"DialContext": func(ctx context.Context, o *Outbound) error {
			_, err := o.DialContext(ctx, N.NetworkUDP, destination)
			return err
		},
	} {
		t.Run(name, func(t *testing.T) {
			port, closed := newErrorPathServer(t, false)
			ctx, cancel := context.WithTimeout(context.Background(), 300*time.Millisecond)
			defer cancel()
			if err := open(ctx, newTestOutboundPort(t, port)); !errors.Is(err, context.DeadlineExceeded) {
				t.Fatalf("error %v, want %v", err, context.DeadlineExceeded)
			}
			if err := <-closed; err != nil {
				t.Fatalf("tcp not closed: %v", err)
			}
		})
	}
}
