package anytlscompat

import (
	"bytes"
	"context"
	stdtls "crypto/tls"
	"crypto/x509"
	"encoding/base64"
	"errors"
	"fmt"
	"io"
	"net"
	"strings"
	"sync"
	"testing"
	"time"

	M "github.com/sagernet/sing/common/metadata"
)

func TestCertificateDifferential(t *testing.T) {
	mihomoBinary(t)
	p := newPKI(t)
	type scenario struct {
		name                    string
		cert                    stdtls.Certificate
		pin, nameOverride, sni  string
		insecure, trusted, want bool
	}
	full := tlsCertificate(t, p.leaf, p.intermediate, p.root)
	expired := tlsCertificate(t, p.expired, p.intermediate, p.root)
	cases := []scenario{
		{"leaf", full, pin(p.leaf), "", testName, false, false, true},
		{"leaf_wrong_name", full, pin(p.leaf), "", "wrong.test", false, false, true},
		{"leaf_expired", expired, pin(p.expired), "", testName, false, false, true},
		{"intermediate", full, pin(p.intermediate), "", testName, false, false, true},
		{"root", full, pin(p.root), "", testName, false, false, true},
		{"root_wrong_name", full, pin(p.root), "", "wrong.test", false, false, false},
		{"root_expired", expired, pin(p.root), "", testName, false, false, false},
		{"root_absent", tlsCertificate(t, p.leaf, p.intermediate), pin(p.root), "", testName, false, true, false},
		{"unrelated_anchor", tlsCertificate(t, p.leaf, p.unrelated), pin(p.unrelated), "", testName, false, false, false},
		{"wrong_pin_insecure", full, pin(p.unrelated), "", testName, true, true, false},
		{"root_wrong_name_insecure", full, pin(p.root), "", "wrong.test", true, false, false},
		{"root_expired_insecure", expired, pin(p.root), "", testName, true, false, false},
		{"verify_name_overrides_sni", full, pin(p.root), testName, "wrong.test", false, false, true},
		{"verify_name_enforced_with_pin", full, pin(p.root), "wrong.test", testName, true, false, false},
		{"trusted", full, "", "", testName, false, true, true},
		{"untrusted", full, "", "", testName, false, false, false},
		{"insecure", full, "", "", "wrong.test", true, false, true},
		{"verify_name_overrides_insecure", full, "", "wrong.test", testName, true, true, false},
		{"verify_name_trusted", full, "", testName, "wrong.test", true, true, true},
	}
	for _, fp := range []string{"none", "chrome", "firefox"} {
		t.Run(fp, func(t *testing.T) {
			for _, c := range cases {
				t.Run(c.name, func(t *testing.T) {
					server := startServer(t, &stdtls.Config{Certificates: []stdtls.Certificate{c.cert}}, nil)
					opts := SecurityOptions{ServerName: c.sni, VerifyName: c.nameOverride, Fingerprint: c.pin, AllowInsecure: c.insecure, ClientFingerprint: fp}
					root := ""
					if c.trusted {
						opts.Roots = roots(p.root)
						root = p.root.certPEM
					}
					prototypeErr := prototypeExchange(t, server.addr, opts)
					oracleErr := oracleExchange(t, server.addr, opts, root)
					if (prototypeErr == nil) != c.want || (oracleErr == nil) != c.want {
						t.Fatalf("want acceptance=%v, prototype=%v, mihomo=%v", c.want, prototypeErr, oracleErr)
					}
				})
			}
		})
	}
}

func TestLegacyPinPolicy(t *testing.T) {
	p := newPKI(t)
	got, err := LegacyPin(pin(p.unrelated), []byte(p.leaf.certPEM))
	if err != nil || got != pin(p.unrelated) {
		t.Fatal("explicit pin must win", err)
	}
	got, err = LegacyPin("", []byte(p.leaf.certPEM+p.root.certPEM))
	if err != nil || got != pin(p.leaf) {
		t.Fatal("first PEM must win", err)
	}
	if _, err = LegacyPin("invalid", []byte(p.leaf.certPEM)); err == nil {
		t.Fatal("invalid explicit pin fell back to PEM")
	}
	if _, err = LegacyPin("", []byte("invalid")); err == nil {
		t.Fatal("invalid PEM accepted")
	}
	if _, err = NewSecurity(SecurityOptions{Fingerprint: strings.Repeat("zz", 32)}); !errors.Is(err, ErrFingerprint) {
		t.Fatal(err)
	}
	canonical := pin(p.leaf)
	var parts []string
	for i := 0; i < len(canonical); i += 2 {
		parts = append(parts, strings.ToUpper(canonical[i:i+2]))
	}
	v, err := pinVerifier(" "+strings.Join(parts, ":")+" ", nil)
	if err != nil {
		t.Fatal(err)
	}
	if err = v([]*x509.Certificate{p.leaf.parsed}, "wrong.test"); err != nil {
		t.Fatal(err)
	}
}

