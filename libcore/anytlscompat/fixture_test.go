package anytlscompat

import (
	"bytes"
	"context"
	stdtls "crypto/tls"
	"encoding/binary"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"libcore/internal/anytlstest"

	anytls "github.com/sagernet/sing-anytls"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
	"golang.org/x/crypto/cryptobyte"
)

const testPassword = "anytls-loopback-only"
const testName = "node.test"

type localDialer struct{ count atomic.Int64 }

func (d *localDialer) DialContext(ctx context.Context, network string, dest M.Socksaddr) (net.Conn, error) {
	if network != "tcp" || !dest.Addr.IsLoopback() {
		return nil, errors.New("test dialer forbids non-loopback")
	}
	d.count.Add(1)
	return (&net.Dialer{}).DialContext(ctx, network, dest.String())
}
func (d *localDialer) ListenPacket(context.Context, M.Socksaddr) (net.PacketConn, error) {
	return nil, errors.New("unexpected direct UDP")
}

type echoHandler struct{}

func (echoHandler) NewConnectionEx(_ context.Context, c net.Conn, _, _ M.Socksaddr, onClose N.CloseHandlerFunc) {
	defer c.Close()
	if err := N.ReportConnHandshakeSuccess(c, c); err != nil {
		return
	}
	_, err := io.Copy(c, c)
	if onClose != nil {
		onClose(err)
	}
}

type localServer struct {
	addr   M.Socksaddr
	states chan stdtls.ConnectionState
	hellos chan []byte
}

type recordingConn struct {
	net.Conn
	mu     sync.Mutex
	prefix []byte
}

func (c *recordingConn) Read(p []byte) (int, error) {
	n, err := c.Conn.Read(p)
	c.mu.Lock()
	if len(c.prefix) < 65536 {
		c.prefix = append(c.prefix, p[:n]...)
	}
	c.mu.Unlock()
	return n, err
}

// 测试服务端只回显数据，不拨号到测试机以外的任何目标。
func startServer(t *testing.T, cfg *stdtls.Config) *localServer {
	t.Helper()
	service, err := anytls.NewService(testPassword, anytls.ServiceOptions{Handler: echoHandler{}})
	if err != nil {
		t.Fatal(err)
	}
	l, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	s := &localServer{M.SocksaddrFromNet(l.Addr()), make(chan stdtls.ConnectionState, 256), make(chan []byte, 256)}
	ctx, cancel := context.WithCancel(context.Background())
	var wg sync.WaitGroup
	var connections sync.Map
	t.Cleanup(func() {
		cancel()
		l.Close()
		connections.Range(func(k, v any) bool { k.(net.Conn).Close(); return true })
		wg.Wait()
	})
	wg.Add(1)
	go func() {
		defer wg.Done()
		for {
			raw, e := l.Accept()
			if e != nil {
				return
			}
			connections.Store(raw, true)
			wg.Add(1)
			go func() {
				defer wg.Done()
				defer connections.Delete(raw)
				defer raw.Close()
				stop := context.AfterFunc(ctx, func() { raw.Close() })
				defer stop()
				record := &recordingConn{Conn: raw}
				tc := stdtls.Server(record, cfg)
				hctx, done := context.WithTimeout(ctx, 5*time.Second)
				err := tc.HandshakeContext(hctx)
				done()
				record.mu.Lock()
				hello := append([]byte(nil), record.prefix...)
				record.mu.Unlock()
				select {
				case s.hellos <- hello:
				default:
				}
				if err != nil {
					return
				}
				select {
				case s.states <- tc.ConnectionState():
				default:
				}
				if err := service.NewConnection(ctx, tc, M.SocksaddrFromNet(raw.RemoteAddr()), nil); err != nil && ctx.Err() == nil {
					t.Logf("test server session: %v", err)
				}
			}()
		}
	}()
	return s
}

func exchange(c net.Conn, payload []byte) error {
	defer c.Close()
	_ = c.SetDeadline(time.Now().Add(3 * time.Second))
	if _, err := c.Write(payload); err != nil {
		return err
	}
	got := make([]byte, len(payload))
	if _, err := io.ReadFull(c, got); err != nil {
		return err
	}
	if !bytes.Equal(got, payload) {
		return errors.New("echo mismatch")
	}
	return nil
}

func prototypeExchange(t *testing.T, addr M.Socksaddr, options SecurityOptions) error {
	t.Helper()
	s, err := NewSecurity(options)
	if err != nil {
		return err
	}
	c, err := NewClient(context.Background(), ClientOptions{Server: addr, Dialer: &localDialer{}, Security: s, Password: testPassword})
	if err != nil {
		return err
	}
	defer c.Close()
	ctx, cancel := context.WithTimeout(context.Background(), 4*time.Second)
	defer cancel()
	conn, err := c.DialContext(ctx, "tcp", M.ParseSocksaddr("127.0.0.1:12345"))
	if err != nil {
		return err
	}
	return exchange(conn, []byte("anytls-compatible-echo"))
}

