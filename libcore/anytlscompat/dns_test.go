package anytlscompat

import (
	"context"
	stdtls "crypto/tls"
	"errors"
	"net"
	"sync/atomic"
	"testing"
	"time"

	"libcore/internal/anytlstest"

	"github.com/miekg/dns"
	M "github.com/sagernet/sing/common/metadata"
)

func echAnswer(request *dns.Msg, config []byte, rcode int) *dns.Msg {
	response := new(dns.Msg).SetReply(request)
	response.Rcode = rcode
	if config != nil {
		response.Answer = []dns.RR{&dns.HTTPS{SVCB: dns.SVCB{
			Hdr: dns.RR_Header{Name: request.Question[0].Name, Rrtype: dns.TypeHTTPS, Class: dns.ClassINET, Ttl: 1}, Priority: 1, Target: ".",
			Value: []dns.SVCBKeyValue{&dns.SVCBECHConfig{ECH: config}},
		}}}
	}
	return response
}

func TestECHDNSDifferential(t *testing.T) {
	anytlstest.MihomoBinary(t)
	p := anytlstest.NewPKI(t, testName)
	ech := anytlstest.NewECH(t, 1)
	for _, fp := range []string{"none", "chrome"} {
		for _, outcome := range []string{"answer", "empty", "nxdomain", "malformed"} {
			t.Run(fp+"/"+outcome, func(t *testing.T) {
				server := startServer(t, &stdtls.Config{Certificates: []stdtls.Certificate{anytlstest.TLSCertificate(t, p.Leaf)}, EncryptedClientHelloKeys: []stdtls.EncryptedClientHelloKey{ech.Key}})
				var queries atomic.Int64
				answer := func(msg *dns.Msg) *dns.Msg {
					if len(msg.Question) != 1 || msg.Question[0].Qtype != dns.TypeHTTPS || msg.Question[0].Name != "lookup.test." {
						t.Errorf("unexpected DNS question: %+v", msg.Question)
						return new(dns.Msg).SetRcode(msg, dns.RcodeNameError)
					}
					queries.Add(1)
					switch outcome {
					case "empty":
						return echAnswer(msg, nil, dns.RcodeSuccess)
					case "nxdomain":
						return echAnswer(msg, nil, dns.RcodeNameError)
					case "malformed":
						return echAnswer(msg, []byte{1, 2, 3}, dns.RcodeSuccess)
					}
					return echAnswer(msg, ech.List, dns.RcodeSuccess)
				}
				pc, err := net.ListenPacket("udp", "127.0.0.1:0")
				if err != nil {
					t.Fatal(err)
				}
				dnsServer := &dns.Server{PacketConn: pc, Handler: dns.HandlerFunc(func(w dns.ResponseWriter, m *dns.Msg) { w.WriteMsg(answer(m)) })}
				go dnsServer.ActivateAndServe()
				t.Cleanup(func() { dnsServer.Shutdown() })
				opts := SecurityOptions{ServerName: testName, Fingerprint: anytlstest.Pin(p.Leaf), ClientFingerprint: fp, ECH: ECHOptions{Enabled: true, QueryServerName: "lookup.test",
					Lookup: HTTPSLookup(func(ctx context.Context, m *dns.Msg) (*dns.Msg, error) { return answer(m), ctx.Err() })}}
				a := prototypeExchange(t, server.addr, opts)
				before := queries.Load()
				b := oracleExchange(t, server.addr, opts, "", pc.LocalAddr().String())
				want := outcome == "answer"
				if (a == nil) != want || (b == nil) != want {
					t.Fatalf("want=%v prototype=%v mihomo=%v", want, a, b)
				}
				if before == 0 || queries.Load() <= before {
					t.Fatal("both paths must use the explicit ECH resolver")
				}
			})
		}
	}
}

