package anytlscompat

import (
	"bytes"
	"context"
	"crypto/ecdh"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/sha256"
	stdtls "crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/base64"
	"encoding/binary"
	"encoding/hex"
	"encoding/json"
	"encoding/pem"
	"errors"
	"fmt"
	"io"
	"math/big"
	"net"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	anytls "github.com/sagernet/sing-anytls"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
	"golang.org/x/crypto/cryptobyte"
)

const testPassword = "anytls-loopback-only"
const testName = "node.test"

type certificate struct {
	parsed          *x509.Certificate
	key             *ecdsa.PrivateKey
	certPEM, keyPEM string
}
type testPKI struct{ root, intermediate, leaf, expired, unrelated certificate }

func newCertificate(t *testing.T, name string, parent *certificate, ca, expired bool) certificate {
	t.Helper()
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	serial, err := rand.Int(rand.Reader, new(big.Int).Lsh(big.NewInt(1), 120))
	if err != nil {
		t.Fatal(err)
	}
	now := time.Now()
	template := &x509.Certificate{SerialNumber: serial, Subject: pkix.Name{CommonName: name}, DNSNames: []string{name, "public.test"},
		NotBefore: now.Add(-24 * time.Hour), NotAfter: now.Add(24 * time.Hour), BasicConstraintsValid: true, IsCA: ca,
		KeyUsage: x509.KeyUsageDigitalSignature, ExtKeyUsage: []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth}}
	if ca {
		template.KeyUsage |= x509.KeyUsageCertSign
	}
	if expired {
		template.NotAfter = now.Add(-time.Hour)
	}
	signer, parentCert := key, template
	if parent != nil {
		signer, parentCert = parent.key, parent.parsed
	}
	der, err := x509.CreateCertificate(rand.Reader, template, parentCert, &key.PublicKey, signer)
	if err != nil {
		t.Fatal(err)
	}
	parsed, err := x509.ParseCertificate(der)
	if err != nil {
		t.Fatal(err)
	}
	keyDER, err := x509.MarshalPKCS8PrivateKey(key)
	if err != nil {
		t.Fatal(err)
	}
	return certificate{parsed, key, string(pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: der})),
		string(pem.EncodeToMemory(&pem.Block{Type: "PRIVATE KEY", Bytes: keyDER}))}
}

func newPKI(t *testing.T) testPKI {
	root := newCertificate(t, "test-root", nil, true, false)
	intermediate := newCertificate(t, "test-intermediate", &root, true, false)
	return testPKI{root, intermediate, newCertificate(t, testName, &intermediate, false, false),
		newCertificate(t, testName, &intermediate, false, true), newCertificate(t, "unrelated", nil, true, false)}
}

func pin(c certificate) string           { h := sha256.Sum256(c.parsed.Raw); return hex.EncodeToString(h[:]) }
func roots(c certificate) *x509.CertPool { p := x509.NewCertPool(); p.AddCert(c.parsed); return p }

func tlsCertificate(t *testing.T, leaf certificate, chain ...certificate) stdtls.Certificate {
	t.Helper()
	p := leaf.certPEM
	for _, c := range chain {
		p += c.certPEM
	}
	cert, err := stdtls.X509KeyPair([]byte(p), []byte(leaf.keyPEM))
	if err != nil {
		t.Fatal(err)
	}
	return cert
}

type echFixture struct {
	list []byte
	key  stdtls.EncryptedClientHelloKey
	pem  string
}

// 生成一次性测试密钥，格式依据 mihomo v1.19.33 component/ech/key.go。
func newECH(t *testing.T, id uint8) echFixture {
	t.Helper()
	k, err := ecdh.X25519().GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	b := cryptobyte.NewBuilder(nil)
	b.AddUint16(0xfe0d)
	b.AddUint16LengthPrefixed(func(b *cryptobyte.Builder) {
		b.AddUint8(id)
		b.AddUint16(0x20)
		b.AddUint16LengthPrefixed(func(b *cryptobyte.Builder) { b.AddBytes(k.PublicKey().Bytes()) })
		b.AddUint16LengthPrefixed(func(b *cryptobyte.Builder) { b.AddUint16(1); b.AddUint16(1) })
		b.AddUint8(0)
		b.AddUint8LengthPrefixed(func(b *cryptobyte.Builder) { b.AddBytes([]byte("public.test")) })
		b.AddUint16(0)
	})
	config := b.BytesOrPanic()
	l := cryptobyte.NewBuilder(nil)
	l.AddUint16LengthPrefixed(func(b *cryptobyte.Builder) { b.AddBytes(config) })
	p := cryptobyte.NewBuilder(nil)
	p.AddUint16LengthPrefixed(func(b *cryptobyte.Builder) { b.AddBytes(k.Bytes()) })
	p.AddUint16LengthPrefixed(func(b *cryptobyte.Builder) { b.AddBytes(config) })
	return echFixture{l.BytesOrPanic(), stdtls.EncryptedClientHelloKey{Config: config, PrivateKey: k.Bytes(), SendAsRetry: true},
		string(pem.EncodeToMemory(&pem.Block{Type: "ECH KEYS", Bytes: p.BytesOrPanic()}))}
}

