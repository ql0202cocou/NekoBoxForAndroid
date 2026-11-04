package libcore

import (
	"context"
	"crypto/ecdh"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/sha256"
	"crypto/tls"
	"crypto/x509"
	"encoding/hex"
	"encoding/pem"
	"fmt"
	"io"
	"math/big"
	"net"
	"net/http"
	"net/http/httptest"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/miekg/dns"
	anytls "github.com/sagernet/sing-anytls"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
	"golang.org/x/crypto/cryptobyte"
	"libcore/anytlscompat/boxadapter"
)

const compatibilityPassword = "loopback-fixture-only"

type compatibilityForwarder struct{}

func (compatibilityForwarder) NewConnectionEx(ctx context.Context, conn net.Conn, _, destination M.Socksaddr, onClose N.CloseHandlerFunc) {
	defer conn.Close()
	if !destination.Addr.IsLoopback() {
		return
	}
	remote, err := (&net.Dialer{}).DialContext(ctx, "tcp", destination.String())
	if err != nil {
		return
	}
	defer remote.Close()
	if err = N.ReportConnHandshakeSuccess(conn, remote); err != nil {
		return
	}
	done := make(chan struct{})
	go func() { io.Copy(remote, conn); remote.Close(); close(done) }()
	_, err = io.Copy(conn, remote)
	conn.Close()
	<-done
	if onClose != nil {
		onClose(err)
	}
}

func compatibilityPKI(t *testing.T) (tls.Certificate, string, string, string) {
	t.Helper()
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	cert := &x509.Certificate{SerialNumber: big.NewInt(1), DNSNames: []string{"inner.test", "public.test"},
		NotBefore: time.Now().Add(-time.Hour), NotAfter: time.Now().Add(time.Hour), KeyUsage: x509.KeyUsageDigitalSignature,
		ExtKeyUsage: []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth}}
	der, err := x509.CreateCertificate(rand.Reader, cert, cert, &key.PublicKey, key)
	if err != nil {
		t.Fatal(err)
	}
	k, err := x509.MarshalPKCS8PrivateKey(key)
	if err != nil {
		t.Fatal(err)
	}
	cp := string(pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: der}))
	kp := string(pem.EncodeToMemory(&pem.Block{Type: "PRIVATE KEY", Bytes: k}))
	pair, err := tls.X509KeyPair([]byte(cp), []byte(kp))
	if err != nil {
		t.Fatal(err)
	}
	hash := sha256.Sum256(der)
	return pair, cp, kp, hex.EncodeToString(hash[:])
}

func compatibilityECH(t *testing.T) (tls.EncryptedClientHelloKey, string, string) {
	t.Helper()
	key, err := ecdh.X25519().GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	b := cryptobyte.NewBuilder(nil)
	b.AddUint16(0xfe0d)
	b.AddUint16LengthPrefixed(func(b *cryptobyte.Builder) {
		b.AddUint8(1)
		b.AddUint16(0x20)
		b.AddUint16LengthPrefixed(func(b *cryptobyte.Builder) { b.AddBytes(key.PublicKey().Bytes()) })
		b.AddUint16LengthPrefixed(func(b *cryptobyte.Builder) { b.AddUint16(1); b.AddUint16(1) })
		b.AddUint8(0)
		b.AddUint8LengthPrefixed(func(b *cryptobyte.Builder) { b.AddBytes([]byte("public.test")) })
		b.AddUint16(0)
	})
	config := b.BytesOrPanic()
	list := cryptobyte.NewBuilder(nil)
	list.AddUint16LengthPrefixed(func(b *cryptobyte.Builder) { b.AddBytes(config) })
	keys := cryptobyte.NewBuilder(nil)
	keys.AddUint16LengthPrefixed(func(b *cryptobyte.Builder) { b.AddBytes(key.Bytes()) })
	keys.AddUint16LengthPrefixed(func(b *cryptobyte.Builder) { b.AddBytes(config) })
	return tls.EncryptedClientHelloKey{Config: config, PrivateKey: key.Bytes(), SendAsRetry: true},
		string(pem.EncodeToMemory(&pem.Block{Type: "ECH CONFIGS", Bytes: list.BytesOrPanic()})),
		string(pem.EncodeToMemory(&pem.Block{Type: "ECH KEYS", Bytes: keys.BytesOrPanic()}))
}

