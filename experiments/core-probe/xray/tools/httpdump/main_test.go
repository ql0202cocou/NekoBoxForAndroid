package main

import (
	"io"
	"net"
	"strings"
	"testing"
	"time"
)

func TestSplitHeaders(t *testing.T) {
	request := "POST /xh HTTP/1.1\r\nX-Test: value\r\nhOsT: node.example.com\r\n\r\n"
	// 每个可能的分段点都覆盖，特别是请求行之后与 Host 中间。
	for split := 1; split < len(request); split++ {
		r := io.MultiReader(strings.NewReader(request[:split]), strings.NewReader(request[split:]))
		data, err := readCapture(r)
		output := formatCapture(data, err)
		if err != nil || !strings.Contains(output, "HOST=node.example.com\n") || !strings.Contains(output, "REQLINE=POST /xh HTTP/1.1\n") {
			t.Fatalf("split %d: %s, %v", split, output, err)
		}
	}
}

func TestCaptureBoundaries(t *testing.T) {
	for _, tc := range []struct {
		name, data, want string
		err              bool
	}{
		{"tls", "\x16\x03\x03\x00\x03xyz", "RAW| \\x16", false},
		{"h2c", "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n", "H2C-PREFACE\n", false},
		{"empty", "", "HTTPDUMP-ERROR=", true},
		{"partial", "GET / HTTP/1.1\r\nHost: lost.example", "HTTPDUMP-ERROR=", true},
		{"limit", "GET / HTTP/1.1\r\nX: " + strings.Repeat("x", 4096), "HTTPDUMP-ERROR=", true},
		{"body-is-not-header", "POST / HTTP/1.1\r\n\r\nHost: body.example\r\n", "REQLINE=POST / HTTP/1.1\n", false},
	} {
		t.Run(tc.name, func(t *testing.T) {
			data, err := readCapture(strings.NewReader(tc.data))
			output := formatCapture(data, err)
			if (err != nil) != tc.err || !strings.Contains(output, tc.want) || strings.Contains(output, "HOST=") {
				t.Fatalf("output=%q err=%v", output, err)
			}
			if len(data) > 4096 {
				t.Fatalf("unbounded capture: %d", len(data))
			}
		})
	}
}

func TestPartialHeaderDeadline(t *testing.T) {
	client, server := net.Pipe()
	defer client.Close()
	defer server.Close()
	client.SetReadDeadline(time.Now().Add(250 * time.Millisecond))
	go server.Write([]byte("GET / HTTP/1.1\r\n"))
	data, err := readCapture(client)
	if err == nil || !strings.Contains(formatCapture(data, err), "HTTPDUMP-ERROR=") {
		t.Fatalf("partial header must not look complete: %q, %v", data, err)
	}
}

func TestCompleteHeaderDoesNotWaitForEOF(t *testing.T) {
	client, server := net.Pipe()
	defer client.Close()
	defer server.Close()
	client.SetReadDeadline(time.Now().Add(time.Second))
	go func() {
		server.Write([]byte("GET / HTTP/1.1\r\n"))
		server.Write([]byte("Host: example.com\r\n\r\n"))
	}()
	data, err := readCapture(client)
	if err != nil || !strings.Contains(formatCapture(data, err), "HOST=example.com\n") {
		t.Fatalf("header=%q err=%v", data, err)
	}
}
