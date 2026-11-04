package libcore

import (
	"context"
	"crypto/tls"
	"encoding/pem"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/miekg/dns"
	anytls "github.com/sagernet/sing-anytls"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
	"libcore/anytlscompat/boxadapter"
	"libcore/internal/anytlstest"
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

func TestCompatibilityFormalAndLegacyTypes(t *testing.T) {
	for _, kind := range []string{boxadapter.Type, "neko-anytls-compat-experiment"} {
		out := map[string]any{"type": kind, "tag": "proxy", "server": "127.0.0.1", "server_port": 443,
			"password": "trial-only", "tls": map[string]any{"enabled": true, "server_name": "node.test"}}
		if got := TrialSingBoxOutbound("outbound", trialJSON(out)); got != "" {
			t.Fatalf("type %s: %s", kind, got)
		}
	}
}

func TestCompatibilityRegisteredRuntimeAndURLTest(t *testing.T) {
	leaf := anytlstest.NewCertificate(t, "inner.test", nil, false, false)
	pair, pin := anytlstest.TLSCertificate(t, leaf), anytlstest.Pin(leaf)
	ech := anytlstest.NewECH(t, 1)
	echConfig := string(pem.EncodeToMemory(&pem.Block{Type: "ECH CONFIGS", Bytes: ech.List}))
	var requests, dnsQueries atomic.Int64
	packet, err := net.ListenPacket("udp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	dnsServer := &dns.Server{PacketConn: packet, Handler: dns.HandlerFunc(func(w dns.ResponseWriter, q *dns.Msg) {
		reply := new(dns.Msg)
		reply.SetReply(q)
		if len(q.Question) == 1 && q.Question[0].Qtype == dns.TypeHTTPS && q.Question[0].Name == "inner.test." {
			dnsQueries.Add(1)
			reply.Answer = []dns.RR{&dns.HTTPS{SVCB: dns.SVCB{Hdr: dns.RR_Header{Name: "inner.test.", Rrtype: dns.TypeHTTPS, Class: dns.ClassINET, Ttl: 0}, Priority: 1, Target: ".", Value: []dns.SVCBKeyValue{&dns.SVCBECHConfig{ECH: ech.List}}}}}
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
				anytlstest.StartMihomo(t, map[string]any{"listeners": []any{map[string]any{"name": "test", "type": "anytls", "listen": "127.0.0.1", "port": port,
					"users": map[string]string{"test": compatibilityPassword}, "certificate": leaf.CertPEM, "private-key": leaf.KeyPEM, "ech-key": ech.PEM, "proxy": "DIRECT"}}})
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
							c := tls.Server(raw, &tls.Config{Certificates: []tls.Certificate{pair}, EncryptedClientHelloKeys: []tls.EncryptedClientHelloKey{ech.Key}})
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