func TestCompatibilityRegisteredRuntimeAndURLTest(t *testing.T) {
	pair, cert, key, pin := compatibilityPKI(t)
	ech, echConfig, echKeys := compatibilityECH(t)
	var requests, dnsQueries atomic.Int64
	packet, err := net.ListenPacket("udp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	block, _ := pem.Decode([]byte(echConfig))
	dnsServer := &dns.Server{PacketConn: packet, Handler: dns.HandlerFunc(func(w dns.ResponseWriter, q *dns.Msg) {
		reply := new(dns.Msg)
		reply.SetReply(q)
		if len(q.Question) == 1 && q.Question[0].Qtype == dns.TypeHTTPS && q.Question[0].Name == "inner.test." {
			dnsQueries.Add(1)
			reply.Answer = []dns.RR{&dns.HTTPS{SVCB: dns.SVCB{Hdr: dns.RR_Header{Name: "inner.test.", Rrtype: dns.TypeHTTPS, Class: dns.ClassINET, Ttl: 0}, Priority: 1, Target: ".", Value: []dns.SVCBKeyValue{&dns.SVCBECHConfig{ECH: block.Bytes}}}}}
		} else {
			reply.Rcode = dns.RcodeRefused
		}
		w.WriteMsg(reply)
	})}
	go dnsServer.ActivateAndServe()
	t.Cleanup(func() { dnsServer.Shutdown() })
	dnsOptions := map[string]any{"servers": []any{map[string]any{"type": "hosts", "tag": "blocked", "predefined": map[string]string{"unused.invalid": "127.0.0.1"}}, map[string]any{"type": "udp", "tag": "dns-group-1", "server": "127.0.0.1", "server_port": packet.LocalAddr().(*net.UDPAddr).Port}}, "final": "blocked"}
	httpServer := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) { requests.Add(1); w.WriteHeader(204) }))
	defer httpServer.Close()
	for _, backend := range []string{"sing-anytls", "mihomo"} {
		t.Run(backend, func(t *testing.T) {
			listener, err := net.Listen("tcp", "127.0.0.1:0")
			if err != nil {
				t.Fatal(err)
			}
			port := listener.Addr().(*net.TCPAddr).Port
			if backend == "mihomo" {
				listener.Close()
				bin := os.Getenv("MIHOMO_BIN")
				if bin == "" {
					t.Skip("MIHOMO_BIN required for the pinned oracle")
				}
				version, err := exec.Command(bin, "-v").CombinedOutput()
				if err != nil || !strings.Contains(string(version), "Mihomo Meta v1.19.33 ") {
					t.Fatal("unexpected mihomo version")
				}
				dir := t.TempDir()
				config := trialJSON(map[string]any{"log-level": "silent", "mode": "rule", "rules": []string{"MATCH,REJECT"},
					"listeners": []any{map[string]any{"name": "test", "type": "anytls", "listen": "127.0.0.1", "port": port,
						"users": map[string]string{"test": compatibilityPassword}, "certificate": cert, "private-key": key, "ech-key": echKeys, "proxy": "DIRECT"}}})
				path := filepath.Join(dir, "test.json")
				if err = os.WriteFile(path, []byte(config), 0600); err != nil {
					t.Fatal(err)
				}
				ctx, cancel := context.WithCancel(context.Background())
				cmd := exec.CommandContext(ctx, bin, "-d", dir, "-f", path)
				if err = cmd.Start(); err != nil {
					cancel()
					t.Fatal(err)
				}
				t.Cleanup(func() { cancel(); cmd.Wait() })
			} else {
				svc, err := anytls.NewService(compatibilityPassword, anytls.ServiceOptions{Handler: compatibilityForwarder{}})
				if err != nil {
					t.Fatal(err)
				}
				ctx, cancel := context.WithCancel(context.Background())
				var wg sync.WaitGroup
				var conns sync.Map
				t.Cleanup(func() {
					cancel()
					listener.Close()
					conns.Range(func(k, _ any) bool { k.(net.Conn).Close(); return true })
					wg.Wait()
				})
				wg.Add(1)
				go func() {
					defer wg.Done()
					for {
						raw, err := listener.Accept()
						if err != nil {
							return
						}
						conns.Store(raw, true)
						wg.Add(1)
						go func() {
							defer wg.Done()
							defer raw.Close()
							defer conns.Delete(raw)
							c := tls.Server(raw, &tls.Config{Certificates: []tls.Certificate{pair}, EncryptedClientHelloKeys: []tls.EncryptedClientHelloKey{ech}})
							if c.HandshakeContext(ctx) == nil {
								svc.NewConnection(ctx, c, M.SocksaddrFromNet(raw.RemoteAddr()), nil)
							}
						}()
					}
				}()
			}
			for _, fp := range []string{"none", "chrome", "firefox", "chrome120", "firefox120"} {
				for _, echMode := range []string{"disabled", "inline", "dns"} {
					t.Run(fmt.Sprintf("%s/ECH_%s", fp, echMode), func(t *testing.T) {
						tlsOptions := map[string]any{"enabled": true, "server_name": "inner.test", "certificate_fingerprint": pin,
							"utls": map[string]any{"enabled": true, "fingerprint": fp}}
						if echMode == "inline" {
							tlsOptions["ech"] = map[string]any{"enabled": true, "config": echConfig}
						}
						if echMode == "dns" {
							tlsOptions["ech"] = map[string]any{"enabled": true}
						}
						out := map[string]any{"type": boxadapter.Type, "tag": "proxy", "server": "127.0.0.1", "server_port": port,
							"password": compatibilityPassword, "tls": tlsOptions, "detour": "controlled", "domain_resolver": map[string]any{"server": "dns-group-1", "disable_cache": true}}
						if got := TrialSingBoxOutbound("outbound", trialJSON(out)); got != "" {
							t.Fatal(got)
						}
						makeBox := func() *BoxInstance {
							b, err := NewSingBoxInstance(trialJSON(map[string]any{"dns": dnsOptions, "log": map[string]any{"level": "panic"}, "outbounds": []any{out,
								map[string]any{"type": "direct", "tag": "controlled", "connect_timeout": "5s"}}, "route": map[string]any{"final": "proxy"}}), nil)
							if err != nil {
								t.Fatal(err)
							}
							t.Cleanup(func() { b.Close() })
							if err = b.Start(); err != nil {
								t.Fatal(err)
							}
							return b
						}
						b := makeBox()
						before := requests.Load()
						dnsBefore := dnsQueries.Load()
						deadline := time.Now().Add(8 * time.Second)
						for {
							_, err = UrlTest(b, httpServer.URL, 1000)
							if err == nil || time.Now().After(deadline) {
								break
							}
							time.Sleep(20 * time.Millisecond)
						}
						if err != nil || requests.Load() <= before {
							t.Fatalf("registered outbound URL test failed: %v", err)
						}
						if echMode == "dns" && dnsQueries.Load() <= dnsBefore {
							t.Fatal("ECH bypassed group DNS")
						}
						b.Close()
						// 错误固定即使打开 insecure 也不能被正式测速路径放行。
						tlsOptions["certificate_fingerprint"] = strings.Repeat("00", 32)
						tlsOptions["insecure"] = true
						bad := makeBox()
						before = requests.Load()
						if _, err = UrlTest(bad, httpServer.URL, 1000); err == nil || requests.Load() != before {
							t.Fatal("bad pin bypassed")
						}
					})
				}
			}
		})
	}
}

