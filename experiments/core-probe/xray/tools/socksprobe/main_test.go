package main

import (
	"bufio"
	"bytes"
	"net"
	"strings"
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
