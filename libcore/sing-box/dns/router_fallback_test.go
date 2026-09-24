package dns

// Regression tests for the neko DNS rule action `fallback` patch (see
// NEKO.md): when a query routed by a rule with `fallback: true` fails — a
// transport error or any non-success rcode — matching continues at the next
// DNS rule instead of returning the failure.

import (
	"context"
	"errors"
	"net/netip"
	"testing"

	"github.com/sagernet/sing-box/adapter"
	C "github.com/sagernet/sing-box/constant"
	"github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing-box/option"
	R "github.com/sagernet/sing-box/route/rule"

	mDNS "github.com/miekg/dns"
	"github.com/stretchr/testify/require"
)

func fallbackRouteRule(server string, fallback bool) option.DNSRule {
	return option.DNSRule{
		Type: "",
		DefaultOptions: option.DefaultDNSRule{
			DNSRuleAction: option.DNSRuleAction{
				Action: C.RuleActionTypeRoute,
				RouteOptions: option.DNSRouteActionOptions{
					Server:   server,
					Fallback: fallback,
				},
			},
		},
	}
}

// A transport error on a fallback rule continues matching at the next rule.
func TestDNSFallbackContinuesOnTransportError(t *testing.T) {
	t.Parallel()
	broken := &fakeDNSTransport{tag: "broken", exchangeErr: errors.New("server unreachable")}
	final := &fakeDNSTransport{tag: "final", rcode: mDNS.RcodeSuccess, address: netip.MustParseAddr("192.0.2.1")}
	router := raceTestRouter(t, broken, final)
	rules := raceTestRules(t, []option.DNSRule{
		fallbackRouteRule("broken", true),
		fallbackRouteRule("final", false),
	})
	result := raceTestExchange(router, rules)
	require.NoError(t, result.err)
	require.Equal(t, netip.MustParseAddr("192.0.2.1"), responseAddress(t, result.response))
	require.Equal(t, int32(1), broken.queryCount.Load())
	require.Equal(t, int32(1), final.queryCount.Load())
}

// A non-success rcode (NXDOMAIN from a split-horizon server) on a fallback
// rule also continues matching at the next rule.
func TestDNSFallbackContinuesOnNXDOMAIN(t *testing.T) {
	t.Parallel()
	broken := &fakeDNSTransport{tag: "broken", rcode: mDNS.RcodeNameError}
	final := &fakeDNSTransport{tag: "final", rcode: mDNS.RcodeSuccess, address: netip.MustParseAddr("192.0.2.1")}
	router := raceTestRouter(t, broken, final)
	rules := raceTestRules(t, []option.DNSRule{
		fallbackRouteRule("broken", true),
		fallbackRouteRule("final", false),
	})
	result := raceTestExchange(router, rules)
	require.NoError(t, result.err)
	require.Equal(t, netip.MustParseAddr("192.0.2.1"), responseAddress(t, result.response))
	require.Equal(t, int32(1), broken.queryCount.Load())
	require.Equal(t, int32(1), final.queryCount.Load())
}

// A successful answer on a fallback rule is final: the next rule is never
// queried.
func TestDNSFallbackStopsOnSuccess(t *testing.T) {
	t.Parallel()
	first := &fakeDNSTransport{tag: "first", rcode: mDNS.RcodeSuccess, address: netip.MustParseAddr("192.0.2.1")}
	final := &fakeDNSTransport{tag: "final", rcode: mDNS.RcodeSuccess, address: netip.MustParseAddr("192.0.2.2")}
	router := raceTestRouter(t, first, final)
	rules := raceTestRules(t, []option.DNSRule{
		fallbackRouteRule("first", true),
		fallbackRouteRule("final", false),
	})
	result := raceTestExchange(router, rules)
	require.NoError(t, result.err)
	require.Equal(t, netip.MustParseAddr("192.0.2.1"), responseAddress(t, result.response))
	require.Equal(t, int32(1), first.queryCount.Load())
	require.Equal(t, int32(0), final.queryCount.Load())
}

// Without `fallback` a failing rule is the final word: the failure is
// returned and the next rule is never queried.
func TestDNSRouteFailureWithoutFallback(t *testing.T) {
	t.Parallel()
	broken := &fakeDNSTransport{tag: "broken", rcode: mDNS.RcodeNameError}
	final := &fakeDNSTransport{tag: "final", rcode: mDNS.RcodeSuccess, address: netip.MustParseAddr("192.0.2.1")}
	router := raceTestRouter(t, broken, final)
	rules := raceTestRules(t, []option.DNSRule{
		fallbackRouteRule("broken", false),
		fallbackRouteRule("final", false),
	})
	result := raceTestExchange(router, rules)
	require.NoError(t, result.err)
	require.NotNil(t, result.response)
	require.Equal(t, mDNS.RcodeNameError, result.response.Rcode)
	require.Equal(t, int32(1), broken.queryCount.Load())
	require.Equal(t, int32(0), final.queryCount.Load())
}

