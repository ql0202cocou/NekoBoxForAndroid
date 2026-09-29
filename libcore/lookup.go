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
		// 总预算用完才停，按 ctx 本身判断而不是看错误：单台服务器 5 秒的拨号 /
		// 握手超时同样匹配 DeadlineExceeded（net 的 timeoutError、tls.Dialer 返回
		// 自己的 ctx.Err），据此放弃会漏掉后面的服务器。截止时间也要看：socket 的
		// deadline 可能比 ctx 自己的计时器早一点触发
		if ctxErr := ctx.Err(); ctxErr != nil {
			return "", ctxErr
		}
		if deadline, ok := ctx.Deadline(); ok && !time.Now().Before(deadline) {
			return "", context.DeadlineExceeded
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

// 单台服务器的超时：不通或不回应的服务器不能吃光 StartLookupHosts 的整个预算，
// 是否换下一台由 lookupHosts 按总 ctx 判断
const perServerTimeout = 5 * time.Second

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
		client := &mDNS.Client{Net: scheme, Timeout: perServerTimeout}
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
			Timeout:   perServerTimeout,
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
	// 出错时是否算预算耗尽由 lookupHosts 按总 ctx 判断，这里原样返回
	response, _, err := client.ExchangeWithConnContext(ctx, query, conn)
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

// dohClient 独立于 http.DefaultClient：Transport 为 nil 时仍会用 http.DefaultTransport，
// 它读取环境变量里的代理设置，所以复制一份相同配置并去掉代理。不设 client 超时：
// exchangeHTTPS 已用 ctx 限定每次交换
var dohClient = &http.Client{Transport: noProxyTransport()}

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
	// 读响应体也在这个 ctx 内
	ctx, cancel := context.WithTimeout(ctx, perServerTimeout)
	defer cancel()
	client := dohClient
	if h3 {
		server = "https://" + strings.TrimPrefix(server, "h3://")
		transport := &http3.Transport{}
		defer transport.Close()
		client = &http.Client{Transport: transport}
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
	// 握手后不回应的服务器也受单台超时约束
	serverCtx, cancel := context.WithTimeout(ctx, perServerTimeout)
	defer cancel()
	// quic.DialAddr 用不带 ctx 的 net.ResolveUDPAddr 解析主机名，取消和预算都管
	// 不住；先按 ctx 解析成 IP 再拨号，ServerName 仍用主机名
	ips, err := net.DefaultResolver.LookupNetIP(serverCtx, "ip", host)
	if err != nil {
		return nil, err
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
		return nil, err
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
		return nil, err
	}
	return response, nil
}