func TestUTLSWireDifferential(t *testing.T) {
	mihomoBinary(t)
	p := newPKI(t)
	profiles := []string{"chrome", "firefox", "safari", "ios", "android", "edge", "360", "qq", "chrome120", "firefox120", "safari16", "chrome_psk_shuffle", "chrome_pq"}
	for _, fp := range profiles {
		t.Run(fp, func(t *testing.T) {
			cfg := &stdtls.Config{Certificates: []stdtls.Certificate{tlsCertificate(t, p.leaf)}, NextProtos: []string{"h2", "http/1.1"}}
			prototypeServer := startServer(t, cfg, nil)
			oracleServer := startServer(t, cfg, nil)
			opts := SecurityOptions{ServerName: testName, Fingerprint: pin(p.leaf), ClientFingerprint: fp, ALPN: []string{"h2", "http/1.1"}}
			if err := prototypeExchange(t, prototypeServer.addr, opts); err != nil {
				t.Fatal("prototype", err)
			}
			if err := oracleExchange(t, oracleServer.addr, opts, ""); err != nil {
				t.Fatal("mihomo", err)
			}
			readShape := func(s *localServer) string {
				select {
				case raw := <-s.hellos:
					shape, err := helloShape(raw)
					if err != nil {
						t.Fatal(err)
					}
					return shape
				case <-time.After(time.Second):
					t.Fatal("no ClientHello")
					return ""
				}
			}
			a, b := readShape(prototypeServer), readShape(oracleServer)
			if a != b {
				t.Fatalf("ClientHello structures differ\nprototype %s\nmihomo %s", a, b)
			}
		})
	}
	for _, fp := range []string{"chrome_psk", "chrome_pq_psk", "chrome_padding_psk_shuffle"} {
		t.Run(fp, func(t *testing.T) {
			server := startServer(t, &stdtls.Config{Certificates: []stdtls.Certificate{tlsCertificate(t, p.leaf)}}, nil)
			opts := SecurityOptions{ServerName: testName, Fingerprint: pin(p.leaf), ClientFingerprint: fp}
			a, b := prototypeExchange(t, server.addr, opts), oracleExchange(t, server.addr, opts, "")
			if a == nil || b == nil {
				t.Fatalf("expected existing unsupported PSK templates to fail; prototype=%v mihomo=%v", a, b)
			}
		})
	}
}

func TestRandomFingerprints(t *testing.T) {
	p := newPKI(t)
	for _, fp := range []string{"random"} {
		t.Run(fp, func(t *testing.T) {
			server := startServer(t, &stdtls.Config{Certificates: []stdtls.Certificate{tlsCertificate(t, p.leaf)}}, nil)
			for range 6 {
				if err := prototypeExchange(t, server.addr, SecurityOptions{ServerName: testName, Fingerprint: pin(p.leaf), ClientFingerprint: fp}); err != nil {
					t.Fatal(err)
				}
			}
		})
	}
	if _, err := NewSecurity(SecurityOptions{ClientFingerprint: "unknown"}); err == nil {
		t.Fatal("unknown fingerprint must be rejected explicitly")
	}
	for _, fp := range []string{"chrome_psk", "chrome_pq_psk", "chrome_padding_psk_shuffle", "randomized"} {
		if _, err := NewSecurity(SecurityOptions{ClientFingerprint: fp}); err == nil {
			t.Fatalf("existing rejection was relaxed: %s", fp)
		}
	}
}

