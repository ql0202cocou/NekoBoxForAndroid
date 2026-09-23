package libcore

import (
	"context"
	"errors"
	"net"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	dns "github.com/miekg/dns"
	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/option"
	routeRule "github.com/sagernet/sing-box/route/rule"
	"github.com/sagernet/sing/common/json"
	M "github.com/sagernet/sing/common/metadata"
)

func TestLookupRetriesTruncatedUDP(t *testing.T) {
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	packet, err := net.ListenPacket("udp", listener.Addr().String())
	if err != nil {
		listener.Close()
		t.Fatal(err)
	}
	handler := dns.HandlerFunc(func(w dns.ResponseWriter, q *dns.Msg) {
		m := new(dns.Msg)
		m.SetReply(q)
		ip := "192.0.2.2"
		if w.RemoteAddr().Network() == "udp" {
			m.Truncated = true
			ip = "192.0.2.1"
		}
		m.Answer = []dns.RR{&dns.A{Hdr: dns.RR_Header{Name: q.Question[0].Name, Rrtype: dns.TypeA, Class: dns.ClassINET}, A: net.ParseIP(ip)}}
		if err := w.WriteMsg(m); err != nil {
			t.Errorf("write: %v", err)
		}
	})
	readyTCP, readyUDP := make(chan struct{}), make(chan struct{})
	tcp := &dns.Server{Listener: listener, Handler: handler, NotifyStartedFunc: func() { close(readyTCP) }}
	udp := &dns.Server{PacketConn: packet, Handler: handler, NotifyStartedFunc: func() { close(readyUDP) }}
	go tcp.ActivateAndServe()
	go udp.ActivateAndServe()
	<-readyTCP
	<-readyUDP
	t.Cleanup(func() { tcp.Shutdown(); udp.Shutdown() })
	for _, scheme := range []string{"udp", "tcp"} {
		ctx, cancel := context.WithTimeout(context.Background(), time.Second)
		got, err := lookupHostType(ctx, scheme+"://"+listener.Addr().String(), "fixture.test", dns.TypeA)
		cancel()
		if err != nil || len(got) != 1 || got[0] != "192.0.2.2" {
			t.Fatalf("%s: %v, %v", scheme, got, err)
		}
	}
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	if _, err := lookupHostType(ctx, "udp://"+listener.Addr().String(), "fixture.test", dns.TypeA); err == nil {
		t.Fatal("cancelled lookup succeeded")
	}
}

func TestExchangeHTTPSStatus(t *testing.T) {
	for _, status := range []int{200, 403, 500} {
		t.Run(http.StatusText(status), func(t *testing.T) {
			query := new(dns.Msg)
			query.SetQuestion("fixture.test.", dns.TypeA)
			response := new(dns.Msg)
			response.SetReply(query)
			body, err := response.Pack()
			if err != nil {
				t.Fatal(err)
			}
			server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { w.WriteHeader(status); w.Write(body) }))
			defer server.Close()
			_, err = exchangeHTTPS(context.Background(), false, server.URL, query)
			if status == 200 && err != nil {
				t.Fatal(err)
			}
			if status != 200 && (err == nil || !strings.Contains(err.Error(), "HTTP")) {
				t.Fatalf("status %d accepted: %v", status, err)
			}
		})
	}
}

func TestDNSDefaultPort(t *testing.T) {
	for input, want := range map[string]string{"1.1.1.1": "1.1.1.1:53", "1.1.1.1:54": "1.1.1.1:54", "2001:db8::1": "[2001:db8::1]:53", "[2001:db8::1]:54": "[2001:db8::1]:54"} {
		if got := withDefaultPort(input, "53"); got != want {
			t.Errorf("%s: %s != %s", input, got, want)
		}
	}
}

