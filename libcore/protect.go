package libcore

import (
	"fmt"
	"net"
	"time"

	"golang.org/x/sys/unix"
)

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
