package libcore

import (
	"crypto/ecdsa"
	"crypto/ed25519"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/base64"
	"encoding/json"
	"encoding/pem"
	"fmt"
	"math/big"
	"os"
	"path/filepath"
	"regexp"
	"runtime"
	"sort"
	"strings"
	"sync"
	"testing"
	"time"

	"golang.org/x/crypto/ssh"
	"golang.org/x/sys/unix"
)

// ---- 夹具 ----

func trialKey(n int) string {
	b := make([]byte, n)
	_, _ = rand.Read(b)
	return base64.StdEncoding.EncodeToString(b)
}

func trialCert(t testing.TB) string {
	t.Helper()
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	tmpl := &x509.Certificate{SerialNumber: big.NewInt(1), Subject: pkix.Name{CommonName: "trial.example"},
		NotBefore: time.Now(), NotAfter: time.Now().Add(time.Hour), DNSNames: []string{"trial.example"}}
	der, err := x509.CreateCertificate(rand.Reader, tmpl, tmpl, &key.PublicKey, key)
	if err != nil {
		t.Fatal(err)
	}
	return string(pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: der}))
}

// 一对 ed25519：不加密的私钥、用 trial-pass 加密的私钥、authorized_keys 形式的公钥（当 host key 用）
func trialSSHKeys(t testing.TB) (plain, protected, hostKey string) {
	t.Helper()
	pub, priv, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	block, err := ssh.MarshalPrivateKey(priv, "")
	if err != nil {
		t.Fatal(err)
	}
	protectedBlock, err := ssh.MarshalPrivateKeyWithPassphrase(priv, "", []byte("trial-pass"))
	if err != nil {
		t.Fatal(err)
	}
	sshPub, err := ssh.NewPublicKey(pub)
	if err != nil {
		t.Fatal(err)
	}
	return string(pem.EncodeToMemory(block)), string(pem.EncodeToMemory(protectedBlock)),
		strings.TrimSpace(string(ssh.MarshalAuthorizedKey(sshPub)))
}

func trialJSON(m map[string]any) string {
	b, err := json.Marshal(m)
	if err != nil {
		panic(err)
	}
	return string(b)
}

type trialCase struct {
	name     string
	kind     string
	config   string
	category trialCategory // 空为通过
	path     string        // 只对 DECODE 有意义
}

const trialUUIDValue = "a3482e88-686a-4a58-8126-99c9df64b7bf"

func trialTLS() map[string]any {
	return map[string]any{"enabled": true, "server_name": "trial.example"}
}

func trialWGPeerConfig(pub, psk string) map[string]any {
	peer := map[string]any{"address": "198.51.100.1", "port": 51820, "public_key": pub, "allowed_ips": []string{"0.0.0.0/0"}}
	if psk != "" {
		peer["pre_shared_key"] = psk
	}
	return peer
}

func trialWG(priv string, peer map[string]any) map[string]any {
	return map[string]any{"type": "wireguard", "tag": "x", "address": []string{"172.16.0.2/32"}, "private_key": priv, "mtu": 1408, "peers": []any{peer}}
}

