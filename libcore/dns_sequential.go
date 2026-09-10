package libcore

import (
	"context"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/dns"
	"github.com/sagernet/sing-box/log"
	E "github.com/sagernet/sing/common/exceptions"
	"github.com/sagernet/sing/common/json/badoption"
	"github.com/sagernet/sing/common/logger"
	"github.com/sagernet/sing/service"

	mDNS "github.com/miekg/dns"
)

// DNSTypeSequential is a libcore-only DNS transport type: it queries its
// member servers in order and falls through on failure, replacing the
// deprecated "dialer resolution walks DNS rules" path that sing-box may
// remove on the next rebase. Group nameserver chains bind their domain
// outbounds to one of these so the group fallback order survives without a
// sing-box patch.
const DNSTypeSequential = "neko-sequential"

type SequentialDNSServerOptions struct {
	Servers badoption.Listable[string] `json:"servers"`
}

func registerSequentialTransport(registry *dns.TransportRegistry) {
	dns.RegisterTransport(registry, DNSTypeSequential, NewSequentialTransport)
}

var _ adapter.DNSTransport = (*SequentialTransport)(nil)

type SequentialTransport struct {
	dns.TransportAdapter
	logger  logger.ContextLogger
	manager adapter.DNSTransportManager
	members []string
}

func NewSequentialTransport(ctx context.Context, logger log.ContextLogger, tag string, options SequentialDNSServerOptions) (adapter.DNSTransport, error) {
	if len(options.Servers) == 0 {
		return nil, E.New("empty member servers")
	}
	for _, member := range options.Servers {
		if member == tag {
			return nil, E.New("member server cannot be the transport itself: ", tag)
		}
	}
	manager := service.FromContext[adapter.DNSTransportManager](ctx)
	if manager == nil {
		return nil, E.New("missing DNS transport manager in context")
	}
	return &SequentialTransport{
		TransportAdapter: dns.NewTransportAdapter(DNSTypeSequential, tag, options.Servers),
		logger:           logger,
		manager:          manager,
		members:          options.Servers,
	}, nil
}

func (t *SequentialTransport) Start(stage adapter.StartStage) error {
	if stage != adapter.StartStateStart {
		return nil
	}
	// Dependencies() already orders member starts and fails on missing ones;
	// this only rejects nesting, which dependency checks cannot see.
	for _, member := range t.members {
		transport, loaded := t.manager.Transport(member)
		if !loaded {
			return E.New("member server not found: ", member)
		}
		if transport.Type() == DNSTypeSequential {
			return E.New("member server cannot be ", DNSTypeSequential, ": ", member)
		}
	}
	return nil
}

func (t *SequentialTransport) Close() error {
	return nil
}

func (t *SequentialTransport) Reset() {
}

func (t *SequentialTransport) Exchange(ctx context.Context, message *mDNS.Msg) (*mDNS.Msg, error) {
	var lastResponse *mDNS.Msg
	var lastErr error
	for _, member := range t.members {
		if ctx.Err() != nil {
			return nil, ctx.Err()
		}
		transport, loaded := t.manager.Transport(member)
		if !loaded {
			lastResponse, lastErr = nil, E.New("member server not found: ", member)
			continue
		}
		response, err := transport.Exchange(ctx, message)
		// Same failure definition as the neko DNS rule fallback: transport
		// errors and non-success rcodes try the next member, while a NOERROR
		// answer — even an empty one — is final.
		if err == nil && response != nil && response.Rcode == mDNS.RcodeSuccess {
			return response, nil
		}
		if err != nil {
			t.logger.DebugContext(ctx, "member ", member, " failed: ", err)
			lastResponse, lastErr = nil, err
		} else {
			t.logger.DebugContext(ctx, "member ", member, " answered ", mDNS.RcodeToString[response.Rcode])
			lastResponse, lastErr = response, nil
		}
	}
	return lastResponse, lastErr
}

func (t *SequentialTransport) ExchangeAsync(ctx context.Context, message *mDNS.Msg, callback func(response *mDNS.Msg, err error)) {
	go func() {
		response, err := t.Exchange(ctx, message)
		callback(response, err)
	}()
}
