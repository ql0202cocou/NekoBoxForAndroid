package protect_server

import (
	"fmt"
	"io"
	"log"
	"net"
	"os"
	"syscall"
	"time"
)

// getOneFd receives the single SCM_RIGHTS fd the peer sends. It reads through
// the net.Conn (runtime poller) instead of a raw recvmsg on the accepted socket:
// accepted fds are non-blocking, so a raw call issued before the peer's sendmsg
// landed returned EAGAIN and the caller's deadline never applied. The poller
// also receives with MSG_CMSG_CLOEXEC, so the fd cannot leak into a plugin
// process forked meanwhile.
func getOneFd(c *net.UnixConn) (int, error) {
	oob := make([]byte, syscall.CmsgSpace(4))
	var data [1]byte // senders on stream sockets carry one dummy byte with the rights
	_, oobn, _, _, err := c.ReadMsgUnix(data[:], oob)
	if err != nil {
		return 0, err
	}

	msgs, err := syscall.ParseSocketControlMessage(oob[:oobn])
	if err != nil {
		return 0, err
	}
	// 先收齐全部控制消息携带的 SCM_RIGHTS fd 再校验数量：数量不对时逐个
	// 关闭，否则已经接收的 fd 会泄漏（对端恒发 1 个，此处纯防御）
	var fds []int
	for i := range msgs {
		rights, err := syscall.ParseUnixRights(&msgs[i])
		if err != nil {
			closeFds(fds)
			return 0, err
		}
		fds = append(fds, rights...)
	}
	if len(fds) != 1 {
		closeFds(fds)
		return 0, fmt.Errorf("invalid fds count: %d", len(fds))
	}
	return fds[0], nil
}

func closeFds(fds []int) {
	for _, fd := range fds {
		syscall.Close(fd)
	}
}

func ServeProtect(path string, verbose bool, fwmark int, protectCtl func(fd int) error) (io.Closer, error) {
	if verbose {
		log.Println("ServeProtect", path, fwmark)
	}

	os.Remove(path)
	l, err := net.ListenUnix("unix", &net.UnixAddr{Name: path, Net: "unix"})
	if err != nil {
		return nil, err
	}
	os.Chmod(path, 0777)

	go func(ctl func(fd int) error) {
		for {
			c, err := l.AcceptUnix()
			if err != nil {
				if verbose {
					log.Println("protect server accept:", err)
				}
				return
			}

			go func() {
				defer c.Close()

				// bound the handshake so a peer that connects but never
				// sends can't park the goroutine (and its fds) forever
				c.SetDeadline(time.Now().Add(5 * time.Second))

				fd, err := getOneFd(c)
				if err != nil {
					if verbose {
						log.Println("protect server getOneFd:", err)
					}
					return
				}
				defer syscall.Close(fd)

				if ctl == nil {
					// linux
					err = syscall.SetsockoptInt(int(fd), syscall.SOL_SOCKET, syscall.SO_MARK, fwmark)
					if err != nil {
						log.Println("protect server syscall.SetsockoptInt:", err)
					}
				} else {
					// android
					err = ctl(fd)
					if err != nil {
						log.Println("protect server ctl:", err)
					}
				}

				if err == nil {
					c.Write([]byte{1})
				} else {
					c.Write([]byte{0})
				}
			}()
		}
	}(protectCtl)

	return l, nil
}