// 合法对照：都要通过（设计稿的 16 个，加上几种「看着可疑但不是加载期错误」的输入与 domain_resolver 的两种写法）
func trialValidCases(t testing.TB) []trialCase {
	cert := trialCert(t)
	sshPlain, sshProtected, hostKey := trialSSHKeys(t)
	ok := func(name, kind string, m map[string]any) trialCase {
		return trialCase{name: name, kind: kind, config: trialJSON(m)}
	}
	return []trialCase{
		ok("ss-aead", "outbound", map[string]any{"type": "shadowsocks", "server": "ss.example", "server_port": 8388, "method": "aes-256-gcm", "password": "p"}),
		ok("ss-2022", "outbound", map[string]any{"type": "shadowsocks", "server": "ss.example", "server_port": 8388, "method": "2022-blake3-aes-256-gcm", "password": trialKey(32)}),
		ok("vmess-tls-ws", "outbound", map[string]any{"type": "vmess", "server": "vm.example", "server_port": 443, "uuid": trialUUIDValue, "security": "aes-128-gcm", "tls": trialTLS(), "transport": map[string]any{"type": "ws", "path": "/ws"}}),
		ok("trojan-cert", "outbound", map[string]any{"type": "trojan", "server": "tj.example", "server_port": 443, "password": "p", "tls": map[string]any{"enabled": true, "server_name": "trial.example", "certificate": cert}}),
		ok("vless-reality", "outbound", map[string]any{"type": "vless", "server": "vl.example", "server_port": 443, "uuid": trialUUIDValue, "flow": "xtls-rprx-vision", "tls": map[string]any{"enabled": true, "server_name": "trial.example", "utls": map[string]any{"enabled": true, "fingerprint": "chrome"}, "reality": map[string]any{"enabled": true, "public_key": "jNXHt1yRo0vDuchQlIP6Z0ZvjT3KtzVI-T4E7RoLJS0", "short_id": "0123abcd"}}}),
		ok("hysteria2", "outbound", map[string]any{"type": "hysteria2", "server": "hy.example", "server_port": 443, "password": "p", "tls": trialTLS()}),
		ok("tuic", "outbound", map[string]any{"type": "tuic", "server": "tu.example", "server_port": 443, "uuid": trialUUIDValue, "password": "p", "tls": trialTLS()}),
		ok("anytls", "outbound", map[string]any{"type": "anytls", "server": "at.example", "server_port": 443, "password": "p", "tls": trialTLS()}),
		ok("ssh-key", "outbound", map[string]any{"type": "ssh", "server": "ssh.example", "server_port": 22, "user": "u", "private_key": sshPlain, "host_key": []string{hostKey}}),
		ok("ssh-key-passphrase", "outbound", map[string]any{"type": "ssh", "server": "ssh.example", "user": "u", "private_key": sshProtected, "private_key_passphrase": "trial-pass"}),
		ok("shadowtls", "outbound", map[string]any{"type": "shadowtls", "server": "st.example", "server_port": 443, "version": 3, "password": "p", "tls": map[string]any{"enabled": true, "server_name": "trial.example", "utls": map[string]any{"enabled": true, "fingerprint": "chrome"}}}),
		ok("http-tls", "outbound", map[string]any{"type": "http", "server": "h.example", "server_port": 443, "tls": trialTLS()}),
		ok("socks", "outbound", map[string]any{"type": "socks", "server": "s.example", "server_port": 1080}),
		ok("ech-pem", "outbound", map[string]any{"type": "vmess", "server": "vm.example", "server_port": 443, "uuid": trialUUIDValue, "tls": map[string]any{"enabled": true, "server_name": "trial.example", "ech": map[string]any{"enabled": true, "config": []string{"-----BEGIN ECH CONFIGS-----", "AEX+DQBBpAAgACCm0Zk2fK0wzpe6kkZ8zVbGGQcLpb8YCnlaqXJcAkD0WgAEAAEAAQASY2xvdWRmbGFyZS1lY2guY29tAAA=", "-----END ECH CONFIGS-----"}}}}),
		ok("wireguard", "endpoint", trialWG(trialKey(32), trialWGPeerConfig(trialKey(32), trialKey(32)))),
		ok("domain-resolver-object", "outbound", map[string]any{"type": "trojan", "server": "tj.example", "server_port": 443, "password": "p", "tls": trialTLS(), "domain_resolver": map[string]any{"server": "dns-node-7", "strategy": "prefer_ipv4"}, "detour": "c-1-2"}),
		// 以下不在设计稿的 16 个里
		ok("domain-resolver-string", "outbound", map[string]any{"type": "vmess", "server": "vm.example", "server_port": 443, "uuid": trialUUIDValue, "domain_resolver": "dns-remote"}),
		ok("wireguard-detour-resolver", "endpoint", map[string]any{"type": "wireguard", "address": []string{"172.16.0.2/32"}, "private_key": trialKey(32), "detour": "c-1-2", "domain_resolver": "dns-remote", "peers": []any{map[string]any{"address": "wg.example", "port": 51820, "public_key": trialKey(32), "allowed_ips": []string{"0.0.0.0/0"}, "reserved": []int{1, 2, 3}}}}),
		ok("ech-utls-garbage-pem", "outbound", map[string]any{"type": "anytls", "server": "at.example", "server_port": 443, "password": "p", "tls": map[string]any{"enabled": true, "server_name": "trial.example", "utls": map[string]any{"enabled": true, "fingerprint": "chrome"}, "ech": map[string]any{"enabled": true, "config": []string{"-----BEGIN ECH CONFIGS-----", "AAAA", "-----END ECH CONFIGS-----"}}}}),
		ok("vmess-non-uuid-id", "outbound", map[string]any{"type": "vmess", "server": "vm.example", "server_port": 443, "uuid": "not-a-uuid"}),
		ok("tls-ip-without-sni", "outbound", map[string]any{"type": "trojan", "server": "192.0.2.1", "server_port": 443, "password": "p", "tls": map[string]any{"enabled": true}}),
		ok("hysteria-speeds", "outbound", map[string]any{"type": "hysteria", "server": "hy.example", "server_port": 443, "auth_str": "p", "up_mbps": 10, "down_mbps": 50, "tls": trialTLS()}),
		ok("ss-obfs-plugin", "outbound", map[string]any{"type": "shadowsocks", "server": "ss.example", "server_port": 8388, "method": "aes-256-gcm", "password": "p", "plugin": "obfs-local", "plugin_opts": "obfs=http;obfs-host=example.com"}),
		ok("trojan-mux", "outbound", map[string]any{"type": "trojan", "server": "tj.example", "server_port": 443, "password": "p", "tls": trialTLS(), "multiplex": map[string]any{"enabled": true, "protocol": "h2mux", "padding": true}}),
		ok("direct", "outbound", map[string]any{"type": "direct"}),
		ok("selector", "outbound", map[string]any{"type": "selector", "outbounds": []string{"a", "b"}}),
	}
}

