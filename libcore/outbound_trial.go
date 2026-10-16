package libcore

// 逐出站试建（BUG-001）：整份配置交给 box.New 时，第一个构造失败的出站就让整份配置起不来；
// 构建在规划每一跳时先把内部核心的出站 / 端点单独交给这里试建，坏节点在规划阶段就被跳过或报错。
// 试建只解析与构造：不监听、不拨号、不起后台任务。sing-box 的报错原文会带出证书、密钥、路径等
// 取值，所以原文不出 Go，只回传封闭的类别（见 trialCategory）。

import (
	"context"
	"crypto/x509"
	"encoding/base64"
	"encoding/json"
	"errors"
	"log"
	"regexp"
	"runtime/debug"
	"strings"
	"sync"

	box "github.com/sagernet/sing-box"
	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/adapter/endpoint"
	"github.com/sagernet/sing-box/adapter/outbound"
	C "github.com/sagernet/sing-box/constant"
	sblog "github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing/service"
)

const (
	// 试建对象的占位 tag：构造只要求非空
	trialTag = "neko-trial"
	// 基础实例里唯一的 DNS 服务器；JSON 里写了 domain_resolver 时改指它（构造只查被引用的服务器是否存在）
	trialResolver = "neko-trial-dns"

	trialKindOutbound = "outbound"
	trialKindEndpoint = "endpoint"
)

// trialCategory 是试建失败的类别，即返回给 Kotlin 的 wire 名。Kotlin 的 SingBoxTrialCategory 与下面这张表
// 一一对应：一致性测试按 `trialCategory = "<WIRE>"` 的写法读本文件，新增 / 改名两边一起改。
type trialCategory string

const (
	trialDecode                  trialCategory = "DECODE"
	trialUnknownType             trialCategory = "UNKNOWN_TYPE"
	trialSSKeyBase64             trialCategory = "SS_KEY_BASE64"
	trialSSKeyLength             trialCategory = "SS_KEY_LENGTH"
	trialPasswordMissing         trialCategory = "PASSWORD_MISSING"
	trialSSPlugin                trialCategory = "SS_PLUGIN"
	trialTLSCertificate          trialCategory = "TLS_CERTIFICATE"
	trialTLSCertificateFile      trialCategory = "TLS_CERTIFICATE_FILE"
	trialTLSECHConfig            trialCategory = "TLS_ECH_CONFIG"
	trialTLSReality              trialCategory = "TLS_REALITY"
	trialTLSUTLSFingerprint      trialCategory = "TLS_UTLS_FINGERPRINT"
	trialTLSOptions              trialCategory = "TLS_OPTIONS"
	trialTLSRequired             trialCategory = "TLS_REQUIRED"
	trialSSHPrivateKey           trialCategory = "SSH_PRIVATE_KEY"
	trialSSHPrivateKeyPassphrase trialCategory = "SSH_PRIVATE_KEY_PASSPHRASE"
	trialSSHHostKey              trialCategory = "SSH_HOST_KEY"
	trialWGPrivateKey            trialCategory = "WG_PRIVATE_KEY"
	trialWGPeerPublicKey         trialCategory = "WG_PEER_PUBLIC_KEY"
	trialWGPreSharedKey          trialCategory = "WG_PRE_SHARED_KEY"
	trialWGPeer                  trialCategory = "WG_PEER"
	trialUUID                    trialCategory = "UUID"
	trialVLESSFlow               trialCategory = "VLESS_FLOW"
	trialPacketEncoding          trialCategory = "PACKET_ENCODING"
	trialTransport               trialCategory = "TRANSPORT"
	trialMultiplex               trialCategory = "MULTIPLEX"
	trialHysteria                trialCategory = "HYSTERIA"
	trialValueSet                trialCategory = "VALUE_SET"
	trialDialer                  trialCategory = "DIALER"
	trialOther                   trialCategory = "OTHER"
	// 以下两类是「没有结论」：Kotlin 放行节点、只告警（真实启动照旧是最后一道检查）
	trialUnavailable trialCategory = "UNAVAILABLE"
	trialPanic       trialCategory = "PANIC"
)

