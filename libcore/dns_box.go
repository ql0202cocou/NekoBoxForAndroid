// Platform "local" DNS transport: bridges sing-box DNS exchanges to the
// Android-side resolver through the ExchangeContext callbacks.

package libcore

import (
	"context"
	"libcore/device"
	"net/netip"
	"strings"
	"sync"
	"sync/atomic"
	"syscall"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/constant"
	"github.com/sagernet/sing-box/dns"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing/common"
	E "github.com/sagernet/sing/common/exceptions"
	M "github.com/sagernet/sing/common/metadata"

	mDNS "github.com/miekg/dns"
)

// rawQueryFunc 只在 dns_android.go 的 init() 里赋值（android && cgo 时）；
// init 的完成先于一切后续 goroutine，读取无需额外同步。
var rawQueryFunc func(ctx context.Context, networkHandle int64, request []byte) ([]byte, error)

type LocalDNSTransport interface {
	Raw() bool
	NetworkHandle() int64
	Lookup(ctx *ExchangeContext, network string, domain string) error
	Exchange(ctx *ExchangeContext, message []byte) error
}

// gLocalDNSTransport 由 InitCore 写入一次，之后被任意 goroutine（如
// http_h3.go 的 ECH 配置拉取）读取，故用 atomic.Pointer 发布。
var gLocalDNSTransport atomic.Pointer[platformLocalDNSTransport]

type platformLocalDNSTransport struct {
	dns.TransportAdapter
	iif LocalDNSTransport
	raw bool
}

func newPlatformTransport(iif LocalDNSTransport, tag string, options option.LocalDNSServerOptions) *platformLocalDNSTransport {
	return &platformLocalDNSTransport{
		TransportAdapter: dns.NewTransportAdapterWithLocalOptions(constant.DNSTypeLocal, tag, options),
		iif:              iif,
		raw:              iif.Raw(),
	}
}

func (p *platformLocalDNSTransport) Start(stage adapter.StartStage) error {
	return nil
}

func (p *platformLocalDNSTransport) Close() error {
	return nil
}

func (p *platformLocalDNSTransport) Reset() {
}

func (p *platformLocalDNSTransport) ExchangeAsync(ctx context.Context, message *mDNS.Msg, callback func(response *mDNS.Msg, err error)) {
	exchangeAsync(ctx, message, p.Exchange, callback)
}

// exchangeAsync adapts a blocking Exchange to the callback form of
// adapter.DNSTransport, shared by the transports in this package.
func exchangeAsync(ctx context.Context, message *mDNS.Msg, exchange func(context.Context, *mDNS.Msg) (*mDNS.Msg, error), callback func(*mDNS.Msg, error)) {
	go func() {
		callback(exchange(ctx, message))
	}()
}

func (p *platformLocalDNSTransport) Exchange(ctx context.Context, message *mDNS.Msg) (ret *mDNS.Msg, err error) {
	defer device.DeferPanicToError("platformLocalDNSTransport.Exchange", func(err_ error) { err = err_ })

	if p.raw {
		// Raw - Android 10 及以上才有

		messageBytes, err := message.Pack()
		if err != nil {
			return nil, err
		}
		if rawQueryFunc != nil {
			msg, err := rawQueryFunc(ctx, p.iif.NetworkHandle(), messageBytes)
			if err != nil {
				return nil, err
			}
			responseMessage := new(mDNS.Msg)
			err = responseMessage.Unpack(msg)
			if err != nil {
				return nil, err
			}
			return responseMessage, nil
		}
		// libandroid.so symbols unavailable: the platform side runs the same
		// raw query through android.net.DnsResolver
		response, err := awaitPlatform(ctx, func(c *ExchangeContext) error {
			return p.iif.Exchange(c, messageBytes)
		})
		if err != nil {
			return nil, err
		}
		return &response.message, nil
	}

	// Lookup - Android 10 以下

	if len(message.Question) == 0 {
		return nil, E.New("query has no question")
	}
	question := message.Question[0]
	var network string
	switch question.Qtype {
	case mDNS.TypeA:
		network = "ip4"
	case mDNS.TypeAAAA:
		network = "ip6"
	default:
		return nil, E.New("only IP queries are supported by current version of Android")
	}

	response, err := awaitPlatform(ctx, func(c *ExchangeContext) error {
		return p.iif.Lookup(c, network, question.Name)
	})
	if err != nil {
		return nil, err
	}
	return dns.FixedResponse(message.Id, question, response.addresses, constant.DefaultDNSTTL), nil
}

