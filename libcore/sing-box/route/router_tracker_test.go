package route

// Regression tests for the neko `trackersAccess` lock patch (see NEKO.md):
// upstream appends to Router.trackers without synchronization while the
// Android app calls AppendTracker (via SetV2rayStats) on an already routing
// box, so the append raced the per-connection tracker reads. These tests run
// AppendTracker concurrently with all three read paths (RouteConnectionEx,
// RoutePacketConnectionEx, PreMatch's NewTracker closure) and are meant to be
// executed with -race.

import (
	"context"
	"errors"
	"net"
	"net/netip"
	"sync"
	"sync/atomic"
	"testing"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing-tun"
	"github.com/sagernet/sing/common/bufio"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"

	"github.com/stretchr/testify/require"
)

type trackerTestOutbound struct{}

func (o *trackerTestOutbound) Type() string           { return "direct" }
func (o *trackerTestOutbound) Tag() string            { return "out" }
func (o *trackerTestOutbound) Network() []string      { return []string{N.NetworkTCP, N.NetworkUDP} }
func (o *trackerTestOutbound) Dependencies() []string { return nil }

func (o *trackerTestOutbound) DialContext(ctx context.Context, network string, destination M.Socksaddr) (net.Conn, error) {
	return nil, errors.New("not implemented")
}

func (o *trackerTestOutbound) ListenPacket(ctx context.Context, destination M.Socksaddr) (net.PacketConn, error) {
	return nil, errors.New("not implemented")
}

func (o *trackerTestOutbound) PortAddresses() (v4 netip.Addr, v6 netip.Addr) {
	return netip.Addr{}, netip.Addr{}
}

func (o *trackerTestOutbound) PortMTU() uint32                          { return 0 }
func (o *trackerTestOutbound) AttachReturn(returnPath tun.Return) error { return nil }
func (o *trackerTestOutbound) DetachReturn(returnPath tun.Return) error { return nil }
func (o *trackerTestOutbound) WritePackets(packets [][]byte) error      { return nil }

func (o *trackerTestOutbound) PreMatchFlow(network string, destination netip.Addr) adapter.PreMatchAction {
	return adapter.PreMatchFlow
}

type trackerTestOutboundManager struct {
	defaultOutbound adapter.Outbound
}

func (m *trackerTestOutboundManager) Start(stage adapter.StartStage) error { return nil }
func (m *trackerTestOutboundManager) Close() error                         { return nil }
func (m *trackerTestOutboundManager) Outbounds() []adapter.Outbound {
	return []adapter.Outbound{m.defaultOutbound}
}
func (m *trackerTestOutboundManager) Outbound(tag string) (adapter.Outbound, bool) {
	return m.defaultOutbound, tag == m.defaultOutbound.Tag()
}
func (m *trackerTestOutboundManager) Default() adapter.Outbound { return m.defaultOutbound }
func (m *trackerTestOutboundManager) Remove(tag string) error   { return nil }
func (m *trackerTestOutboundManager) Create(ctx context.Context, router adapter.Router, logger log.ContextLogger, tag string, outboundType string, options any) error {
	return nil
}

type trackerTestConnectionManager struct{}

func (m *trackerTestConnectionManager) Start(stage adapter.StartStage) error { return nil }
func (m *trackerTestConnectionManager) Close() error                         { return nil }
func (m *trackerTestConnectionManager) Count() int                           { return 0 }
func (m *trackerTestConnectionManager) CloseAll()                            {}
func (m *trackerTestConnectionManager) TrackConn(conn net.Conn) net.Conn     { return conn }
func (m *trackerTestConnectionManager) TrackPacketConn(conn net.PacketConn) net.PacketConn {
	return conn
}
func (m *trackerTestConnectionManager) NewConnection(ctx context.Context, this N.Dialer, conn net.Conn, metadata adapter.InboundContext, onClose N.CloseHandlerFunc) {
	conn.Close()
	onClose(nil)
}
func (m *trackerTestConnectionManager) NewPacketConnection(ctx context.Context, this N.Dialer, conn N.PacketConn, metadata adapter.InboundContext, onClose N.CloseHandlerFunc) {
	conn.Close()
	onClose(nil)
}

type trackerTestDNSTransportManager struct{}

func (m *trackerTestDNSTransportManager) Start(stage adapter.StartStage) error { return nil }
func (m *trackerTestDNSTransportManager) Close() error                         { return nil }
func (m *trackerTestDNSTransportManager) Transports() []adapter.DNSTransport   { return nil }
func (m *trackerTestDNSTransportManager) Transport(tag string) (adapter.DNSTransport, bool) {
	return nil, false
}
func (m *trackerTestDNSTransportManager) Default() adapter.DNSTransport   { return nil }
func (m *trackerTestDNSTransportManager) FakeIP() adapter.FakeIPTransport { return nil }
func (m *trackerTestDNSTransportManager) Remove(tag string) error         { return nil }
func (m *trackerTestDNSTransportManager) Create(ctx context.Context, logger log.ContextLogger, tag string, outboundType string, options any) error {
	return nil
}

type trackerTestCounters struct {
	connections       atomic.Int32
	packetConnections atomic.Int32
	flows             atomic.Int32
}

type countingConnectionTracker struct {
	counters *trackerTestCounters
}

