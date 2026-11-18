package libcore

import (
	"context"
	"net"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-tun"
	N "github.com/sagernet/sing/common/network"
)

// 仅过滤构建器明确登记的映射入站，不按 tag 前缀猜测，也不改路由或其它 tracker。
type trafficTracker struct {
	adapter.ConnectionTracker
	ignored map[string]bool
}

func newTrafficTracker(delegate adapter.ConnectionTracker, inbounds []string) adapter.ConnectionTracker {
	ignored := make(map[string]bool)
	for _, tag := range inbounds {
		if tag != "" {
			ignored[tag] = true
		}
	}
	return &trafficTracker{ConnectionTracker: delegate, ignored: ignored}
}

func (t *trafficTracker) RoutedConnection(ctx context.Context, conn net.Conn, metadata adapter.InboundContext, rule adapter.Rule, outbound adapter.Outbound) net.Conn {
	if t.ignored[metadata.Inbound] {
		return conn
	}
	return t.ConnectionTracker.RoutedConnection(ctx, conn, metadata, rule, outbound)
}

func (t *trafficTracker) RoutedPacketConnection(ctx context.Context, conn N.PacketConn, metadata adapter.InboundContext, rule adapter.Rule, outbound adapter.Outbound) N.PacketConn {
	if t.ignored[metadata.Inbound] {
		return conn
	}
	return t.ConnectionTracker.RoutedPacketConnection(ctx, conn, metadata, rule, outbound)
}

func (t *trafficTracker) RoutedFlow(ctx context.Context, metadata adapter.InboundContext, rule adapter.Rule, outbound adapter.Outbound) tun.FlowTracker {
	if t.ignored[metadata.Inbound] {
		return nil
	}
	return t.ConnectionTracker.RoutedFlow(ctx, metadata, rule, outbound)
}