func (e echFixture) encoded() string { return base64.StdEncoding.EncodeToString(e.list) }

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
func startServer(t *testing.T, cfg *stdtls.Config, padding []byte) *localServer {
	t.Helper()
	service, err := anytls.NewService(testPassword, anytls.ServiceOptions{Handler: echoHandler{}, PaddingScheme: padding})
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

func freePort(t *testing.T) int {
	t.Helper()
	l, e := net.Listen("tcp", "127.0.0.1:0")
	if e != nil {
		t.Fatal(e)
	}
	p := l.Addr().(*net.TCPAddr).Port
	l.Close()
	return p
}

func mihomoBinary(t *testing.T) string {
	t.Helper()
	bin := os.Getenv("MIHOMO_BIN")
	if bin == "" {
		if os.Getenv("REQUIRE_MIHOMO") == "1" {
			t.Fatal("MIHOMO_BIN required")
		}
		t.Skip("set MIHOMO_BIN for pinned mihomo integration tests")
	}
	b, err := exec.Command(bin, "-v").CombinedOutput()
	if err != nil || !strings.Contains(string(b), "Mihomo Meta v1.19.33 ") {
		t.Fatalf("expected mihomo v1.19.33, got %q (%v)", b, err)
	}
	return bin
}

func startMihomo(t *testing.T, config map[string]any, port int) {
	t.Helper()
	bin := mihomoBinary(t)
	dir := t.TempDir()
	healthEcho, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { healthEcho.Close() })
	go func() {
		for {
			c, err := healthEcho.Accept()
			if err != nil {
				return
			}
			go func() { defer c.Close(); c.SetDeadline(time.Now().Add(time.Second)); io.Copy(c, c) }()
		}
	}()
	healthPort := freePort(t)
	listeners, _ := config["listeners"].([]any)
	config["listeners"] = append(listeners, map[string]any{"name": "health", "type": "tunnel", "listen": "127.0.0.1", "port": healthPort,
		"network": []string{"tcp"}, "target": healthEcho.Addr().String(), "proxy": "DIRECT"})
	config["log-level"] = "debug"
	config["mode"] = "rule"
	config["rules"] = []string{"MATCH,REJECT"}
	data, err := json.Marshal(config)
	if err != nil {
		t.Fatal(err)
	}
	path := filepath.Join(dir, "config.json")
	if err = os.WriteFile(path, data, 0600); err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithCancel(context.Background())
	cmd := exec.CommandContext(ctx, bin, "-d", dir, "-f", path)
	var output bytes.Buffer
	cmd.Stdout = &output
	cmd.Stderr = &output
	if err = cmd.Start(); err != nil {
		cancel()
		t.Fatal(err)
	}
	done := make(chan error, 1)
	go func() { done <- cmd.Wait() }()
	t.Cleanup(func() {
		cancel()
		select {
		case <-done:
		case <-time.After(5 * time.Second):
			t.Error("mihomo did not exit")
		}
		if t.Failed() {
			t.Logf("mihomo test process: %s", output.String())
		}
	})
	address := fmt.Sprintf("127.0.0.1:%d", healthPort)
	deadline := time.Now().Add(5 * time.Second)
	for time.Now().Before(deadline) {
		conn, e := net.DialTimeout("tcp", address, 30*time.Millisecond)
		if e == nil {
			// 监听早于 mihomo 的 Running 状态；实际穿过 DIRECT 路由才算就绪。
			conn.SetDeadline(time.Now().Add(100 * time.Millisecond))
			_, writeErr := conn.Write([]byte("ready"))
			var reply [5]byte
			_, readErr := io.ReadFull(conn, reply[:])
			conn.Close()
			if writeErr == nil && readErr == nil && string(reply[:]) == "ready" {
				return
			}
		}
		select {
		case err := <-done:
			done <- err
			t.Fatalf("mihomo startup failed: %v\n%s", err, output.String())
		default:
		}
		time.Sleep(10 * time.Millisecond)
	}
	t.Fatal("mihomo listener readiness timed out")
}

func oracleExchange(t *testing.T, addr M.Socksaddr, options SecurityOptions, rootPEM string, dnsAddress ...string) error {
	t.Helper()
	port := freePort(t)
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
	startMihomo(t, config, port)
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