// 坏输入：每个类别至少一条，断言分到该类（sing-box 升级改了文案时这里先红）
func trialBadCases(t testing.TB) []trialCase {
	_, sshProtected, _ := trialSSHKeys(t)
	bad := func(name, kind string, category trialCategory, m map[string]any) trialCase {
		return trialCase{name: name, kind: kind, config: trialJSON(m), category: category}
	}
	decode := func(name, kind, path, config string) trialCase {
		return trialCase{name: name, kind: kind, config: config, category: trialDecode, path: path}
	}
	trojan := func(extra map[string]any) map[string]any {
		m := map[string]any{"type": "trojan", "server": "tj.example", "server_port": 443, "password": "p", "tls": trialTLS()}
		for k, v := range extra {
			m[k] = v
		}
		return m
	}
	ss := func(method, password string, extra map[string]any) map[string]any {
		m := map[string]any{"type": "shadowsocks", "server": "ss.example", "server_port": 8388, "method": method, "password": password}
		for k, v := range extra {
			m[k] = v
		}
		return m
	}
	wgPeer := func(peer map[string]any) map[string]any {
		return map[string]any{"type": "wireguard", "address": []string{"172.16.0.2/32"}, "private_key": trialKey(32), "peers": []any{peer}}
	}
	return []trialCase{
		decode("json-unknown-field", "outbound", "secret_typo_field", trialJSON(trojan(map[string]any{"secret_typo_field": "x"}))),
		decode("json-wrong-type", "outbound", "server_port", `{"type":"trojan","server":"tj.example","server_port":"443","password":"p"}`),
		decode("json-nested-unknown-field", "outbound", "tls.secret_typo", trialJSON(trojan(map[string]any{"tls": map[string]any{"enabled": true, "secret_typo": 1}}))),
		decode("wg-local-address-no-prefix", "endpoint", "address", trialJSON(map[string]any{"type": "wireguard", "address": []string{"172.16.0.2"}, "private_key": trialKey(32), "peers": []any{trialWGPeerConfig(trialKey(32), "")}})),
		decode("hy2-obfs-type", "outbound", "obfs", trialJSON(map[string]any{"type": "hysteria2", "server": "hy.example", "server_port": 443, "password": "p", "tls": trialTLS(), "obfs": map[string]any{"type": "rot13", "password": "o"}})),
		decode("malformed-json", "outbound", "", `{"type":"trojan",`),
		decode("not-an-object", "outbound", "", `["trojan"]`),
		bad("wireguard-as-outbound", "outbound", trialUnknownType, map[string]any{"type": "wireguard", "private_key": trialKey(32)}),
		bad("vmess-as-endpoint", "endpoint", trialUnknownType, map[string]any{"type": "vmess", "server": "vm.example", "server_port": 443, "uuid": trialUUIDValue}),
		bad("dns-outbound", "outbound", trialUnknownType, map[string]any{"type": "dns"}),
		bad("ss-2022-base64", "outbound", trialSSKeyBase64, ss("2022-blake3-aes-256-gcm", "not*base64", nil)),
		bad("ss-2022-length", "outbound", trialSSKeyLength, ss("2022-blake3-aes-256-gcm", trialKey(16), nil)),
		bad("ss-aead-empty-password", "outbound", trialPasswordMissing, ss("aes-256-gcm", "", nil)),
		bad("ss-obfs-mode", "outbound", trialSSPlugin, ss("aes-256-gcm", "p", map[string]any{"plugin": "obfs-local", "plugin_opts": "obfs=secret-ish-mode"})),
		bad("ss-v2ray-plugin-mode", "outbound", trialSSPlugin, ss("aes-256-gcm", "p", map[string]any{"plugin": "v2ray-plugin", "plugin_opts": "mode=bogus"})),
		bad("ss-plugin-unknown", "outbound", trialSSPlugin, ss("aes-256-gcm", "p", map[string]any{"plugin": "bogus-plugin"})),
		bad("pem-certificate", "outbound", trialTLSCertificate, trojan(map[string]any{"tls": map[string]any{"enabled": true, "server_name": "trial.example", "certificate": "-----BEGIN CERTIFICATE-----\nSECRET-LOOKING-BYTES\n-----END CERTIFICATE-----"}})),
		bad("pem-certificate-hy2", "outbound", trialTLSCertificate, map[string]any{"type": "hysteria2", "server": "hy.example", "server_port": 443, "password": "p", "tls": map[string]any{"enabled": true, "server_name": "trial.example", "certificate": "not a pem"}}),
		bad("certificate-path", "outbound", trialTLSCertificateFile, trojan(map[string]any{"tls": map[string]any{"enabled": true, "server_name": "trial.example", "certificate_path": "/nonexistent/trial.pem"}})),
		bad("ss-v2ray-plugin-cert-path", "outbound", trialTLSCertificateFile, ss("aes-256-gcm", "p", map[string]any{"plugin": "v2ray-plugin", "plugin_opts": "tls;cert=/nonexistent/trial.pem"})),
		bad("ech-not-pem", "outbound", trialTLSECHConfig, map[string]any{"type": "vmess", "server": "vm.example", "server_port": 443, "uuid": trialUUIDValue, "tls": map[string]any{"enabled": true, "server_name": "trial.example", "ech": map[string]any{"enabled": true, "config": []string{"AEX+DQBBpAAgACCm0Zk2fK0wzpe6"}}}}),
		bad("ech-pem-bad-base64", "outbound", trialTLSECHConfig, trojan(map[string]any{"tls": map[string]any{"enabled": true, "server_name": "trial.example", "ech": map[string]any{"enabled": true, "config": []string{"-----BEGIN ECH CONFIGS-----", "abc*def", "-----END ECH CONFIGS-----"}}}})),
		bad("reality-public-key", "outbound", trialTLSReality, map[string]any{"type": "vless", "server": "vl.example", "server_port": 443, "uuid": trialUUIDValue, "tls": map[string]any{"enabled": true, "server_name": "trial.example", "utls": map[string]any{"enabled": true, "fingerprint": "chrome"}, "reality": map[string]any{"enabled": true, "public_key": "short", "short_id": "0123abcd"}}}),
		bad("reality-short-id", "outbound", trialTLSReality, map[string]any{"type": "vless", "server": "vl.example", "server_port": 443, "uuid": trialUUIDValue, "tls": map[string]any{"enabled": true, "server_name": "trial.example", "utls": map[string]any{"enabled": true, "fingerprint": "chrome"}, "reality": map[string]any{"enabled": true, "public_key": "jNXHt1yRo0vDuchQlIP6Z0ZvjT3KtzVI-T4E7RoLJS0", "short_id": "zz"}}}),
		bad("utls-fingerprint", "outbound", trialTLSUTLSFingerprint, map[string]any{"type": "http", "server": "h.example", "server_port": 443, "tls": map[string]any{"enabled": true, "server_name": "trial.example", "utls": map[string]any{"enabled": true, "fingerprint": "netscape"}}}),
		bad("tls-cipher-suite", "outbound", trialTLSOptions, trojan(map[string]any{"tls": map[string]any{"enabled": true, "server_name": "trial.example", "cipher_suites": []string{"BOGUS_SUITE"}}})),
		bad("tls-client-certificate-alone", "outbound", trialTLSOptions, trojan(map[string]any{"tls": map[string]any{"enabled": true, "server_name": "trial.example", "client_certificate": []string{"x"}}})),
		bad("anytls-without-tls", "outbound", trialTLSRequired, map[string]any{"type": "anytls", "server": "at.example", "server_port": 443, "password": "p"}),
		bad("shadowtls-without-tls", "outbound", trialTLSRequired, map[string]any{"type": "shadowtls", "server": "st.example", "server_port": 443, "version": 3, "password": "p"}),
		bad("ssh-private-key", "outbound", trialSSHPrivateKey, map[string]any{"type": "ssh", "server": "ssh.example", "user": "u", "private_key": "-----BEGIN OPENSSH PRIVATE KEY-----\nSECRET\n-----END OPENSSH PRIVATE KEY-----"}),
		bad("ssh-private-key-path", "outbound", trialSSHPrivateKey, map[string]any{"type": "ssh", "server": "ssh.example", "user": "u", "private_key_path": "/nonexistent/trial_key"}),
		bad("ssh-key-missing-passphrase", "outbound", trialSSHPrivateKeyPassphrase, map[string]any{"type": "ssh", "server": "ssh.example", "user": "u", "private_key": sshProtected}),
		bad("ssh-key-wrong-passphrase", "outbound", trialSSHPrivateKeyPassphrase, map[string]any{"type": "ssh", "server": "ssh.example", "user": "u", "private_key": sshProtected, "private_key_passphrase": "wrong"}),
		bad("ssh-host-key", "outbound", trialSSHHostKey, map[string]any{"type": "ssh", "server": "ssh.example", "user": "u", "password": "p", "host_key": []string{"ssh-ed25519 SECRETHOSTKEY"}}),
		bad("wg-private-key-base64", "endpoint", trialWGPrivateKey, trialWG("not*base64", trialWGPeerConfig(trialKey(32), ""))),
		bad("wg-private-key-length", "endpoint", trialWGPrivateKey, trialWG(trialKey(31), trialWGPeerConfig(trialKey(32), ""))),
		bad("wg-private-key-missing", "endpoint", trialWGPrivateKey, trialWG("", trialWGPeerConfig(trialKey(32), ""))),
		bad("wg-public-key-length", "endpoint", trialWGPeerPublicKey, trialWG(trialKey(32), trialWGPeerConfig(trialKey(16), ""))),
		bad("wg-public-key-base64", "endpoint", trialWGPeerPublicKey, trialWG(trialKey(32), trialWGPeerConfig("zz*", ""))),
		bad("wg-psk-base64", "endpoint", trialWGPreSharedKey, trialWG(trialKey(32), trialWGPeerConfig(trialKey(32), "zz*"))),
		bad("wg-psk-length", "endpoint", trialWGPreSharedKey, trialWG(trialKey(32), trialWGPeerConfig(trialKey(32), trialKey(33)))),
		bad("wg-allowed-ips-empty", "endpoint", trialWGPeer, wgPeer(map[string]any{"address": "198.51.100.1", "port": 51820, "public_key": trialKey(32)})),
		bad("wg-reserved", "endpoint", trialWGPeer, wgPeer(map[string]any{"address": "198.51.100.1", "port": 51820, "public_key": trialKey(32), "allowed_ips": []string{"0.0.0.0/0"}, "reserved": []int{1, 2}})),
		bad("tuic-uuid", "outbound", trialUUID, map[string]any{"type": "tuic", "server": "tu.example", "server_port": 443, "uuid": "secret-uuid-ish", "password": "p", "tls": trialTLS()}),
		bad("vless-flow", "outbound", trialVLESSFlow, map[string]any{"type": "vless", "server": "vl.example", "server_port": 443, "uuid": trialUUIDValue, "flow": "xtls-rprx-direct"}),
		bad("vmess-packet-encoding", "outbound", trialPacketEncoding, map[string]any{"type": "vmess", "server": "vm.example", "server_port": 443, "uuid": trialUUIDValue, "packet_encoding": "bogus"}),
		bad("transport-path", "outbound", trialTransport, map[string]any{"type": "vmess", "server": "vm.example", "server_port": 443, "uuid": trialUUIDValue, "transport": map[string]any{"type": "ws", "path": "/%zz"}}),
		bad("mux-protocol", "outbound", trialMultiplex, trojan(map[string]any{"multiplex": map[string]any{"enabled": true, "protocol": "mplex"}})),
		bad("mux-brutal-speed", "outbound", trialMultiplex, trojan(map[string]any{"multiplex": map[string]any{"enabled": true, "protocol": "h2mux", "brutal": map[string]any{"enabled": true}}})),
		bad("hysteria1-speed", "outbound", trialHysteria, map[string]any{"type": "hysteria", "server": "hy.example", "server_port": 443, "auth_str": "p", "tls": trialTLS()}),
		bad("hy2-port-range", "outbound", trialHysteria, map[string]any{"type": "hysteria2", "server": "hy.example", "server_ports": []string{"443-abc"}, "password": "p", "tls": trialTLS()}),
		bad("hy2-obfs-password", "outbound", trialHysteria, map[string]any{"type": "hysteria2", "server": "hy.example", "server_port": 443, "password": "p", "tls": trialTLS(), "obfs": map[string]any{"type": "salamander"}}),
		bad("hy2-bbr-profile", "outbound", trialHysteria, map[string]any{"type": "hysteria2", "server": "hy.example", "server_port": 443, "password": "p", "tls": trialTLS(), "bbr_profile": "bogus"}),
		bad("ss-method", "outbound", trialValueSet, ss("bogus-method", "p", nil)),
		bad("vmess-security", "outbound", trialValueSet, map[string]any{"type": "vmess", "server": "vm.example", "server_port": 443, "uuid": trialUUIDValue, "security": "bogus"}),
		bad("tuic-congestion", "outbound", trialValueSet, map[string]any{"type": "tuic", "server": "tu.example", "server_port": 443, "uuid": trialUUIDValue, "password": "p", "tls": trialTLS(), "congestion_control": "bogus"}),
		bad("anytls-tfo", "outbound", trialDialer, map[string]any{"type": "anytls", "server": "at.example", "server_port": 443, "password": "p", "tls": trialTLS(), "tcp_fast_open": true}),
		bad("direct-detour", "outbound", trialDialer, map[string]any{"type": "direct", "detour": "x"}),
		bad("wg-listen-port-detour", "endpoint", trialDialer, map[string]any{"type": "wireguard", "address": []string{"172.16.0.2/32"}, "private_key": trialKey(32), "listen_port": 51000, "detour": "x", "peers": []any{trialWGPeerConfig(trialKey(32), "")}}),
		bad("selector-without-members", "outbound", trialOther, map[string]any{"type": "selector"}),
		{name: "unknown-kind", kind: "inbound", config: trialJSON(map[string]any{"type": "socks"}), category: trialUnavailable},
	}
}