func TestECHDifferential(t *testing.T) {
	mihomoBinary(t)
	p := newPKI(t)
	good, bad := newECH(t, 1), newECH(t, 2)
	for _, fp := range []string{"none", "chrome", "firefox"} {
		t.Run(fp, func(t *testing.T) {
			for _, mode := range []string{"accepted_leaf", "accepted_root", "accepted_wrong_pin", "accepted_expired_root", "rejected_trusted", "rejected_pin_only", "malformed"} {
				t.Run(mode, func(t *testing.T) {
					cert := tlsCertificate(t, p.leaf, p.intermediate, p.root)
					opts := SecurityOptions{ServerName: testName, Fingerprint: pin(p.leaf), ClientFingerprint: fp, ECH: ECHOptions{Enabled: true, Config: good.encoded()}}
					want := mode == "accepted_leaf" || mode == "accepted_root"
					root := ""
					switch mode {
					case "accepted_root":
						opts.Fingerprint = pin(p.root)
					case "accepted_wrong_pin":
						opts.Fingerprint = pin(p.unrelated)
						opts.AllowInsecure = true
					case "accepted_expired_root":
						cert = tlsCertificate(t, p.expired, p.intermediate, p.root)
						opts.Fingerprint = pin(p.root)
					case "rejected_trusted":
						opts.ECH.Config = bad.encoded()
						opts.Roots = roots(p.root)
						root = p.root.certPEM
					case "rejected_pin_only":
						opts.ECH.Config = bad.encoded()
						opts.AllowInsecure = true
					case "malformed":
						opts.ECH.Config = base64.StdEncoding.EncodeToString([]byte{1, 2, 3})
					}
					cfg := &stdtls.Config{Certificates: []stdtls.Certificate{cert}, EncryptedClientHelloKeys: []stdtls.EncryptedClientHelloKey{good.key}}
					ps, os := startServer(t, cfg, nil), startServer(t, cfg, nil)
					a, b := prototypeExchange(t, ps.addr, opts), oracleExchange(t, os.addr, opts, root)
					if (a == nil) != want || (b == nil) != want {
						t.Fatalf("want=%v prototype=%v mihomo=%v", want, a, b)
					}
					if want {
						for _, server := range []*localServer{ps, os} {
							select {
							case state := <-server.states:
								if !state.ECHAccepted || state.ServerName != testName {
									t.Fatalf("ECH not accepted: %+v", state)
								}
							case <-time.After(time.Second):
								t.Fatal("missing TLS state")
							}
						}
					}
				})
			}
		})
	}
}

