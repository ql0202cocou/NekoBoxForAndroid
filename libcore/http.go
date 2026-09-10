package libcore

import (
	"bytes"
	"context"
	"crypto/sha256"
	"crypto/tls"
	"crypto/x509"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"libcore/device"
	"libcore/ech"
	"log"
	"net"
	"net/http"
	"net/url"
	"os"
	"strconv"
	"sync"
	"sync/atomic"
	"time"

	"github.com/sagernet/quic-go"
	"github.com/sagernet/quic-go/http3"
	"github.com/sagernet/sing/common/metadata"
	"github.com/sagernet/sing/protocol/socks"
	"github.com/sagernet/sing/protocol/socks/socks5"
)

var errFailConnectSocks5 = errors.New("fail connect socks5")

// maxContentSize caps response bodies read by GetContent (32 MB).
const maxContentSize = 32 * 1024 * 1024

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

type HTTPClient interface {
	RestrictedTLS()
	ModernTLS()
	PinnedTLS12()
	PinnedSHA256(sumHex string)
	TrySocks5(port int32)
	TryH3Direct()
	KeepAlive()
	NewRequest() HTTPRequest
	Close()
}

type HTTPRequest interface {
	SetURL(link string) error
	SetMethod(method string)
	SetHeader(key string, value string)
	SetContent(content []byte)
	SetContentString(content string)
	SetUserAgent(userAgent string)
	AllowInsecure()
	Execute() (HTTPResponse, error)
}

