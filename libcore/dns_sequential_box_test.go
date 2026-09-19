package libcore

import (
	"os"
	"strings"
	"testing"

	"github.com/matsuridayo/libneko/neko_log"
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
