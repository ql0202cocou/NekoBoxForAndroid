package device

import (
	"fmt"
	"log"
	"runtime/debug"
)

func DeferPanicToError(name string, onError func(error)) {
	if r := recover(); r != nil {
		s := fmt.Errorf("%s panic: %v\n%s", name, r, string(debug.Stack()))
		if onError != nil {
			onError(s)
		} else {
			// Fallback so a panic is never swallowed silently. Std log is
			// redirected into neko.log by InitCore (neko_log.SetupLog).
			log.Println(s)
		}
	}
}
