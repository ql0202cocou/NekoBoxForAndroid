package libcore

import (
	"fmt"
	"io"
	"net"
	"os"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"github.com/matsuridayo/libneko/neko_log"
	mDNS "github.com/miekg/dns"
)

// neko_log.SetupLog only runs on Android; without it the platform log writer
// dereferences a nil *logWriter the first time the box logs anything.
func TestMain(m *testing.M) {
	neko_log.LogWriterDisable = true
	os.Exit(m.Run())
}

// ConfigBuilder-style synthetic config exercising the domain_resolver
// migration: neko-sequential transport, per-outbound binding and
// route.default_domain_resolver.
const sequentialBoxConfig = `{
	"log": {"level": "panic"},
	"dns": {
		"servers": [
			{"type": "local", "tag": "dns-local"},
			{"type": "udp", "tag": "dns-direct", "server": "223.5.5.5", "domain_resolver": "dns-local"},
			{"type": "udp", "tag": "dns-group-0", "server": "192.0.2.1", "domain_resolver": "dns-local"},
			{"type": "tls", "tag": "dns-group-1", "server": "192.0.2.2", "domain_resolver": "dns-local"},
			{"type": "neko-sequential", "tag": "dns-node-1", "servers": ["dns-group-0", "dns-group-1", "dns-direct"]}
		],
		"rules": [
			{"domain": ["node.example.com"], "server": "dns-group-0", "strategy": "prefer_ipv4", "fallback": true},
			{"domain": ["node.example.com"], "server": "dns-group-1", "strategy": "prefer_ipv4", "fallback": true}
		],
		"final": "dns-direct"
	},
	"inbounds": [
		{"type": "direct", "tag": "c-0-mapping-1", "listen": "127.0.0.1", "listen_port": 0,
		 "override_address": "node.example.com", "override_port": 1080}
	],
	"outbounds": [
		{"type": "direct", "tag": "direct"},
		{"type": "socks", "tag": "proxy", "server": "node.example.com", "server_port": 1080,
		 "domain_resolver": {"server": "dns-node-1", "strategy": "prefer_ipv4"}},
		{"type": "socks", "tag": "cross-group", "server": "other.example.com", "server_port": 1080}
	],
	"route": {
		"default_domain_resolver": {"server": "dns-direct", "strategy": "prefer_ipv4"},
		"rules": [
			{"inbound": ["c-0-mapping-1"], "action": "resolve", "server": "dns-node-1", "strategy": "prefer_ipv4"},
			{"inbound": ["c-0-mapping-1"], "outbound": "direct"}
		],
		"final": "proxy"
	}
}`

func TestSequentialBoxConfigLoadsAndStarts(t *testing.T) {
	instance, err := NewSingBoxInstance(sequentialBoxConfig, nil)
	if err != nil {
		t.Fatal(err)
	}
	defer instance.Close()
	if err := instance.Start(); err != nil {
		t.Fatal(err)
	}
}

func TestSequentialBoxConfigMissingMember(t *testing.T) {
	config := strings.Replace(sequentialBoxConfig,
		`"servers": ["dns-group-0", "dns-group-1", "dns-direct"]`,
		`"servers": ["dns-group-0", "dns-gone", "dns-direct"]`, 1)
	instance, err := NewSingBoxInstance(config, nil)
	if err != nil {
		t.Fatal(err)
	}
	defer instance.Close()
	err = instance.Start()
	if err == nil || !strings.Contains(err.Error(), "dns-gone") {
		t.Fatalf("expected missing member dependency error, got: %v", err)
	}
}

func TestSequentialBoxConfigNestedMember(t *testing.T) {
	config := strings.Replace(sequentialBoxConfig,
		`{"type": "tls", "tag": "dns-group-1", "server": "192.0.2.2", "domain_resolver": "dns-local"}`,
		`{"type": "neko-sequential", "tag": "dns-group-1", "servers": ["dns-direct"]}`, 1)
	instance, err := NewSingBoxInstance(config, nil)
	if err != nil {
		t.Fatal(err)
	}
	defer instance.Close()
	err = instance.Start()
	if err == nil || !strings.Contains(err.Error(), DNSTypeSequential) {
		t.Fatalf("expected nested sequential rejection, got: %v", err)
	}
}

func TestSequentialBoxConfigMissingResolver(t *testing.T) {
	config := strings.Replace(sequentialBoxConfig,
		`"domain_resolver": {"server": "dns-node-1", "strategy": "prefer_ipv4"}`,
		`"domain_resolver": {"server": "dns-gone", "strategy": "prefer_ipv4"}`, 1)
	_, err := NewSingBoxInstance(config, nil)
	if err == nil || !strings.Contains(err.Error(), "dns-gone") {
		t.Fatalf("expected domain resolver not found, got: %v", err)
	}
}

