package libcore

import (
	"bytes"
	"context"
	"crypto/tls"
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"libcore/device"
	"net"
	"net/http"
	"strings"
	"time"

	mDNS "github.com/miekg/dns"
	"github.com/sagernet/quic-go"
	"github.com/sagernet/quic-go/http3"
)

// LookupTask runs LookupHosts off the calling thread so Kotlin can cancel the
// native context when its coroutine is cancelled; a plain gomobile call would
// block the caller for the whole ten-second budget regardless.
type LookupTask struct {
	cancel context.CancelFunc
	done   chan struct{}
	result string
	err    error
}

func StartLookupHosts(servers string, domain string) *LookupTask {
	defer device.DeferPanicToError("StartLookupHosts", nil)

	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	task := &LookupTask{cancel: cancel, done: make(chan struct{})}
	go func() {
		defer close(task.done)
		defer cancel()
		defer device.DeferPanicToError("StartLookupHosts", func(err error) { task.err = err })
		task.result, task.err = lookupHosts(ctx, servers, domain)
	}()
	return task
}

// Await blocks until the lookup finishes or Cancel is called. Not named Wait:
// gomobile would emit a Java method clashing with the final Object.wait().
func (t *LookupTask) Await() (ret string, err error) {
	defer device.DeferPanicToError("LookupTask.Await", func(err_ error) { err = err_ })

	<-t.done
	return t.result, t.err
}

// Cancel is safe from any thread, before or after completion.
func (t *LookupTask) Cancel() {
	defer device.DeferPanicToError("LookupTask.Cancel", nil)

	t.cancel()
}

func lookupHosts(ctx context.Context, servers string, domain string) (string, error) {
	for _, server := range strings.Split(servers, "\n") {
		server = strings.TrimSpace(server)
		if server == "" {
			continue
		}
		if err := ctx.Err(); err != nil {
			return "", err
		}
		addresses, err := lookupHost(ctx, server, domain)
		if err == nil {
			return addresses, nil
		}
		// The budget is gone: do not touch the next server. Checked on the
		// error as well as on ctx.Err(), because the socket deadline can fire a
		// moment before the context's own timer marks it done.
		if errors.Is(err, context.DeadlineExceeded) || errors.Is(err, context.Canceled) {
			return "", err
		}
	}
	if err := ctx.Err(); err != nil {
		return "", err
	}
	// Do not expose server URLs (which may contain credentials) in errors.
	return "", fmt.Errorf("no DNS server returned addresses")
}

func lookupHost(ctx context.Context, server string, domain string) (string, error) {
	// An empty answer covers NOERROR-empty, NXDOMAIN and REFUSED alike —
	// lookupHostType does not inspect Rcode, so those all return (nil, nil) and
	// still reach the AAAA leg. Only a transport-level failure short-circuits,
	// and retrying that over the same server would just burn the timeout twice.
	addresses, err := lookupHostType(ctx, server, domain, mDNS.TypeA)
	if err == nil && len(addresses) == 0 {
		addresses, err = lookupHostType(ctx, server, domain, mDNS.TypeAAAA)
	}
	if err != nil {
		return "", err
	}
	if len(addresses) == 0 {
		return "", fmt.Errorf("empty response for %s", domain)
	}
	return strings.Join(addresses, "\n"), nil
}

