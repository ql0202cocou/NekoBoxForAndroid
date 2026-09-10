package libcore

import (
	"errors"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

type trackedBody struct {
	io.Reader
	closed bool
}

func (b *trackedBody) Close() error { b.closed = true; return nil }

type brokenBody struct{}

func (brokenBody) Read([]byte) (int, error) { return 0, io.ErrUnexpectedEOF }
func TestHTTPWriteToResourceLifetime(t *testing.T) {
	for _, tc := range []struct {
		name    string
		reader  io.Reader
		badPath bool
		want    error
	}{
		{"success", strings.NewReader("asset"), false, nil},
		{"body read error", brokenBody{}, false, io.ErrUnexpectedEOF},
		{"create failure", strings.NewReader("asset"), true, os.ErrNotExist},
	} {
		t.Run(tc.name, func(t *testing.T) {
			body := &trackedBody{Reader: tc.reader}
			response := &httpResponse{Response: &http.Response{Body: body}}
			path := filepath.Join(t.TempDir(), "asset")
			if tc.badPath {
				path = filepath.Join(path, "missing")
			}
			err := response.WriteTo(path)
			if !errors.Is(err, tc.want) {
				t.Fatalf("got %v want %v", err, tc.want)
			}
			if !body.closed {
				t.Fatal("body not closed")
			}
			if tc.want == nil {
				data, err := os.ReadFile(path)
				if err != nil || string(data) != "asset" {
					t.Fatalf("content %q: %v", data, err)
				}
			}
		})
	}
}
