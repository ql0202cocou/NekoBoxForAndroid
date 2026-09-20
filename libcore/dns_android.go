//go:build android && cgo

package libcore

/*
#include <stdint.h>
#include <stddef.h>
#include <stdlib.h>
#include <dlfcn.h>

typedef int (*android_res_nsend_t)(uint64_t network, const uint8_t* msg, size_t msglen, int flags);
typedef int (*android_res_nresult_t)(int fd, int* rcode, uint8_t* resp, size_t resp_len);
typedef void (*android_res_cancel_t)(int nsend_fd);

static int call_android_res_nsend(void* sym, uint64_t network, const uint8_t* msg, size_t msglen, int flags) {
    android_res_nsend_t f = (android_res_nsend_t)sym;
    if (!f) return -1;
    return f(network, msg, msglen, flags);
}

static int call_android_res_nresult(void* sym, int fd, int* rcode, uint8_t* resp, size_t resp_len) {
    android_res_nresult_t f = (android_res_nresult_t)sym;
    if (!f) return -1;
    return f(fd, rcode, resp, resp_len);
}

static void call_android_res_cancel(void* sym, int nsend_fd) {
    android_res_cancel_t f = (android_res_cancel_t)sym;
    if (f) f(nsend_fd);
}
*/
import "C"

import (
	"context"
	"errors"
	"os"
	"time"
	"unsafe"

	"github.com/sagernet/sing-box/dns"

	"golang.org/x/sys/unix"
)

func init() {
	libname := C.CString("libandroid.so")
	defer C.free(unsafe.Pointer(libname))

	libHandle := C.dlopen(libname, C.int(C.RTLD_NOW))
	if libHandle == nil {
		return
	}

	symNameSend := C.CString("android_res_nsend")
	defer C.free(unsafe.Pointer(symNameSend))
	androidResNSendSym := C.dlsym(libHandle, symNameSend)
	if androidResNSendSym == nil {
		return
	}

	symNameResult := C.CString("android_res_nresult")
	defer C.free(unsafe.Pointer(symNameResult))
	androidResNResultSym := C.dlsym(libHandle, symNameResult)
	if androidResNResultSym == nil {
		return
	}

	symNameCancel := C.CString("android_res_cancel")
	defer C.free(unsafe.Pointer(symNameCancel))
	androidResCancelSym := C.dlsym(libHandle, symNameCancel)
	if androidResCancelSym == nil {
		return
	}

	callAndroidResNSend := func(network uint64, msg []byte) (int, error) {
		if len(msg) == 0 {
			return 0, errors.New("empty payload")
		}
		msgPtr := (*C.uint8_t)(unsafe.Pointer(&msg[0]))
		msgLen := C.size_t(len(msg))
		ret := C.call_android_res_nsend(androidResNSendSym, C.uint64_t(network), msgPtr, msgLen, C.int(0))
		return int(ret), nil
	}

	callAndroidResNResult := func(fd int, resp []byte) (int, int) {
		if len(resp) == 0 {
			return 0, 0
		}
		respPtr := (*C.uint8_t)(unsafe.Pointer(&resp[0]))
		respLen := C.size_t(len(resp))
		var rcode C.int
		n := C.call_android_res_nresult(androidResNResultSym, C.int(fd), &rcode, respPtr, respLen)
		return int(rcode), int(n)
	}

	callAndroidResCancel := func(fd int) {
		C.call_android_res_cancel(androidResCancelSym, C.int(fd))
	}

	// set rawQueryFunc
	rawQueryFunc = func(ctx context.Context, networkHandle int64, request []byte) ([]byte, error) {
		fd, err := callAndroidResNSend(uint64(networkHandle), request)
		if err != nil {
			return nil, err
		}
		if fd < 0 {
			return nil, unix.Errno(-fd)
		}
		// fd ownership follows the NDK contract: android_res_nresult "closes
		// |fd| before returning", so only a query abandoned before it (timeout,
		// cancel, poll error) is released here, via android_res_cancel. A close
		// after nresult would hit a number the runtime may already have handed
		// to another socket.
		settled := false
		defer func() {
			if !settled {
				callAndroidResCancel(fd)
			}
		}()

		// a context cancelled before the first poll must return immediately
		// instead of waiting out a whole poll slice
		if err := ctx.Err(); err != nil {
			return nil, err
		}

		// wait for response (timeout 5000 ms), polling in short slices so a
		// cancelled context returns promptly instead of after the full timeout
		pfds := []unix.PollFd{{Fd: int32(fd), Events: unix.POLLIN | unix.POLLERR}}
		deadline := time.Now().Add(5 * time.Second)
		for {
			remaining := time.Until(deadline)
			if remaining <= 0 {
				return nil, context.DeadlineExceeded
			}
			slice := remaining
			if slice > 100*time.Millisecond {
				slice = 100 * time.Millisecond
			}
			timeout := int(slice.Milliseconds())
			if timeout < 1 {
				timeout = 1
			}
			nReady, err := unix.Poll(pfds, timeout)
			if err != nil {
				if err == unix.EINTR {
					continue
				}
				return nil, err
			}
			if nReady > 0 {
				break
			}
			select {
			case <-ctx.Done():
				return nil, ctx.Err()
			default:
			}
		}

		// read response into buffer; nresult closes fd
		settled = true
		// 65535 是 DNS 消息长度上限：缓冲不足时超大响应会被 bionic 截断
		response := make([]byte, 65535)
		rcode, n := callAndroidResNResult(fd, response)
		if n < 0 {
			return nil, unix.Errno(-n)
		}
		if n == 0 {
			if rcode != 0 {
				// e.g. NXDOMAIN with an empty body: keep the real rcode
				return nil, dns.RcodeError(rcode)
			}
			return nil, os.ErrInvalid
		}
		return response[:n], nil
	}
}