type HTTPResponse interface {
	GetHeader(string) *StringBox
	GetContent() ([]byte, error)
	GetContentString() (*StringBox, error)
	WriteTo(path string) error
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

func (c *httpClient) ModernTLS() {
	c.tls.MinVersion = tls.VersionTLS12
}

func (c *httpClient) RestrictedTLS() {
	c.tls.MinVersion = tls.VersionTLS13
}

func (c *httpClient) PinnedTLS12() {
	c.tls.MinVersion = tls.VersionTLS12
	c.tls.MaxVersion = tls.VersionTLS12
}

func (c *httpClient) PinnedSHA256(sumHex string) {
	c.tls.VerifyPeerCertificate = func(rawCerts [][]byte, verifiedChains [][]*x509.Certificate) error {
		for _, rawCert := range rawCerts {
			certSum := sha256.Sum256(rawCert)
			if sumHex == hex.EncodeToString(certSum[:]) {
				return nil
			}
		}
		return errors.New("pinned sha256 sum mismatch")
	}
}

func (c *httpClient) TrySocks5(port int32) {
	dialer := &net.Dialer{Timeout: httpDialTimeout}
	c.h1h2Transport.DialContext = func(ctx context.Context, network, addr string) (net.Conn, error) {
		for {
			socksConn, err := dialer.DialContext(ctx, "tcp", "127.0.0.1:"+strconv.Itoa(int(port)))
			if err != nil {
				if c.tryH3Direct {
					return nil, errFailConnectSocks5
				}
				break
			}
			_, err = socks.ClientHandshake5(socksConn, socks5.CommandConnect, metadata.ParseSocksaddr(addr), "", "")
			if err != nil {
				socksConn.Close()
				if c.tryH3Direct {
					return nil, errFailConnectSocks5
				}
				break
			}
			return socksConn, err
		}
		return dialer.DialContext(ctx, network, addr)
	}
	c.trySocks5 = true
}

func (c *httpClient) TryH3Direct() {
	c.tryH3Direct = true
}

func (c *httpClient) KeepAlive() {
	c.h1h2Transport.ForceAttemptHTTP2 = true
	c.h1h2Transport.DisableKeepAlives = false
}

func (c *httpClient) NewRequest() HTTPRequest {
	req := &httpRequest{httpClient: c, tls: c.tls.Clone()}
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
	t := r.h1h2Transport.Clone()
	t.TLSClientConfig = r.tls
	t.DisableKeepAlives = true // an isolated pool must not outlive the request
	return t
}

func (c *httpClient) Close() {
	c.h1h2Transport.CloseIdleConnections()
}

type httpRequest struct {
	*httpClient
	// tls shadows the client's config with a per-request clone, so a request
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
	r.tls.InsecureSkipVerify = true
	r.ownTLS = true
}

func (r *httpRequest) SetURL(link string) (err error) {
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

func (r *httpRequest) SetMethod(method string) {
	r.request.Method = method
}

func (r *httpRequest) SetHeader(key string, value string) {
	r.request.Header.Set(key, value)
}

func (r *httpRequest) SetUserAgent(userAgent string) {
	r.request.Header.Set("User-Agent", userAgent)
}

func (r *httpRequest) SetContent(content []byte) {
	buffer := bytes.Buffer{}
	buffer.Write(content)
	r.request.Body = io.NopCloser(bytes.NewReader(buffer.Bytes()))
	r.request.ContentLength = int64(len(content))
}

func (r *httpRequest) SetContentString(content string) {
	r.SetContent([]byte(content))
}

func (r *httpRequest) Execute() (resp HTTPResponse, err error) {
	defer device.DeferPanicToError("http execute", func(err_ error) { err = err_ })
	// full direct
	if r.tryH3Direct && !r.trySocks5 {
		return r.doH3Direct()
	}
	client := &r.h1h2Client
	if r.ownTLS {
		// this request's TLS config differs from the client's; give it an
		// isolated transport instead of rewriting the shared one
		client = &http.Client{
			Transport: r.perRequestTransport(),
			Timeout:   r.h1h2Client.Timeout,
		}
	}
	response, err := client.Do(&r.request)
	if err != nil {
		// trySocks5 && tryH3Direct
		if r.tryH3Direct && errors.Is(err, errFailConnectSocks5) {
			return r.doH3Direct()
		}
		return nil, err
	}
	httpResp := &httpResponse{Response: response}
	if response.StatusCode != http.StatusOK {
		return nil, errors.New(httpResp.errorString())
	}
	return httpResp, nil
}

type requestFunc func(ctx context.Context) (response *http.Response, err error)

// raceResult is the winning response together with its index in funcs, so
// the losing requests' contexts can be cancelled once a winner is chosen.
type raceResult struct {
	index    int
	response *http.Response
}

func (r *httpRequest) doH3Direct() (HTTPResponse, error) {
	// waitCtx bounds only the wait for a winner below. Each request
	// derives from its own context instead: a request's context also
	// governs reading its response body, so cancelling a context shared
	// with the winner on return would kill the winning body while the
	// caller is still reading it.
	waitCtx, waitCancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer waitCancel()

	// Unbuffered: the winner's send is a rendezvous with the wait below, so
	// a success arriving after the wait returned cannot park a response in
	// the channel buffer with nobody left to close its body.
	successCh := make(chan raceResult)
	var finalErr error
	var failedCount atomic.Uint32
	var successCount atomic.Uint32
	var mu sync.Mutex

	// Clone below is a shallow copy, so the racing requests would share one
	// body reader, and a failed socks5 attempt on the fallback path may
	// already have consumed it. Buffer the body once and hand each request
	// its own reader.
	var bodyBytes []byte
	if r.request.Body != nil {
		var err error
		bodyBytes, err = io.ReadAll(r.request.Body)
		r.request.Body.Close()
		if err != nil {
			return nil, err
		}
		r.request.Body = io.NopCloser(bytes.NewReader(bodyBytes))
	}
	// Every racing request gets its own reader over the buffered body.
	cloneRequest := func(ctx context.Context) *http.Request {
		request := r.request.Clone(ctx)
		if bodyBytes != nil {
			newBody := func() io.ReadCloser {
				return io.NopCloser(bytes.NewReader(bodyBytes))
			}
			request.Body = newBody()
			request.GetBody = func() (io.ReadCloser, error) { return newBody(), nil }
		}
		return request
	}

	funcs := []requestFunc{
		// Http(s) With Ech
		func(ctx context.Context) (response *http.Response, err error) {
			request := cloneRequest(ctx)
			echClient := &http.Client{
				Transport: &http.Transport{
					DialTLSContext: func(ctx context.Context, network, addr string) (net.Conn, error) {
						var d net.Dialer
						c, err := d.DialContext(ctx, network, addr)
						if err != nil {
							return c, err
						}
						domain := addr
						if host, _, _ := net.SplitHostPort(addr); host != "" {
							domain = host
						}
						echTls := ech.NewECHClientConfig(domain, r.tls, gLocalDNSTransport)
						return echTls.Client(ctx, c)
					},
					DisableKeepAlives: true,
				},
			}
			return echClient.Do(request)
		},
		// H3 HTTPS
		func(ctx context.Context) (response *http.Response, err error) {
			request := cloneRequest(ctx)
			h3Transport := &http3.Transport{
				TLSClientConfig: r.tls.Clone(),
				QUICConfig: &quic.Config{
					MaxIdleTimeout: time.Second,
				},
			}
			h3Client := &http.Client{
				Transport: h3Transport,
			}
			response, err = h3Client.Do(request)
			if err != nil {
				h3Transport.Close()
				return nil, err
			}
			// A http3.Transport only releases its UDP socket and receive
			// goroutine on Close; the response body is drained and closed by
			// the caller, so tie the transport's lifetime to it.
			response.Body = &bodyCloseHook{ReadCloser: response.Body, after: h3Transport.Close}
			return response, nil
		},
	}

	if r.request.URL.Scheme == "http" {
		funcs = funcs[:1]
	}

	reqCancels := make([]context.CancelFunc, len(funcs))
	for i, f := range funcs {
		// The timeout bounds the whole request, including reading the
		// winning body after doH3Direct returns; cancellation still happens
		// for losers and on body Close via reqCancels.
		reqCtx, reqCancel := context.WithTimeout(context.Background(), httpOverallTimeout)
		reqCancels[i] = reqCancel
		go func(f requestFunc, reqCtx context.Context) {
			defer device.DeferPanicToError("http", func(err error) { log.Println(err) })
			defer func() {
				if successCount.Load() == 0 {
					if failedCount.Add(1) >= uint32(len(funcs)) {
						// 全部失败了，唤醒下方等待的 select
						waitCancel()
					}
				}
			}()

			var t string
			switch i {
			case 0:
				t = "http(s)"
			case 1:
				t = "h3"
			}

			// 执行HTTP请求
			rsp, err := f(reqCtx)
			if rsp == nil || err != nil {
				mu.Lock()
				finalErr = errors.Join(finalErr, fmt.Errorf("%s: %w", t, err))
				mu.Unlock()
				if rsp != nil && rsp.Body != nil {
					rsp.Body.Close()
				}
				return
			}

			// 处理 HTTP 状态码
			if rsp.StatusCode != http.StatusOK {
				hr := &httpResponse{Response: rsp}
				err = fmt.Errorf("%s: %s", t, hr.errorString())
				mu.Lock()
				finalErr = errors.Join(finalErr, err)
				mu.Unlock()
				return
			}

			// The first success wins; every later one has no receiver left
			// (the winner was already taken, or the wait timed out), so
			// close its body in place instead of racing a send against
			// waitCtx.Done(), where Go picks randomly between the two ready
			// cases. The deferred check above must not observe a window
			// where the winner was already sent but not yet counted.
			if successCount.Add(1) != 1 {
				// 非第一个成功者，无人接收，直接关闭 body
				rsp.Body.Close()
				return
			}
			select {
			case successCh <- raceResult{i, rsp}:
				// Body ownership passes to the receiver.
			case <-waitCtx.Done():
				// The wait already returned (timeout or all requests
				// failed), so nobody will ever receive; close the body in
				// place, otherwise the h3 transport tied to it leaks.
				rsp.Body.Close()
			}
		}(f, reqCtx)
	}

	succeed := func(result raceResult) *httpResponse {
		// Abort any loser still in flight. The winner's own context is
		// cancelled only when the caller closes the body, since cancelling
		// it earlier would abort body reads.
		for j, reqCancel := range reqCancels {
			if j != result.index {
				reqCancel()
			}
		}
		result.response.Body = &bodyCloseHook{ReadCloser: result.response.Body, after: func() error {
			reqCancels[result.index]()
			return nil
		}}
		return &httpResponse{Response: result.response}
	}

	select {
	case result := <-successCh:
		return succeed(result), nil
	case <-waitCtx.Done():
		// The deadline may win the select against a response already sent
		// to the channel; prefer the response over a spurious timeout,
		// otherwise its body would never be closed.
		select {
		case result := <-successCh:
			return succeed(result), nil
		default:
		}
		for _, reqCancel := range reqCancels {
			reqCancel()
		}
		mu.Lock()
		err := finalErr
		mu.Unlock()
		if err == nil {
			// timed out before any request finished; never return (nil, nil)
			err = waitCtx.Err()
		}
		return nil, err
	}
}

type httpResponse struct {
	*http.Response

	getContentOnce sync.Once
	content        []byte
	contentError   error
}

// bodyCloseHook runs after once the wrapped body is closed.
type bodyCloseHook struct {
	io.ReadCloser
	after func() error
	once  sync.Once
}

func (b *bodyCloseHook) Close() error {
	err := b.ReadCloser.Close()
	b.once.Do(func() {
		b.after()
	})
	return err
}

func (h *httpResponse) errorString() string {
	content, err := h.getContentString()
	if err != nil {
		return fmt.Sprint("HTTP ", h.Status)
	}
	if len(content) > 100 {
		content = content[:100] + " ..."
	}
	return fmt.Sprint("HTTP ", h.Status, ": ", content)
}

func (h *httpResponse) GetHeader(key string) *StringBox {
	return wrapString(h.Header.Get(key))
}

func (h *httpResponse) GetContent() ([]byte, error) {
	h.getContentOnce.Do(func() {
		defer h.Body.Close()
		h.content, h.contentError = io.ReadAll(io.LimitReader(h.Body, maxContentSize+1))
		if h.contentError == nil && len(h.content) > maxContentSize {
			h.content = nil
			h.contentError = fmt.Errorf("content too large, limit is %d bytes", maxContentSize)
		}
	})
	return h.content, h.contentError
}

func (h *httpResponse) GetContentString() (*StringBox, error) {
	content, err := h.getContentString()
	if err != nil {
		return nil, err
	}
	return wrapString(content), nil
}

func (h *httpResponse) getContentString() (string, error) {
	content, err := h.GetContent()
	if err != nil {
		return "", err
	}
	return string(content), nil
}

func (h *httpResponse) WriteTo(path string) error {
	defer h.Body.Close()
	file, err := os.Create(path)
	if err != nil {
		return err
	}
	return copyAndClose(file, h.Body)
}
