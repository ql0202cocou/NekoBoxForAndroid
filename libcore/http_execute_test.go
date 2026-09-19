package libcore

import (
	"encoding/binary"
	"errors"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"strconv"
	"testing"
	"time"
)

// fakeSocks5Server is a minimal no-auth SOCKS5 CONNECT server: it completes
// the handshake, records the requested target, and relays the connection to
// it, so tests can prove a request really went through the proxy.
type fakeSocks5Server struct {
	listener net.Listener
	targets  chan string
}

func startFakeSocks5(t *testing.T) *fakeSocks5Server {
	t.Helper()
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	server := &fakeSocks5Server{listener: listener, targets: make(chan string, 16)}
	go func() {
		for {
			conn, err := listener.Accept()
			if err != nil {
				return
			}
			go server.serve(conn)
		}
	}()
	t.Cleanup(func() { listener.Close() })
	return server
}

func (s *fakeSocks5Server) port() int32 {
	return int32(s.listener.Addr().(*net.TCPAddr).Port)
}

func (s *fakeSocks5Server) serve(conn net.Conn) {
	defer conn.Close()
	target, err := socks5Handshake(conn)
	if err != nil {
		return
	}
	s.targets <- target
	upstream, err := net.DialTimeout("tcp", target, 5*time.Second)
	if err != nil {
		return
	}
	defer upstream.Close()
	go func() {
		io.Copy(upstream, conn)
		if tcpConn, ok := upstream.(*net.TCPConn); ok {
			tcpConn.CloseWrite()
		}
	}()
	io.Copy(conn, upstream)
}

// socks5Handshake answers the no-auth greeting, parses the CONNECT request
// and replies success with a dummy bind address, returning the requested
// target address.
func socks5Handshake(conn net.Conn) (string, error) {
	var greeting [2]byte
	if _, err := io.ReadFull(conn, greeting[:]); err != nil {
		return "", err
	}
	if greeting[0] != 0x05 {
		return "", errors.New("not a socks5 greeting")
	}
	if _, err := io.ReadFull(conn, make([]byte, int(greeting[1]))); err != nil {
		return "", err
	}
	if _, err := conn.Write([]byte{0x05, 0x00}); err != nil {
		return "", err
	}
	var header [4]byte
	if _, err := io.ReadFull(conn, header[:]); err != nil {
		return "", err
	}
	var host string
	switch header[3] {
	case 0x01:
		buf := make([]byte, net.IPv4len)
		if _, err := io.ReadFull(conn, buf); err != nil {
			return "", err
		}
		host = net.IP(buf).String()
	case 0x03:
		var length [1]byte
		if _, err := io.ReadFull(conn, length[:]); err != nil {
			return "", err
		}
		buf := make([]byte, int(length[0]))
		if _, err := io.ReadFull(conn, buf); err != nil {
			return "", err
		}
		host = string(buf)
	case 0x04:
		buf := make([]byte, net.IPv6len)
		if _, err := io.ReadFull(conn, buf); err != nil {
			return "", err
		}
		host = net.IP(buf).String()
	default:
		return "", errors.New("unknown socks5 address type")
	}
	var port [2]byte
	if _, err := io.ReadFull(conn, port[:]); err != nil {
		return "", err
	}
	if _, err := conn.Write([]byte{0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0}); err != nil {
		return "", err
	}
	return net.JoinHostPort(host, strconv.Itoa(int(binary.BigEndian.Uint16(port[:])))), nil
}

// refusedAddr grabs a port and releases it, so a dial to the returned address
// is refused at once (and a UDP dial to the same port finds nothing).
func refusedAddr(t *testing.T) *net.TCPAddr {
	t.Helper()
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer listener.Close()
	return listener.Addr().(*net.TCPAddr)
}

func executeOK(t *testing.T, client HTTPClient, link string) HTTPResponse {
	t.Helper()
	request := client.NewRequest()
	if err := request.SetURL(link); err != nil {
		t.Fatal(err)
	}
	response, err := request.Execute()
	if err != nil {
		t.Fatal(err)
	}
	return response
}