func TestMulticastRuleDirections(t *testing.T) {
	makeRule := func(body string) adapter.Rule {
		var options option.Rule
		if err := json.UnmarshalContext(context.Background(), []byte(body), &options); err != nil {
			t.Fatal(err)
		}
		rule, err := routeRule.NewRule(context.Background(), nil, options, false)
		if err != nil {
			t.Fatal(err)
		}
		return rule
	}
	old := makeRule(`{"ip_cidr":["224.0.0.0/3","ff00::/8"],"source_ip_cidr":["224.0.0.0/3","ff00::/8"],"action":"reject"}`)
	dst := makeRule(`{"ip_cidr":["224.0.0.0/3","ff00::/8"],"action":"reject"}`)
	src := makeRule(`{"source_ip_cidr":["224.0.0.0/3","ff00::/8"],"action":"reject"}`)
	for _, tc := range []struct {
		source, destination string
		want                bool
	}{
		{"192.0.2.1:1234", "224.0.0.251:5353", true},
		{"224.0.0.251:5353", "192.0.2.1:1234", true},
		{"[2001:db8::1]:1234", "[ff02::fb]:5353", true},
		{"192.0.2.1:1234", "198.51.100.1:443", false},
	} {
		metadata := adapter.InboundContext{Source: M.ParseSocksaddr(tc.source), Destination: M.ParseSocksaddr(tc.destination)}
		if old.Match(&metadata) {
			t.Fatal("old combined rule unexpectedly matched")
		}
		metadata = adapter.InboundContext{Source: M.ParseSocksaddr(tc.source), Destination: M.ParseSocksaddr(tc.destination)}
		matched := dst.Match(&metadata)
		metadata = adapter.InboundContext{Source: M.ParseSocksaddr(tc.source), Destination: M.ParseSocksaddr(tc.destination)}
		matched = matched || src.Match(&metadata)
		if matched != tc.want {
			t.Fatalf("%s -> %s: %v", tc.source, tc.destination, matched)
		}
	}
}

func TestLookupHostsSharedBudget(t *testing.T) {
	var queries atomic.Int32
	packet, err := net.ListenPacket("udp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	ready := make(chan struct{})
	server := &dns.Server{PacketConn: packet, NotifyStartedFunc: func() { close(ready) }, Handler: dns.HandlerFunc(func(w dns.ResponseWriter, q *dns.Msg) {
		queries.Add(1)
		if q.Question[0].Name == "timeout.test." {
			return
		}
		reply := new(dns.Msg)
		reply.SetReply(q)
		if q.Question[0].Qtype == dns.TypeAAAA {
			reply.Answer = []dns.RR{&dns.AAAA{Hdr: dns.RR_Header{Name: q.Question[0].Name, Rrtype: dns.TypeAAAA, Class: dns.ClassINET}, AAAA: net.ParseIP("2001:db8::1")}}
		}
		if err := w.WriteMsg(reply); err != nil {
			t.Error(err)
		}
	})}
	go server.ActivateAndServe()
	<-ready
	defer server.Shutdown()
	address := "udp://" + packet.LocalAddr().String()
	t.Run("fallback and AAAA", func(t *testing.T) {
		ctx, cancel := context.WithTimeout(context.Background(), time.Second)
		defer cancel()
		got, err := lookupHosts(ctx, "\nunsupported://user:secret@fixture\n"+address+"\n", "fixture.test")
		if err != nil || got != "2001:db8::1" {
			t.Fatalf("%q, %v", got, err)
		}
		if queries.Load() != 2 {
			t.Fatal("expected A then AAAA")
		}
	})
	t.Run("deadline prevents next server", func(t *testing.T) {
		before := queries.Load()
		ctx, cancel := context.WithTimeout(context.Background(), 100*time.Millisecond)
		defer cancel()
		start := time.Now()
		_, err := lookupHosts(ctx, address+"\n"+address, "timeout.test")
		if !errors.Is(err, context.DeadlineExceeded) {
			t.Fatalf("expected deadline: %v", err)
		}
		if time.Since(start) > time.Second {
			t.Fatal("lookup exceeded shared budget tolerance")
		}
		if queries.Load()-before != 1 {
			t.Fatal("queried again after deadline")
		}
	})
	t.Run("already cancelled", func(t *testing.T) {
		before := queries.Load()
		ctx, cancel := context.WithCancel(context.Background())
		cancel()
		_, err := lookupHosts(ctx, address, "fixture.test")
		if !errors.Is(err, context.Canceled) || queries.Load() != before {
			t.Fatalf("cancel not respected: %v", err)
		}
	})
	t.Run("empty and failed servers", func(t *testing.T) {
		for _, servers := range []string{"", "  \n", "unsupported://user:secret@fixture"} {
			_, err := lookupHosts(context.Background(), servers, "fixture.test")
			if err == nil || strings.Contains(err.Error(), "secret") {
				t.Fatalf("unsafe or absent error: %v", err)
			}
		}
	})
}
