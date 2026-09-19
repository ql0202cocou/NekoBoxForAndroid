package libcore

import (
	"context"
	"errors"
	"testing"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing/service"

	mDNS "github.com/miekg/dns"
)

type stubDNSTransport struct {
	tag      string
	typeName string
	calls    int
	response *mDNS.Msg
	err      error
}

func (t *stubDNSTransport) Start(stage adapter.StartStage) error { return nil }
func (t *stubDNSTransport) Close() error                         { return nil }
func (t *stubDNSTransport) Type() string                         { return t.typeName }
func (t *stubDNSTransport) Tag() string                          { return t.tag }
func (t *stubDNSTransport) Dependencies() []string               { return nil }
func (t *stubDNSTransport) Reset()                               {}

func (t *stubDNSTransport) Exchange(ctx context.Context, message *mDNS.Msg) (*mDNS.Msg, error) {
	t.calls++
	return t.response, t.err
}

func (t *stubDNSTransport) ExchangeAsync(ctx context.Context, message *mDNS.Msg, callback func(response *mDNS.Msg, err error)) {
	go func() {
		response, err := t.Exchange(ctx, message)
		callback(response, err)
	}()
}

type stubDNSTransportManager struct {
	transports map[string]adapter.DNSTransport
}

func (m *stubDNSTransportManager) Start(stage adapter.StartStage) error { return nil }
func (m *stubDNSTransportManager) Close() error                         { return nil }
func (m *stubDNSTransportManager) Transports() []adapter.DNSTransport   { return nil }
func (m *stubDNSTransportManager) Transport(tag string) (adapter.DNSTransport, bool) {
	transport, loaded := m.transports[tag]
	return transport, loaded
}
func (m *stubDNSTransportManager) Default() adapter.DNSTransport   { return nil }
func (m *stubDNSTransportManager) FakeIP() adapter.FakeIPTransport { return nil }
func (m *stubDNSTransportManager) Remove(tag string) error         { return nil }
func (m *stubDNSTransportManager) Create(ctx context.Context, logger log.ContextLogger, tag string, transportType string, options any) error {
	return nil
}

func newSeqResponse(rcode int) *mDNS.Msg {
	message := new(mDNS.Msg)
	message.Rcode = rcode
	return message
}

func newSeqQuery() *mDNS.Msg {
	return new(mDNS.Msg).SetQuestion("example.com.", mDNS.TypeA)
}

func newSequentialTestTransport(t *testing.T, manager *stubDNSTransportManager, tag string, members ...string) *SequentialTransport {
	t.Helper()
	ctx := service.ContextWith(context.Background(), adapter.DNSTransportManager(manager))
	transport, err := newSequentialTransport(ctx, log.NewNOPFactory().NewLogger("test"), tag, SequentialDNSServerOptions{Servers: members})
	if err != nil {
		t.Fatal(err)
	}
	return transport.(*SequentialTransport)
}

func TestSequentialFirstMemberSuccess(t *testing.T) {
	first := &stubDNSTransport{tag: "a", typeName: "udp", response: newSeqResponse(mDNS.RcodeSuccess)}
	second := &stubDNSTransport{tag: "b", typeName: "udp", response: newSeqResponse(mDNS.RcodeSuccess)}
	manager := &stubDNSTransportManager{transports: map[string]adapter.DNSTransport{"a": first, "b": second}}
	transport := newSequentialTestTransport(t, manager, "seq", "a", "b")

	response, err := transport.Exchange(context.Background(), newSeqQuery())
	if err != nil {
		t.Fatal(err)
	}
	if response != first.response {
		t.Fatal("expected first member's response")
	}
	if first.calls != 1 || second.calls != 0 {
		t.Fatalf("expected only first member queried, got a=%d b=%d", first.calls, second.calls)
	}
}

func TestSequentialTransportErrorFallsThrough(t *testing.T) {
	first := &stubDNSTransport{tag: "a", typeName: "udp", err: errors.New("timeout")}
	second := &stubDNSTransport{tag: "b", typeName: "udp", response: newSeqResponse(mDNS.RcodeSuccess)}
	manager := &stubDNSTransportManager{transports: map[string]adapter.DNSTransport{"a": first, "b": second}}
	transport := newSequentialTestTransport(t, manager, "seq", "a", "b")

	response, err := transport.Exchange(context.Background(), newSeqQuery())
	if err != nil {
		t.Fatal(err)
	}
	if response != second.response {
		t.Fatal("expected second member's response")
	}
	if first.calls != 1 || second.calls != 1 {
		t.Fatalf("expected both members queried once, got a=%d b=%d", first.calls, second.calls)
	}
}

func TestSequentialBadRcodeFallsThrough(t *testing.T) {
	first := &stubDNSTransport{tag: "a", typeName: "udp", response: newSeqResponse(mDNS.RcodeNameError)}
	second := &stubDNSTransport{tag: "b", typeName: "udp", response: newSeqResponse(mDNS.RcodeSuccess)}
	manager := &stubDNSTransportManager{transports: map[string]adapter.DNSTransport{"a": first, "b": second}}
	transport := newSequentialTestTransport(t, manager, "seq", "a", "b")

	response, err := transport.Exchange(context.Background(), newSeqQuery())
	if err != nil {
		t.Fatal(err)
	}
	if response != second.response {
		t.Fatal("expected second member's response after NXDOMAIN")
	}
}

