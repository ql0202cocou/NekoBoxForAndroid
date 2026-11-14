package main

import (
	"bufio"
	"bytes"
	"errors"
	"net"
	"os"
	"strings"
	"syscall"
	"testing"
	"time"
)

func TestTCPReply(t *testing.T) {
	for _, tc := range []struct {
		name   string
		parts  []string
		linger bool
		want   string
		err    bool
	}{
		{"whole", []string{"TARGET-REPLY-OK\n"}, false, "TCP-REPLY=TARGET-REPLY-OK\n", false},
		{"split", []string{"T", "ARGET-", "REPLY-OK\n"}, true, "TCP-REPLY=TARGET-REPLY-OK\n", false},
		{"eof", nil, false, "TCP-NOREPLY\n", false},
		{"timeout", nil, true, "TCP-NOREPLY\n", false},
		{"partial-eof", []string{"TARGET-REPLY-OK"}, false, "", true},
		{"partial-timeout", []string{"T"}, true, "", true},
		{"too-long", []string{strings.Repeat("x", 4097)}, false, "", true},
	} {
		t.Run(tc.name, func(t *testing.T) {
			client, server := net.Pipe()
			defer client.Close()
			defer server.Close()
			done := make(chan struct{})
			defer close(done)
			go func() {
				defer server.Close()
				bufio.NewReader(server).ReadString('\n')
				for _, part := range tc.parts {
					if _, err := server.Write([]byte(part)); err != nil {
						return
					}
				}
				if tc.linger {
					<-done
				}
			}()
			var output bytes.Buffer
			err := probeTCP(client, 250*time.Millisecond, &output)
			if (err != nil) != tc.err || output.String() != tc.want {
				t.Fatalf("output=%q err=%v; want %q error=%v", output.String(), err, tc.want, tc.err)
			}
		})
	}
}

func TestTCPWriteFailure(t *testing.T) {
	client, server := net.Pipe()
	defer client.Close()
	server.Close()
	var output bytes.Buffer
	if err := probeTCP(client, time.Second, &output); err == nil || output.Len() != 0 {
		t.Fatalf("write failure must not produce a traffic verdict: %q, %v", output.String(), err)
	}
}

// 读写在指定阶段返回给定错误的连接
type failingConn struct {
	net.Conn
	writeErr, readErr error
}

func (c failingConn) Write(b []byte) (int, error) {
	if c.writeErr != nil {
		return 0, c.writeErr
	}
	return len(b), nil
}

func (c failingConn) Read([]byte) (int, error) { return 0, c.readErr }

func (failingConn) SetDeadline(time.Time) error { return nil }

func (failingConn) Close() error { return nil }

func sysErr(op string, errno syscall.Errno) error {
	return &net.OpError{Op: op, Net: "tcp", Err: os.NewSyscallError(op, errno)}
}

func TestTCPResetByPeer(t *testing.T) {
	reset := func(op string) error { return sysErr(op, syscall.ECONNRESET) }
	for _, tc := range []struct {
		stage string
		dial  func() (net.Conn, error)
	}{
		{"dial", func() (net.Conn, error) { return nil, reset("connect") }},
		{"write", func() (net.Conn, error) { return failingConn{writeErr: reset("write")}, nil }},
		{"read", func() (net.Conn, error) { return failingConn{readErr: reset("read")}, nil }},
	} {
		t.Run(tc.stage, func(t *testing.T) {
			var output bytes.Buffer
			want := "TCP-RESET=" + tc.stage + "\nTCP-NOREPLY\n"
			if err := dialAndProbeTCP(tc.dial, time.Second, &output); err != nil || output.String() != want {
				t.Fatalf("output=%q err=%v; want %q", output.String(), err, want)
			}
		})
	}
}

func TestTCPOtherFailuresStayErrors(t *testing.T) {
	for name, dial := range map[string]func() (net.Conn, error){
		"dial-refused":  func() (net.Conn, error) { return nil, sysErr("connect", syscall.ECONNREFUSED) },
		"dial-timeout":  func() (net.Conn, error) { return nil, os.ErrDeadlineExceeded },
		"write-timeout": func() (net.Conn, error) { return failingConn{writeErr: os.ErrDeadlineExceeded}, nil },
		"write-pipe":    func() (net.Conn, error) { return failingConn{writeErr: sysErr("write", syscall.EPIPE)}, nil },
	} {
		t.Run(name, func(t *testing.T) {
			var output bytes.Buffer
			err := dialAndProbeTCP(dial, time.Second, &output)
			if err == nil || errors.Is(err, syscall.ECONNRESET) || output.Len() != 0 {
				t.Fatalf("must stay an error without a traffic verdict: %q, %v", output.String(), err)
			}
		})
	}
}
