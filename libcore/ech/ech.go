package ech

import (
	"context"
	"crypto/tls"
	"encoding/base64"
	"log"
	"net"
	"os"

	mDNS "github.com/miekg/dns"
	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/dns"
	"github.com/sagernet/sing/common/exceptions"
)

// ECHClientConfig is a per-connection TLS config whose ECH keys are resolved
// on demand: Client rewrites the embedded tls.Config on every call, so an
// instance must not be shared across connections.
type ECHClientConfig struct {
	*tls.Config
	domain            string
	localDnsTransport adapter.DNSTransport
}

func NewECHClientConfig(domain string, tlsConfig *tls.Config, localDnsTransport adapter.DNSTransport) *ECHClientConfig {
	config := tlsConfig.Clone()
	config.ServerName = domain
	return &ECHClientConfig{
		Config:            config,
		domain:            domain,
		localDnsTransport: localDnsTransport,
	}
}

// Client wraps conn in a TLS client. Each call refreshes
// s.Config.EncryptedClientHelloConfigList from a DNS HTTPS lookup, mutating the
// shared tls.Config — callers must build a new ECHClientConfig per connection
// (see libcore's http.go, which constructs one inside every DialTLSContext).
func (s *ECHClientConfig) Client(ctx context.Context, conn net.Conn) (*tls.Conn, error) {
	err := s.fetchEchKeys(ctx)
	if err != nil {
		// allow empty ech keys
		// Std log is redirected into neko.log by InitCore (neko_log.SetupLog),
		// so this is visible on Android like the rest of libcore's logging.
		log.Println("fetchEchKeys:", err)
	}
	return tls.Client(conn, s.Config), nil
}

func (s *ECHClientConfig) fetchEchKeys(ctx context.Context) error {
	message := &mDNS.Msg{
		MsgHdr: mDNS.MsgHdr{
			RecursionDesired: true,
		},
		Question: []mDNS.Question{
			{
				Name:   mDNS.Fqdn(s.domain),
				Qtype:  mDNS.TypeHTTPS,
				Qclass: mDNS.ClassINET,
			},
		},
	}
	if s.localDnsTransport == nil {
		return os.ErrInvalid
	}
	response, err := s.localDnsTransport.Exchange(ctx, message)
	if err != nil {
		return exceptions.Cause(err, "fetch ECH config list")
	}
	if response.Rcode != mDNS.RcodeSuccess {
		return exceptions.Cause(dns.RcodeError(response.Rcode), "fetch ECH config list")
	}
	for _, rr := range response.Answer {
		switch resource := rr.(type) {
		case *mDNS.HTTPS:
			// Last-wins: if multiple HTTPS answers carry an ech value, the
			// last successfully decoded list overwrites earlier ones.
			for _, value := range resource.Value {
				if value.Key() == mDNS.SVCB_ECHCONFIG {
					echConfigList, err := base64.StdEncoding.DecodeString(value.String())
					if err != nil {
						log.Println("decode ECH config list:", err)
						continue
					}
					s.Config.EncryptedClientHelloConfigList = echConfigList
				}
			}
		}
	}
	return nil
}
