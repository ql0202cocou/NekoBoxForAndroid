package anytlscompat

import (
	stdtls "crypto/tls"
	"testing"
	"time"
)

func TestECHAdditionalProfiles(t *testing.T) {
	mihomoBinary(t)
	p := newPKI(t)
	ech := newECH(t, 1)
	for _, fp := range []string{"safari", "ios", "android", "edge", "360", "qq", "chrome120", "firefox120", "safari16", "chrome_psk_shuffle", "chrome_pq", "random"} {
		t.Run(fp, func(t *testing.T) {
			cfg := &stdtls.Config{Certificates: []stdtls.Certificate{tlsCertificate(t, p.leaf)}, EncryptedClientHelloKeys: []stdtls.EncryptedClientHelloKey{ech.key}}
			ps, os := startServer(t, cfg, nil), startServer(t, cfg, nil)
			opts := SecurityOptions{ServerName: testName, Fingerprint: pin(p.leaf), ClientFingerprint: fp, ECH: ECHOptions{Enabled: true, Config: ech.encoded()}}
			oracleOptions := opts
			if fp == "random" {
				// 两个独立进程的随机选择不能直接逐次相等；用本次实际选中的模板做对照。
				hello, _ := fingerprint(fp)
				for name, id := range fingerprints {
					if (name == "chrome" || name == "firefox" || name == "safari" || name == "ios") && id.Str() == hello.Str() {
						oracleOptions.ClientFingerprint = name
						break
					}
				}
				if oracleOptions.ClientFingerprint == "random" {
					t.Fatal("unknown weighted random template")
				}
			}
			want := oracleOptions.ClientFingerprint == "chrome120" || oracleOptions.ClientFingerprint == "firefox120" || oracleOptions.ClientFingerprint == "chrome" || oracleOptions.ClientFingerprint == "firefox"
			a, b := prototypeExchange(t, ps.addr, opts), oracleExchange(t, os.addr, oracleOptions, "")
			if (a == nil) != want || (b == nil) != want {
				t.Fatalf("ECH compatibility differs: prototype=%v mihomo=%v", a, b)
			}
			t.Logf("ECH acceptance: prototype=%v mihomo=%v", a == nil, b == nil)
			if a == nil {
				for _, s := range []*localServer{ps, os} {
					select {
					case state := <-s.states:
						if !state.ECHAccepted {
							t.Fatal("successful connection silently omitted ECH")
						}
					case <-time.After(time.Second):
						t.Fatal("missing ECH state")
					}
				}
			}
		})
	}
}