func TestSequentialEmptyNoerrorIsSuccess(t *testing.T) {
	// NOERROR with no answers is final: a split-horizon server answering
	// "exists but no A record" must not fall through (same as rule fallback).
	first := &stubDNSTransport{tag: "a", typeName: "udp", response: newSeqResponse(mDNS.RcodeSuccess)}
	second := &stubDNSTransport{tag: "b", typeName: "udp", response: newSeqResponse(mDNS.RcodeSuccess)}
	manager := &stubDNSTransportManager{transports: map[string]adapter.DNSTransport{"a": first, "b": second}}
	transport := newSequentialTestTransport(t, manager, "seq", "a", "b")

	response, err := transport.Exchange(context.Background(), newSeqQuery())
	if err != nil {
		t.Fatal(err)
	}
	if response != first.response {
		t.Fatal("expected first member's empty NOERROR response")
	}
	if second.calls != 0 {
		t.Fatal("empty NOERROR must not fall through")
	}
}

func TestSequentialAllFailReturnsLastFailure(t *testing.T) {
	first := &stubDNSTransport{tag: "a", typeName: "udp", err: errors.New("timeout")}
	second := &stubDNSTransport{tag: "b", typeName: "udp", response: newSeqResponse(mDNS.RcodeServerFailure)}
	manager := &stubDNSTransportManager{transports: map[string]adapter.DNSTransport{"a": first, "b": second}}
	transport := newSequentialTestTransport(t, manager, "seq", "a", "b")

	response, err := transport.Exchange(context.Background(), newSeqQuery())
	if err != nil {
		t.Fatal("last member's rcode response must surface as an answer, not an error")
	}
	if response == nil || response.Rcode != mDNS.RcodeServerFailure {
		t.Fatal("expected last member's SERVFAIL response")
	}

	transport2 := newSequentialTestTransport(t, manager, "seq2", "b", "a")
	_, err = transport2.Exchange(context.Background(), newSeqQuery())
	if err == nil || err.Error() != "timeout" {
		t.Fatal("expected last member's error when it is the final failure")
	}
}

func TestSequentialContextCancelled(t *testing.T) {
	first := &stubDNSTransport{tag: "a", typeName: "udp", response: newSeqResponse(mDNS.RcodeSuccess)}
	manager := &stubDNSTransportManager{transports: map[string]adapter.DNSTransport{"a": first}}
	transport := newSequentialTestTransport(t, manager, "seq", "a")

	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	_, err := transport.Exchange(ctx, newSeqQuery())
	if !errors.Is(err, context.Canceled) {
		t.Fatal("expected context.Canceled")
	}
	if first.calls != 0 {
		t.Fatal("cancelled context must not query any member")
	}
}

func TestSequentialDependencies(t *testing.T) {
	manager := &stubDNSTransportManager{transports: map[string]adapter.DNSTransport{}}
	transport := newSequentialTestTransport(t, manager, "seq", "a", "b")
	dependencies := transport.Dependencies()
	if len(dependencies) != 2 || dependencies[0] != "a" || dependencies[1] != "b" {
		t.Fatalf("unexpected dependencies: %v", dependencies)
	}
}

func TestSequentialConstructorValidation(t *testing.T) {
	manager := &stubDNSTransportManager{transports: map[string]adapter.DNSTransport{}}
	ctx := service.ContextWith(context.Background(), adapter.DNSTransportManager(manager))
	logger := log.NewNOPFactory().NewLogger("test")

	if _, err := newSequentialTransport(ctx, logger, "seq", SequentialDNSServerOptions{}); err == nil {
		t.Fatal("empty member servers must be rejected")
	}
	if _, err := newSequentialTransport(ctx, logger, "seq", SequentialDNSServerOptions{Servers: []string{"a", "seq"}}); err == nil {
		t.Fatal("self member must be rejected")
	}
}

func TestSequentialStartValidation(t *testing.T) {
	// missing member
	manager := &stubDNSTransportManager{transports: map[string]adapter.DNSTransport{}}
	transport := newSequentialTestTransport(t, manager, "seq", "a")
	if err := transport.Start(adapter.StartStateStart); err == nil {
		t.Fatal("missing member must fail start")
	}

	// nested sequential member
	inner := &stubDNSTransport{tag: "inner", typeName: DNSTypeSequential}
	manager2 := &stubDNSTransportManager{transports: map[string]adapter.DNSTransport{"inner": inner}}
	transport2 := newSequentialTestTransport(t, manager2, "seq", "inner")
	if err := transport2.Start(adapter.StartStateStart); err == nil {
		t.Fatal("sequential member must fail start")
	}

	// other stages are no-ops even with broken members
	if err := transport.Start(adapter.StartStatePostStart); err != nil {
		t.Fatal(err)
	}
}