func TestMihomoServerInterop(t *testing.T) {
	mihomoBinary(t)
	p := newPKI(t)
	ech := newECH(t, 1)
	echo, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer echo.Close()
	go func() {
		for {
			c, err := echo.Accept()
			if err != nil {
				return
			}
			go func() { defer c.Close(); io.Copy(c, c) }()
		}
	}()
	udp, err := net.ListenPacket("udp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer udp.Close()
	go func() {
		b := make([]byte, 65535)
		for {
			n, a, err := udp.ReadFrom(b)
			if err != nil {
				return
			}
			udp.WriteTo(b[:n], a)
		}
	}()
	for _, fp := range []string{"none", "chrome"} {
		for _, useECH := range []bool{false, true} {
			t.Run(fmt.Sprintf("%s/ECH_%v", fp, useECH), func(t *testing.T) {
				port := freePort(t)
				listener := map[string]any{"name": "server", "type": "anytls", "listen": "127.0.0.1", "port": port, "users": map[string]string{"test": testPassword},
					"certificate": p.leaf.certPEM + p.intermediate.certPEM + p.root.certPEM, "private-key": p.leaf.keyPEM, "proxy": "DIRECT",
					"padding-scheme": "stop=4\n0=64-96\n1=200-600\n2=300-700,c,400-900\n3=100-200"}
				opts := SecurityOptions{ServerName: testName, Fingerprint: pin(p.root), ClientFingerprint: fp}
				if useECH {
					listener["ech-key"] = ech.pem
					opts.ECH = ECHOptions{Enabled: true, Config: ech.encoded()}
				}
				startMihomo(t, map[string]any{"listeners": []any{listener}}, port)
				security, err := NewSecurity(opts)
				if err != nil {
					t.Fatal(err)
				}
				for _, reuse := range []bool{false, true} {
					t.Run(fmt.Sprintf("disable_reuse_%v", reuse), func(t *testing.T) {
						d := &localDialer{}
						c, err := NewClient(context.Background(), ClientOptions{Server: M.ParseSocksaddr(fmt.Sprintf("127.0.0.1:%d", port)), Dialer: d, Security: security, Password: testPassword, DisableReuse: reuse, ClientMetadata: "compat-prototype"})
						if err != nil {
							t.Fatal(err)
						}
						defer c.Close()
						for i := range 3 {
							ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
							conn, err := c.DialContext(ctx, "tcp", M.SocksaddrFromNet(echo.Addr()))
							cancel()
							if err != nil {
								t.Fatal(err)
							}
							if err = exchange(conn, bytes.Repeat([]byte{byte(i + 1)}, 128*1024)); err != nil {
								t.Fatal(err)
							}
						}
						if reuse && d.count.Load() != 3 {
							t.Fatalf("disable_reuse opened %d sessions", d.count.Load())
						}
						if !reuse && d.count.Load() != 1 {
							t.Fatalf("sequential streams did not reuse one session: %d", d.count.Load())
						}
						ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
						defer cancel()
						pc, err := c.ListenPacket(ctx, M.SocksaddrFromNet(udp.LocalAddr()))
						if err != nil {
							t.Fatal(err)
						}
						defer pc.Close()
						pc.SetDeadline(time.Now().Add(3 * time.Second))
						payload := []byte("uot-v2-echo")
						if _, err = pc.WriteTo(payload, udp.LocalAddr()); err != nil {
							t.Fatal(err)
						}
						buf := make([]byte, 256)
						n, addr, err := pc.ReadFrom(buf)
						if err != nil || !bytes.Equal(buf[:n], payload) || addr.String() != udp.LocalAddr().String() {
							t.Fatalf("UoT failed: %v", err)
						}
					})
				}
				bad, err := NewClient(context.Background(), ClientOptions{Server: M.ParseSocksaddr(fmt.Sprintf("127.0.0.1:%d", port)), Dialer: &localDialer{}, Security: security, Password: "wrong-loopback-password"})
				if err != nil {
					t.Fatal(err)
				}
				defer bad.Close()
				ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
				defer cancel()
				conn, err := bad.DialContext(ctx, "tcp", M.SocksaddrFromNet(echo.Addr()))
				if err == nil {
					err = exchange(conn, []byte("must fail"))
				}
				if err == nil {
					t.Fatal("wrong password accepted")
				}
			})
		}
	}
}

func TestConcurrentClose(t *testing.T) {
	p := newPKI(t)
	server := startServer(t, &stdtls.Config{Certificates: []stdtls.Certificate{tlsCertificate(t, p.leaf)}}, nil)
	s, err := NewSecurity(SecurityOptions{ServerName: testName, Fingerprint: pin(p.leaf)})
	if err != nil {
		t.Fatal(err)
	}
	parent, cancel := context.WithCancel(context.Background())
	defer cancel()
	c, err := NewClient(parent, ClientOptions{Server: server.addr, Dialer: &localDialer{}, Security: s, Password: testPassword})
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	var wg sync.WaitGroup
	ready := make(chan error, 16)
	for range 16 {
		wg.Add(1)
		go func() {
			defer wg.Done()
			ctx, done := context.WithTimeout(context.Background(), 3*time.Second)
			defer done()
			conn, err := c.DialContext(ctx, "tcp", M.ParseSocksaddr("127.0.0.1:12345"))
			if err != nil {
				ready <- err
				return
			}
			defer conn.Close()
			conn.SetDeadline(time.Now().Add(3 * time.Second))
			payload := bytes.Repeat([]byte("race"), 4096)
			if _, err = conn.Write(payload); err != nil {
				ready <- err
				return
			}
			got := make([]byte, len(payload))
			if _, err = io.ReadFull(conn, got); err != nil {
				ready <- err
				return
			}
			if !bytes.Equal(got, payload) {
				ready <- errors.New("concurrent payload corrupted")
				return
			}
			ready <- nil
			var wait [1]byte
			if _, err = conn.Read(wait[:]); err == nil {
				t.Error("blocked reader did not fail on close")
			}
		}()
	}
	for range 16 {
		if err := <-ready; err != nil {
			t.Error(err)
		}
	}
	cancel()
	c.Close()
	wg.Wait()
	if _, err = c.DialContext(context.Background(), "tcp", M.ParseSocksaddr("127.0.0.1:12345")); err == nil {
		t.Fatal("dial after close succeeded")
	}
}
