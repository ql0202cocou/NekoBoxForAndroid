// Package anytlstest 是 AnyTLS 兼容测试在 libcore 根包与 anytlscompat 之间共用的夹具。
// 只能从 _test.go 引用：gomobile 绑定的根包不链接它，AAR 里也就没有这些测试代码。
package anytlstest

import (
	"bytes"
	"context"
	"crypto/ecdh"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/sha256"
	stdtls "crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"encoding/pem"
	"fmt"
	"io"
	"math/big"
	"net"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"golang.org/x/crypto/cryptobyte"
)

type Certificate struct {
	Parsed          *x509.Certificate
	Key             *ecdsa.PrivateKey
	CertPEM, KeyPEM string
}
type PKI struct{ Root, Intermediate, Leaf, Expired, Unrelated Certificate }

func NewCertificate(t testing.TB, name string, parent *Certificate, ca, expired bool) Certificate {
	t.Helper()
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	serial, err := rand.Int(rand.Reader, new(big.Int).Lsh(big.NewInt(1), 120))
	if err != nil {
		t.Fatal(err)
	}
	now := time.Now()
	template := &x509.Certificate{SerialNumber: serial, Subject: pkix.Name{CommonName: name}, DNSNames: []string{name, "public.test"},
		NotBefore: now.Add(-24 * time.Hour), NotAfter: now.Add(24 * time.Hour), BasicConstraintsValid: true, IsCA: ca,
		KeyUsage: x509.KeyUsageDigitalSignature, ExtKeyUsage: []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth}}
	if ca {
		template.KeyUsage |= x509.KeyUsageCertSign
	}
	if expired {
		template.NotAfter = now.Add(-time.Hour)
	}
	signer, parentCert := key, template
	if parent != nil {
		signer, parentCert = parent.Key, parent.Parsed
	}
	der, err := x509.CreateCertificate(rand.Reader, template, parentCert, &key.PublicKey, signer)
	if err != nil {
		t.Fatal(err)
	}
	parsed, err := x509.ParseCertificate(der)
	if err != nil {
		t.Fatal(err)
	}
	keyDER, err := x509.MarshalPKCS8PrivateKey(key)
	if err != nil {
		t.Fatal(err)
	}
	return Certificate{parsed, key, string(pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: der})),
		string(pem.EncodeToMemory(&pem.Block{Type: "PRIVATE KEY", Bytes: keyDER}))}
}

// NewPKI 生成根、中间、叶子、过期叶子和无关根；两张叶子证书都签给 leafName。
func NewPKI(t testing.TB, leafName string) PKI {
	root := NewCertificate(t, "test-root", nil, true, false)
	intermediate := NewCertificate(t, "test-intermediate", &root, true, false)
	return PKI{root, intermediate, NewCertificate(t, leafName, &intermediate, false, false),
		NewCertificate(t, leafName, &intermediate, false, true), NewCertificate(t, "unrelated", nil, true, false)}
}

func Pin(c Certificate) string           { h := sha256.Sum256(c.Parsed.Raw); return hex.EncodeToString(h[:]) }
func Roots(c Certificate) *x509.CertPool { p := x509.NewCertPool(); p.AddCert(c.Parsed); return p }

func TLSCertificate(t testing.TB, leaf Certificate, chain ...Certificate) stdtls.Certificate {
	t.Helper()
	p := leaf.CertPEM
	for _, c := range chain {
		p += c.CertPEM
	}
	cert, err := stdtls.X509KeyPair([]byte(p), []byte(leaf.KeyPEM))
	if err != nil {
		t.Fatal(err)
	}
	return cert
}

type ECHFixture struct {
	List []byte
	Key  stdtls.EncryptedClientHelloKey
	PEM  string
}

// 生成一次性测试密钥，格式依据 mihomo v1.19.33 component/ech/key.go。
func NewECH(t testing.TB, id uint8) ECHFixture {
	t.Helper()
	k, err := ecdh.X25519().GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	b := cryptobyte.NewBuilder(nil)
	b.AddUint16(0xfe0d)
	b.AddUint16LengthPrefixed(func(b *cryptobyte.Builder) {
		b.AddUint8(id)
		b.AddUint16(0x20)
		b.AddUint16LengthPrefixed(func(b *cryptobyte.Builder) { b.AddBytes(k.PublicKey().Bytes()) })
		b.AddUint16LengthPrefixed(func(b *cryptobyte.Builder) { b.AddUint16(1); b.AddUint16(1) })
		b.AddUint8(0)
		b.AddUint8LengthPrefixed(func(b *cryptobyte.Builder) { b.AddBytes([]byte("public.test")) })
		b.AddUint16(0)
	})
	config := b.BytesOrPanic()
	l := cryptobyte.NewBuilder(nil)
	l.AddUint16LengthPrefixed(func(b *cryptobyte.Builder) { b.AddBytes(config) })
	p := cryptobyte.NewBuilder(nil)
	p.AddUint16LengthPrefixed(func(b *cryptobyte.Builder) { b.AddBytes(k.Bytes()) })
	p.AddUint16LengthPrefixed(func(b *cryptobyte.Builder) { b.AddBytes(config) })
	return ECHFixture{l.BytesOrPanic(), stdtls.EncryptedClientHelloKey{Config: config, PrivateKey: k.Bytes(), SendAsRetry: true},
		string(pem.EncodeToMemory(&pem.Block{Type: "ECH KEYS", Bytes: p.BytesOrPanic()}))}
}