// When no further rule matches, fallback continues to the default transport.
func TestDNSFallbackToDefaultTransport(t *testing.T) {
	t.Parallel()
	broken := &fakeDNSTransport{tag: "broken", rcode: mDNS.RcodeNameError}
	final := &fakeDNSTransport{tag: "final", rcode: mDNS.RcodeSuccess, address: netip.MustParseAddr("192.0.2.1")}
	router := raceTestRouter(t, broken, final)
	rules := raceTestRules(t, []option.DNSRule{
		fallbackRouteRule("broken", true),
	})
	result := raceTestExchange(router, rules)
	require.NoError(t, result.err)
	require.Equal(t, netip.MustParseAddr("192.0.2.1"), responseAddress(t, result.response))
	require.Equal(t, int32(1), broken.queryCount.Load())
	require.Equal(t, int32(1), final.queryCount.Load())
}

// The async walk path must honor fallback too (the direct-ExchangeAsync fast
// path is bypassed for fallback rules).
func TestDNSFallbackExchangeAsync(t *testing.T) {
	t.Parallel()
	broken := &fakeDNSTransport{tag: "broken", rcode: mDNS.RcodeNameError}
	final := &fakeDNSTransport{tag: "final", rcode: mDNS.RcodeSuccess, address: netip.MustParseAddr("192.0.2.1")}
	router := raceTestRouter(t, broken, final)
	rules := raceTestRules(t, []option.DNSRule{
		fallbackRouteRule("broken", true),
		fallbackRouteRule("final", false),
	})
	message := &mDNS.Msg{
		MsgHdr: mDNS.MsgHdr{
			Id:               1,
			RecursionDesired: true,
		},
		Question: []mDNS.Question{{
			Name:   "race.example.org.",
			Qtype:  mDNS.TypeA,
			Qclass: mDNS.ClassINET,
		}},
	}
	metadata := &adapter.InboundContext{
		Domain:    "race.example.org",
		QueryType: mDNS.TypeA,
	}
	ctx := adapter.WithContext(context.Background(), metadata)
	done := make(chan exchangeWithRulesResult, 1)
	router.exchangeWithRulesAsync(ctx, rules, message, adapter.DNSQueryOptions{}, false, func(result exchangeWithRulesResult) {
		done <- result
	})
	result := <-done
	require.NoError(t, result.err)
	require.Equal(t, netip.MustParseAddr("192.0.2.1"), responseAddress(t, result.response))
	require.Equal(t, int32(1), broken.queryCount.Load())
	require.Equal(t, int32(1), final.queryCount.Load())
}

// End-to-end through the public Exchange entry: a query matching a fallback
// rule comes back with the next server's answer.
func TestDNSFallbackThroughExchange(t *testing.T) {
	t.Parallel()
	broken := &fakeDNSTransport{tag: "broken", rcode: mDNS.RcodeNameError}
	final := &fakeDNSTransport{tag: "final", rcode: mDNS.RcodeSuccess, address: netip.MustParseAddr("192.0.2.1")}
	router := raceTestRouter(t, broken, final)
	router.rules = raceTestRules(t, []option.DNSRule{
		fallbackRouteRule("broken", true),
		fallbackRouteRule("final", false),
	})
	message := &mDNS.Msg{
		MsgHdr: mDNS.MsgHdr{
			Id:               1,
			RecursionDesired: true,
		},
		Question: []mDNS.Question{{
			Name:   "fallback.example.org.",
			Qtype:  mDNS.TypeA,
			Qclass: mDNS.ClassINET,
		}},
	}
	response, err := router.Exchange(context.Background(), message, adapter.DNSQueryOptions{})
	require.NoError(t, err)
	require.Equal(t, netip.MustParseAddr("192.0.2.1"), responseAddress(t, response))
	require.Equal(t, int32(1), broken.queryCount.Load())
	require.Equal(t, int32(1), final.queryCount.Load())
}

// app 生成的 DNS 规则都带 strategy，任一规则动作带 strategy 整个路由器就进 legacy
// 模式，Exchange / Lookup 走 exchangeLegacy 与 legacy 循环而不是规则遍历。下面的
// 用例按这个形态建规则，并先断言确实进了 legacy 模式
func legacyFallbackRouteRule(server string, fallback bool) option.DNSRule {
	rule := fallbackRouteRule(server, fallback)
	rule.DefaultOptions.RouteOptions.Strategy = option.DomainStrategy(C.DomainStrategyIPv4Only)
	return rule
}

func legacyFallbackRouter(t *testing.T, rawRules []option.DNSRule, transports ...*fakeDNSTransport) *Router {
	legacy, _, err := resolveLegacyDNSMode(nil, rawRules, nil)
	require.NoError(t, err)
	require.True(t, legacy)
	router := raceTestRouter(t, transports...)
	router.legacyDNSMode = true
	for _, rawRule := range rawRules {
		rule, err := R.NewDNSRule(context.Background(), log.NewNOPFactory().Logger(), rawRule, true, true)
		require.NoError(t, err)
		router.rules = append(router.rules, rule)
	}
	return router
}