func TestECHRefreshAndRecovery(t *testing.T) {
	p := anytlstest.NewPKI(t, testName)
	good, bad := anytlstest.NewECH(t, 1), anytlstest.NewECH(t, 2)
	server := startServer(t, &stdtls.Config{Certificates: []stdtls.Certificate{anytlstest.TLSCertificate(t, p.Leaf)}, EncryptedClientHelloKeys: []stdtls.EncryptedClientHelloKey{good.Key}})
	var calls atomic.Int64
	security, err := NewSecurity(SecurityOptions{ServerName: testName, Fingerprint: anytlstest.Pin(p.Leaf), ECH: ECHOptions{Enabled: true, Lookup: HTTPSLookup(func(ctx context.Context, m *dns.Msg) (*dns.Msg, error) {
		n := calls.Add(1)
		if m.Question[0].Name != testName+"." {
			t.Error("SNI must be default query name")
		}
		if n == 2 {
			return nil, errors.New("simulated DNS failure")
		}
		if n == 3 {
			return echAnswer(m, bad.List, dns.RcodeSuccess), nil
		}
		return echAnswer(m, good.List, dns.RcodeSuccess), nil
	})}})
	if err != nil {
		t.Fatal(err)
	}
	for i := range 4 {
		c, err := NewClient(context.Background(), ClientOptions{Server: server.addr, Dialer: &localDialer{}, Security: security, Password: testPassword, DisableReuse: true})
		if err != nil {
			t.Fatal(err)
		}
		ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
		conn, err := c.DialContext(ctx, "tcp", M.ParseSocksaddr("127.0.0.1:12345"))
		if err == nil {
			err = exchange(conn, []byte("rotation"))
		}
		cancel()
		c.Close()
		want := i == 0 || i == 3
		if (err == nil) != want {
			t.Fatalf("step %d want=%v error=%v", i, want, err)
		}
	}
	if calls.Load() != 4 {
		t.Fatalf("stale ECH configuration cached: queries=%d", calls.Load())
	}
}

type observedConn struct {
	net.Conn
	closed atomic.Bool
}

func (c *observedConn) Close() error { c.closed.Store(true); return c.Conn.Close() }

func TestHandshakeCancellation(t *testing.T) {
	for _, phase := range []string{"dns", "tls"} {
		t.Run(phase, func(t *testing.T) {
			a, b := net.Pipe()
			defer b.Close()
			raw := &observedConn{Conn: a}
			opts := SecurityOptions{ServerName: testName, AllowInsecure: true}
			entered := make(chan struct{})
			if phase == "dns" {
				opts.ECH = ECHOptions{Enabled: true, Lookup: func(ctx context.Context, _ string) ([]byte, error) {
					close(entered)
					<-ctx.Done()
					return nil, ctx.Err()
				}}
			} else {
				close(entered)
			}
			s, err := NewSecurity(opts)
			if err != nil {
				t.Fatal(err)
			}
			ctx, cancel := context.WithCancel(context.Background())
			defer cancel()
			done := make(chan error, 1)
			go func() { _, err := s.Handshake(ctx, raw); done <- err }()
			<-entered
			cancel()
			select {
			case err := <-done:
				if err == nil || !raw.closed.Load() {
					t.Fatal("cancel must fail and close connection")
				}
			case <-time.After(time.Second):
				t.Fatal("handshake cancellation blocked")
			}
		})
	}
}

func TestStaticECHDoesNotQuery(t *testing.T) {
	p := anytlstest.NewPKI(t, testName)
	ech := anytlstest.NewECH(t, 1)
	server := startServer(t, &stdtls.Config{Certificates: []stdtls.Certificate{anytlstest.TLSCertificate(t, p.Leaf)}, EncryptedClientHelloKeys: []stdtls.EncryptedClientHelloKey{ech.Key}})
	if err := prototypeExchange(t, server.addr, SecurityOptions{ServerName: testName, Fingerprint: anytlstest.Pin(p.Leaf), ECH: ECHOptions{Enabled: true, Config: ech.Encoded(), Lookup: func(context.Context, string) ([]byte, error) { t.Error("static ECH queried DNS"); return nil, ErrECH }}}); err != nil {
		t.Fatal(err)
	}
}