func lookupHostType(ctx context.Context, server string, domain string, queryType uint16) ([]string, error) {
	scheme := "udp"
	address := server
	if i := strings.Index(server, "://"); i >= 0 {
		scheme = server[:i]
		address = server[i+3:]
	}

	query := new(mDNS.Msg)
	query.SetQuestion(mDNS.Fqdn(domain), queryType)

	var response *mDNS.Msg
	var err error
	switch scheme {
	case "udp", "tcp":
		address = withDefaultPort(address, "53")
		client := &mDNS.Client{Net: scheme, Timeout: 5 * time.Second}
		response, err = exchangeCancellable(ctx, client, query, address)
		// A truncated UDP answer is incomplete even when it contains some A/AAAA
		// records. Retry the same question over TCP within the original deadline.
		if err == nil && scheme == "udp" && response.Truncated {
			client.Net = "tcp"
			response, err = exchangeCancellable(ctx, client, query, address)
		}
	case "tls":
		address = withDefaultPort(address, "853")
		host, _, _ := net.SplitHostPort(address)
		client := &mDNS.Client{
			Net:       "tcp-tls",
			Timeout:   5 * time.Second,
			TLSConfig: &tls.Config{ServerName: host},
		}
		response, err = exchangeCancellable(ctx, client, query, address)
	case "quic":
		response, err = exchangeQUIC(ctx, withDefaultPort(address, "853"), query)
	case "https", "h3":
		response, err = exchangeHTTPS(ctx, scheme == "h3", server, query)
	default:
		err = fmt.Errorf("unsupported DNS server: %s", server)
	}
	if err != nil {
		return nil, err
	}

	var addresses []string
	for _, answer := range response.Answer {
		switch record := answer.(type) {
		case *mDNS.A:
			if queryType == mDNS.TypeA {
				addresses = append(addresses, record.A.String())
			}
		case *mDNS.AAAA:
			if queryType == mDNS.TypeAAAA {
				addresses = append(addresses, record.AAAA.String())
			}
		}
	}
	return addresses, nil
}

// miekg/dns maps only the context deadline onto socket deadlines, so a context
// cancelled mid-exchange would still wait out the client timeout. Closing the
// connection when ctx is done makes a Kotlin-side cancel return immediately.
func exchangeCancellable(ctx context.Context, client *mDNS.Client, query *mDNS.Msg, address string) (*mDNS.Msg, error) {
	conn, err := client.DialContext(ctx, address)
	if err != nil {
		return nil, err
	}
	defer conn.Close()
	stop := context.AfterFunc(ctx, func() { conn.Close() })
	defer stop()
	response, _, err := client.ExchangeWithConnContext(ctx, query, conn)
	if err != nil {
		if ctxErr := ctx.Err(); ctxErr != nil {
			return nil, ctxErr
		}
		// miekg/dns derives the socket deadline from ctx.Deadline(); the read can
		// time out before the context's timer has run, so report the budget as
		// exhausted rather than as a generic i/o timeout that would be retried.
		if deadline, ok := ctx.Deadline(); ok && !time.Now().Before(deadline) {
			return nil, context.DeadlineExceeded
		}
	}
	return response, err
}

// withDefaultPort appends the default port unless address already has one.
// Bare IPv6 literals (e.g. 2606:4700::1111) get bracketed via JoinHostPort.
func withDefaultPort(address, port string) string {
	if _, _, err := net.SplitHostPort(address); err == nil {
		return address
	}
	return net.JoinHostPort(strings.Trim(address, "[]"), port)
}

// dohClient is private so DoH does not ride http.DefaultClient. A nil
// Transport would still be http.DefaultTransport, which picks up proxy
// settings from the environment, so it gets the same configuration minus the
// proxy. The timeout is a backstop; callers already bound every exchange with
// a context.
var dohClient = &http.Client{Timeout: 10 * time.Second, Transport: noProxyTransport()}

func noProxyTransport() *http.Transport {
	transport := http.DefaultTransport.(*http.Transport).Clone()
	transport.Proxy = nil
	return transport
}

// h3 为 true 时走 DNS over HTTP/3：地址写作 h3://host/path，按 https 发请求，
// 每次查询用一个临时的 http3.Transport，查完关闭以释放 UDP socket
func exchangeHTTPS(ctx context.Context, h3 bool, server string, query *mDNS.Msg) (*mDNS.Msg, error) {
	body, err := query.Pack()
	if err != nil {
		return nil, err
	}
	client := dohClient
	if h3 {
		server = "https://" + strings.TrimPrefix(server, "h3://")
		transport := &http3.Transport{}
		defer transport.Close()
		client = &http.Client{Timeout: dohClient.Timeout, Transport: transport}
	}
	req, err := http.NewRequestWithContext(ctx, "POST", server, bytes.NewReader(body))
	if err != nil {
		return nil, err
	}
	// 与 sing-box 一致：地址没写路径时发到 /dns-query。Kotlin 的 makeDnsServer
	// 此时不设 path、由 sing-box 补默认值，这里不补就会发到 /，VPN 内能解析的
	// 地址在订阅解析和 ping 里却静默回退
	if req.URL.Path == "" {
		req.URL.Path = "/dns-query"
	}
	req.Header.Set("Content-Type", "application/dns-message")
	req.Header.Set("Accept", "application/dns-message")
	resp, err := client.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("DNS over HTTPS returned HTTP %d", resp.StatusCode)
	}
	data, err := io.ReadAll(io.LimitReader(resp.Body, 64*1024))
	if err != nil {
		return nil, err
	}
	response := new(mDNS.Msg)
	return response, response.Unpack(data)
}

