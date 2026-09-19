package libcore

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"io"
	"libcore/device"
	"libcore/ech"
	"net"
	"net/http"
	"sync"
	"sync/atomic"
	"time"

	"github.com/sagernet/quic-go"
	"github.com/sagernet/quic-go/http3"
)

type requestFunc func(ctx context.Context) (response *http.Response, err error)

// racer pairs a racing request with the name reported in error messages, so
// the label follows the entry itself instead of its position in funcs (the
// plain-http cut below would otherwise mislabel or lose names).
type racer struct {
	name string
	fn   requestFunc
}

// raceResult is the winning response together with its index in funcs, so
// the losing requests' contexts can be cancelled once a winner is chosen.
type raceResult struct {
	index    int
	response *http.Response
}

// echTransport builds the ECH-capable TLS transport for the http(s) racer.
// Its bounds mirror NewHttpClient's (dial timeout, response header timeout):
// a server that accepts but never answers must not occupy the whole race
// window.
func (r *httpRequest) echTransport() *http.Transport {
	return &http.Transport{
		// Plain http URLs never reach DialTLSContext; bound their dial like
		// NewHttpClient does instead of falling back to the transport default.
		DialContext: (&net.Dialer{
			Timeout:   httpDialTimeout,
			KeepAlive: 30 * time.Second,
		}).DialContext,
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
			echTls := ech.NewECHClientConfig(domain, r.tls, gLocalDNSTransport.Load())
			return echTls.Client(ctx, c)
		},
		ResponseHeaderTimeout: httpResponseHeaderTimeout,
		DisableKeepAlives:     true,
	}
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
		// Bound the buffered body like getContent bounds responses: the race
		// holds the entire body in memory and hands every racer a reader over
		// it, so an unbounded body could exhaust memory.
		bodyBytes, err = io.ReadAll(io.LimitReader(r.request.Body, maxContentSize+1))
		r.request.Body.Close()
		if err != nil {
			return nil, err
		}
		if len(bodyBytes) > maxContentSize {
			return nil, fmt.Errorf("request body too large, limit is %d bytes", maxContentSize)
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

	funcs := []racer{
		// Http(s) With Ech
		{name: "http(s)", fn: func(ctx context.Context) (response *http.Response, err error) {
			request := cloneRequest(ctx)
			echClient := &http.Client{
				Transport: r.echTransport(),
			}
			return echClient.Do(request)
		}},
		// H3 HTTPS
		{name: "h3", fn: func(ctx context.Context) (response *http.Response, err error) {
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
		}},
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
		go func(f racer, reqCtx context.Context) {
			defer device.DeferPanicToError("http", nil)
			defer func() {
				if successCount.Load() == 0 {
					if failedCount.Add(1) >= uint32(len(funcs)) {
						// 全部失败了，唤醒下方等待的 select
						waitCancel()
					}
				}
			}()

			t := f.name

			// 执行HTTP请求
			rsp, err := f.fn(reqCtx)
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