func (e ECHFixture) Encoded() string { return base64.StdEncoding.EncodeToString(e.List) }

func FreePort(t testing.TB) int {
	t.Helper()
	l, e := net.Listen("tcp", "127.0.0.1:0")
	if e != nil {
		t.Fatal(e)
	}
	p := l.Addr().(*net.TCPAddr).Port
	l.Close()
	return p
}

func MihomoBinary(t testing.TB) string {
	t.Helper()
	bin := os.Getenv("MIHOMO_BIN")
	if bin == "" {
		if os.Getenv("REQUIRE_MIHOMO") == "1" {
			t.Fatal("MIHOMO_BIN required")
		}
		t.Skip("set MIHOMO_BIN for pinned mihomo integration tests")
	}
	b, err := exec.Command(bin, "-v").CombinedOutput()
	if err != nil || !strings.Contains(string(b), "Mihomo Meta v1.19.33 ") {
		t.Fatalf("expected mihomo v1.19.33, got %q (%v)", b, err)
	}
	return bin
}

func StartMihomo(t testing.TB, config map[string]any) {
	t.Helper()
	bin := MihomoBinary(t)
	dir := t.TempDir()
	healthEcho, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { healthEcho.Close() })
	go func() {
		for {
			c, err := healthEcho.Accept()
			if err != nil {
				return
			}
			go func() { defer c.Close(); c.SetDeadline(time.Now().Add(time.Second)); io.Copy(c, c) }()
		}
	}()
	healthPort := FreePort(t)
	listeners, _ := config["listeners"].([]any)
	config["listeners"] = append(listeners, map[string]any{"name": "health", "type": "tunnel", "listen": "127.0.0.1", "port": healthPort,
		"network": []string{"tcp"}, "target": healthEcho.Addr().String(), "proxy": "DIRECT"})
	config["log-level"] = "debug"
	config["mode"] = "rule"
	config["rules"] = []string{"MATCH,REJECT"}
	data, err := json.Marshal(config)
	if err != nil {
		t.Fatal(err)
	}
	path := filepath.Join(dir, "config.json")
	if err = os.WriteFile(path, data, 0600); err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithCancel(context.Background())
	cmd := exec.CommandContext(ctx, bin, "-d", dir, "-f", path)
	var output bytes.Buffer
	cmd.Stdout = &output
	cmd.Stderr = &output
	if err = cmd.Start(); err != nil {
		cancel()
		t.Fatal(err)
	}
	done := make(chan error, 1)
	go func() { done <- cmd.Wait() }()
	t.Cleanup(func() {
		cancel()
		select {
		case <-done:
		case <-time.After(5 * time.Second):
			t.Error("mihomo did not exit")
		}
		if t.Failed() {
			t.Logf("mihomo test process: %s", output.String())
		}
	})
	address := fmt.Sprintf("127.0.0.1:%d", healthPort)
	deadline := time.Now().Add(5 * time.Second)
	for time.Now().Before(deadline) {
		conn, e := net.DialTimeout("tcp", address, 30*time.Millisecond)
		if e == nil {
			// 监听早于 mihomo 的 Running 状态；实际穿过 DIRECT 路由才算就绪。
			conn.SetDeadline(time.Now().Add(100 * time.Millisecond))
			_, writeErr := conn.Write([]byte("ready"))
			var reply [5]byte
			_, readErr := io.ReadFull(conn, reply[:])
			conn.Close()
			if writeErr == nil && readErr == nil && string(reply[:]) == "ready" {
				return
			}
		}
		select {
		case err := <-done:
			done <- err
			t.Fatalf("mihomo startup failed: %v\n%s", err, output.String())
		default:
		}
		time.Sleep(10 * time.Millisecond)
	}
	t.Fatal("mihomo listener readiness timed out")
}