func (t *countingConnectionTracker) RoutedConnection(ctx context.Context, conn net.Conn, metadata adapter.InboundContext, matchedRule adapter.Rule, matchOutbound adapter.Outbound) net.Conn {
	t.counters.connections.Add(1)
	return conn
}

func (t *countingConnectionTracker) RoutedPacketConnection(ctx context.Context, conn N.PacketConn, metadata adapter.InboundContext, matchedRule adapter.Rule, matchOutbound adapter.Outbound) N.PacketConn {
	t.counters.packetConnections.Add(1)
	return conn
}

func (t *countingConnectionTracker) RoutedFlow(ctx context.Context, metadata adapter.InboundContext, matchedRule adapter.Rule, matchOutbound adapter.Outbound) tun.FlowTracker {
	t.counters.flows.Add(1)
	return nil
}

func newTrackerTestRouter() *Router {
	return &Router{
		ctx:          context.Background(),
		logger:       log.NewNOPFactory().NewLogger("test"),
		outbound:     &trackerTestOutboundManager{defaultOutbound: &trackerTestOutbound{}},
		connection:   &trackerTestConnectionManager{},
		dnsTransport: &trackerTestDNSTransportManager{},
	}
}

func routeTrackerTestTCP(router *Router) {
	client, server := net.Pipe()
	defer server.Close()
	router.RouteConnectionEx(context.Background(), client, adapter.InboundContext{
		Domain:      "example.com",
		Source:      M.ParseSocksaddrHostPort("10.0.0.1", 12345),
		Destination: M.ParseSocksaddrHostPort("example.com", 443),
	}, func(it error) {})
}

func routeTrackerTestUDP(router *Router) {
	udpConn, err := net.ListenPacket("udp", "127.0.0.1:0")
	if err != nil {
		return
	}
	router.RoutePacketConnectionEx(context.Background(), bufio.NewPacketConn(udpConn), adapter.InboundContext{
		Domain:      "example.com",
		Source:      M.ParseSocksaddrHostPort("10.0.0.1", 12345),
		Destination: M.ParseSocksaddrHostPort("example.com", 443),
	}, func(it error) {})
}

func preMatchTrackerTestFlow(router *Router) {
	destination := M.SocksaddrFrom(netip.MustParseAddr("198.51.100.1"), 443)
	result := router.PreMatch(adapter.InboundContext{
		Network:     N.NetworkUDP,
		Domain:      "example.com",
		Source:      M.ParseSocksaddrHostPort("10.0.0.1", 12345),
		Destination: destination,
	}, nil)
	if result.Action != adapter.PreMatchFlow || result.NewTracker == nil {
		return
	}
	result.NewTracker()
}

// Every appended tracker is invoked once per routed connection on each of the
// three read paths.
func TestRouterTrackersInvoked(t *testing.T) {
	t.Parallel()
	router := newTrackerTestRouter()
	counters := &trackerTestCounters{}
	for range 3 {
		router.AppendTracker(&countingConnectionTracker{counters: counters})
	}
	routeTrackerTestTCP(router)
	require.Equal(t, int32(3), counters.connections.Load())
	routeTrackerTestUDP(router)
	require.Equal(t, int32(3), counters.packetConnections.Load())
	preMatchTrackerTestFlow(router)
	require.Equal(t, int32(3), counters.flows.Load())
}

// AppendTracker concurrent with the routing read paths must not race; run
// with -race. After the dust settles every tracker must still be invoked on
// each path.
func TestRouterTrackersConcurrentAppend(t *testing.T) {
	t.Parallel()
	router := newTrackerTestRouter()
	counters := &trackerTestCounters{}
	const (
		writers          = 4
		appendsPerWriter = 25
		readers          = 4
		maxReadRounds    = 1000
	)
	stopReaders := make(chan struct{})
	var writerWG, readerWG sync.WaitGroup
	for range writers {
		writerWG.Add(1)
		go func() {
			defer writerWG.Done()
			for range appendsPerWriter {
				router.AppendTracker(&countingConnectionTracker{counters: counters})
			}
		}()
	}
	for reader := range readers {
		readerWG.Add(1)
		go func() {
			defer readerWG.Done()
			for round := 0; round < maxReadRounds; round++ {
				select {
				case <-stopReaders:
					return
				default:
				}
				switch reader % 3 {
				case 0:
					routeTrackerTestTCP(router)
				case 1:
					routeTrackerTestUDP(router)
				default:
					preMatchTrackerTestFlow(router)
				}
			}
		}()
	}
	writerWG.Wait()
	close(stopReaders)
	readerWG.Wait()

	const totalTrackers = writers * appendsPerWriter
	router.trackersAccess.RLock()
	trackerCount := len(router.trackers)
	router.trackersAccess.RUnlock()
	require.Equal(t, totalTrackers, trackerCount)

	counters.connections.Store(0)
	counters.packetConnections.Store(0)
	counters.flows.Store(0)
	routeTrackerTestTCP(router)
	require.Equal(t, int32(totalTrackers), counters.connections.Load())
	routeTrackerTestUDP(router)
	require.Equal(t, int32(totalTrackers), counters.packetConnections.Load())
	preMatchTrackerTestFlow(router)
	require.Equal(t, int32(totalTrackers), counters.flows.Load())
}