func legacyFallbackMessage() *mDNS.Msg {
	return &mDNS.Msg{
		MsgHdr: mDNS.MsgHdr{
			Id:               1,
			RecursionDesired: true,
		},
		Question: []mDNS.Question{{
			Name:   "fallback.example.org.",
			Qtype:  mDNS.TypeA,
			Qclass: mDNS.ClassINET,
		}},
	}
}

// legacy 模式的 Exchange：带 fallback 的规则回 NXDOMAIN 时继续匹配下一条规则
func TestDNSFallbackLegacyExchange(t *testing.T) {
	t.Parallel()
	broken := &fakeDNSTransport{tag: "broken", rcode: mDNS.RcodeNameError}
	final := &fakeDNSTransport{tag: "final", rcode: mDNS.RcodeSuccess, address: netip.MustParseAddr("192.0.2.1")}
	router := legacyFallbackRouter(t, []option.DNSRule{
		legacyFallbackRouteRule("broken", true),
		legacyFallbackRouteRule("final", false),
	}, broken, final)
	response, err := router.Exchange(context.Background(), legacyFallbackMessage(), adapter.DNSQueryOptions{})
	require.NoError(t, err)
	require.Equal(t, netip.MustParseAddr("192.0.2.1"), responseAddress(t, response))
	require.Equal(t, int32(1), broken.queryCount.Load())
	require.Equal(t, int32(1), final.queryCount.Load())
}

// legacy 模式的 ExchangeAsync 同样回退（它在 goroutine 里调 exchangeLegacy）
func TestDNSFallbackLegacyExchangeAsync(t *testing.T) {
	t.Parallel()
	broken := &fakeDNSTransport{tag: "broken", exchangeErr: errors.New("server unreachable")}
	final := &fakeDNSTransport{tag: "final", rcode: mDNS.RcodeSuccess, address: netip.MustParseAddr("192.0.2.1")}
	router := legacyFallbackRouter(t, []option.DNSRule{
		legacyFallbackRouteRule("broken", true),
	}, broken, final)
	type result struct {
		response *mDNS.Msg
		err      error
	}
	done := make(chan result, 1)
	router.ExchangeAsync(context.Background(), legacyFallbackMessage(), adapter.DNSQueryOptions{}, func(response *mDNS.Msg, err error) {
		done <- result{response, err}
	})
	r := <-done
	require.NoError(t, r.err)
	require.Equal(t, netip.MustParseAddr("192.0.2.1"), responseAddress(t, r.response))
	require.Equal(t, int32(1), broken.queryCount.Load())
	require.Equal(t, int32(1), final.queryCount.Load())
}

// legacy 模式的 Lookup：传输出错时继续匹配下一条规则（它的 ipv4_only 让
// final 只收到一个 A 查询）
func TestDNSFallbackLegacyLookup(t *testing.T) {
	t.Parallel()
	broken := &fakeDNSTransport{tag: "broken", exchangeErr: errors.New("server unreachable")}
	final := &fakeDNSTransport{tag: "final", rcode: mDNS.RcodeSuccess, address: netip.MustParseAddr("192.0.2.1")}
	router := legacyFallbackRouter(t, []option.DNSRule{
		legacyFallbackRouteRule("broken", true),
		legacyFallbackRouteRule("final", false),
	}, broken, final)
	addresses, err := router.Lookup(context.Background(), "fallback.example.org", adapter.DNSQueryOptions{})
	require.NoError(t, err)
	require.Equal(t, []netip.Addr{netip.MustParseAddr("192.0.2.1")}, addresses)
	require.Equal(t, int32(1), broken.queryCount.Load())
	require.Equal(t, int32(1), final.queryCount.Load())
}

// legacy 模式下不带 fallback 的规则失败即终止：Exchange 原样返回 NXDOMAIN，
// Lookup 返回错误，下一台服务器都不被查询
func TestDNSRouteFailureWithoutFallbackLegacy(t *testing.T) {
	t.Parallel()
	broken := &fakeDNSTransport{tag: "broken", rcode: mDNS.RcodeNameError}
	final := &fakeDNSTransport{tag: "final", rcode: mDNS.RcodeSuccess, address: netip.MustParseAddr("192.0.2.1")}
	router := legacyFallbackRouter(t, []option.DNSRule{
		legacyFallbackRouteRule("broken", false),
		legacyFallbackRouteRule("final", false),
	}, broken, final)
	response, err := router.Exchange(context.Background(), legacyFallbackMessage(), adapter.DNSQueryOptions{})
	require.NoError(t, err)
	require.Equal(t, mDNS.RcodeNameError, response.Rcode)
	_, err = router.Lookup(context.Background(), "fallback.example.org", adapter.DNSQueryOptions{})
	require.Error(t, err)
	require.Equal(t, int32(2), broken.queryCount.Load())
	require.Equal(t, int32(0), final.queryCount.Load())
}