// 返回给 Kotlin 的文本只能是类别（加 DECODE 的字段路径），不能带任何取值
var trialWirePattern = regexp.MustCompile(`^[A-Z0-9_]+(\t[a-z0-9_]+(\.[a-z0-9_]+|\[[0-9]+\])*)?$`)

func expectedWire(c trialCase) string {
	return trialResult{category: c.category, path: c.path}.wire()
}

func TestTrialValidInputsPass(t *testing.T) {
	for _, c := range trialValidCases(t) {
		if got := TrialSingBoxOutbound(c.kind, c.config); got != "" {
			t.Errorf("%s: 合法输入没通过试建，得到 %q", c.name, got)
		}
	}
}

func TestTrialBadInputsClassified(t *testing.T) {
	for _, c := range trialBadCases(t) {
		got := TrialSingBoxOutbound(c.kind, c.config)
		if want := expectedWire(c); got != want {
			t.Errorf("%s: 得到 %q，期望 %q", c.name, got, want)
		}
		if !trialWirePattern.MatchString(got) {
			t.Errorf("%s: 返回文本 %q 不是「类别 + 可选字段路径」的形态", c.name, got)
		}
	}
}

// 类别表里的每一类都要有坏输入钉住（PANIC 无法用输入触发，单独测恢复逻辑）
func TestTrialEveryCategoryCovered(t *testing.T) {
	source, err := os.ReadFile("outbound_trial.go")
	if err != nil {
		t.Fatal(err)
	}
	declared := regexp.MustCompile(`trialCategory = "([A-Z0-9_]+)"`).FindAllStringSubmatch(string(source), -1)
	if len(declared) == 0 {
		t.Fatal("读不到 outbound_trial.go 的类别常量表")
	}
	covered := map[string]bool{string(trialPanic): true}
	for _, c := range trialBadCases(t) {
		covered[string(c.category)] = true
	}
	for _, match := range declared {
		if !covered[match[1]] {
			t.Errorf("类别 %s 没有坏输入用例", match[1])
		}
	}
}

