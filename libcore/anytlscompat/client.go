package anytlscompat

import (
	"context"
	"errors"
	"net"
	"sync"
	"time"

	anytls "github.com/sagernet/sing-anytls"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
	"github.com/sagernet/sing/common/uot"
)

type ClientOptions struct {
	Server                   M.Socksaddr
	Dialer                   N.Dialer
	Security                 *Security
	Password                 string
	ClientMetadata           string
	DisableReuse             bool
	IdleSessionCheckInterval time.Duration
	IdleSessionTimeout       time.Duration
	MinIdleSession           int
}

type Client struct {
	sessions  *anytls.Client
	uot       uot.Client
	ctx       context.Context
	cancel    context.CancelFunc
	closeOnce sync.Once
	closeErr  error
}

// 只使用调用方提供的受控拨号器，连接生命周期覆盖认证与会话复用。
func NewClient(parent context.Context, options ClientOptions) (*Client, error) {
	if options.Dialer == nil || options.Security == nil || !options.Server.IsValid() {
		return nil, errors.New("missing controlled dialer, security or server")
	}
	ctx, cancel := context.WithCancel(parent)
	c := &Client{ctx: ctx, cancel: cancel}
	var err error
	c.sessions, err = anytls.NewClient(anytls.ClientOptions{
		Password: options.Password, ClientMetadata: options.ClientMetadata, DisableReuse: options.DisableReuse,
		IdleSessionCheckInterval: options.IdleSessionCheckInterval, IdleSessionTimeout: options.IdleSessionTimeout,
		MinIdleSession: options.MinIdleSession,
		DialOut: func(caller context.Context) (net.Conn, error) {
			dialCtx, done := context.WithCancel(caller)
			stop := context.AfterFunc(ctx, done)
			defer func() { stop(); done() }()
			if err := ctx.Err(); err != nil {
				return nil, err
			}
			raw, err := options.Dialer.DialContext(dialCtx, N.NetworkTCP, options.Server)
			if err != nil {
				return nil, err
			}
			// 会话库登记连接之前也可能阻塞在认证写入，生命周期必须覆盖这个窗口。
			original := raw
			raw = &lifetimeConn{Conn: original, stop: context.AfterFunc(ctx, func() { original.Close() })}
			if attempt, _ := caller.Value((*dialAttempt)(nil)).(*dialAttempt); attempt != nil {
				attempt.stops = append(attempt.stops, context.AfterFunc(caller, func() { original.Close() }))
			}
			conn, _, err := options.Security.Handshake(dialCtx, raw)
			return conn, err
		},
	})
	if err != nil {
		cancel()
		return nil, err
	}
	c.uot = uot.Client{Dialer: sessionDialer{c}, Version: uot.Version}
	context.AfterFunc(ctx, func() { c.Close() })
	return c, nil
}

func (c *Client) DialContext(ctx context.Context, network string, destination M.Socksaddr) (net.Conn, error) {
	if err := c.ctx.Err(); err != nil {
		return nil, err
	}
	switch network {
	case N.NetworkTCP:
		if err := ctx.Err(); err != nil {
			return nil, err
		}
		attempt := &dialAttempt{}
		defer func() {
			for _, stop := range attempt.stops {
				stop()
			}
		}()
		conn, err := c.sessions.DialContext(context.WithValue(ctx, (*dialAttempt)(nil), attempt), destination)
		if cause := ctx.Err(); cause != nil {
			if conn != nil {
				conn.Close()
			}
			return nil, cause
		}
		return conn, err
	case N.NetworkUDP:
		return c.uot.DialContext(ctx, network, destination)
	default:
		return nil, errors.New("unsupported network")
	}
}

func (c *Client) ListenPacket(ctx context.Context, destination M.Socksaddr) (net.PacketConn, error) {
	if err := c.ctx.Err(); err != nil {
		return nil, err
	}
	return c.uot.ListenPacket(ctx, destination)
}

func (c *Client) Close() error {
	c.closeOnce.Do(func() { c.cancel(); c.closeErr = c.sessions.Close() })
	return c.closeErr
}

type sessionDialer struct{ client *Client }

type dialAttempt struct{ stops []func() bool }
type lifetimeConn struct {
	net.Conn
	stop func() bool
}

func (c *lifetimeConn) Close() error { c.stop(); return c.Conn.Close() }

func (d sessionDialer) DialContext(ctx context.Context, network string, destination M.Socksaddr) (net.Conn, error) {
	return d.client.DialContext(ctx, N.NetworkTCP, destination)
}

func (d sessionDialer) ListenPacket(context.Context, M.Socksaddr) (net.PacketConn, error) {
	return nil, errors.New("AnyTLS session only supports streams")
}
