package boxadapter

import (
	"context"
	"errors"
	"net"
	"sync"

	compat "libcore/anytlscompat"

	"github.com/miekg/dns"
	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/adapter/outbound"
	"github.com/sagernet/sing-box/common/dialer"
	"github.com/sagernet/sing-box/option"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
)

const Type = "neko-anytls"

// 受控构建接口；产品 JSON 注册入口在 config.go，不承诺上游导出格式。
type Options struct {
	Server        M.Socksaddr
	Dialer        N.Dialer
	DialerOptions option.DialerOptions
	DNSRouter     adapter.DNSRouter
	DNSOptions    adapter.DNSQueryOptions
	TLS           compat.SecurityOptions
	Password      string
}

type Outbound struct {
	outbound.Adapter
	options compat.ClientOptions
	mu      sync.RWMutex
	client  *compat.Client
}

var _ adapter.OutboundWithMultiplex = (*Outbound)(nil)
var _ adapter.Lifecycle = (*Outbound)(nil)

func New(ctx context.Context, tag string, options Options) (*Outbound, error) {
	if options.DialerOptions.TCPFastOpen {
		return nil, errors.New("tcp_fast_open is not supported with anytls compatibility outbound")
	}
	if options.TLS.ECH.Enabled && options.TLS.ECH.Config == "" {
		if options.DNSRouter == nil {
			return nil, errors.New("ECH requires an explicit DNS router")
		}
		// 只捕获路由和查询选项，免得 Security 经由 Lookup 持有整个 Options
		router, queryOptions := options.DNSRouter, options.DNSOptions
		options.TLS.ECH.Lookup = compat.HTTPSLookup(func(ctx context.Context, msg *dns.Msg) (*dns.Msg, error) {
			return router.Exchange(ctx, msg, queryOptions)
		})
	}
	security, err := compat.NewSecurity(options.TLS)
	if err != nil {
		return nil, err
	}
	d := options.Dialer
	if d == nil {
		d, err = dialer.NewWithOptions(dialer.Options{Context: ctx, Options: options.DialerOptions, RemoteIsDomain: options.Server.IsDomain()})
		if err != nil {
			return nil, err
		}
	}
	return &Outbound{Adapter: outbound.NewAdapterWithDialerOptions(Type, tag, []string{N.NetworkTCP, N.NetworkUDP}, options.DialerOptions),
		options: compat.ClientOptions{Server: options.Server, Dialer: multiplexDialer{d}, Security: security, Password: options.Password}}, nil
}

func (o *Outbound) Start(stage adapter.StartStage, scope *adapter.Scope) error {
	if stage != adapter.StartStateInitialize {
		return nil
	}
	o.mu.Lock()
	defer o.mu.Unlock()
	if o.client != nil {
		return errors.New("compatibility outbound already initialized")
	}
	c, err := compat.NewClient(scope.Context(), o.options)
	if err != nil {
		return err
	}
	scope.Add(c.Close)
	o.client = c
	return nil
}

func (o *Outbound) MultiplexEnabled() bool { return true }

func (o *Outbound) clientFor(ctx context.Context, destination M.Socksaddr) (context.Context, *compat.Client, error) {
	o.mu.RLock()
	c := o.client
	o.mu.RUnlock()
	if c == nil {
		return nil, nil, errors.New("compatibility outbound not initialized")
	}
	ctx, metadata := adapter.ExtendContext(ctx)
	metadata.Outbound = o.Tag()
	metadata.Destination = destination
	return ctx, c, nil
}

func (o *Outbound) DialContext(ctx context.Context, network string, destination M.Socksaddr) (net.Conn, error) {
	ctx, c, err := o.clientFor(ctx, destination)
	if err != nil {
		return nil, err
	}
	return c.DialContext(ctx, network, destination)
}

func (o *Outbound) ListenPacket(ctx context.Context, destination M.Socksaddr) (net.PacketConn, error) {
	ctx, c, err := o.clientFor(ctx, destination)
	if err != nil {
		return nil, err
	}
	return c.ListenPacket(ctx, destination)
}

type multiplexDialer struct{ N.Dialer }

func (d multiplexDialer) DialContext(ctx context.Context, network string, destination M.Socksaddr) (net.Conn, error) {
	return d.Dialer.DialContext(adapter.ContextForMultiplexSession(ctx), network, destination)
}