func TestTrialPanicRecovered(t *testing.T) {
	got := func() (result string) {
		defer recoverTrialPanic(&result)
		panic("secret value that must not be returned")
	}()
	if got != string(trialPanic) {
		t.Fatalf("panic 应换成 %q，得到 %q", trialPanic, got)
	}
}

func TestTrialDecodePathFilter(t *testing.T) {
	for _, c := range []struct {
		message string
		want    trialResult
	}{
		{`secret_typo: json: unknown field "secret_typo"`, trialResult{trialDecode, "secret_typo"}},
		{`peers[0].allowed_ips: json: cannot unmarshal`, trialResult{trialDecode, "peers[0].allowed_ips"}},
		{`json: cannot unmarshal string into Go value`, trialResult{trialDecode, ""}},
		{`unexpected end of JSON input`, trialResult{trialDecode, ""}},
		{`Secret Value: something`, trialResult{trialDecode, ""}},
		{`AbC: x`, trialResult{trialDecode, ""}},
		// 长度上限 trialPathMax（与 Kotlin 的 SING_BOX_TRIAL_PATH_MAX 相同）：正好上限的保留，多一个字符就不带
		{strings.Repeat("a", trialPathMax) + `: x`, trialResult{trialDecode, strings.Repeat("a", trialPathMax)}},
		{strings.Repeat("a", trialPathMax+1) + `: x`, trialResult{trialDecode, ""}},
		{`unknown outbound type: secret`, trialResult{trialUnknownType, ""}},
		{`unknown endpoint type: secret`, trialResult{trialUnknownType, ""}},
	} {
		if got := classifyDecode(fmt.Errorf("%s", c.message)); got != c.want {
			t.Errorf("%q: 得到 %+v，期望 %+v", c.message, got, c.want)
		}
	}
}

