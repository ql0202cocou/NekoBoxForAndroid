package libcore

import (
	"errors"
	"io"
	"strings"
	"testing"
)

type failingOutput struct {
	writeErr, closeErr error
	closed             int
}

func (w *failingOutput) Write(p []byte) (int, error) {
	if w.writeErr != nil {
		return 0, w.writeErr
	}
	return len(p), nil
}
func (w *failingOutput) Close() error { w.closed++; return w.closeErr }
func TestCopyAndClose(t *testing.T) {
	writeErr, closeErr := errors.New("write failure"), errors.New("close failure")
	for _, tc := range []struct {
		name               string
		writeErr, closeErr error
	}{
		{"success", nil, nil}, {"close failure", nil, closeErr}, {"write failure", writeErr, nil}, {"both failures", writeErr, closeErr},
	} {
		t.Run(tc.name, func(t *testing.T) {
			dst := &failingOutput{writeErr: tc.writeErr, closeErr: tc.closeErr}
			err := copyAndClose(dst, strings.NewReader("asset"))
			if dst.closed != 1 {
				t.Fatalf("closed %d times", dst.closed)
			}
			if tc.writeErr == nil && tc.closeErr == nil && err != nil {
				t.Fatal(err)
			}
			for _, want := range []error{tc.writeErr, tc.closeErr} {
				if want != nil && !errors.Is(err, want) {
					t.Fatalf("lost error %v: %v", want, err)
				}
			}
		})
	}
}

var _ io.WriteCloser = (*failingOutput)(nil)
