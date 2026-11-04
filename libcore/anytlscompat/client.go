package anytlscompat

import (
	"context"
	"errors"
	"net"
	"sync"

	anytls "github.com/sagernet/sing-anytls"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
	"github.com/sagernet/sing/common/uot"
)

type ClientOptions struct {
	Server         M.Socksaddr
	Dialer         N.Dialer
	Security       *Security
	Password       string
	ClientMetadata string
	DisableReuse   bool
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
		// 会话库把 DialContext 的上下文原样传进来，即下面 DialContext 建立的尝试上下文。
		DialOut: func(caller context.Context) (net.Conn, error) {
			attempt, _ := caller.Value(dialAttemptKey{}).(*dialAttempt)
			if attempt == nil {
				return nil, errors.New("AnyTLS dial outside a client attempt")
			}
			if err := ctx.Err(); err != nil {
				return nil, err
			}
			raw, err := options.Dialer.DialContext(caller, N.NetworkTCP, options.Server)
			if err != nil {
				return nil, err
			}
			// 会话库登记连接之前还会阻塞在认证写入（只设写超时、不看上下文），这个窗口靠尝试上下文
			// 取消时直接关闭原始连接来覆盖；登记之后由会话库在 Client.Close 时关闭。
			attempt.stops = append(attempt.stops, context.AfterFunc(caller, func() { raw.Close() }))
			return options.Security.Handshake(caller, raw)
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
		// 每次拨号尝试只有一个上下文：调用方取消或客户端关闭都会取消它，拨号、TLS 握手和认证写入
		// 都在它的覆盖之下。
		attemptCtx, cancel := context.WithCancel(ctx)
		stopClientClose := context.AfterFunc(c.ctx, cancel)
		attempt := &dialAttempt{}
		conn, err := c.sessions.DialContext(context.WithValue(attemptCtx, dialAttemptKey{}, attempt), destination)
		// 先撤销原始连接的关闭登记再取消尝试上下文，否则取消本身会关掉刚登记的会话连接。
		for _, stop := range attempt.stops {
			stop()
		}
		stopClientClose()
		cancel()
		// 取消与拨号完成竞争时，会话库仍可能返回连接；调用方已取消就不交出去。
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

type dialAttemptKey struct{}

// 一次尝试里 DialOut 登记的关闭函数；会话库目前每次最多拨一次，留切片以防它重试。
type dialAttempt struct{ stops []func() bool }

func (d sessionDialer) DialContext(ctx context.Context, network string, destination M.Socksaddr) (net.Conn, error) {
	return d.client.DialContext(ctx, N.NetworkTCP, destination)
}

func (d sessionDialer) ListenPacket(context.Context, M.Socksaddr) (net.PacketConn, error) {
	return nil, errors.New("AnyTLS session only supports streams")
}
