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
	"github.com/sagernet/sing-box/option"

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
