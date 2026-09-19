package libcore

import (
	"context"
	"errors"
	"net/netip"
	"runtime"
	"slices"
	"strings"
	"sync"
	"sync/atomic"
	"syscall"
	"testing"
	"time"

	"github.com/sagernet/sing-box/dns"

	mDNS "github.com/miekg/dns"
)

// newTestExchangeContext mirrors the wiring awaitPlatform installs: settle
// guards the field writes and the single close of doneChan.
func newTestExchangeContext(ctx context.Context) *ExchangeContext {
	return &ExchangeContext{
		context:  ctx,
		doneChan: make(chan struct{}),
	}
}

func requireSettled(t *testing.T, c *ExchangeContext) {
	t.Helper()
	select {
	case <-c.doneChan:
	default:
		t.Fatal("exchange context not settled")
	}
}

func TestExchangeContextSuccessFiltersInvalidAddresses(t *testing.T) {
	c := newTestExchangeContext(context.Background())
	c.Success("1.1.1.1\nnot-an-ip\n\n2606:4700:4700::1111\n300.300.300.300")
	requireSettled(t, c)
	want := []netip.Addr{netip.MustParseAddr("1.1.1.1"), netip.MustParseAddr("2606:4700:4700::1111")}
	if !slices.Equal(c.addresses, want) {
		t.Fatalf("addresses = %v, want %v", c.addresses, want)
	}
	if c.error != nil {
		t.Fatalf("error = %v, want nil", c.error)
	}
}

func TestExchangeContextCallbacksSettleOnce(t *testing.T) {
	rawResponse := new(mDNS.Msg)
	rawResponse.SetQuestion("example.com.", mDNS.TypeA)
	packed, err := rawResponse.Pack()
	if err != nil {
		t.Fatal(err)
	}

	cases := []struct {
		name     string
		callback func(c *ExchangeContext)
		check    func(t *testing.T, c *ExchangeContext)
	}{
		{"Success",
			func(c *ExchangeContext) { c.Success("1.1.1.1") },
			func(t *testing.T, c *ExchangeContext) {
				want := []netip.Addr{netip.MustParseAddr("1.1.1.1")}
				if !slices.Equal(c.addresses, want) {
					t.Fatalf("addresses = %v, want %v", c.addresses, want)
				}
			},
		},
		{"RawSuccess",
			func(c *ExchangeContext) { c.RawSuccess(packed) },
			func(t *testing.T, c *ExchangeContext) {
				if len(c.message.Question) != 1 || c.message.Question[0].Name != "example.com." {
					t.Fatalf("message question = %v, want example.com.", c.message.Question)
				}
			},
		},
		{"ErrorCode",
			func(c *ExchangeContext) { c.ErrorCode(3) },
			func(t *testing.T, c *ExchangeContext) {
				var rcodeError dns.RcodeError
				if !errors.As(c.error, &rcodeError) || rcodeError != 3 {
					t.Fatalf("error = %v, want RcodeError(3)", c.error)
				}
			},
		},
		{"ErrnoCode",
			func(c *ExchangeContext) { c.ErrnoCode(int32(syscall.ENOENT)) },
			func(t *testing.T, c *ExchangeContext) {
				if c.error != syscall.Errno(syscall.ENOENT) {
					t.Fatalf("error = %v, want ENOENT", c.error)
				}
			},
		},
	}
	for _, testCase := range cases {
		t.Run(testCase.name, func(t *testing.T) {
			c := newTestExchangeContext(context.Background())
			testCase.callback(c)
			requireSettled(t, c)
			// a repeated callback must be safe: settling happens once
			testCase.callback(c)
			requireSettled(t, c)
			testCase.check(t, c)
		})
	}
}

// The platform catch path may invoke a second callback after the exchange
// already settled (ErrnoCode after RawSuccess); it must be a no-op and never
// overwrite the settled fields.
func TestExchangeContextSecondCallbackIsNoop(t *testing.T) {
	rawResponse := new(mDNS.Msg)
	rawResponse.SetQuestion("example.com.", mDNS.TypeA)
	packed, err := rawResponse.Pack()
	if err != nil {
		t.Fatal(err)
	}

	c := newTestExchangeContext(context.Background())
	c.RawSuccess(packed)
	c.ErrnoCode(int32(syscall.ENOENT))
	requireSettled(t, c)
	if c.error != nil {
		t.Fatalf("error = %v, want nil: RawSuccess settled first", c.error)
	}
	if len(c.message.Question) != 1 || c.message.Question[0].Name != "example.com." {
		t.Fatalf("message question = %v, want example.com.", c.message.Question)
	}
}

