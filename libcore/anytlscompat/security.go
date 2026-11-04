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
)

var (
	ErrPin         = errors.New("certificate pin mismatch")
	ErrFingerprint = errors.New("invalid certificate fingerprint")
	ErrECH         = errors.New("ECH configuration unavailable")
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
	Lookup          func(context.Context, string) ([]byte, error)
}

type Security struct {
	options SecurityOptions
	verify  func([]*x509.Certificate, string) error
	hello   *utls.ClientHelloID
	ech     []byte
}

type TLSState struct {
	ServerName  string
	ALPN        string
	Version     uint16
	ECHAccepted bool
}

// 显式 SHA-256 优先于旧节点 PEM；旧字段只能在明确选择固定策略时使用。
func LegacyPin(explicit string, certificatesPEM []byte) (string, error) {
	if explicit != "" {
		if _, err := pinVerifier(explicit, nil); err != nil {
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
	var err error
	if options.Fingerprint != "" {
		s.verify, err = pinVerifier(options.Fingerprint, options.Time)
		if err != nil {
			return nil, err
		}
	} else if options.VerifyName != "" {
		s.verify = func(certs []*x509.Certificate, _ string) error {
			return verifyChain(certs, options.Roots, options.VerifyName, options.Time())
		}
	}
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

// 固定语义源自 mihomo v1.19.33 component/ca/fingerprint.go，许可见 NOTICE.md。
func pinVerifier(value string, now func() time.Time) (func([]*x509.Certificate, string) error, error) {
	digest, err := hex.DecodeString(strings.TrimSpace(strings.ReplaceAll(value, ":", "")))
	if err != nil || len(digest) != sha256.Size {
		return nil, ErrFingerprint
	}
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
			var current time.Time
			if now != nil {
				current = now()
			}
			return verifyChain(certs[:i+1], roots, name, current)
		}
		return ErrPin
	}, nil
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
func (s *Security) Handshake(ctx context.Context, raw net.Conn) (conn net.Conn, state TLSState, err error) {
	defer func() {
		if err != nil {
			raw.Close()
		}
	}()
	if err = ctx.Err(); err != nil {
		return
	}
	o := s.options
	ech := append([]byte(nil), s.ech...)
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
		ech = append([]byte(nil), ech...)
	}
	verify := func(certs []*x509.Certificate, name string) error {
		if o.VerifyName != "" {
			name = o.VerifyName
		}
		return s.verify(certs, name)
	}
	if s.hello == nil {
		cfg := &tls.Config{ServerName: o.ServerName, NextProtos: append([]string(nil), o.ALPN...), RootCAs: o.Roots,
			Time: o.Time, InsecureSkipVerify: o.AllowInsecure, EncryptedClientHelloConfigList: ech}
		if s.verify != nil {
			cfg.InsecureSkipVerify = true
			cfg.VerifyConnection = func(state tls.ConnectionState) error { return verify(state.PeerCertificates, state.ServerName) }
		}
		tc := tls.Client(raw, cfg)
		if err = tc.HandshakeContext(ctx); err != nil {
			return
		}
		cs := tc.ConnectionState()
		state = TLSState{cs.ServerName, cs.NegotiatedProtocol, cs.Version, cs.ECHAccepted}
		conn = tc
	} else {
		cfg := &utls.Config{ServerName: o.ServerName, NextProtos: append([]string(nil), o.ALPN...), RootCAs: o.Roots,
			Time: o.Time, InsecureSkipVerify: o.AllowInsecure, EncryptedClientHelloConfigList: ech}
		if s.verify != nil {
			cfg.InsecureSkipVerify = true
			cfg.VerifyConnection = func(state utls.ConnectionState) error { return verify(state.PeerCertificates, state.ServerName) }
		}
		tc := utls.UClient(raw, cfg, *s.hello)
		if err = tc.HandshakeContext(ctx); err != nil {
			return
		}
		cs := tc.ConnectionState()
		state = TLSState{cs.ServerName, cs.NegotiatedProtocol, cs.Version, cs.ECHAccepted}
		conn = tc
	}
	if o.ECH.Enabled && !state.ECHAccepted {
		err = ErrECH
		conn = nil
	}
	return
}
