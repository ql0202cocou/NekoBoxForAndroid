package libcore

import (
	"errors"
	"io"
)

// copyAndClose reports delayed write failures from Close before callers publish
// a downloaded or extracted file. Both errors remain available to errors.Is.
func copyAndClose(dst io.WriteCloser, src io.Reader) error {
	_, copyErr := io.Copy(dst, src)
	return errors.Join(copyErr, dst.Close())
}