// trialResult 是一次试建的结论；category 为空表示通过。path 只给 DECODE，是 sing-box 报错里的字段路径（不含取值）
type trialResult struct {
	category trialCategory
	path     string
}

// wire 是返回给 Kotlin 的文本：通过为空串，否则是类别，DECODE 带路径时为 "DECODE\t<路径>"
func (r trialResult) wire() string {
	if r.path != "" {
		return string(r.category) + "\t" + r.path
	}
	return string(r.category)
}

// TrialSingBoxOutbound 试建一个内部核心的 sing-box 出站或端点（BUG-001）。
// kind 为 "outbound" 或 "endpoint"（由调用方按它在配置里放进 outbounds 还是 endpoints 决定），
// config 是它的 sing-box JSON（单个对象）。只解析与构造：不监听、不拨号、不起后台任务。
// 返回空串表示通过；否则是失败类别（trialCategory 的 wire 名），解析类错误可能为 "DECODE\t<字段路径>"。
// 字段路径只含 snake_case 键名与数组下标，从不含取值；sing-box 的报错原文与传入的 JSON 都不出 Go、不写日志。
// "UNAVAILABLE" / "PANIC" 表示没有结论（基础实例建不起来、种类不认识、构造 panic）。
func TrialSingBoxOutbound(kind string, config string) (result string) {
	defer recoverTrialPanic(&result)

	trialAccess.Lock()
	defer trialAccess.Unlock()
	if trialBase == nil {
		base, err := newOutboundTrial()
		if err != nil {
			// 基础实例的配置是固定的，报错里没有调用方的数据
			log.Println("TrialSingBoxOutbound: base instance unavailable:", err)
			return string(trialUnavailable)
		}
		trialBase = base
	}
	return trialBase.run(kind, []byte(config)).wire()
}

// recoverTrialPanic 把构造中的 panic 换成 PANIC。panic 的值可能带出配置内容，不记；只记调用栈
func recoverTrialPanic(result *string) {
	if r := recover(); r != nil {
		log.Println("TrialSingBoxOutbound: panic during trial (value withheld)\n" + string(debug.Stack()))
		*result = string(trialPanic)
	}
}

var (
	// 串行化全部试建：构造函数没有「在共享上下文上并发构造」的承诺，而构建是顺序规划，不需要并行吞吐
	trialAccess sync.Mutex
	// 进程内复用的基础实例；建失败不记住，下次再建
	trialBase *outboundTrial
)

// outboundTrial 是试建用的上下文：一个只 box.New、从不 Start 的实例（与真实启动同一套服务装配），
// 加上 libcore 自己的出站 / 端点注册表。从不启动，所以不持有 socket、不起后台任务
type outboundTrial struct {
	ctx       context.Context
	router    adapter.Router
	outbounds *outbound.Registry
	endpoints *endpoint.Registry
	logger    sblog.ContextLogger
}

func newOutboundTrial() (*outboundTrial, error) {
	outbounds, endpoints := nekoboxAndroidOutboundRegistry(), nekoboxAndroidEndpointRegistry()
	// 不调 waitAssetsReady：试建不用 geo 资产，也不做 TLS 握手。DNS 本地传输用 sing-box 自带的实现，
	// 基础实例不解析任何东西
	ctx := box.Context(context.Background(),
		nekoboxAndroidInboundRegistry(), outbounds, endpoints,
		nekoboxAndroidDNSTransportRegistry(nil), nekoboxAndroidServiceRegistry(),
		nekoboxAndroidCertificateProviderRegistry(),
	)
	ctx = service.ContextWithDefaultRegistry(ctx)
	// 与 NewSingBoxInstance 相同的平台接口：构造只取接口查找器等结构，不回调 Java
	service.MustRegister[adapter.PlatformInterface](ctx, boxPlatformInterfaceInstance)
	var options option.Options
	err := options.UnmarshalJSONContext(ctx, []byte(`{"log":{"disabled":true},`+
		`"dns":{"servers":[{"type":"udp","tag":"`+trialResolver+`","server":"127.0.0.1"}]}}`))
	if err != nil {
		return nil, err
	}
	instance, err := box.New(box.Options{Options: options, Context: ctx})
	if err != nil {
		return nil, err
	}
	return &outboundTrial{
		ctx:       ctx,
		router:    instance.Router(),
		outbounds: outbounds,
		endpoints: endpoints,
		logger:    sblog.NewNOPFactory().NewLogger("trial"),
	}, nil
}

