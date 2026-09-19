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
	closed   int
	closeErr error
}

func (b *trackedBody) Close() error { b.closed++; return b.closeErr }

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
			if body.closed == 0 {
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

// Close releases the body exactly once, whether it is called repeatedly or
// after the content was already consumed, and fires the bodyCloseHook (which
// tears down an h3 transport) a single time.
func TestHTTPResponseClose(t *testing.T) {
	t.Run("idempotent", func(t *testing.T) {
		body := &trackedBody{Reader: strings.NewReader("unused")}
		response := &httpResponse{Response: &http.Response{Body: body}}
		response.Close()
		response.Close()
		if body.closed != 1 {
			t.Fatalf("body closed %d times", body.closed)
		}
	})

	t.Run("after consume", func(t *testing.T) {
		body := &trackedBody{Reader: strings.NewReader("asset")}
		response := &httpResponse{Response: &http.Response{Body: body}}
		if _, err := response.getContent(); err != nil {
			t.Fatal(err)
		}
		response.Close()
		if body.closed != 1 {
			t.Fatalf("body closed %d times", body.closed)
		}
	})

	t.Run("hook once", func(t *testing.T) {
		hooks := 0
		body := &bodyCloseHook{
			ReadCloser: &trackedBody{Reader: strings.NewReader("unused")},
			after:      func() error { hooks++; return nil },
		}
		response := &httpResponse{Response: &http.Response{Body: body}}
		response.Close()
		response.Close()
		if hooks != 1 {
			t.Fatalf("hook ran %d times", hooks)
		}
	})
}

// bodyCloseHook must not swallow the after hook's error: it surfaces when the
// wrapped body closed cleanly, while the body's own error takes precedence.
func TestBodyCloseHookError(t *testing.T) {
	afterErr := errors.New("after failed")
	bodyErr := errors.New("body close failed")

	t.Run("after error surfaces", func(t *testing.T) {
		hook := &bodyCloseHook{
			ReadCloser: &trackedBody{Reader: strings.NewReader("unused")},
			after:      func() error { return afterErr },
		}
		if err := hook.Close(); !errors.Is(err, afterErr) {
			t.Fatalf("got %v want %v", err, afterErr)
		}
	})

	t.Run("body error wins", func(t *testing.T) {
		hook := &bodyCloseHook{
			ReadCloser: &trackedBody{Reader: strings.NewReader("unused"), closeErr: bodyErr},
			after:      func() error { return afterErr },
		}
		if err := hook.Close(); !errors.Is(err, bodyErr) {
			t.Fatalf("got %v want %v", err, bodyErr)
		}
	})

	t.Run("no error", func(t *testing.T) {
		hook := &bodyCloseHook{
			ReadCloser: &trackedBody{Reader: strings.NewReader("unused")},
			after:      func() error { return nil },
		}
		if err := hook.Close(); err != nil {
			t.Fatalf("got %v want nil", err)
		}
	})
}
