package boxadapter

import (
	"context"
	"encoding/base64"
	"encoding/binary"
	"encoding/pem"
	"errors"
	"strings"

	compat "libcore/anytlscompat"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/adapter/outbound"
	"github.com/sagernet/sing-box/common/dialer"
	C "github.com/sagernet/sing-box/constant"
	"github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing/common/json/badoption"
	"github.com/sagernet/sing/common/ntp"
	"github.com/sagernet/sing/service"
)

// 私有配置只接受已经验证的字段，避免把未知的上游 TLS 选项静默忽略。
type Config struct {
	option.DialerOptions
	option.ServerOptions
	Password string     `json:"password"`
	TLS      *TLSConfig `json:"tls"`
}

type TLSConfig struct {
	Enabled     bool                        `json:"enabled"`
	ServerName  string                      `json:"server_name,omitempty"`
	Insecure    bool                        `json:"insecure,omitempty"`
	ALPN        badoption.Listable[string]  `json:"alpn,omitempty"`
	Certificate string                      `json:"certificate,omitempty"`
	Fingerprint string                      `json:"certificate_fingerprint,omitempty"`
	UTLS        *option.OutboundUTLSOptions `json:"utls,omitempty"`
	ECH         *ECHConfig                  `json:"ech,omitempty"`
}

type ECHConfig struct {
	Enabled bool                       `json:"enabled"`
	Config  badoption.Listable[string] `json:"config,omitempty"`
}

// 构造失败的哨兵错误，libcore 的 trial 用 errors.Is 分类；文案沿用 sing-box 同类报错的写法，不带用户取值。
var (
	ErrUTLSFingerprint = errors.New("unknown uTLS fingerprint")
	ErrCertificatePin  = errors.New("failed to parse certificate pin")
	ErrECHConfig       = errors.New("invalid ECH configs pem")
	ErrTLSOptions      = errors.New("unsupported usage for compatibility ECH fingerprint")
)

func RegisterOutbound(registry *outbound.Registry) {
	outbound.Register[Config](registry, Type, NewOutbound)
}

func NewOutbound(ctx context.Context, _ adapter.Router, _ log.ContextLogger, tag string, config Config) (adapter.Outbound, error) {
	if config.TLS == nil || !config.TLS.Enabled {
		return nil, C.ErrTLSRequired
	}
	server := config.ServerOptions.Build()
	if !server.IsValid() || config.ServerPort == 0 {
		return nil, errors.New("invalid server address")
	}
	t := config.TLS
	security := compat.SecurityOptions{ServerName: t.ServerName, ALPN: t.ALPN, AllowInsecure: t.Insecure,
		Roots: adapter.RootPoolFromContext(ctx), Time: ntp.TimeFuncFromContext(ctx)}
	if security.ServerName == "" {
		security.ServerName = config.Server
	}
	if t.Fingerprint != "" || t.Certificate != "" {
		pin, err := compat.LegacyPin(t.Fingerprint, []byte(t.Certificate))
		if err != nil {
			return nil, ErrCertificatePin
		}
		security.Fingerprint = pin
	}
	if t.UTLS != nil && t.UTLS.Enabled {
		security.ClientFingerprint = t.UTLS.Fingerprint
	}
	options := Options{Server: server, DialerOptions: config.DialerOptions, Password: config.Password}
	if t.ECH != nil && t.ECH.Enabled {
		// 只开放真实对照中成功的确定性组合；random 的选择可能落到不支持 ECH 的模板。
		if !compat.ECHFingerprintAllowed(security.ClientFingerprint) {
			return nil, ErrTLSOptions
		}
		security.ECH.Enabled = true
		if len(t.ECH.Config) > 0 {
			block, rest := pem.Decode([]byte(strings.Join(t.ECH.Config, "\n")))
			if block == nil || block.Type != "ECH CONFIGS" || len(strings.TrimSpace(string(rest))) != 0 || !validECHList(block.Bytes) {
				return nil, ErrECHConfig
			}
			security.ECH.Config = base64.StdEncoding.EncodeToString(block.Bytes)
		} else {
			options.DNSRouter = service.FromContext[adapter.DNSRouter](ctx)
			if options.DNSRouter == nil {
				return nil, errors.New("read ECH config: DNS router unavailable")
			}
			// 与服务器域名共用 domain_resolver，包括组 DNS；不调用系统解析器。
			var err error
			options.DNSOptions, err = dialer.NewDNSQueryOptions(ctx, config.DomainResolver, false)
			if err != nil {
				return nil, err
			}
		}
	}
	options.TLS = security
	result, err := New(ctx, tag, options)
	if errors.Is(err, compat.ErrClientFingerprint) {
		return nil, ErrUTLSFingerprint
	}
	return result, err
}

// trial 只检查容器边界；密码学参数仍由 TLS 握手完整校验，失败不会降级。
func validECHList(data []byte) bool {
	if len(data) < 6 || int(binary.BigEndian.Uint16(data)) != len(data)-2 {
		return false
	}
	for data = data[2:]; len(data) > 0; {
		if len(data) < 4 {
			return false
		}
		n := int(binary.BigEndian.Uint16(data[2:]))
		if n == 0 || n > len(data)-4 {
			return false
		}
		data = data[4+n:]
	}
	return true
}
