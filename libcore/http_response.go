package libcore

import (
	"fmt"
	"io"
	"libcore/device"
	"net/http"
	"os"
	"strings"
	"sync"
)

// maxContentSize caps response bodies read by getContent (32 MB).
const maxContentSize = 32 * 1024 * 1024

type httpResponse struct {
	*http.Response

	getContentOnce sync.Once
	closeOnce      sync.Once
	content        []byte
	contentError   error
}

// bodyCloseHook runs after once the wrapped body is closed. Close reports the
// wrapped body's own error first and otherwise the hook's error, so a failing
// teardown (e.g. an h3 transport's Close) stays visible instead of being
// swallowed.
type bodyCloseHook struct {
	io.ReadCloser
	after func() error
	once  sync.Once
}

func (b *bodyCloseHook) Close() error {
	err := b.ReadCloser.Close()
	var afterErr error
	b.once.Do(func() {
		afterErr = b.after()
	})
	if err != nil {
		return err
	}
	return afterErr
}

// errorSnippetSize caps the body read for an error message. Only the first
// bytes of it are quoted, so an error page must not be pulled into memory in
// full (getContent allows 32 MB, and turning it into a string copies it
// again) just to be cut back down.
const errorSnippetSize = 1024

func (h *httpResponse) errorString() string {
	defer h.Close()

	content, err := io.ReadAll(io.LimitReader(h.Body, errorSnippetSize))
	if err != nil {
		return fmt.Sprint("HTTP ", h.Status)
	}
	text := string(content)
	if len(text) > 100 {
		// A mid-sequence byte cut would produce invalid UTF-8, which is
		// undefined behavior once gomobile marshals the string into a Java
		// exception message.
		text = strings.ToValidUTF8(text[:100], "") + " ..."
	}
	return fmt.Sprint("HTTP ", h.Status, ": ", text)
}

func (h *httpResponse) GetHeader(key string) *StringBox {
	defer device.DeferPanicToError("http GetHeader", nil)

	return wrapString(h.Header.Get(key))
}

func (h *httpResponse) getContent() (content []byte, err error) {
	defer device.DeferPanicToError("http GetContent", func(err_ error) { err = err_ })

	h.getContentOnce.Do(func() {
		defer h.Close()
		h.content, h.contentError = io.ReadAll(io.LimitReader(h.Body, maxContentSize+1))
		if h.contentError == nil && len(h.content) > maxContentSize {
			h.content = nil
			h.contentError = fmt.Errorf("content too large, limit is %d bytes", maxContentSize)
		}
	})
	return h.content, h.contentError
}

func (h *httpResponse) GetContentString() (ret *StringBox, err error) {
	defer device.DeferPanicToError("http GetContentString", func(err_ error) { err = err_ })

	content, err := h.getContentString()
	if err != nil {
		return nil, err
	}
	return wrapString(content), nil
}

func (h *httpResponse) getContentString() (string, error) {
	content, err := h.getContent()
	if err != nil {
		return "", err
	}
	return string(content), nil
}

func (h *httpResponse) WriteTo(path string) (err error) {
	defer device.DeferPanicToError("http WriteTo", func(err_ error) { err = err_ })

	defer h.Close()
	file, err := os.Create(path)
	if err != nil {
		return err
	}
	return copyAndClose(file, h.Body)
}

// Close releases the response body without reading it, for callers that only
// needed the headers; for an H3-direct response this also tears down the h3
// transport tied to the body via bodyCloseHook. Idempotent, and a no-op once
// the content was consumed.
func (h *httpResponse) Close() {
	defer device.DeferPanicToError("http Close", nil)

	h.closeOnce.Do(func() { h.Body.Close() })
}