func TestTrialRewriteDomainResolver(t *testing.T) {
	for _, c := range []struct {
		name, input, want string
	}{
		{"absent", `{"type":"direct","server_port":443}`, `{"type":"direct","server_port":443}`},
		{"string", `{"type":"vmess","domain_resolver":"dns-remote"}`, `{"domain_resolver":"neko-trial-dns","type":"vmess"}`},
		{"empty-string", `{"type":"vmess","domain_resolver":""}`, `{"type":"vmess","domain_resolver":""}`},
		{"object", `{"domain_resolver":{"server":"dns-node-1","strategy":"ipv4_only"},"type":"vmess"}`, `{"domain_resolver":{"server":"neko-trial-dns","strategy":"ipv4_only"},"type":"vmess"}`},
		{"object-without-server", `{"domain_resolver":{"strategy":"ipv4_only"}}`, `{"domain_resolver":{"strategy":"ipv4_only"}}`},
		{"case-insensitive", `{"Domain_Resolver":{"Server":"dns-remote"}}`, `{"Domain_Resolver":{"Server":"neko-trial-dns"}}`},
		{"wrong-shape", `{"domain_resolver":42}`, `{"domain_resolver":42}`},
		{"numbers-kept", `{"domain_resolver":"x","up_mbps":1.0,"n":12345678901234567890}`, `{"domain_resolver":"neko-trial-dns","n":12345678901234567890,"up_mbps":1.0}`},
	} {
		got, err := rewriteDomainResolver([]byte(c.input))
		if err != nil {
			t.Errorf("%s: %v", c.name, err)
			continue
		}
		if string(got) != c.want {
			t.Errorf("%s: 得到 %s，期望 %s", c.name, got, c.want)
		}
	}
}

