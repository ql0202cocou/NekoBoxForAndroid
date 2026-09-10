package libcore

import (
	"golang.org/x/sys/unix"
	"net"
	"os"
	"path/filepath"
	"testing"
	"time"
)

func TestProtectHandshake(t *testing.T) {
	for _, tc := range []struct {
		name    string
		reply   []byte
		delay   time.Duration
		wantErr bool
	}{
		{"busy server", []byte{1}, 200 * time.Millisecond, false},
		{"rejected", []byte{0}, 0, true},
		{"closed without reply", nil, 0, true},
	} {
		t.Run(tc.name, func(t *testing.T) {
			// Unix socket paths have a short platform limit; avoid long testing paths.
			dir, err := os.MkdirTemp("/tmp", "protect-")
			if err != nil {
				t.Fatal(err)
			}
			defer os.RemoveAll(dir)
			path := filepath.Join(dir, "s")
			listener, err := net.ListenUnix("unix", &net.UnixAddr{Name: path, Net: "unix"})
			if err != nil {
				t.Fatal(err)
			}
			defer listener.Close()
			source, err := os.Open(os.DevNull)
			if err != nil {
				t.Fatal(err)
			}
			defer source.Close()
			done := make(chan error, 1)
			go func() {
				c, err := listener.AcceptUnix()
				if err != nil {
					done <- err
					return
				}
				defer c.Close()
				c.SetDeadline(time.Now().Add(2 * time.Second))
				data, oob := make([]byte, 1), make([]byte, unix.CmsgSpace(4))
				_, n, _, _, err := c.ReadMsgUnix(data, oob)
				if err != nil {
					done <- err
					return
				}
				msgs, err := unix.ParseSocketControlMessage(oob[:n])
				if err != nil {
					done <- err
					return
				}
				count := 0
				for _, msg := range msgs {
					fds, e := unix.ParseUnixRights(&msg)
					if e != nil {
						done <- e
						return
					}
					for _, fd := range fds {
						count++
						unix.Close(fd)
					}
				}
				if count != 1 {
					done <- os.ErrInvalid
					return
				}
				time.Sleep(tc.delay)
				if tc.reply != nil {
					_, err = c.Write(tc.reply)
				}
				done <- err
			}()
			err = sendFdToProtect(int(source.Fd()), path)
			if (err != nil) != tc.wantErr {
				t.Errorf("got %v; wantErr=%v", err, tc.wantErr)
			}
			if err := <-done; err != nil {
				t.Fatal(err)
			}
			if _, err = source.Stat(); err != nil {
				t.Fatalf("sender fd closed: %v", err)
			}
		})
	}
}
