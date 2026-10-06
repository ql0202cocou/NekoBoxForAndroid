package socks

import (
	"context"
	"net"
	"net/netip"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/adapter/outbound"
	"github.com/sagernet/sing-box/common/dialer"
	C "github.com/sagernet/sing-box/constant"
	"github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing/common"
	E "github.com/sagernet/sing/common/exceptions"
	"github.com/sagernet/sing/common/logger"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
	"github.com/sagernet/sing/common/uot"
	"github.com/sagernet/sing/protocol/socks"
	"github.com/sagernet/sing/protocol/socks/socks5"
	"github.com/sagernet/sing/service"
)

func RegisterOutbound(registry *outbound.Registry) {
	outbound.Register[option.SOCKSOutboundOptions](registry, C.TypeSOCKS, NewOutbound)
}

var _ adapter.Outbound = (*Outbound)(nil)

type Outbound struct {
	outbound.Adapter
	dnsRouter adapter.DNSRouter
	logger    logger.ContextLogger
	client    *socks.Client
	resolve   bool
	uotClient *uot.Client

	// UDP ASSOCIATE 由本出站自己握手（见 associate），需要 client 里不导出的这几项
	dialer     N.Dialer
	serverAddr M.Socksaddr
	version    socks.Version
	username   string
	password   string
}

func NewOutbound(ctx context.Context, router adapter.Router, logger log.ContextLogger, tag string, options option.SOCKSOutboundOptions) (adapter.Outbound, error) {
	var version socks.Version
	var err error
	if options.Version != "" {
		version, err = socks.ParseVersion(options.Version)
	} else {
		version = socks.Version5
	}
	if err != nil {
		return nil, err
	}
	outboundDialer, err := dialer.New(ctx, options.DialerOptions, options.ServerIsDomain())
	if err != nil {
		return nil, err
	}
	outbound := &Outbound{
		Adapter:   outbound.NewAdapterWithDialerOptions(C.TypeSOCKS, tag, options.Network.Build(), options.DialerOptions),
		dnsRouter: service.FromContext[adapter.DNSRouter](ctx),
		logger:    logger,
		client:    socks.NewClient(outboundDialer, options.ServerOptions.Build(), version, options.Username, options.Password),
		resolve:   version == socks.Version4,

		dialer:     outboundDialer,
		serverAddr: options.ServerOptions.Build(),
		version:    version,
		username:   options.Username,
		password:   options.Password,
	}
	uotOptions := common.PtrValueOrDefault(options.UDPOverTCP)
	if uotOptions.Enabled {
		outbound.uotClient = &uot.Client{
			Dialer:  outbound.client,
			Version: uotOptions.Version,
		}
	}
	return outbound, nil
}

func (h *Outbound) DialContext(ctx context.Context, network string, destination M.Socksaddr) (net.Conn, error) {
	ctx, metadata := adapter.ExtendContext(ctx)
	metadata.Outbound = h.Tag()
	metadata.Destination = destination
	switch N.NetworkName(network) {
	case N.NetworkTCP:
		h.logger.InfoContext(ctx, "outbound connection to ", destination)
	case N.NetworkUDP:
		if h.uotClient != nil {
			h.logger.InfoContext(ctx, "outbound UoT connect packet connection to ", destination)
			return h.uotClient.DialContext(ctx, network, destination)
		}
		h.logger.InfoContext(ctx, "outbound packet connection to ", destination)
	default:
		return nil, E.Extend(N.ErrUnknownNetwork, network)
	}
	if h.resolve && destination.IsDomain() {
		destinationAddresses, err := h.dnsRouter.Lookup(ctx, destination.Fqdn, adapter.DNSQueryOptions{})
		if err != nil {
			return nil, err
		}
		return N.DialSerial(ctx, h.client, network, destination, destinationAddresses)
	}
	// neko：SOCKS5 的 UDP 自己握手，声明未指定来源（见 associate）
	if N.NetworkName(network) == N.NetworkUDP && h.version == socks.Version5 {
		conn, err := h.associate(ctx, destination)
		if err != nil {
			return nil, err
		}
		return conn, nil
	}
	return h.client.DialContext(ctx, network, destination)
}

func (h *Outbound) ListenPacket(ctx context.Context, destination M.Socksaddr) (net.PacketConn, error) {
	ctx, metadata := adapter.ExtendContext(ctx)
	metadata.Outbound = h.Tag()
	metadata.Destination = destination
	if h.uotClient != nil {
		h.logger.InfoContext(ctx, "outbound UoT packet connection to ", destination)
		return h.uotClient.ListenPacket(ctx, destination)
	}
	if h.resolve && destination.IsDomain() {
		destinationAddresses, err := h.dnsRouter.Lookup(ctx, destination.Fqdn, adapter.DNSQueryOptions{})
		if err != nil {
			return nil, err
		}
		packetConn, _, err := N.ListenSerial(ctx, h.client, destination, destinationAddresses)
		if err != nil {
			return nil, err
		}
		return packetConn, nil
	}
	h.logger.InfoContext(ctx, "outbound packet connection to ", destination)
	// neko：同上，socks4 / 4a 仍交给 sing（照旧报 udp unsupported）
	if h.version == socks.Version5 {
		conn, err := h.associate(ctx, destination)
		if err != nil {
			return nil, err
		}
		return conn, nil
	}
	return h.client.ListenPacket(ctx, destination)
}

// associate 与 sing 的 Client.DialContext（UDP 分支）相同，只是 UDP ASSOCIATE 请求里不带目标：
// sing 的 ClientHandshake5 按目标改写请求的 DST（私有 IPv4 → [::1]:0，私有 IPv6 → 127.0.0.1:0），
// 而数据报实际从拨号用的本机地址发出；只收声明来源的服务端（如 Xray v26.9.30 的 socks 入站，v26.3.27 不查）会把
// 这个关联的包全部丢掉，经映射的 Xray 跳因此连不了私有 IPv4。这里交给握手的是未指定地址（请求写成
// [::]:0，服务端改用 TCP 对端的 IP），原目标照旧交给 AssociatePacketConn（Write / RemoteAddr 用它）
func (h *Outbound) associate(ctx context.Context, destination M.Socksaddr) (result *socks.AssociatePacketConn, err error) {
	tcpConn, err := h.dialer.DialContext(ctx, N.NetworkTCP, h.serverAddr)
	if err != nil {
		return nil, err
	}
	if ctx.Done() != nil {
		stopContext := context.AfterFunc(ctx, func() {
			_ = tcpConn.Close()
		})
		defer func() {
			if !stopContext() {
				if result != nil {
					result.Close()
				}
				result = nil
				err = ctx.Err()
			}
		}()
	}
	response, err := socks.ClientHandshake5(tcpConn, socks5.CommandUDPAssociate, M.Socksaddr{Addr: netip.IPv6Unspecified()}, h.username, h.password)
	if err != nil {
		tcpConn.Close()
		return nil, err
	}
	udpConn, err := h.dialer.DialContext(ctx, N.NetworkUDP, response.Bind)
	if err != nil {
		tcpConn.Close()
		return nil, err
	}
	return socks.NewAssociatePacketConn(udpConn, destination, tcpConn), nil
}