// 映射入站（外核跳经 sing-box 拨服务器的入口）上的 resolve 规则：服务器域名经分组 DNS 的有序链解析，
// 与内部核心跳绑 domain_resolver 的语义一致。直连 DNS 一律回 NXDOMAIN、分组 DNS 回 127.0.0.1，
// 连通且直连 DNS 没被查过才算用了分组 DNS。chained：映射入站交给下一跳出站，下一跳由同一实例里的
// socks 入站扮演，它自己解析只能走 dns-direct（会失败），拿到的已是 IP 才连得通
func TestMappingInboundResolvesThroughGroupDNS(t *testing.T) {
	for _, chained := range []bool{false, true} {
		t.Run(fmt.Sprintf("chained_%v", chained), func(t *testing.T) {
			var directCalls, groupCalls atomic.Int32
			directPort := startTestDNSServer(t, &directCalls, false)
			groupPort := startTestDNSServer(t, &groupCalls, true)
			echoPort := startTestEchoServer(t)
			mappingPort, nextHopPort := freeTCPPort(t), freeTCPPort(t)
			target := "direct"
			if chained {
				target = "next-hop"
			}
			config := fmt.Sprintf(`{
				"log": {"level": "panic"},
				"dns": {
					"servers": [
						{"type": "udp", "tag": "dns-direct", "server": "127.0.0.1", "server_port": %d},
						{"type": "udp", "tag": "dns-group-0", "server": "127.0.0.1", "server_port": %d},
						{"type": "neko-sequential", "tag": "dns-node-1", "servers": ["dns-group-0", "dns-direct"]}
					],
					"final": "dns-direct"
				},
				"inbounds": [
					{"type": "direct", "tag": "c-0-mapping-1", "listen": "127.0.0.1", "listen_port": %d,
					 "override_address": "node.example.invalid", "override_port": %d},
					{"type": "socks", "tag": "next-hop-in", "listen": "127.0.0.1", "listen_port": %d}
				],
				"outbounds": [
					{"type": "direct", "tag": "direct"},
					{"type": "socks", "tag": "next-hop", "server": "127.0.0.1", "server_port": %d}
				],
				"route": {
					"default_domain_resolver": {"server": "dns-direct", "strategy": "ipv4_only"},
					"rules": [
						{"inbound": ["c-0-mapping-1"], "action": "resolve", "server": "dns-node-1", "strategy": "ipv4_only"},
						{"inbound": ["c-0-mapping-1"], "outbound": %q},
						{"inbound": ["next-hop-in"], "outbound": "direct"}
					],
					"final": "direct"
				}
			}`, directPort, groupPort, mappingPort, echoPort, nextHopPort, nextHopPort, target)
			instance, err := NewSingBoxInstance(config, nil)
			if err != nil {
				t.Fatal(err)
			}
			defer instance.Close()
			if err := instance.Start(); err != nil {
				t.Fatal(err)
			}

			connection, err := net.DialTimeout("tcp", fmt.Sprintf("127.0.0.1:%d", mappingPort), time.Second)
			if err != nil {
				t.Fatal(err)
			}
			defer connection.Close()
			_ = connection.SetDeadline(time.Now().Add(3 * time.Second))
			if _, err := connection.Write([]byte("x")); err != nil {
				t.Fatal(err)
			}
			var response [2]byte
			_, readErr := io.ReadFull(connection, response[:])
			if readErr != nil || string(response[:]) != "OK" || groupCalls.Load() == 0 || directCalls.Load() != 0 {
				t.Fatalf("group DNS not used: read error=%v, response=%q, group queries=%d, direct queries=%d",
					readErr, response, groupCalls.Load(), directCalls.Load())
			}
		})
	}
}

