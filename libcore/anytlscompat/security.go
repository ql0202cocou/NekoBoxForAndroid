package anytlscompat

import (
	"bytes"
	"context"
	"crypto/sha256"
	"crypto/x509"
	"encoding/base64"
	"encoding/hex"
	"encoding/pem"
	"errors"
	"net"
	"strings"
	"time"

	tls "github.com/metacubex/tls"
	utls "github.com/metacubex/utls"
	C "github.com/sagernet/sing-box/constant"
)

var (
	ErrPin         = errors.New("certificate pin mismatch")
	ErrFingerprint = errors.New("invalid certificate fingerprint")
	// 客户端（uTLS）指纹名不可用；ErrFingerprint 指的是证书固定值，两者不混用。
	ErrClientFingerprint = errors.New("unsupported client fingerprint")
	ErrECH               = errors.New("ECH configuration unavailable")
)

// 安全层只封装调用方提供的连接，不自行拨号或使用进程级信任配置。
type SecurityOptions struct {
	ServerName        string
	VerifyName        string
	ALPN              []string
	Roots             *x509.CertPool
	Time              func() time.Time
	AllowInsecure     bool
	Fingerprint       string
	ClientFingerprint string
	ECH               ECHOptions
}

type ECHOptions struct {
	Enabled         bool
	Config          string
	QueryServerName string
	// 握手直接使用并可能保留返回的切片，不再复制；实现每次都必须返回新分配的切片（HTTPSLookup 即如此）。
	Lookup func(context.Context, string) ([]byte, error)
}

type Security struct {
	options SecurityOptions
	verify  func([]*x509.Certificate, string) error
	hello   *utls.ClientHelloID
	ech     []byte
}

// 显式 SHA-256 优先于旧节点 PEM；旧字段只能在明确选择固定策略时使用。
func LegacyPin(explicit string, certificatesPEM []byte) (string, error) {
	if explicit != "" {
		if _, err := parsePin(explicit); err != nil {
			return "", err
		}
		return explicit, nil
	}
	block, _ := pem.Decode(certificatesPEM)
	if block == nil || block.Type != "CERTIFICATE" {
		return "", ErrFingerprint
	}
	if _, err := x509.ParseCertificate(block.Bytes); err != nil {
		return "", ErrFingerprint
	}
	hash := sha256.Sum256(block.Bytes)
	return hex.EncodeToString(hash[:]), nil
}

func NewSecurity(options SecurityOptions) (*Security, error) {
	options.ALPN = append([]string(nil), options.ALPN...)
	if options.Roots != nil {
		options.Roots = options.Roots.Clone()
	}
	if options.Time == nil {
		options.Time = time.Now
	}
	s := &Security{options: options}
	if options.Fingerprint != "" {
		digest, err := parsePin(options.Fingerprint)
		if err != nil {
			return nil, err
		}
		s.verify = pinVerifier(digest, options.Time)
		if options.VerifyName != "" {
			// 验证域名覆盖握手 SNI；在构造时一次折进校验函数，握手时不再另建闭包。
			base, name := s.verify, options.VerifyName
			s.verify = func(certs []*x509.Certificate, _ string) error { return base(certs, name) }
		}
	} else if options.VerifyName != "" {
		roots, name, now := options.Roots, options.VerifyName, options.Time
		s.verify = func(certs []*x509.Certificate, _ string) error {
			return verifyChain(certs, roots, name, now())
		}
	}
	var err error
	s.hello, err = fingerprint(options.ClientFingerprint)
	if err != nil {
		return nil, err
	}
	if options.ECH.Enabled {
		if options.ECH.Config != "" {
			s.ech, err = base64.StdEncoding.DecodeString(options.ECH.Config)
			if err != nil || len(s.ech) == 0 {
				return nil, ErrECH
			}
		} else if options.ECH.Lookup == nil {
			return nil, ErrECH
		}
	}
	return s, nil
}

// parsePin 把整证书 SHA-256 固定值解成摘要：去掉首尾空白和冒号分隔，长度必须正好 32 字节。
func parsePin(value string) ([]byte, error) {
	digest, err := hex.DecodeString(strings.TrimSpace(strings.ReplaceAll(value, ":", "")))
	if err != nil || len(digest) != sha256.Size {
		return nil, ErrFingerprint
	}
	return digest, nil
}

