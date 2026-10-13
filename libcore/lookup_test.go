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
			t.Fatal("expected one A and one AAAA query")
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
		// 同一台服务器的 A 与 AAAA 并行各查一次，预算用完后不再查第二台
		if queries.Load()-before != 2 {
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

// 假 DNS 服务器：按域名前缀决定 A / AAAA 各自的行为。
// 域名第一段形如 a-ok / a-drop / a-empty，后接 "." 与 aaaa-ok / aaaa-drop / aaaa-empty
// （drop 为不回应）。记录两类查询各被收到的次数
type familyServer struct {
	address string
	silent  bool // 为 true 时对所有查询都不回应
	a, aaaa atomic.Int32
}

func startFamilyServer(t *testing.T, silent bool) *familyServer {
	t.Helper()
	packet, err := net.ListenPacket("udp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	fake := &familyServer{address: "udp://" + packet.LocalAddr().String(), silent: silent}
	ready := make(chan struct{})
	server := &dns.Server{PacketConn: packet, NotifyStartedFunc: func() { close(ready) }, Handler: dns.HandlerFunc(func(w dns.ResponseWriter, q *dns.Msg) {
		question := q.Question[0]
		parts := strings.SplitN(question.Name, ".", 3)
		mode := parts[0]
		if question.Qtype == dns.TypeAAAA {
			fake.aaaa.Add(1)
			mode = parts[1]
		} else {
			fake.a.Add(1)
		}
		if fake.silent || strings.HasSuffix(mode, "drop") {
			return
		}
		reply := new(dns.Msg)
		reply.SetReply(q)
		if strings.HasSuffix(mode, "ok") {
			header := dns.RR_Header{Name: question.Name, Rrtype: question.Qtype, Class: dns.ClassINET}
			if question.Qtype == dns.TypeAAAA {
				reply.Answer = []dns.RR{&dns.AAAA{Hdr: header, AAAA: net.ParseIP("2001:db8::1")}}
			} else {
				reply.Answer = []dns.RR{&dns.A{Hdr: header, A: net.ParseIP("192.0.2.1")}}
			}
		}
		if err := w.WriteMsg(reply); err != nil {
			t.Error(err)
		}
	})}
	go server.ActivateAndServe()
	<-ready
	t.Cleanup(func() { server.Shutdown() })
	return fake
}

func TestLookupHostBothFamilies(t *testing.T) {
	server := startFamilyServer(t, false)
	for _, tc := range []struct {
		name, domain, want string
		fails              bool
	}{
		{"A 与 AAAA 都有，取并集且 A 在前", "a-ok.aaaa-ok.test", "192.0.2.1\n2001:db8::1", false},
		{"只有 A", "a-ok.aaaa-empty.test", "192.0.2.1", false},
		{"只有 AAAA", "a-empty.aaaa-ok.test", "2001:db8::1", false},
		{"两类都为空", "a-empty.aaaa-empty.test", "", true},
	} {
		t.Run(tc.name, func(t *testing.T) {
			ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
			defer cancel()
			beforeA, beforeAAAA := server.a.Load(), server.aaaa.Load()
			got, err := lookupHost(ctx, server.address, tc.domain)
			if tc.fails != (err != nil) || got != tc.want {
				t.Fatalf("%q, %v", got, err)
			}
			if server.a.Load()-beforeA != 1 || server.aaaa.Load()-beforeAAAA != 1 {
				t.Fatal("A 与 AAAA 应各查询一次")
			}
		})
	}
}

func TestLookupHostOneFamilyTimesOut(t *testing.T) {
	server := startFamilyServer(t, false)
	// 一类不回应、另一类有地址：只等宽限期就返回已有的结果，不等满单台超时
	for domain, want := range map[string]string{
		"a-ok.aaaa-drop.test": "192.0.2.1",
		"a-drop.aaaa-ok.test": "2001:db8::1",
	} {
		ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
		start := time.Now()
		got, err := lookupHost(ctx, server.address, domain)
		cancel()
		if err != nil || got != want {
			t.Fatalf("%s: %q, %v", domain, got, err)
		}
		if elapsed := time.Since(start); elapsed > perServerTimeout/2 {
			t.Fatalf("%s: 等待 %v，超过宽限期", domain, elapsed)
		}
	}
	// 一类不回应、另一类为空：没有可用地址，整台服务器算失败
	ctx, cancel := context.WithTimeout(context.Background(), 200*time.Millisecond)
	defer cancel()
	if _, err := lookupHost(ctx, server.address, "a-drop.aaaa-empty.test"); err == nil {
		t.Fatal("没有地址却成功")
	}
}

func TestLookupHostsSilentFirstServer(t *testing.T) {
	silent := startFamilyServer(t, true)
	good := startFamilyServer(t, false)
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	start := time.Now()
	// 第一台两类都不回应，只吃它自己的单台超时（A 与 AAAA 并行，不是 5 + 5 秒）；
	// 第二台仍在总预算内被尝试，并返回两类地址的并集
	got, err := lookupHosts(ctx, silent.address+"\n"+good.address, "a-ok.aaaa-ok.test")
	if err != nil || got != "192.0.2.1\n2001:db8::1" {
		t.Fatalf("%q, %v", got, err)
	}
	if elapsed := time.Since(start); elapsed > perServerTimeout+2*time.Second {
		t.Fatalf("耗时 %v，第一台吃掉了超过一份单台超时", elapsed)
	}
	if silent.a.Load() != 1 || silent.aaaa.Load() != 1 || good.a.Load() != 1 || good.aaaa.Load() != 1 {
		t.Fatalf("每台服务器 A / AAAA 各应查一次: %d %d %d %d", silent.a.Load(), silent.aaaa.Load(), good.a.Load(), good.aaaa.Load())
	}
}
