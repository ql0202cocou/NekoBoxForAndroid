package libcore

import (
	"context"
	"crypto/tls"
	"errors"
	"libcore/device"
	"net"
	"net/http"
	"net/url"
	"strconv"
	"time"

	"github.com/sagernet/sing/common/metadata"
	"github.com/sagernet/sing/protocol/socks"
	"github.com/sagernet/sing/protocol/socks/socks5"
)

var errFailConnectSocks5 = errors.New("fail connect socks5")

const (
	// httpDialTimeout bounds connection establishment.
	httpDialTimeout = 10 * time.Second
	// httpResponseHeaderTimeout bounds waiting for response headers once the
	// request is written; it catches servers that accept and never respond.
	httpResponseHeaderTimeout = 10 * time.Second
	// httpOverallTimeout bounds a whole request including the body read.
	// Bodies are capped at maxContentSize, so this leaves room for slow
	// servers while still bounding a stalled transfer (a JNI call blocked
	// forever would wedge the group update holding its cross-process lock).
	httpOverallTimeout = 3 * time.Minute
)

// HTTPClient configuration methods (RestrictedTLS, ModernTLS, TrySocks5,
// TryH3Direct, KeepAlive) mutate the shared client
// without synchronization, while NewRequest snapshots the TLS config with
// Clone and gomobile may invoke exported methods from any thread: call them
// single-threaded, before the first NewRequest.
type HTTPClient interface {
	RestrictedTLS()
	ModernTLS()
	TrySocks5(port int32)
	TryH3Direct()
	KeepAlive()
	NewRequest() HTTPRequest
	Close()
}

type HTTPRequest interface {
	SetURL(link string) error
	SetHeader(key string, value string)
	SetUserAgent(userAgent string)
	AllowInsecure()
	Execute() (HTTPResponse, error)
}

// HTTPResponse must be either consumed (GetContentString/WriteTo)
// or closed: the response body ties up the underlying connection and, for a
// response from an H3-direct race, the winning h3 transport's UDP socket and
// receive goroutine leak until process exit if the body is never closed.
type HTTPResponse interface {
	GetHeader(string) *StringBox
	GetContentString() (*StringBox, error)
	WriteTo(path string) error
	Close()
}

var (
	_ HTTPClient   = (*httpClient)(nil)
	_ HTTPRequest  = (*httpRequest)(nil)
	_ HTTPResponse = (*httpResponse)(nil)
)

type httpClient struct {
	tls           tls.Config
	h1h2Transport http.Transport
	h1h2Client    http.Client
	trySocks5     bool
	tryH3Direct   bool
}

func NewHttpClient() HTTPClient {
	defer device.DeferPanicToError("NewHttpClient", nil)

	// 自定义 CA 由 InitCore 的后台 goroutine 异步加载（见 assetsReady）：
	// 订阅更新等路径在进程启动后立刻就会发 TLS 请求，可能抢在 CA 加载完成前
	// 校验证书，这里同 NewSingBoxInstance 一样等待（主进程只等 CA 读取，
	// 毫秒级；:bg 还会等 extractAssets，仅 APK 升级后首次启动时较长）
	waitAssetsReady()

	client := new(httpClient)
	client.h1h2Client.Transport = &client.h1h2Transport
	client.h1h2Client.Timeout = httpOverallTimeout
	client.h1h2Transport.TLSClientConfig = &client.tls
	client.h1h2Transport.DisableKeepAlives = true
	client.h1h2Transport.DialContext = newHTTPDialer().DialContext
	client.h1h2Transport.ResponseHeaderTimeout = httpResponseHeaderTimeout
	return client
}

// newHTTPDialer 是 NewHttpClient 与 echTransport 共用的拨号器，两处的拨号
// 超时由此保持一致
func newHTTPDialer() *net.Dialer {
	return &net.Dialer{Timeout: httpDialTimeout, KeepAlive: 30 * time.Second}
}

// ModernTLS requires TLS 1.2 or later.
// Must be called before the first NewRequest; see HTTPClient.
func (c *httpClient) ModernTLS() {
	defer device.DeferPanicToError("http ModernTLS", nil)

	c.tls.MinVersion = tls.VersionTLS12
}

// RestrictedTLS requires TLS 1.3.
// Must be called before the first NewRequest; see HTTPClient.
func (c *httpClient) RestrictedTLS() {
	defer device.DeferPanicToError("http RestrictedTLS", nil)

	c.tls.MinVersion = tls.VersionTLS13
}

// TrySocks5 dials through the local socks5 proxy at port, falling back to a
// direct dial when the proxy is unreachable (unless TryH3Direct is set).
// Must be called before the first NewRequest; see HTTPClient.
func (c *httpClient) TrySocks5(port int32) {
	defer device.DeferPanicToError("http TrySocks5", nil)

	dialer := &net.Dialer{Timeout: httpDialTimeout}
	c.h1h2Transport.DialContext = func(ctx context.Context, network, addr string) (net.Conn, error) {
		socksConn, err := dialer.DialContext(ctx, "tcp", "127.0.0.1:"+strconv.Itoa(int(port)))
		if err == nil {
			// ClientHandshake5 是阻塞读写且不感知 ctx：加 deadline 兜底，
			// 否则对端不应答时握手会无限挂起，泄漏 goroutine 和 fd
			socksConn.SetDeadline(time.Now().Add(httpDialTimeout))
			_, err = socks.ClientHandshake5(socksConn, socks5.CommandConnect, metadata.ParseSocksaddr(addr), "", "")
		}
		if err != nil {
			if socksConn != nil {
				socksConn.Close()
			}
			if c.tryH3Direct {
				return nil, errFailConnectSocks5
			}
			// 直连回退用的是纯 net.Dialer，不经过 VPN protect：VPN 运行中且
			// 本地 socks 不可达时，这部分 "direct" 流量会被自身 tun 捕获、
			// 经代理出站。触发条件苛刻，行为与上游一致，故保持现状不改。
			// no H3 fallback: dial the target directly instead
			return dialer.DialContext(ctx, network, addr)
		}
		// 握手成功后清零 deadline，之后的读写超时由 HTTP 层接管
		socksConn.SetDeadline(time.Time{})
		return socksConn, nil
	}
	c.trySocks5 = true
}

