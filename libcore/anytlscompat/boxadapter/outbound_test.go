package boxadapter

import (
	"context"
	"errors"
	"net"
	"sync/atomic"
	"testing"
	"time"

	compat "libcore/anytlscompat"

	"github.com/miekg/dns"
	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing-box/option"
	M "github.com/sagernet/sing/common/metadata"
)

type controlledDialer struct {
	calls atomic.Int64
	peers chan net.Conn
}

func (d *controlledDialer) DialContext(ctx context.Context, network string, server M.Socksaddr) (net.Conn, error) {
	if network != "tcp" || server.String() != "127.0.0.1:12345" {
		return nil, errors.New("unexpected dial target")
	}
	d.calls.Add(1)
	a, b := net.Pipe()
	d.peers <- b
	return a, nil
}
func (d *controlledDialer) ListenPacket(context.Context, M.Socksaddr) (net.PacketConn, error) {
	return nil, errors.New("unexpected direct UDP")
}

type controlledDNS struct {
	adapter.DNSRouter
	t       *testing.T
	calls   atomic.Int64
	entered chan struct{}
}

func (d *controlledDNS) Exchange(ctx context.Context, msg *dns.Msg, opts adapter.DNSQueryOptions) (*dns.Msg, error) {
	d.calls.Add(1)
	if len(msg.Question) != 1 || msg.Question[0].Qtype != dns.TypeHTTPS || msg.Question[0].Name != "lookup.test." || !opts.DisableCache || opts.Timeout != 2*time.Second {
		d.t.Error("DNS route or query options lost")
	}
	close(d.entered)
	<-ctx.Done()
	return nil, ctx.Err()
}

func TestControlledDialDNSAndScope(t *testing.T) {
	d := &controlledDialer{peers: make(chan net.Conn, 1)}
	r := &controlledDNS{t: t, entered: make(chan struct{})}
	opts := Options{Server: M.ParseSocksaddr("127.0.0.1:12345"), Dialer: d, DNSRouter: r,
		DNSOptions:    adapter.DNSQueryOptions{DisableCache: true, Timeout: 2 * time.Second},
		DialerOptions: option.DialerOptions{Detour: "controlled-parent"},
		TLS:           compat.SecurityOptions{ServerName: "inner.test", ECH: compat.ECHOptions{Enabled: true, QueryServerName: "lookup.test"}}}
	o, err := New(context.Background(), "prototype", opts)
	if err != nil {
		t.Fatal(err)
	}
	if deps := o.Dependencies(); len(deps) != 1 || deps[0] != "controlled-parent" {
		t.Fatal("detour dependency lost")
	}
	dest := M.ParseSocksaddr("127.0.0.1:80")
	if _, err = o.DialContext(context.Background(), "tcp", dest); err == nil {
		t.Fatal("dial before initialization")
	}
	scope := adapter.NewScope(context.Background(), log.NewNOPFactory().NewLogger("test"))
	t.Cleanup(func() { scope.Close() })
	for _, stage := range adapter.ListStartStages {
		if err = o.Start(stage, scope); err != nil {
			t.Fatal(err)
		}
	}
	done := make(chan error, 1)
	go func() { _, err := o.DialContext(context.Background(), "tcp", dest); done <- err }()
	select {
	case <-r.entered:
	case <-time.After(time.Second):
		t.Fatal("explicit DNS router not reached")
	}
	if err = scope.Close(); err != nil {
		t.Fatal(err)
	}
	select {
	case err = <-done:
		if !errors.Is(err, context.Canceled) {
			t.Fatalf("scope cancellation lost: %v", err)
		}
	case <-time.After(time.Second):
		t.Fatal("scope did not cancel handshake")
	}
	peer := <-d.peers
	defer peer.Close()
	peer.SetReadDeadline(time.Now().Add(time.Second))
	var b [1]byte
	if _, err = peer.Read(b[:]); err == nil {
		t.Fatal("failed handshake kept the raw socket")
	}
	if _, err = o.DialContext(context.Background(), "tcp", dest); err == nil {
		t.Fatal("dial after scope closure")
	}
	if d.calls.Load() != 1 || r.calls.Load() != 1 {
		t.Fatal("dial or DNS unexpectedly retried")
	}
}

func TestNoImplicitECHResolver(t *testing.T) {
	_, err := New(context.Background(), "prototype", Options{TLS: compat.SecurityOptions{ECH: compat.ECHOptions{Enabled: true}}})
	if err == nil {
		t.Fatal("missing router silently fell back to system DNS")
	}
}
