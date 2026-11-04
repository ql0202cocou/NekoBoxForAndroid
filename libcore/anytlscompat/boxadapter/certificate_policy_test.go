package boxadapter

import (
	"context"
	"crypto/tls"
	"net"
	"testing"
	"time"

	boxtls "github.com/sagernet/sing-box/common/tls"
	"github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing-box/option"
	compat "libcore/anytlscompat"
	"libcore/internal/anytlstest"
)

// 对照旧 sing-box 的真实 TLS 构造器，不能用新的固定校验器充当信任策略的参照。
func TestCompatibilityCertificateTrustPolicy(t *testing.T) {
	pki := anytlstest.NewPKI(t, "leaf.test")
	valid := anytlstest.TLSCertificate(t, pki.Leaf, pki.Intermediate)
	expired := anytlstest.TLSCertificate(t, pki.Expired, pki.Intermediate)
	cases := []struct {
		name, pem, serverName string
		pair                  tls.Certificate
		insecure, want        bool
	}{
		{"root_not_sent", pki.Root.CertPEM, "leaf.test", valid, false, true},
		{"multiple_roots", pki.Unrelated.CertPEM + pki.Root.CertPEM, "leaf.test", valid, false, true},
		{"wrong_name_with_leaf", pki.Leaf.CertPEM, "wrong.test", valid, false, false},
		{"expired_leaf", pki.Expired.CertPEM, "leaf.test", expired, false, false},
		{"wrong_name_with_ca", pki.Root.CertPEM, "wrong.test", valid, false, false},
		{"unrelated_ca", pki.Unrelated.CertPEM, "leaf.test", valid, false, false},
		{"insecure_preserved", pki.Root.CertPEM, "wrong.test", expired, true, true},
	}
	for _, fp := range []string{"", "chrome", "firefox"} {
		for _, tc := range cases {
			t.Run(fp+"/"+tc.name, func(t *testing.T) {
				old, err := boxtls.NewClient(context.Background(), log.NewNOPFactory().NewLogger("test"), "leaf.test", option.OutboundTLSOptions{
					Enabled: true, ServerName: tc.serverName, Insecure: tc.insecure, Certificate: []string{tc.pem},
					UTLS: &option.OutboundUTLSOptions{Enabled: fp != "", Fingerprint: fp},
				})
				if err != nil {
					t.Fatal(err)
				}
				security := compat.SecurityOptions{ServerName: tc.serverName, AllowInsecure: tc.insecure, ClientFingerprint: fp}
				if err = applyCertificatePolicy(&security, &TLSConfig{Certificate: tc.pem, CertificatePolicy: "trust"}); err != nil {
					t.Fatal(err)
				}
				current, err := compat.NewSecurity(security)
				if err != nil {
					t.Fatal(err)
				}
				before := policyHandshake(t, tc.pair, func(ctx context.Context, raw net.Conn) error {
					c, err := old.Client(raw)
					if err != nil {
						return err
					}
					return c.HandshakeContext(ctx)
				})
				after := policyHandshake(t, tc.pair, func(ctx context.Context, raw net.Conn) error { _, err := current.Handshake(ctx, raw); return err })
				if before != tc.want || after != before {
					t.Fatalf("legacy=%v migrated=%v want=%v", before, after, tc.want)
				}
			})
		}
	}
	// 固定策略仍保留 mihomo 的叶子匹配语义，显式指纹在信任策略下同样优先。
	for _, policy := range []string{"pin", "trust"} {
		security := compat.SecurityOptions{ServerName: "wrong.test"}
		config := &TLSConfig{Certificate: pki.Expired.CertPEM, CertificatePolicy: policy}
		if policy == "trust" {
			config.Fingerprint = anytlstest.Pin(pki.Expired)
			config.Certificate = "invalid ignored PEM"
		}
		if err := applyCertificatePolicy(&security, config); err != nil {
			t.Fatal(err)
		}
		current, err := compat.NewSecurity(security)
		if err != nil {
			t.Fatal(err)
		}
		if !policyHandshake(t, expired, func(ctx context.Context, raw net.Conn) error { _, err := current.Handshake(ctx, raw); return err }) {
			t.Fatal("pin semantics changed")
		}
	}
	for _, config := range []*TLSConfig{{CertificatePolicy: "unknown"}, {CertificatePolicy: "trust", Certificate: "invalid PEM"}} {
		if err := applyCertificatePolicy(&compat.SecurityOptions{}, config); err == nil {
			t.Fatal("invalid certificate policy accepted")
		}
	}
}

func policyHandshake(t *testing.T, pair tls.Certificate, handshake func(context.Context, net.Conn) error) bool {
	t.Helper()
	client, server := net.Pipe()
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	done := make(chan struct{})
	go func() {
		defer close(done)
		defer server.Close()
		_ = tls.Server(server, &tls.Config{Certificates: []tls.Certificate{pair}}).HandshakeContext(ctx)
	}()
	err := handshake(ctx, client)
	client.Close()
	<-done
	return err == nil
}