// awaitPlatform runs one platform resolver call and waits for its callback
// (Success/RawSuccess/ErrorCode/ErrnoCode settle the context exactly once) or
// the context, whichever comes first.
func awaitPlatform(ctx context.Context, call func(*ExchangeContext) error) (*ExchangeContext, error) {
	response := &ExchangeContext{
		context:  ctx,
		doneChan: make(chan struct{}),
	}
	if err := call(response); err != nil {
		return nil, err
	}
	select {
	case <-response.doneChan:
	case <-ctx.Done():
		return nil, ctx.Err()
	}
	if response.error != nil {
		return nil, response.error
	}
	return response, nil
}

type Func interface {
	Invoke() error
}

type ExchangeContext struct {
	context   context.Context
	message   mDNS.Msg
	addresses []netip.Addr
	error     error
	// settle makes the whole settle — field writes plus close(doneChan) —
	// run exactly once, so a second callback (e.g. the platform catch path
	// invoking ErrnoCode after RawSuccess) is a no-op instead of racing the
	// field reads awaitPlatform makes after doneChan is closed.
	settle   sync.Once
	doneChan chan struct{}
}

func (c *ExchangeContext) OnCancel(callback Func) {
	defer device.DeferPanicToError("ExchangeContext.OnCancel", nil)

	go func() {
		defer device.DeferPanicToError("ExchangeContext.OnCancel", nil)

		select {
		case <-c.context.Done():
			callback.Invoke()
		case <-c.doneChan:
			// already settled: nothing left to cancel
		}
	}()
}

// settleWith runs fn as the one settle of this exchange. doneChan is closed
// even if fn panics: an unsettled exchange would block awaitPlatform until the
// context deadline. Every callback below goes through it, so a new one cannot
// forget the close.
func (c *ExchangeContext) settleWith(name string, fn func()) {
	defer device.DeferPanicToError(name, nil)

	c.settle.Do(func() {
		defer close(c.doneChan)
		fn()
	})
}

func (c *ExchangeContext) Success(result string) {
	c.settleWith("ExchangeContext.Success", func() {
		lines := common.Filter(strings.Split(result, "\n"), func(it string) bool {
			return !common.IsEmpty(it)
		})
		addresses := common.Map(lines, func(it string) netip.Addr {
			return M.ParseSocksaddrHostPort(it, 0).Unwrap().Addr
		})
		// the platform side may echo malformed lines; drop everything that did
		// not parse to an IP address instead of failing the whole exchange
		c.addresses = common.Filter(addresses, func(it netip.Addr) bool {
			return it.IsValid()
		})
	})
}

func (c *ExchangeContext) RawSuccess(result []byte) {
	c.settleWith("ExchangeContext.RawSuccess", func() {
		err := c.message.Unpack(result)
		if err != nil {
			c.error = E.Cause(err, "parse response")
		}
	})
}

func (c *ExchangeContext) ErrorCode(code int32) {
	c.settleWith("ExchangeContext.ErrorCode", func() {
		c.error = dns.RcodeError(code)
	})
}

func (c *ExchangeContext) ErrnoCode(code int32) {
	c.settleWith("ExchangeContext.ErrnoCode", func() {
		c.error = syscall.Errno(code)
	})
}
