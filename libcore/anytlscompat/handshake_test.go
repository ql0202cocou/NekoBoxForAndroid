package anytlscompat

import (
	"context"
	"crypto/tls"
	"errors"
	"net"
	"slices"
	"testing"
	"time"

	C "github.com/sagernet/sing-box/constant"
	"libcore/internal/anytlstest"
)

func TestConfiguredClientHelloALPN(t *testing.T) {
	p := anytlstest.NewPKI(t, testName)
	ech := anytlstest.NewECH(t, 1)
	protocols := []string{"test-primary", "test-fallback"}
	for _, fp := range []string{"chrome", "firefox", "android"} {
		for _, withECH := range []bool{false, true} {
			if withECH && !ECHFingerprintAllowed(fp) {
				continue
			}
			name := fp
			if withECH {
				name += "-ech"
			}
			t.Run(name, func(t *testing.T) {
				cfg := &tls.Config{Certificates: []tls.Certificate{anytlstest.TLSCertificate(t, p.Leaf)},
					NextProtos: protocols[1:],
					GetConfigForClient: func(hello *tls.ClientHelloInfo) (*tls.Config, error) {
						if !slices.Equal(hello.SupportedProtos, protocols) {
							t.Errorf("ALPN = %v", hello.SupportedProtos)
						}
						return nil, nil
					},
				}
				options := SecurityOptions{ServerName: testName, Fingerprint: anytlstest.Pin(p.Leaf), ClientFingerprint: fp, ALPN: protocols}
				if withECH {
					cfg.EncryptedClientHelloKeys = []tls.EncryptedClientHelloKey{ech.Key}
					options.ECH = ECHOptions{Enabled: true, Config: ech.Encoded()}
				}
				server := startServer(t, cfg)
				if err := prototypeExchange(t, server.addr, options); err != nil {
					t.Fatal(err)
				}
				state := <-server.states
				if state.NegotiatedProtocol != protocols[1] || state.ECHAccepted != withECH {
					t.Fatal("unexpected TLS negotiation")
				}
			})
		}
	}
}

func TestSilentPeerHandshakeTimeout(t *testing.T) {
	for _, fp := range []string{"none", "chrome"} {
		t.Run(fp, func(t *testing.T) {
			t.Parallel()
			ctx, cancel := context.WithTimeout(context.Background(), C.TCPTimeout+5*time.Second)
			defer cancel()
			a, b := net.Pipe()
			defer b.Close()
			go func() {
				var buf [8192]byte
				_, _ = b.Read(buf[:])
			}()
			sec, err := NewSecurity(SecurityOptions{ServerName: testName, ClientFingerprint: fp})
			if err != nil {
				t.Fatal(err)
			}
			started := time.Now()
			_, err = sec.Handshake(ctx, a)
			if !errors.Is(err, context.DeadlineExceeded) || ctx.Err() != nil || time.Since(started) < C.TCPTimeout {
				t.Fatalf("elapsed=%s caller=%v err=%v", time.Since(started), ctx.Err(), err)
			}
		})
	}
}