// exchangeQUIC 按 RFC 9250（DNS over QUIC）发一次查询：每个查询独占一条双向流，
// 报文前加 2 字节长度，Message ID 必须为 0。ctx 结束时直接关连接，让阻塞中的
// 读写立刻返回（同 exchangeCancellable）
func exchangeQUIC(ctx context.Context, address string, query *mDNS.Msg) (*mDNS.Msg, error) {
	host, port, err := net.SplitHostPort(address)
	if err != nil {
		return nil, err
	}
	// 单台服务器最多 5 秒（同 udp/tcp/tls 的 Client.Timeout），握手后不回应的服务器
	// 不能吃光整个预算。它到期要报成普通错误：lookupHosts 见到 DeadlineExceeded
	// 会当作总预算耗尽，不再试下一台
	serverCtx, cancel := context.WithTimeout(ctx, 5*time.Second)
	defer cancel()
	fail := func(err error) (*mDNS.Msg, error) {
		if ctxErr := ctx.Err(); ctxErr != nil {
			return nil, ctxErr
		}
		if serverCtx.Err() != nil {
			return nil, errors.New("DNS over QUIC timed out")
		}
		return nil, err
	}
	// quic.DialAddr 用不带 ctx 的 net.ResolveUDPAddr 解析主机名，取消和预算都管
	// 不住；先按 ctx 解析成 IP 再拨号，ServerName 仍用主机名
	ips, err := net.DefaultResolver.LookupNetIP(serverCtx, "ip", host)
	if err != nil {
		return fail(err)
	}
	if len(ips) == 0 {
		return nil, errors.New("no address for DNS over QUIC server")
	}
	// 与原先的 net.ResolveUDPAddr 一致，有 IPv4 先用 IPv4：这里只拨一个地址、没有
	// Happy Eyeballs，有 v6 路由但 v6 不通的网络上 IPv6 在前会白等到超时
	ip := ips[0]
	for _, a := range ips {
		if a.Unmap().Is4() {
			ip = a
			break
		}
	}
	remote := net.JoinHostPort(ip.Unmap().String(), port)
	conn, err := quic.DialAddr(serverCtx, remote, &tls.Config{ServerName: host, NextProtos: []string{"doq"}}, nil)
	if err != nil {
		return fail(err)
	}
	defer conn.CloseWithError(0, "")
	stop := context.AfterFunc(serverCtx, func() { conn.CloseWithError(0, "") })
	defer stop()

	response, err := func() (*mDNS.Msg, error) {
		stream, err := conn.OpenStreamSync(serverCtx)
		if err != nil {
			return nil, err
		}
		query = query.Copy()
		query.Id = 0
		body, err := query.Pack()
		if err != nil {
			return nil, err
		}
		packet := binary.BigEndian.AppendUint16(make([]byte, 0, 2+len(body)), uint16(len(body)))
		if _, err = stream.Write(append(packet, body...)); err != nil {
			return nil, err
		}
		// 只关发送方向，告诉服务端查询已发完
		if err = stream.Close(); err != nil {
			return nil, err
		}
		var length uint16
		if err = binary.Read(stream, binary.BigEndian, &length); err != nil {
			return nil, err
		}
		data := make([]byte, length)
		if _, err = io.ReadFull(stream, data); err != nil {
			return nil, err
		}
		response := new(mDNS.Msg)
		return response, response.Unpack(data)
	}()
	if err != nil {
		return fail(err)
	}
	return response, nil
}