// Concurrent duplicate callbacks (platform threads may race a second settle)
// must write the fields and close doneChan exactly once; run under -race.
func TestExchangeContextConcurrentDoubleSettle(t *testing.T) {
	rawResponse := new(mDNS.Msg)
	rawResponse.SetQuestion("example.com.", mDNS.TypeA)
	packed, err := rawResponse.Pack()
	if err != nil {
		t.Fatal(err)
	}

	for i := 0; i < 20; i++ {
		c := newTestExchangeContext(context.Background())
		var wg sync.WaitGroup
		for j := 0; j < 8; j++ {
			wg.Add(1)
			go func() {
				defer wg.Done()
				c.RawSuccess(packed)
				c.ErrnoCode(int32(syscall.ENOENT))
			}()
		}
		wg.Wait()
		requireSettled(t, c)
		// whichever callback won the settle, the fields must be consistent
		if c.error != nil {
			if c.error != syscall.Errno(syscall.ENOENT) {
				t.Fatalf("error = %v, want ENOENT", c.error)
			}
			if len(c.message.Question) != 0 {
				t.Fatalf("message question = %v, want empty after ErrnoCode settle", c.message.Question)
			}
		} else if len(c.message.Question) != 1 || c.message.Question[0].Name != "example.com." {
			t.Fatalf("message question = %v, want example.com.", c.message.Question)
		}
	}
}

func TestExchangeContextRawSuccessInvalidPayload(t *testing.T) {
	c := newTestExchangeContext(context.Background())
	c.RawSuccess([]byte("not a dns message"))
	requireSettled(t, c)
	if c.error == nil || !strings.Contains(c.error.Error(), "parse response") {
		t.Fatalf("error = %v, want parse response error", c.error)
	}
}

func TestAwaitPlatformSettledByCallback(t *testing.T) {
	response, err := awaitPlatform(context.Background(), func(c *ExchangeContext) error {
		c.Success("1.1.1.1\n2606:4700:4700::1111")
		return nil
	})
	if err != nil {
		t.Fatal(err)
	}
	want := []netip.Addr{netip.MustParseAddr("1.1.1.1"), netip.MustParseAddr("2606:4700:4700::1111")}
	if !slices.Equal(response.addresses, want) {
		t.Fatalf("addresses = %v, want %v", response.addresses, want)
	}
}

// A malformed Success payload must settle the exchange instead of leaving
// awaitPlatform blocked until the context deadline.
func TestAwaitPlatformSettledByMalformedSuccess(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	response, err := awaitPlatform(ctx, func(c *ExchangeContext) error {
		c.Success("not-an-ip")
		return nil
	})
	if err != nil {
		t.Fatal(err)
	}
	if len(response.addresses) != 0 {
		t.Fatalf("addresses = %v, want empty", response.addresses)
	}
}

func TestAwaitPlatformContextCancel(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	_, err := awaitPlatform(ctx, func(c *ExchangeContext) error { return nil })
	if !errors.Is(err, context.Canceled) {
		t.Fatalf("err = %v, want context.Canceled", err)
	}
}

func TestAwaitPlatformCallError(t *testing.T) {
	callErr := errors.New("call failed")
	_, err := awaitPlatform(context.Background(), func(c *ExchangeContext) error { return callErr })
	if !errors.Is(err, callErr) {
		t.Fatalf("err = %v, want %v", err, callErr)
	}
}

type invokeRecorder struct {
	count atomic.Int32
}

func (r *invokeRecorder) Invoke() error {
	r.count.Add(1)
	return nil
}

func TestExchangeContextOnCancelInvokesOnContextCancel(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	c := newTestExchangeContext(ctx)
	recorder := new(invokeRecorder)
	c.OnCancel(recorder)
	cancel()

	deadline := time.Now().Add(5 * time.Second)
	for recorder.count.Load() == 0 && time.Now().Before(deadline) {
		time.Sleep(10 * time.Millisecond)
	}
	if got := recorder.count.Load(); got != 1 {
		t.Fatalf("OnCancel callback invoked %d times, want 1", got)
	}
}

// With a long-lived context the watcher goroutine must exit once the exchange
// settles instead of hanging until the context dies.
func TestExchangeContextOnCancelExitsAfterSettle(t *testing.T) {
	before := runtime.NumGoroutine()
	c := newTestExchangeContext(context.Background())
	recorder := new(invokeRecorder)
	c.OnCancel(recorder)
	c.Success("1.1.1.1")

	deadline := time.Now().Add(5 * time.Second)
	for runtime.NumGoroutine() > before && time.Now().Before(deadline) {
		time.Sleep(10 * time.Millisecond)
	}
	if got := runtime.NumGoroutine(); got > before {
		t.Fatalf("OnCancel goroutine still running after settle: %d > %d", got, before)
	}
	if got := recorder.count.Load(); got != 0 {
		t.Fatalf("OnCancel callback invoked %d times after settle, want 0", got)
	}
}