func TestExecuteViaSocks5(t *testing.T) {
	backend := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Write([]byte("via socks5"))
	}))
	defer backend.Close()
	socks5Server := startFakeSocks5(t)

	client := NewHttpClient()
	defer client.Close()
	client.TrySocks5(socks5Server.port())
	response := executeOK(t, client, backend.URL)
	defer response.Close()
	content, err := response.GetContentString()
	if err != nil {
		t.Fatal(err)
	}
	if content.Value != "via socks5" {
		t.Fatalf("content = %q", content.Value)
	}
	select {
	case target := <-socks5Server.targets:
		if backendAddr := backend.Listener.Addr().String(); target != backendAddr {
			t.Fatalf("socks5 target = %q, want %q", target, backendAddr)
		}
	default:
		t.Fatal("request did not go through the socks5 proxy")
	}
}

// Without TryH3Direct a broken socks5 proxy must fall back to a direct dial,
// both when the proxy refuses the connection and when the handshake fails.
func TestExecuteSocks5Fallback(t *testing.T) {
	backend := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Write([]byte("direct fallback"))
	}))
	defer backend.Close()

	t.Run("connection refused", func(t *testing.T) {
		port := int32(refusedAddr(t).Port)

		client := NewHttpClient()
		defer client.Close()
		client.TrySocks5(port)
		response := executeOK(t, client, backend.URL)
		defer response.Close()
		content, err := response.GetContentString()
		if err != nil {
			t.Fatal(err)
		}
		if content.Value != "direct fallback" {
			t.Fatalf("content = %q", content.Value)
		}
	})

	t.Run("handshake failure", func(t *testing.T) {
		// accept and close immediately, so the socks5 handshake fails
		listener, err := net.Listen("tcp", "127.0.0.1:0")
		if err != nil {
			t.Fatal(err)
		}
		go func() {
			for {
				conn, err := listener.Accept()
				if err != nil {
					return
				}
				conn.Close()
			}
		}()
		defer listener.Close()

		client := NewHttpClient()
		defer client.Close()
		client.TrySocks5(int32(listener.Addr().(*net.TCPAddr).Port))
		response := executeOK(t, client, backend.URL)
		defer response.Close()
		content, err := response.GetContentString()
		if err != nil {
			t.Fatal(err)
		}
		if content.Value != "direct fallback" {
			t.Fatalf("content = %q", content.Value)
		}
	})
}

// AllowInsecure clones the TLS config per request (ownTLS), so it must not
// disable verification for later requests on the same client.
func TestExecuteAllowInsecureIsolation(t *testing.T) {
	server := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Write([]byte("ok"))
	}))
	defer server.Close()

	client := NewHttpClient()
	defer client.Close()

	insecureRequest := client.NewRequest()
	insecureRequest.AllowInsecure()
	if err := insecureRequest.SetURL(server.URL); err != nil {
		t.Fatal(err)
	}
	response, err := insecureRequest.Execute()
	if err != nil {
		t.Fatalf("insecure request against self-signed server: %v", err)
	}
	response.Close()

	plainRequest := client.NewRequest()
	if err := plainRequest.SetURL(server.URL); err != nil {
		t.Fatal(err)
	}
	if _, err := plainRequest.Execute(); err == nil {
		t.Fatal("AllowInsecure leaked into the shared client: self-signed certificate was accepted")
	}
}

// A request with its own TLS config runs on a cloned transport whose idle
// pool must be tied to the response's Close instead of waiting for GC, while
// the shared transport must never be torn down per request.
func TestExecuteOwnTLSTransportLifetime(t *testing.T) {
	t.Run("own TLS response carries teardown hook", func(t *testing.T) {
		server := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			w.Write([]byte("ok"))
		}))
		defer server.Close()

		client := NewHttpClient()
		defer client.Close()
		request := client.NewRequest()
		request.AllowInsecure()
		if err := request.SetURL(server.URL); err != nil {
			t.Fatal(err)
		}
		response, err := request.Execute()
		if err != nil {
			t.Fatal(err)
		}
		defer response.Close()
		if _, ok := response.(*httpResponse).Body.(*bodyCloseHook); !ok {
			t.Fatal("own-TLS response body is not tied to the cloned transport's CloseIdleConnections")
		}
	})

	t.Run("shared transport response stays unhooked", func(t *testing.T) {
		server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			w.Write([]byte("ok"))
		}))
		defer server.Close()

		client := NewHttpClient()
		defer client.Close()
		response := executeOK(t, client, server.URL)
		defer response.Close()
		if _, ok := response.(*httpResponse).Body.(*bodyCloseHook); ok {
			t.Fatal("shared transport must not be torn down per request")
		}
	})
}