func oracleExchange(t *testing.T, addr M.Socksaddr, options SecurityOptions, rootPEM string, dnsAddress ...string) error {
	t.Helper()
	port := anytlstest.FreePort(t)
	proxy := map[string]any{"name": "oracle", "type": "anytls", "server": addr.Addr.String(), "port": addr.Port,
		"password": testPassword, "sni": options.ServerName, "fingerprint": options.Fingerprint,
		"name-cert-verify": options.VerifyName, "skip-cert-verify": options.AllowInsecure,
		"client-fingerprint": options.ClientFingerprint, "alpn": options.ALPN}
	if options.ECH.Enabled {
		proxy["ech-opts"] = map[string]any{"enable": true, "config": options.ECH.Config, "query-server-name": options.ECH.QueryServerName}
	}
	config := map[string]any{"proxies": []any{proxy}, "listeners": []any{map[string]any{"name": "in", "type": "tunnel", "listen": "127.0.0.1", "port": port,
		"network": []string{"tcp"}, "target": "127.0.0.1:12345", "proxy": "oracle"}}}
	if rootPEM != "" {
		config["tls"] = map[string]any{"custom-certifactes": []string{rootPEM}}
	}
	if len(dnsAddress) > 0 {
		config["dns"] = map[string]any{"enable": true, "nameserver": []string{dnsAddress[0]}, "proxy-server-nameserver": []string{dnsAddress[0]}}
	}
	anytlstest.StartMihomo(t, config)
	conn, err := net.DialTimeout("tcp", fmt.Sprintf("127.0.0.1:%d", port), time.Second)
	if err != nil {
		return err
	}
	return exchange(conn, []byte("anytls-compatible-echo"))
}

// 提取实际 ClientHello 的稳定结构，随机数、GREASE 与扩展排列不参与相等判断。
func helloShape(wire []byte) (string, error) {
	var handshake []byte
	for len(wire) >= 5 {
		n := int(binary.BigEndian.Uint16(wire[3:5]))
		if len(wire) < 5+n {
			break
		}
		if wire[0] == 22 {
			handshake = append(handshake, wire[5:5+n]...)
		}
		wire = wire[5+n:]
		if len(handshake) >= 4 && len(handshake) >= 4+(int(handshake[1])<<16)+(int(handshake[2])<<8)+int(handshake[3]) {
			break
		}
	}
	if len(handshake) < 38 || handshake[0] != 1 {
		return "", errors.New("missing ClientHello")
	}
	p := cryptobyte.String(handshake[4:])
	var version uint16
	var random []byte
	var sid, ciphers, compress, extensions cryptobyte.String
	if !p.ReadUint16(&version) || !p.ReadBytes(&random, 32) || !p.ReadUint8LengthPrefixed(&sid) || !p.ReadUint16LengthPrefixed(&ciphers) || !p.ReadUint8LengthPrefixed(&compress) || !p.ReadUint16LengthPrefixed(&extensions) {
		return "", errors.New("malformed ClientHello")
	}
	grease := func(v uint16) bool { return v&0x0f0f == 0x0a0a && byte(v>>8) == byte(v) }
	var suites []uint16
	for !ciphers.Empty() {
		var v uint16
		if !ciphers.ReadUint16(&v) {
			return "", errors.New("bad ciphers")
		}
		if !grease(v) {
			suites = append(suites, v)
		}
	}
	exts := map[uint16]string{}
	for !extensions.Empty() {
		var id uint16
		var body cryptobyte.String
		if !extensions.ReadUint16(&id) || !extensions.ReadUint16LengthPrefixed(&body) {
			return "", errors.New("bad extensions")
		}
		// Chrome 的 GREASE ECH 长度会影响动态 padding 是否出现，不属于模板差异。
		if grease(id) || id == 21 {
			continue
		}
		switch id {
		case 10, 43:
			var values cryptobyte.String
			if id == 10 {
				body.ReadUint16LengthPrefixed(&values)
			} else {
				body.ReadUint8LengthPrefixed(&values)
			}
			var list []uint16
			for !values.Empty() {
				var v uint16
				if !values.ReadUint16(&v) {
					break
				}
				if !grease(v) {
					list = append(list, v)
				}
			}
			exts[id] = fmt.Sprint(list)
		case 13, 16:
			exts[id] = hex.EncodeToString(body)
		default:
			exts[id] = "present"
		}
	}
	b, _ := json.Marshal(struct {
		Version    uint16
		Suites     []uint16
		Extensions map[uint16]string
	}{version, suites, exts})
	return string(b), nil
}