// WireGuard 的密钥补查要与真实启动的结论一致：补查拒绝的，NewSingBoxInstance + Start 确实失败；补查放过的能启动。
// 启动报错里有密钥原文，这里只看成败，不打印报错
func TestTrialWireGuardKeysMatchStart(t *testing.T) {
	wgConfig := func(priv, pub, psk string) string {
		return `{"log":{"level":"panic"},"endpoints":[` + trialJSON(map[string]any{
			"type": "wireguard", "tag": "wg", "address": []string{"172.16.0.2/32"}, "private_key": priv,
			"peers": []any{trialWGPeerConfig(pub, psk)},
		}) + `],"outbounds":[{"type":"direct","tag":"direct"}]}`
	}
	for _, c := range []struct {
		name           string
		priv, pub, psk string
		want           trialCategory
	}{
		{"valid", trialKey(32), trialKey(32), trialKey(32), ""},
		{"private-31", trialKey(31), trialKey(32), "", trialWGPrivateKey},
		{"private-33", trialKey(33), trialKey(32), "", trialWGPrivateKey},
		{"public-16", trialKey(32), trialKey(16), "", trialWGPeerPublicKey},
		{"psk-31", trialKey(32), trialKey(32), trialKey(31), trialWGPreSharedKey},
	} {
		endpoint := trialJSON(trialWG(c.priv, trialWGPeerConfig(c.pub, c.psk)))
		if got := TrialSingBoxOutbound("endpoint", endpoint); got != string(c.want) {
			t.Errorf("%s: 试建得到 %q，期望 %q", c.name, got, c.want)
		}
		instance, err := NewSingBoxInstance(wgConfig(c.priv, c.pub, c.psk), nil)
		if err != nil {
			t.Errorf("%s: 构造就失败了（补查的前提是构造查不出长度）", c.name)
			continue
		}
		started := instance.Start() == nil
		_ = instance.Close()
		if started != (c.want == "") {
			t.Errorf("%s: 真实启动成功 = %v，与试建结论 %q 不一致", c.name, started, c.want)
		}
	}
}

// ---- 副作用 ----

func trialGoroutines() int {
	runtime.GC()
	time.Sleep(20 * time.Millisecond)
	return runtime.NumGoroutine()
}

func trialFDs() int {
	n := 0
	for fd := 0; fd < 4096; fd++ {
		if _, err := unix.FcntlInt(uintptr(fd), unix.F_GETFD, 0); err == nil {
			n++
		}
	}
	return n
}

// 等计数回落到 limit 以下（同包别的测试留下的后台任务可能还在退出）；超时返回最后的值
func trialSettle(count func() int, limit int) int {
	deadline := time.Now().Add(3 * time.Second)
	got := count()
	for got > limit && time.Now().Before(deadline) {
		time.Sleep(50 * time.Millisecond)
		got = count()
	}
	return got
}

// 每类合法输入各试建 10 次，goroutine 与 fd 都不增长（升级后某个构造函数开始起后台任务或开 socket，这里先红）。
// WireGuard 不收尾时 10 次会留下约 100 个 goroutine；anytls 若被误跑 Initialize 每次多 1 个
func TestTrialNoSideEffects(t *testing.T) {
	if got := TrialSingBoxOutbound("outbound", `{"type":"direct"}`); got != "" {
		t.Fatalf("基础实例不可用：%q", got)
	}
	for _, c := range trialValidCases(t) {
		goroutines, fds := trialGoroutines(), trialFDs()
		for i := 0; i < 10; i++ {
			if got := TrialSingBoxOutbound(c.kind, c.config); got != "" {
				t.Fatalf("%s: %q", c.name, got)
			}
		}
		if after := trialSettle(trialGoroutines, goroutines); after > goroutines {
			t.Errorf("%s x10: goroutine %d -> %d", c.name, goroutines, after)
		}
		if after := trialSettle(trialFDs, fds); after > fds {
			t.Errorf("%s x10: fd %d -> %d", c.name, fds, after)
		}
	}
}

// 并发调用（gomobile 可能从多个 Java 线程进来）：结果与串行相同；配合 -race 运行检查数据竞争
func TestTrialConcurrentCalls(t *testing.T) {
	cases := append(trialValidCases(t), trialBadCases(t)...)
	var wg sync.WaitGroup
	errs := make(chan string, 8*len(cases))
	for w := 0; w < 8; w++ {
		wg.Add(1)
		go func(w int) {
			defer wg.Done()
			for i := range cases {
				c := cases[(i+w)%len(cases)]
				if got, want := TrialSingBoxOutbound(c.kind, c.config), expectedWire(c); got != want {
					errs <- fmt.Sprintf("%s: 得到 %q，期望 %q", c.name, got, want)
				}
			}
		}(w)
	}
	wg.Wait()
	close(errs)
	for e := range errs {
		t.Error(e)
	}
}

// ---- 黄金基线 ----

// 已知不过试建的黄金场景（场景名 → 原因）。「正好是这些」：列着的场景若已能通过，测试同样失败，提醒删掉
// 现在没有例外：基线里每个出站都要能过试建。加例外要写明原因，并在原因消除后删掉（下面的测试会提醒）
var trialGoldenKnownFailures = map[string]string{}

