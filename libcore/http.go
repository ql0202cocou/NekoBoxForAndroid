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

	client := new(httpClient)
	client.h1h2Client.Transport = &client.h1h2Transport
	client.h1h2Client.Timeout = httpOverallTimeout
	client.h1h2Transport.TLSClientConfig = &client.tls
	client.h1h2Transport.DisableKeepAlives = true
	client.h1h2Transport.DialContext = (&net.Dialer{
		Timeout:   httpDialTimeout,
		KeepAlive: 30 * time.Second,
	}).DialContext
	client.h1h2Transport.ResponseHeaderTimeout = httpResponseHeaderTimeout
	return client
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
			_, err = socks.ClientHandshake5(socksConn, socks5.CommandConnect, metadata.ParseSocksaddr(addr), "", "")
		}
		if err != nil {
			if socksConn != nil {
				socksConn.Close()
			}
			if c.tryH3Direct {
				return nil, errFailConnectSocks5
			}
			// no H3 fallback: dial the target directly instead
			return dialer.DialContext(ctx, network, addr)
		}
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