// 固定语义源自 mihomo v1.19.33 component/ca/fingerprint.go，许可见 NOTICE.md。
func pinVerifier(digest []byte, now func() time.Time) func([]*x509.Certificate, string) error {
	return func(certs []*x509.Certificate, name string) error {
		for i, cert := range certs {
			hash := sha256.Sum256(cert.Raw)
			if !bytes.Equal(digest, hash[:]) {
				continue
			}
			if i == 0 {
				return nil
			}
			roots := x509.NewCertPool()
			roots.AddCert(cert)
			return verifyChain(certs[:i+1], roots, name, now())
		}
		return ErrPin
	}
}

func verifyChain(certs []*x509.Certificate, roots *x509.CertPool, name string, now time.Time) error {
	if len(certs) == 0 {
		return ErrPin
	}
	intermediates := x509.NewCertPool()
	for _, cert := range certs[1:] {
		intermediates.AddCert(cert)
	}
	_, err := certs[0].Verify(x509.VerifyOptions{Roots: roots, Intermediates: intermediates, DNSName: name, CurrentTime: now})
	return err
}

// 任一配置、查询或握手错误都关闭传入连接；不会降级重试明文 ClientHello。
func (s *Security) Handshake(ctx context.Context, raw net.Conn) (conn net.Conn, err error) {
	defer func() {
		if err != nil {
			raw.Close()
		}
	}()
	if err = ctx.Err(); err != nil {
		return
	}
	ctx, cancel := context.WithTimeout(ctx, C.TCPTimeout)
	defer cancel()
	o := &s.options
	ech := s.ech
	if o.ECH.Enabled && o.ECH.Config == "" {
		name := o.ECH.QueryServerName
		if name == "" {
			name = o.ServerName
		}
		ech, err = o.ECH.Lookup(ctx, name)
		if err != nil {
			return
		}
		if len(ech) == 0 {
			err = ErrECH
			return
		}
	}
	// metacubex/tls v0.1.9 与 utls v1.8.8 只读 NextProtos 和 ECH 列表，握手时都不再复制（ALPN 在构造时已复制）；
	// uTLS 会给 cfg.NextProtos 重新赋值但不写入原切片，所以 Config 仍按每次握手新建、不共享。
	var echAccepted bool
	if s.hello == nil {
		cfg := &tls.Config{ServerName: o.ServerName, NextProtos: o.ALPN, RootCAs: o.Roots,
			Time: o.Time, InsecureSkipVerify: o.AllowInsecure, EncryptedClientHelloConfigList: ech}
		if s.verify != nil {
			cfg.InsecureSkipVerify = true
			cfg.VerifyConnection = func(state tls.ConnectionState) error { return s.verify(state.PeerCertificates, state.ServerName) }
		}
		tc := tls.Client(raw, cfg)
		if err = tc.HandshakeContext(ctx); err != nil {
			return
		}
		conn, echAccepted = tc, tc.ConnectionState().ECHAccepted
	} else {
		cfg := &utls.Config{ServerName: o.ServerName, NextProtos: o.ALPN, RootCAs: o.Roots,
			Time: o.Time, InsecureSkipVerify: o.AllowInsecure, EncryptedClientHelloConfigList: ech}
		if s.verify != nil {
			cfg.InsecureSkipVerify = true
			cfg.VerifyConnection = func(state utls.ConnectionState) error { return s.verify(state.PeerCertificates, state.ServerName) }
		}
		tc, buildErr := clientWithALPN(raw, cfg, *s.hello, o.ALPN)
		if buildErr != nil {
			return nil, buildErr
		}
		if err = tc.HandshakeContext(ctx); err != nil {
			return
		}
		conn, echAccepted = tc, tc.ConnectionState().ECHAccepted
	}
	if o.ECH.Enabled && !echAccepted {
		err = ErrECH
		conn = nil
	}
	return
}

// 有显式 ALPN 时在每条连接自己的模板上设置扩展，外层和 ECH 内层使用同一顺序。
func clientWithALPN(raw net.Conn, cfg *utls.Config, id utls.ClientHelloID, protocols []string) (*utls.UConn, error) {
	if len(protocols) == 0 {
		return utls.UClient(raw, cfg, id), nil
	}
	spec, err := utls.UTLSIdToSpec(id)
	if err != nil {
		return nil, err
	}
	var alpn *utls.ALPNExtension
	for _, ext := range spec.Extensions {
		if v, ok := ext.(*utls.ALPNExtension); ok {
			alpn = v
			break
		}
	}
	if alpn == nil {
		alpn = &utls.ALPNExtension{}
		spec.Extensions = append(spec.Extensions, alpn)
	}
	alpn.AlpnProtocols = protocols
	conn := utls.UClient(raw, cfg, utls.HelloCustom)
	return conn, conn.ApplyPreset(&spec)
}