// 不映射的外核跳（hysteria 1 插件）的本机 DNS 入站（ConfigBuild.applyPluginDns 的形状）：插件把它当 resolver，
// 查询经置顶的 hijack-dns 交给 DNS 模块，置顶的 DNS 规则按入站匹配：本组域名走分组 DNS 的有序链，其余走 dns-direct。
// 后面另有一条兜底规则把一切交给 dns-trap（扮演经代理的 dns-remote），它被查到就说明入站规则没排在最前
func TestPluginDNSInboundRoutesByInbound(t *testing.T) {
	var directCalls, groupCalls, trapCalls atomic.Int32
	directPort := startTestDNSServer(t, &directCalls, true)
	groupPort := startTestDNSServer(t, &groupCalls, true)
	trapPort := startTestDNSServer(t, &trapCalls, true)
	dnsPort := freeUDPPort(t)
	config := fmt.Sprintf(`{
		"log": {"level": "panic"},
		"dns": {
			"servers": [
				{"type": "udp", "tag": "dns-direct", "server": "127.0.0.1", "server_port": %d},
				{"type": "udp", "tag": "dns-group-0", "server": "127.0.0.1", "server_port": %d},
				{"type": "udp", "tag": "dns-trap", "server": "127.0.0.1", "server_port": %d},
				{"type": "neko-sequential", "tag": "dns-node-1", "servers": ["dns-group-0", "dns-direct"]}
			],
			"rules": [
				{"inbound": ["plugin-dns-in"], "domain": ["node.example.invalid"], "server": "dns-node-1", "strategy": "ipv4_only"},
				{"inbound": ["plugin-dns-in"], "server": "dns-direct", "strategy": "ipv4_only"},
				{"domain_suffix": ["invalid"], "server": "dns-trap"}
			],
			"final": "dns-trap"
		},
		"inbounds": [
			{"type": "direct", "tag": "plugin-dns-in", "listen": "127.0.0.1", "listen_port": %d, "network": "udp"}
		],
		"outbounds": [{"type": "direct", "tag": "direct"}],
		"route": {
			"default_domain_resolver": {"server": "dns-direct", "strategy": "ipv4_only"},
			"rules": [
				{"inbound": ["plugin-dns-in"], "action": "hijack-dns"},
				{"action": "sniff"}
			],
			"final": "direct"
		}
	}`, directPort, groupPort, trapPort, dnsPort)
	instance, err := NewSingBoxInstance(config, nil)
	if err != nil {
		t.Fatal(err)
	}
	defer instance.Close()
	if err := instance.Start(); err != nil {
		t.Fatal(err)
	}

	query := func(name string) {
		t.Helper()
		message := new(mDNS.Msg).SetQuestion(mDNS.Fqdn(name), mDNS.TypeA)
		client := &mDNS.Client{Net: "udp", Timeout: 3 * time.Second}
		response, _, err := client.Exchange(message, fmt.Sprintf("127.0.0.1:%d", dnsPort))
		if err != nil {
			t.Fatalf("query %s: %v", name, err)
		}
		if len(response.Answer) != 1 {
			t.Fatalf("query %s: answer %v", name, response.Answer)
		}
	}
	query("node.example.invalid")
	if groupCalls.Load() == 0 || directCalls.Load() != 0 {
		t.Fatalf("group domain not resolved via the group DNS: group=%d direct=%d", groupCalls.Load(), directCalls.Load())
	}
	query("other.example.invalid")
	if directCalls.Load() == 0 {
		t.Fatalf("other domain not resolved via dns-direct: direct=%d", directCalls.Load())
	}
	if trapCalls.Load() != 0 {
		t.Fatalf("a later DNS rule caught a query from the plugin DNS inbound: trap=%d", trapCalls.Load())
	}
}

// 取一个当前空闲的 UDP 端口（关掉后交给 sing-box 监听）
func freeUDPPort(t *testing.T) int {
	packet, err := net.ListenPacket("udp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer packet.Close()
	return packet.LocalAddr().(*net.UDPAddr).Port
}

// 本机 UDP DNS 服务：answer 时 A 查询一律回 127.0.0.1，否则回 NXDOMAIN；calls 记查询次数
func startTestDNSServer(t *testing.T, calls *atomic.Int32, answer bool) int {
	packet, err := net.ListenPacket("udp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	server := &mDNS.Server{PacketConn: packet, Handler: mDNS.HandlerFunc(func(w mDNS.ResponseWriter, q *mDNS.Msg) {
		calls.Add(1)
		response := new(mDNS.Msg).SetReply(q)
		if answer && len(q.Question) > 0 && q.Question[0].Qtype == mDNS.TypeA {
			response.Answer = []mDNS.RR{&mDNS.A{
				Hdr: mDNS.RR_Header{Name: q.Question[0].Name, Rrtype: mDNS.TypeA, Class: mDNS.ClassINET, Ttl: 1},
				A:   net.ParseIP("127.0.0.1"),
			}}
		} else {
			response.Rcode = mDNS.RcodeNameError
		}
		_ = w.WriteMsg(response)
	})}
	go func() { _ = server.ActivateAndServe() }()
	t.Cleanup(func() { _ = server.Shutdown() })
	return packet.LocalAddr().(*net.UDPAddr).Port
}

// 本机 TCP 服务：每个连接读到 1 字节后回 "OK"
func startTestEchoServer(t *testing.T) int {
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = listener.Close() })
	go func() {
		for {
			connection, err := listener.Accept()
			if err != nil {
				return
			}
			go func() {
				defer connection.Close()
				_ = connection.SetDeadline(time.Now().Add(3 * time.Second))
				var request [1]byte
				if _, err := io.ReadFull(connection, request[:]); err == nil {
					_, _ = connection.Write([]byte("OK"))
				}
			}()
		}
	}()
	return listener.Addr().(*net.TCPAddr).Port
}

// 取一个当前空闲的 TCP 端口（关掉后交给 sing-box 监听）
func freeTCPPort(t *testing.T) int {
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer listener.Close()
	return listener.Addr().(*net.TCPAddr).Port
}
