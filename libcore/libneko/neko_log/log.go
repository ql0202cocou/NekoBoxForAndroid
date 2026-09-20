package neko_log

import (
	"fmt"
	"io"
	"log"
	"os"
	"runtime"
	"sync"

	"github.com/matsuridayo/libneko/neko_common"
	"github.com/matsuridayo/libneko/syscallw"
)

var LogWriter *logWriter
var LogWriterDisable = false
var TruncateOnStart = true
var NB4AGuiLogWriter io.Writer

func SetupLog(maxSize int, path string) (err error) {
	if LogWriter != nil {
		return
	}

	var f *os.File
	f, err = os.OpenFile(path, os.O_RDWR|os.O_APPEND|os.O_CREATE, 0644)
	if err == nil {
		fd := int(f.Fd())
		if TruncateOnStart {
			syscallw.Flock(fd, syscallw.LOCK_EX)
			// Check if need truncate
			if size, _ := f.Seek(0, io.SeekEnd); size > int64(maxSize) {
				// read oldBytes for maxSize
				f.Seek(-int64(maxSize), io.SeekCurrent)
				oldBytes, err := io.ReadAll(f)
				if err == nil {
					// truncate file
					if runtime.GOOS == "windows" {
						f.Close()
						os.Remove(path)
						// reopen file
						f, err = os.OpenFile(path, os.O_RDWR|os.O_APPEND|os.O_CREATE, 0644)
					} else {
						err = f.Truncate(0)
					}
					// write oldBytes
					if err == nil {
						f.Write(oldBytes)
					}
				}
			}
			syscallw.Flock(fd, syscallw.LOCK_UN)
		}
		if neko_common.RunMode == neko_common.RunMode_NekoBoxForAndroid {
			// redirect stderr
			syscallw.Dup3(fd, int(os.Stderr.Fd()), 0)
		}
	}

	if err != nil {
		err = fmt.Errorf("error open log: %v", err)
		log.Println(err)
	}

	// 日志文件打开失败时 f 是 nil 的 *os.File：放进 writers 后是
	// typed-nil，Write 里的 w == nil 拦不住接口非空，文件日志会静默丢失，
	// 所以只有 f 非 nil（打开成功）才放入 writers
	LogWriter = &logWriter{}
	if neko_common.RunMode == neko_common.RunMode_NekoBoxForAndroid {
		LogWriter.writers = []io.Writer{NB4AGuiLogWriter}
	} else {
		LogWriter.writers = []io.Writer{os.Stdout}
	}
	if f != nil {
		LogWriter.writers = append(LogWriter.writers, f)
	}
	// setup std log
	log.SetFlags(log.LstdFlags | log.LUTC)
	log.SetOutput(LogWriter)

	return
}

type logWriter struct {
	// mu serializes Write/Truncate/Close in-process; the flock in Write only
	// excludes other processes, flock on the same fd is a no-op in-process.
	mu      sync.Mutex
	writers []io.Writer
}

func (w *logWriter) Write(p []byte) (int, error) {
	if LogWriterDisable {
		return len(p), nil
	}

	w.mu.Lock()
	defer w.mu.Unlock()

	for _, w := range w.writers {
		if w == nil {
			continue
		}
		if f, ok := w.(*os.File); ok {
			fd := int(f.Fd())
			syscallw.Flock(fd, syscallw.LOCK_EX)
			f.Write(p)
			syscallw.Flock(fd, syscallw.LOCK_UN)
		} else {
			w.Write(p)
		}
	}

	return len(p), nil
}

func (w *logWriter) Truncate() {
	w.mu.Lock()
	defer w.mu.Unlock()

	for _, w := range w.writers {
		if w == nil {
			continue
		}
		if f, ok := w.(*os.File); ok {
			_ = f.Truncate(0)
		}
	}
}

func (w *logWriter) Close() error {
	w.mu.Lock()
	defer w.mu.Unlock()

	for _, w := range w.writers {
		if w == nil {
			continue
		}
		if f, ok := w.(*os.File); ok {
			_ = f.Close()
		}
	}
	return nil
}