// run 试建一个出站 / 端点。不经 Manager.Create：省掉 tag 去重与登记，试建对象构造完即可回收
func (t *outboundTrial) run(kind string, raw []byte) trialResult {
	if kind != trialKindOutbound && kind != trialKindEndpoint {
		return trialResult{category: trialUnavailable}
	}
	raw, err := rewriteDomainResolver(raw)
	if err != nil {
		return classifyDecode(err)
	}
	// 与 box.New 给出站 / 端点的上下文相同
	ctx := adapter.WithContext(t.ctx, &adapter.InboundContext{Outbound: trialTag})
	switch kind {
	case trialKindOutbound:
		var o option.Outbound
		if err := o.UnmarshalJSONContext(t.ctx, raw); err != nil {
			return classifyDecode(err)
		}
		if _, err := t.outbounds.CreateOutbound(ctx, t.router, t.logger, trialTag, o.Type, o.Options); err != nil {
			return classifyCreate(err)
		}
		// 出站的构造不占资源，不跑任何 Start 阶段。收尾按调用种类决定、不按接口：anytls 出站结构上也满足
		// adapter.Endpoint，给它跑 Initialize 会建会话客户端、起一个最长 30 秒才退出的空闲检查 goroutine
	case trialKindEndpoint:
		var e option.Endpoint
		if err := e.UnmarshalJSONContext(t.ctx, raw); err != nil {
			return classifyDecode(err)
		}
		if wg, ok := e.Options.(*option.WireGuardEndpointOptions); ok {
			if category := checkWireGuardKeys(wg); category != "" {
				return trialResult{category: category}
			}
		}
		ep, err := t.endpoints.Create(ctx, t.router, t.logger, trialTag, e.Type, e.Options)
		if err != nil {
			return classifyCreate(err)
		}
		// WireGuard 构造时就建了 gVisor 协议栈（约 10 个 goroutine），没启动的实例 Close 什么都不关；
		// 它的 Initialize 阶段只登记 endpoint.Close，跑完随即关 scope 收回
		scope := adapter.NewScope(t.ctx, t.logger)
		_ = ep.Start(adapter.StartStateInitialize, scope)
		_ = scope.Close()
	}
	return trialResult{}
}

// rewriteDomainResolver 把 domain_resolver 引用的 DNS 服务器换成基础实例里的占位服务器，其余字段原样留给解析。
// 它是构建拼出来的跨对象引用（或自定义出站 JSON 里写的服务器 tag），试建的实例里没有那台服务器。
// 键名按 sing-box 解析的方式不区分大小写；形态不对时不改，交给 sing-box 的解析报错
func rewriteDomainResolver(raw []byte) ([]byte, error) {
	var fields map[string]json.RawMessage
	if err := json.Unmarshal(raw, &fields); err != nil {
		return nil, err
	}
	changed := false
	for key, value := range fields {
		if !strings.EqualFold(key, "domain_resolver") {
			continue
		}
		if rewritten, ok := rewriteResolverValue(value); ok {
			fields[key] = rewritten
			changed = true
		}
	}
	if !changed {
		return raw, nil
	}
	return json.Marshal(fields)
}

var trialResolverJSON, _ = json.Marshal(trialResolver)

// rewriteResolverValue 处理 domain_resolver 的两种写法：服务器 tag 字符串，或带 server 的对象
func rewriteResolverValue(value json.RawMessage) (json.RawMessage, bool) {
	var server string
	if json.Unmarshal(value, &server) == nil {
		if server == "" {
			return nil, false
		}
		return trialResolverJSON, true
	}
	var object map[string]json.RawMessage
	if json.Unmarshal(value, &object) != nil {
		return nil, false
	}
	changed := false
	for key, inner := range object {
		if !strings.EqualFold(key, "server") {
			continue
		}
		if json.Unmarshal(inner, &server) != nil || server == "" {
			continue
		}
		object[key] = trialResolverJSON
		changed = true
	}
	if !changed {
		return nil, false
	}
	rewritten, err := json.Marshal(object)
	if err != nil {
		return nil, false
	}
	return rewritten, true
}

