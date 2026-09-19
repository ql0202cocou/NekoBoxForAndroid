package libcore

import (
	"fmt"
	"io"
	"log"
	"net"
	"time"

	"github.com/matsuridayo/libneko/protect_server"
	"golang.org/x/sys/unix"
)

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
		closer, err := protect_server.ServeProtect("protect_path", false, 0, func(fd int) error {
			return intfBox.AutoDetectInterfaceControl(int32(fd))
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
