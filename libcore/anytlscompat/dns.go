package anytlscompat

import (
	"context"
	"errors"

	"github.com/miekg/dns"
)

// DNS 缓存、TTL 和传输归属调用方；每次新会话查询，兼容层不保存陈旧 ECH 密钥。
// sing-box 接入时可将 DNSRouter.Exchange(ctx, msg, queryOptions) 闭包传入。
func HTTPSLookup(exchange func(context.Context, *dns.Msg) (*dns.Msg, error)) func(context.Context, string) ([]byte, error) {
	return func(ctx context.Context, name string) ([]byte, error) {
		if exchange == nil || name == "" {
			return nil, ErrECH
		}
		msg := new(dns.Msg).SetQuestion(dns.Fqdn(name), dns.TypeHTTPS)
		response, err := exchange(ctx, msg)
		if err != nil {
			return nil, err
		}
		if response == nil || response.Rcode != dns.RcodeSuccess {
			return nil, ErrECH
		}
		for _, record := range response.Answer {
			https, ok := record.(*dns.HTTPS)
			if !ok {
				continue
			}
			for _, value := range https.Value {
				if ech, ok := value.(*dns.SVCBECHConfig); ok && len(ech.ECH) > 0 {
					return append([]byte(nil), ech.ECH...), nil
				}
			}
		}
		return nil, errors.New("HTTPS answer has no ECH configuration")
	}
}
