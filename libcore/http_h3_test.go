package libcore

import (
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/sagernet/sing-box/option"
)

// stubLocalDNSTransport satisfies LocalDNSTransport without any platform; Raw
// is false so the ECH key lookup fails fast on the HTTPS question type and the
// racer falls back to plain TLS instead of dereferencing a nil transport.
type stubLocalDNSTransport struct{}

func (stubLocalDNSTransport) Raw() bool            { return false }
func (stubLocalDNSTransport) NetworkHandle() int64 { return 0 }

func (stubLocalDNSTransport) Lookup(ctx *ExchangeContext, network string, domain string) error {
	return errors.New("not implemented")
}

func (stubLocalDNSTransport) Exchange(ctx *ExchangeContext, message []byte) error {
	return errors.New("not implemented")
}

func useStubLocalDNSTransport(t *testing.T) {
	t.Helper()
	saved := gLocalDNSTransport
	gLocalDNSTransport = newPlatformTransport(stubLocalDNSTransport{}, "", option.LocalDNSServerOptions{})
	t.Cleanup(func() { gLocalDNSTransport = saved })
}

func executeH3(t *testing.T, url string, insecure bool) HTTPResponse {
	t.Helper()
	client := NewHttpClient()
	defer client.Close()
	client.TryH3Direct()
	request := client.NewRequest()
	if insecure {
		request.AllowInsecure()
	}
	if err := request.SetURL(url); err != nil {
		t.Fatal(err)
	}
	response, err := request.Execute()
	if err != nil {
		t.Fatal(err)
	}
	return response
}

// Plain http races only the ECH transport (funcs is cut to one entry), which
// dials directly for http URLs.
func TestDoH3DirectPlainHTTP(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("X-Test", "h3direct")
		w.Write([]byte("hello h3"))
	}))
	defer server.Close()

	response := executeH3(t, server.URL, false)
	if got := response.GetHeader("X-Test"); got == nil || got.Value != "h3direct" {
		t.Fatalf("header = %v", got)
	}
	content, err := response.GetContentString()
	if err != nil {
		t.Fatal(err)
	}
	if content.Value != "hello h3" {
		t.Fatalf("content = %q", content.Value)
	}
}

// https races ECH-over-TLS against real H3. The local server speaks TCP/TLS
// only, so the ECH racer wins and the H3 racer is cancelled mid-handshake.
func TestDoH3DirectHTTPS(t *testing.T) {
	useStubLocalDNSTransport(t)
	server := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Write([]byte("hello tls"))
	}))
	defer server.Close()

	response := executeH3(t, server.URL, true)
	content, err := response.GetContentString()
	if err != nil {
		t.Fatal(err)
	}
	if content.Value != "hello tls" {
		t.Fatalf("content = %q", content.Value)
	}
}

// Every racer failing must surface a joined error, not a hang or (nil, nil).
func TestDoH3DirectAllFail(t *testing.T) {
	address := refusedAddr(t).String()

	client := NewHttpClient()
	defer client.Close()
	client.TryH3Direct()
	request := client.NewRequest()
	if err := request.SetURL("http://" + address + "/"); err != nil {
		t.Fatal(err)
	}
	_, err := request.Execute()
	if err == nil || !strings.Contains(err.Error(), "http(s)") {
		t.Fatalf("expected joined racer failure, got: %v", err)
	}
}

// The ECH racer's transport must bound the wait for response headers like the
// shared transport does, otherwise a server that accepts but never answers
// occupies the whole race window.
func TestECHTransportResponseHeaderTimeout(t *testing.T) {
	client := NewHttpClient()
	defer client.Close()
	transport := client.NewRequest().(*httpRequest).echTransport()
	if transport.ResponseHeaderTimeout != httpResponseHeaderTimeout {
		t.Fatalf("ResponseHeaderTimeout = %v, want %v", transport.ResponseHeaderTimeout, httpResponseHeaderTimeout)
	}
}

// Racer names travel with the racer entry, not its position: an https race
// where both transports fail must report each by its own name.
func TestDoH3DirectRacerNames(t *testing.T) {
	// the ECH dial is refused at once and the h3 handshake dies against the
	// same, dead UDP port
	address := refusedAddr(t).String()

	client := NewHttpClient()
	defer client.Close()
	client.TryH3Direct()
	request := client.NewRequest()
	if err := request.SetURL("https://" + address + "/"); err != nil {
		t.Fatal(err)
	}
	_, err := request.Execute()
	if err == nil {
		t.Fatal("expected joined racer failure")
	}
	if !strings.Contains(err.Error(), "http(s)") || !strings.Contains(err.Error(), "h3") {
		t.Fatalf("expected both racer names in error, got: %v", err)
	}
}

// zeroReader is an endless zero-filled reader, for a large request body
// without a large allocation.
type zeroReader struct{}

func (zeroReader) Read(p []byte) (int, error) { return len(p), nil }

// The race buffers the whole request body in memory, so a body beyond
// maxContentSize must be rejected up front instead of being read in full.
func TestDoH3DirectBodyTooLarge(t *testing.T) {
	client := NewHttpClient()
	defer client.Close()
	client.TryH3Direct()
	request := client.NewRequest()
	if err := request.SetURL("http://127.0.0.1:1/"); err != nil {
		t.Fatal(err)
	}
	body := &trackedBody{Reader: io.LimitReader(zeroReader{}, maxContentSize+1)}
	request.(*httpRequest).request.Body = body
	_, err := request.Execute()
	if err == nil || !strings.Contains(err.Error(), "too large") {
		t.Fatalf("expected body size error, got: %v", err)
	}
	if body.closed == 0 {
		t.Fatal("original body not closed")
	}
}