// TryH3Direct races an ECH-capable TLS request against HTTP/3.
// Must be called before the first NewRequest; see HTTPClient.
func (c *httpClient) TryH3Direct() {
	defer device.DeferPanicToError("http TryH3Direct", nil)

	c.tryH3Direct = true
}

// KeepAlive enables connection reuse and HTTP/2 on the shared transport.
// Must be called before the first NewRequest; see HTTPClient.
func (c *httpClient) KeepAlive() {
	defer device.DeferPanicToError("http KeepAlive", nil)

	c.h1h2Transport.ForceAttemptHTTP2 = true
	c.h1h2Transport.DisableKeepAlives = false
}

func (c *httpClient) NewRequest() HTTPRequest {
	defer device.DeferPanicToError("http NewRequest", nil)

	req := &httpRequest{client: c, tls: c.tls.Clone()}
	req.request = http.Request{
		Method: "GET",
		Header: http.Header{},
	}
	return req
}

// perRequestTransport clones the shared transport for a request whose TLS config
// differs from the client's — Clone carries every option the client set (dialer,
// timeouts, ForceAttemptHTTP2), so this cannot drift out of sync with
// NewHttpClient/TrySocks5/KeepAlive.
func (r *httpRequest) perRequestTransport() *http.Transport {
	t := r.client.h1h2Transport.Clone()
	t.TLSClientConfig = r.tls
	t.DisableKeepAlives = true // an isolated pool must not outlive the request
	return t
}

func (c *httpClient) Close() {
	defer device.DeferPanicToError("http Close", nil)

	c.h1h2Transport.CloseIdleConnections()
}

type httpRequest struct {
	client *httpClient
	// tls is a per-request clone of the client's config, so a request
	// changing it (AllowInsecure) cannot disable verification for every later
	// request on the same client. The shared transport keeps using the client's
	// own config; see perRequestTransport.
	tls *tls.Config
	// set once the clone diverges from the client's config, so Execute knows
	// it cannot use the shared transport
	ownTLS  bool
	request http.Request
}

func (r *httpRequest) AllowInsecure() {
	defer device.DeferPanicToError("http AllowInsecure", nil)

	r.tls.InsecureSkipVerify = true
	r.ownTLS = true
}

func (r *httpRequest) SetURL(link string) (err error) {
	defer device.DeferPanicToError("http SetURL", func(err_ error) { err = err_ })

	r.request.URL, err = url.Parse(link)
	if err != nil {
		return
	}
	if r.request.URL.User != nil {
		user := r.request.URL.User.Username()
		password, _ := r.request.URL.User.Password()
		r.request.SetBasicAuth(user, password)
	}
	return
}

func (r *httpRequest) SetHeader(key string, value string) {
	defer device.DeferPanicToError("http SetHeader", nil)

	r.request.Header.Set(key, value)
}

func (r *httpRequest) SetUserAgent(userAgent string) {
	defer device.DeferPanicToError("http SetUserAgent", nil)

	r.request.Header.Set("User-Agent", userAgent)
}

func (r *httpRequest) Execute() (resp HTTPResponse, err error) {
	defer device.DeferPanicToError("http execute", func(err_ error) { err = err_ })
	// full direct
	if r.client.tryH3Direct && !r.client.trySocks5 {
		return r.doH3Direct()
	}
	client := &r.client.h1h2Client
	// ownTransport is set only when this request needs an isolated transport;
	// its idle pool is then tied to the response's Close below, since nobody
	// else ever closes it (DisableKeepAlives only stops reuse).
	var ownTransport *http.Transport
	if r.ownTLS {
		// this request's TLS config differs from the client's; give it an
		// isolated transport instead of rewriting the shared one
		ownTransport = r.perRequestTransport()
		client = &http.Client{
			Transport: ownTransport,
			Timeout:   client.Timeout,
		}
	}
	response, err := client.Do(&r.request)
	if err == nil && response.StatusCode != http.StatusOK {
		// errorString consumes and closes the body
		err = errors.New((&httpResponse{Response: response}).errorString())
	}
	if err != nil {
		if ownTransport != nil {
			ownTransport.CloseIdleConnections()
		}
		// trySocks5 && tryH3Direct
		if r.client.tryH3Direct && errors.Is(err, errFailConnectSocks5) {
			return r.doH3Direct()
		}
		return nil, err
	}
	httpResp := &httpResponse{Response: response}
	if ownTransport != nil {
		// Tie the cloned transport's lifetime to the response body, the same
		// way doH3Direct ties the winning h3 transport to it.
		response.Body = &bodyCloseHook{ReadCloser: response.Body, after: func() error {
			ownTransport.CloseIdleConnections()
			return nil
		}}
	}
	return httpResp, nil
}
