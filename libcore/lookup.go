package libcore

import (
	"bytes"
	"context"
	"crypto/tls"
	"fmt"
	"io"
	"net"
	"net/http"
	"strings"
	"time"

	mDNS "github.com/miekg/dns"
)

// LookupHost resolves domain via the given DNS server address
// (plain IP/host or udp/tcp/tls/https URL), and returns the
// resolved IP addresses joined by newline.
// Used by subscription forceResolve; unsupported schemes return an error.
func LookupHost(server string, domain string) (string, error) {
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()

	return lookupHost(ctx, server, domain)
}

// LookupHosts tries newline-separated servers in order within one ten-second
// budget, including A/AAAA queries and UDP-to-TCP retries.
func LookupHosts(servers string, domain string) (string, error) {
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	return lookupHosts(ctx, servers, domain)
}

func lookupHosts(ctx context.Context, servers string, domain string) (string, error) {
	for _, server := range strings.Split(servers, "\n") {
		server = strings.TrimSpace(server)
		if server == "" {
			continue
		}
		if err := ctx.Err(); err != nil {
			return "", err
		}
		addresses, err := lookupHost(ctx, server, domain)
		if err == nil {
			return addresses, nil
		}
	}
	if err := ctx.Err(); err != nil {
		return "", err
	}
	// Do not expose server URLs (which may contain credentials) in errors.
	return "", fmt.Errorf("no DNS server returned addresses")
}

func lookupHost(ctx context.Context, server string, domain string) (string, error) {
	// An empty answer covers NOERROR-empty, NXDOMAIN and REFUSED alike —
	// lookupHostType does not inspect Rcode, so those all return (nil, nil) and
	// still reach the AAAA leg. Only a transport-level failure short-circuits,
	// and retrying that over the same server would just burn the timeout twice.
	addresses, err := lookupHostType(ctx, server, domain, mDNS.TypeA)
	if err == nil && len(addresses) == 0 {
		addresses, err = lookupHostType(ctx, server, domain, mDNS.TypeAAAA)
	}
	if err != nil {
		return "", err
	}
	if len(addresses) == 0 {
		return "", fmt.Errorf("empty response for %s", domain)
	}
	return strings.Join(addresses, "\n"), nil
}

func lookupHostType(ctx context.Context, server string, domain string, queryType uint16) ([]string, error) {
	scheme := "udp"
	address := server
	if i := strings.Index(server, "://"); i >= 0 {
		scheme = server[:i]
		address = server[i+3:]
	}

	query := new(mDNS.Msg)
	query.SetQuestion(mDNS.Fqdn(domain), queryType)

	var response *mDNS.Msg
	var err error
	switch scheme {
	case "udp", "tcp":
		address = withDefaultPort(address, "53")
		client := &mDNS.Client{Net: scheme, Timeout: 5 * time.Second}
		response, _, err = client.ExchangeContext(ctx, query, address)
		// A truncated UDP answer is incomplete even when it contains some A/AAAA
		// records. Retry the same question over TCP within the original deadline.
		if err == nil && scheme == "udp" && response.Truncated {
			client.Net = "tcp"
			response, _, err = client.ExchangeContext(ctx, query, address)
		}
	case "tls":
		address = withDefaultPort(address, "853")
		host, _, _ := net.SplitHostPort(address)
		client := &mDNS.Client{
			Net:       "tcp-tls",
			Timeout:   5 * time.Second,
			TLSConfig: &tls.Config{ServerName: host},
		}
		response, _, err = client.ExchangeContext(ctx, query, address)
	case "https":
		response, err = exchangeHTTPS(ctx, server, query)
	default:
		err = fmt.Errorf("unsupported DNS server: %s", server)
	}
	if err != nil {
		return nil, err
	}

	var addresses []string
	for _, answer := range response.Answer {
		switch record := answer.(type) {
		case *mDNS.A:
			if queryType == mDNS.TypeA {
				addresses = append(addresses, record.A.String())
			}
		case *mDNS.AAAA:
			if queryType == mDNS.TypeAAAA {
				addresses = append(addresses, record.AAAA.String())
			}
		}
	}
	return addresses, nil
}

// withDefaultPort appends the default port unless address already has one.
// Bare IPv6 literals (e.g. 2606:4700::1111) get bracketed via JoinHostPort.
func withDefaultPort(address, port string) string {
	if _, _, err := net.SplitHostPort(address); err == nil {
		return address
	}
	return net.JoinHostPort(strings.Trim(address, "[]"), port)
}

func exchangeHTTPS(ctx context.Context, server string, query *mDNS.Msg) (*mDNS.Msg, error) {
	body, err := query.Pack()
	if err != nil {
		return nil, err
	}
	req, err := http.NewRequestWithContext(ctx, "POST", server, bytes.NewReader(body))
	if err != nil {
		return nil, err
	}
	req.Header.Set("Content-Type", "application/dns-message")
	req.Header.Set("Accept", "application/dns-message")
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("DNS over HTTPS returned HTTP %d", resp.StatusCode)
	}
	data, err := io.ReadAll(io.LimitReader(resp.Body, 64*1024))
	if err != nil {
		return nil, err
	}
	response := new(mDNS.Msg)
	return response, response.Unpack(data)
}
