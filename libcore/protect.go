package libcore

import (
	"fmt"
	"io"
	"log"
	"net"
	"sync/atomic"
	"time"

	"github.com/matsuridayo/libneko/protect_server"
	"golang.org/x/sys/unix"
)

// protectSocketPath 是 protect unix socket 的绝对路径，由 InitCore 依据
// cachePath 算出后写入；goServeProtect 与主进程的 AutoDetectInterfaceControl
// 在不同 goroutine 读取，故用 atomic.Pointer 发布，且不再依赖进程 CWD。
var protectSocketPath atomic.Pointer[string]

// protectWarnUnix 是上一次 protect 失败告警的 Unix 秒，用于限频。
var protectWarnUnix atomic.Int64

// warnProtectFailed 记录 protect 未生效的告警，每分钟最多一条：主进程在
// VPN 未运行时每次 URL 测试都会在这里失败，逐条打日志会刷屏。
func warnProtectFailed(fd int, err error) {
	now := time.Now().Unix()
	if last := protectWarnUnix.Load(); now-last < 60 || !protectWarnUnix.CompareAndSwap(last, now) {
		return
	}
	log.Printf("Warning: protect not applied for fd %d, traffic is not protected and will be routed through the proxy: %v", fd, err)
}

// protectCloser is only accessed with mainInstanceAccess held; the call
// sites are BoxInstance.Close and BoxInstance.SetAsMain in box.go. Do not
// touch it (or call goServeProtect) anywhere else without taking that lock.
var protectCloser io.Closer

func goServeProtect(start bool) {
	if protectCloser != nil {
		protectCloser.Close()
		protectCloser = nil
	}
	if start {
		path := protectSocketPath.Load()
		if path == nil {
			// InitCore 还没跑，正常流程不会发生
			log.Println("protect server start skipped: protect path not initialized")
			return
		}
		closer, err := protect_server.ServeProtect(*path, false, 0, func(fd int) error {
			return boxIntf().AutoDetectInterfaceControl(int32(fd))
		})
		if err != nil {
			log.Println("protect server start failed:", err)
			return
		}
		protectCloser = closer
	}
}

func sendFdToProtect(fd int, path string) error {
	// Use the runtime poller and close-on-exec sockets, with one deadline for
	// the whole handshake. Match the server's five-second handshake budget.
	deadline := time.Now().Add(5 * time.Second)
	conn, err := (&net.Dialer{Deadline: deadline}).Dial("unix", path)
	if err != nil {
		return fmt.Errorf("failed to connect: %w", err)
	}
	defer conn.Close()
	if err := conn.SetDeadline(deadline); err != nil {
		return fmt.Errorf("set protect deadline: %w", err)
	}
	_, _, err = conn.(*net.UnixConn).WriteMsgUnix([]byte{0}, unix.UnixRights(fd), nil)
	if err != nil {
		return fmt.Errorf("failed to send: %w", err)
	}
	var reply [1]byte
	n, err := conn.Read(reply[:])
	if err != nil {
		return fmt.Errorf("failed to receive: %w", err)
	}
	if n != 1 || reply[0] != 1 {
		return fmt.Errorf("protect failed")
	}
	return nil
}