// 黄金基线里每份 sing-box.json 的每个出站 / 端点逐个试建，都要通过：试建在模拟器采集时会真实运行，
// 这里在主机上先确认「基线之外不会多出被拦下的节点」
func TestTrialGoldenOutbounds(t *testing.T) {
	root := filepath.Join("..", "app", "src", "test", "resources", "golden", "scenarios")
	files, err := filepath.Glob(filepath.Join(root, "*", "*", "sing-box.json"))
	if err != nil {
		t.Fatal(err)
	}
	if len(files) == 0 {
		t.Fatalf("%s 下没有 sing-box.json（目录挪了？）", root)
	}
	seen := map[string]bool{}
	types := map[string]int{}
	failedKnown := map[string]bool{}
	for _, file := range files {
		content, err := os.ReadFile(file)
		if err != nil {
			t.Fatal(err)
		}
		var config map[string]json.RawMessage
		if err := json.Unmarshal(content, &config); err != nil {
			t.Fatalf("%s: %v", file, err)
		}
		rel, _ := filepath.Rel(root, file)
		scenario := strings.Split(filepath.ToSlash(rel), "/")[0]
		for _, kind := range []string{"outbound", "endpoint"} {
			var items []json.RawMessage
			if raw, ok := config[kind+"s"]; ok {
				if err := json.Unmarshal(raw, &items); err != nil {
					t.Fatalf("%s: %v", file, err)
				}
			}
			for _, item := range items {
				key := kind + "\x00" + string(item)
				if seen[key] {
					continue
				}
				seen[key] = true
				var head struct{ Type string }
				_ = json.Unmarshal(item, &head)
				types[kind+"/"+head.Type]++
				got := TrialSingBoxOutbound(kind, string(item))
				if got == "" {
					continue
				}
				if _, known := trialGoldenKnownFailures[scenario]; known {
					failedKnown[scenario] = true
					continue
				}
				t.Errorf("%s 的 %s/%s 试建失败：%q", rel, kind, head.Type, got)
			}
		}
	}
	for scenario, reason := range trialGoldenKnownFailures {
		if !failedKnown[scenario] {
			t.Errorf("已知例外 %s（%s）现在能通过试建：从 trialGoldenKnownFailures 删掉", scenario, reason)
		}
	}
	names := make([]string, 0, len(types))
	for name := range types {
		names = append(names, name)
	}
	sort.Strings(names)
	var summary []string
	for _, name := range names {
		summary = append(summary, fmt.Sprintf("%s=%d", name, types[name]))
	}
	t.Logf("%d 份 sing-box.json，%d 个不同的出站 / 端点：%s", len(files), len(seen), strings.Join(summary, " "))
}

// ---- 耗时（设计稿 4.1 的方法；不在 ./run lib test 里跑，用 -bench 手动跑） ----

func BenchmarkTrialBaseInstance(b *testing.B) {
	for i := 0; i < b.N; i++ {
		if _, err := newOutboundTrial(); err != nil {
			b.Fatal(err)
		}
	}
}

func BenchmarkTrialSingBoxOutbound(b *testing.B) {
	cert := trialCert(b)
	sets := []struct {
		name string
		kind string
		make func(i int) map[string]any
	}{
		{"vmess+utls+ws", "outbound", func(i int) map[string]any {
			return map[string]any{"type": "vmess", "server": fmt.Sprintf("vm%d.example", i), "server_port": 443, "uuid": trialUUIDValue, "security": "aes-128-gcm", "tls": map[string]any{"enabled": true, "server_name": "trial.example", "utls": map[string]any{"enabled": true, "fingerprint": "chrome"}}, "transport": map[string]any{"type": "ws", "path": "/ws"}}
		}},
		{"trojan+tls", "outbound", func(i int) map[string]any {
			return map[string]any{"type": "trojan", "server": fmt.Sprintf("tj%d.example", i), "server_port": 443, "password": "p", "tls": trialTLS()}
		}},
		{"trojan+tls+cert", "outbound", func(i int) map[string]any {
			return map[string]any{"type": "trojan", "server": fmt.Sprintf("tj%d.example", i), "server_port": 443, "password": "p", "tls": map[string]any{"enabled": true, "server_name": "trial.example", "certificate": cert}}
		}},
		{"ss-2022", "outbound", func(i int) map[string]any {
			return map[string]any{"type": "shadowsocks", "server": fmt.Sprintf("ss%d.example", i), "server_port": 8388, "method": "2022-blake3-aes-256-gcm", "password": trialKey(32)}
		}},
		{"wireguard", "endpoint", func(i int) map[string]any {
			return trialWG(trialKey(32), trialWGPeerConfig(trialKey(32), ""))
		}},
	}
	if got := TrialSingBoxOutbound("outbound", `{"type":"direct"}`); got != "" {
		b.Fatalf("基础实例不可用：%q", got)
	}
	for _, set := range sets {
		inputs := make([]string, 100)
		for i := range inputs {
			inputs[i] = trialJSON(set.make(i))
		}
		b.Run(set.name, func(b *testing.B) {
			for i := 0; i < b.N; i++ {
				if got := TrialSingBoxOutbound(set.kind, inputs[i%len(inputs)]); got != "" {
					b.Fatalf("%q", got)
				}
			}
		})
	}
}