// checkWireGuardKeys 补查 WireGuard 的密钥长度。sing-box 构造时只把 base64 解成十六进制，长度要到 Start 里
// wireguard-go 的 IpcSet 才查（device/noise-types.go 的 loadExactHex：私钥、公钥、预共享密钥都要正好 32 字节），
// 而那条启动报错会带出密钥原文。规则与 sing-box 构造一致：标准 base64；私钥为空交给构造报 missing private key，
// 预共享密钥为空表示不用
func checkWireGuardKeys(options *option.WireGuardEndpointOptions) trialCategory {
	if options.PrivateKey != "" && !isWireGuardKey(options.PrivateKey) {
		return trialWGPrivateKey
	}
	for _, peer := range options.Peers {
		if !isWireGuardKey(peer.PublicKey) {
			return trialWGPeerPublicKey
		}
		if peer.PreSharedKey != "" && !isWireGuardKey(peer.PreSharedKey) {
			return trialWGPreSharedKey
		}
	}
	return ""
}

func isWireGuardKey(key string) bool {
	decoded, err := base64.StdEncoding.DecodeString(key)
	return err == nil && len(decoded) == 32
}

// 字段路径的形态：snake_case 键名，以 . 连接，可带数组下标；长度不超过 trialPathMax。不合格就不带路径
var trialPathPattern = regexp.MustCompile(`^[a-z0-9_]+(\.[a-z0-9_]+|\[[0-9]+\])*$`)

// 字段路径的长度上限，与 Kotlin 的 SING_BOX_TRIAL_PATH_MAX 相同（SingBoxTrialCategoryWireTest 读本文件核对）
const trialPathMax = 64

// classifyDecode 给解析（UnmarshalJSONContext）阶段的错误分类。路径取报错开头到第一个 ": " 之前的部分
// （sing-box 的写法是 "<路径>: <原因>"）；"json" 是解码库自己的前缀，不是路径
func classifyDecode(err error) trialResult {
	message := err.Error()
	for _, prefix := range []string{"unknown outbound type", "unknown endpoint type", "dns outbound is deprecated"} {
		if strings.HasPrefix(message, prefix) {
			return trialResult{category: trialUnknownType}
		}
	}
	result := trialResult{category: trialDecode}
	if index := strings.Index(message, ": "); index > 0 {
		path := message[:index]
		if path != "json" && len(path) <= trialPathMax && trialPathPattern.MatchString(path) {
			result.path = path
		}
	}
	return result
}

// trialRule 按报错文本给构造阶段的错误分类：exact 整句相同，prefix 开头相同，contains 含有。
// 文本取自 vendored sing-box 与它的依赖模块；升级后改了文案的会落到 OTHER（仍然拦下，只是不说哪一类），
// 分类测试先红
type trialRule struct {
	category trialCategory
	exact    []string
	prefix   []string
	contains []string
}

func (r trialRule) matches(message string) bool {
	for _, text := range r.exact {
		if message == text {
			return true
		}
	}
	for _, text := range r.prefix {
		if strings.HasPrefix(message, text) {
			return true
		}
	}
	for _, text := range r.contains {
		if strings.Contains(message, text) {
			return true
		}
	}
	return false
}