func TestCompatibilityTrialRejectsUnsafeInputs(t *testing.T) {
	for _, c := range []struct {
		name     string
		tls      map[string]any
		category string
	}{
		{"pin", map[string]any{"certificate_fingerprint": "secret-invalid-pin"}, "TLS_CERTIFICATE"},
		{"pem", map[string]any{"certificate": "secret-invalid-pem"}, "TLS_CERTIFICATE"},
		{"utls", map[string]any{"utls": map[string]any{"enabled": true, "fingerprint": "secret-invalid-profile"}}, "TLS_UTLS_FINGERPRINT"},
		{"ech", map[string]any{"ech": map[string]any{"enabled": true, "config": "secret-invalid-ech"}}, "TLS_ECH_CONFIG"},
		{"ech-profile", map[string]any{"ech": map[string]any{"enabled": true}, "utls": map[string]any{"enabled": true, "fingerprint": "safari"}}, "TLS_OPTIONS"},
	} {
		t.Run(c.name, func(t *testing.T) {
			c.tls["enabled"] = true
			config := map[string]any{"type": boxadapter.Type, "server": "node.example.org", "server_port": 443, "password": "secret-password", "tls": c.tls}
			if got := TrialSingBoxOutbound("outbound", trialJSON(config)); got != c.category {
				t.Fatalf("category = %q, want %q", got, c.category)
			}
		})
	}
	config := map[string]any{"type": boxadapter.Type, "server": "node.example.org", "server_port": 443, "password": "fake",
		"domain_resolver": "dns-group-1", "detour": "parent", "tls": map[string]any{"enabled": true, "ech": map[string]any{"enabled": true}}}
	if got := TrialSingBoxOutbound("outbound", trialJSON(config)); got != "" {
		t.Fatalf("group DNS trial failed: %s", got)
	}
}
