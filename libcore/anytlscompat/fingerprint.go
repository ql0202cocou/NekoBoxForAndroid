package anytlscompat

import (
	"fmt"
	"math/rand/v2"
	"slices"
	"sync"

	utls "github.com/metacubex/utls"
)

// 名称映射与权重来自 mihomo v1.19.33 component/tls/utls.go；不映射到近似模板。
var fingerprints = map[string]utls.ClientHelloID{
	"chrome": utls.HelloChrome_Auto, "firefox": utls.HelloFirefox_Auto,
	"safari": utls.HelloSafari_Auto, "ios": utls.HelloIOS_Auto,
	"android": utls.HelloAndroid_11_OkHttp, "edge": utls.HelloEdge_Auto,
	"360": utls.Hello360_Auto, "qq": utls.HelloQQ_Auto,
	"chrome120": utls.HelloChrome_120, "firefox120": utls.HelloFirefox_120, "safari16": utls.HelloSafari_16_0,
	"chrome_psk_shuffle": utls.HelloChrome_106_Shuffle,
	"chrome_pq":          utls.HelloChrome_115_PQ,
}

// 与 app 侧 CoreCapabilityValues.kt 的 ANYTLS_COMPAT_ECH_FINGERPRINTS 保持一致（由 app 的 wire 测试核对）。
var echFingerprints = []string{"none", "chrome", "firefox", "chrome120", "firefox120"}

// ECHFingerprintAllowed 报告该客户端指纹名是否属于实测可用的 ECH 组合；空串表示标准 TLS。
func ECHFingerprintAllowed(name string) bool {
	return name == "" || slices.Contains(echFingerprints, name)
}

var randomFingerprint = sync.OnceValue(func() utls.ClientHelloID {
	names := []string{"chrome", "chrome", "chrome", "chrome", "chrome", "chrome", "safari", "safari", "safari", "ios", "ios", "firefox"}
	return fingerprints[names[rand.IntN(len(names))]]
})

func fingerprint(name string) (*utls.ClientHelloID, error) {
	// 与产品现有拒绝表保持一致；样机不放开不稳定或必败模板。
	switch name {
	case "chrome_psk", "chrome_pq_psk", "chrome_padding_psk_shuffle", "randomized":
		return nil, fmt.Errorf("%w: blocked by the existing compatibility policy", ErrClientFingerprint)
	}
	if name == "" || name == "none" {
		return nil, nil
	}
	if name == "random" {
		id := randomFingerprint()
		return &id, nil
	}
	id, ok := fingerprints[name]
	if !ok {
		return nil, ErrClientFingerprint
	}
	return &id, nil
}