// 按顺序匹配，先中先得：口令类的 SSH 私钥错误在一般私钥错误之前，TLS 的 kTLS 在拨号器的「only supported on」之前
var trialCreateRules = []trialRule{
	{category: trialSSKeyBase64, prefix: []string{"decode key"}},
	{category: trialSSKeyLength, prefix: []string{"bad key length"}},
	{category: trialPasswordMissing, exact: []string{"missing password"}},
	{category: trialSSPlugin, prefix: []string{
		"unknown obfs mode", "v2ray-plugin: unknown mode", "parse plugin_opts", "parse mux value", "plugin not found",
	}},
	{category: trialTLSCertificate, prefix: []string{"failed to parse certificate"}},
	{category: trialTLSCertificateFile, prefix: []string{"read certificate", "read client certificate", "read client key"}},
	{category: trialTLSECHConfig, prefix: []string{"invalid ECH configs pem", "read ECH config"}},
	{category: trialTLSReality, prefix: []string{
		"decode public_key", "invalid public_key", "decode short_id", "invalid short_id", "uTLS is required by reality",
	}},
	{category: trialTLSUTLSFingerprint, prefix: []string{"unknown uTLS fingerprint"}},
	{category: trialTLSOptions, prefix: []string{
		"missing server_name or insecure=true", "unknown cipher_suite", "parse min_version", "parse max_version",
		"unknown tls version", "certificate_public_key_sha256 is conflict", "client certificate and client key",
		"parse client x509 key pair", "`spoof`", "spoof is unsupported", "kTLS", "unknown tls engine",
		"Reality is conflict with ECH", "disable_sni is unsupported", "legacy ECH options are deprecated",
		"unsupported usage for", "reality is unsupported in", "utls is unsupported in", "ech is unsupported in",
		"cipher_suites is unsupported in", "curve_preferences is unsupported in", "client certificate is unsupported in",
		"tls fragment is unsupported in",
	}},
	{category: trialSSHPrivateKeyPassphrase, contains: []string{
		"this private key is passphrase protected", "decryption password incorrect",
	}},
	{category: trialSSHPrivateKey, prefix: []string{"parse private key", "read private key"}},
	{category: trialSSHHostKey, prefix: []string{"parse host key"}},
	{category: trialWGPrivateKey, prefix: []string{"missing private key", "decode private key"}},
	{category: trialWGPeerPublicKey, prefix: []string{"decode public key for peer"}},
	{category: trialWGPreSharedKey, prefix: []string{"decode pre shared key for peer"}},
	// 本地地址加进 gVisor 协议栈失败时，报错是 "create WireGuard device: parse local address …"
	{category: trialWGPeer, prefix: []string{
		"missing allowed ips for peer", "invalid reserved value for peer", "create WireGuard device",
	}},
	{category: trialUUID, prefix: []string{"invalid uuid"}},
	{category: trialVLESSFlow, prefix: []string{"unsupported flow"}},
	{category: trialPacketEncoding, prefix: []string{"unknown packet encoding"}},
	{category: trialTransport, prefix: []string{"create client transport"}},
	{category: trialMultiplex, prefix: []string{"unknown protocol", "brutal:"}},
	{category: trialHysteria, prefix: []string{
		"missing upload speed", "invalid upload speed", "missing download speed", "invalid download speed",
		"bad port range", "missing obfs password", "unknown obfs type", "gecko:", "unsupported BBR profile",
		"realm", "create realm http client", "invalid IP version", "port mapping requires IPv4",
		"min and max hop interval", "min hop interval", "hop interval must be",
	}},
	{category: trialValueSet, prefix: []string{"unknown method", "unknown congestion control algorithm"},
		contains: []string{"unsupported security type"}},
	{category: trialDialer, prefix: []string{
		"tcp_fast_open is not supported with anytls", "`network_strategy` is conflict", "`detour` is not supported in direct context",
		"`listen_port` is conflict with `detour`", "domain resolver not found", "missing domain resolver",
	}, contains: []string{"is only supported on"}},
}

// classifyCreate 给构造阶段的错误分类。sing-box 的报错原文只在这里读，不外传
func classifyCreate(err error) trialResult {
	if errors.Is(err, C.ErrTLSRequired) {
		return trialResult{category: trialTLSRequired}
	}
	if errors.Is(err, x509.IncorrectPasswordError) {
		return trialResult{category: trialSSHPrivateKeyPassphrase}
	}
	message := err.Error()
	for _, rule := range trialCreateRules {
		if rule.matches(message) {
			return trialResult{category: rule.category}
		}
	}
	return trialResult{category: trialOther}
}
