package anytlscompat

import (
	"context"
	stdtls "crypto/tls"
	"errors"
	"net"
	"sync"
	"testing"
	"time"

	M "github.com/sagernet/sing/common/metadata"
)

type blockedAuthDialer struct {
	certificate stdtls.Certificate
	blocked     chan struct{}
	stop        chan struct{}
}

type applicationWriteConn struct {
	net.Conn
	once    sync.Once
	blocked chan struct{}
}

func (c *applicationWriteConn) Write(p []byte) (int, error) {
	// TLS 1.2 的应用记录类型独立于握手记录，不依赖 goroutine 调度判断阻塞窗口。
	if len(p) > 0 && p[0] == 23 {
		c.once.Do(func() { close(c.blocked) })
	}
	return c.Conn.Write(p)
}

func (d *blockedAuthDialer) DialContext(context.Context, string, M.Socksaddr) (net.Conn, error) {
	a, b := net.Pipe()
	go func() {
		defer b.Close()
		conn := stdtls.Server(b, &stdtls.Config{Certificates: []stdtls.Certificate{d.certificate}, MaxVersion: stdtls.VersionTLS12})
		ctx, cancel := context.WithTimeout(context.Background(), time.Second)
		defer cancel()
		if err := conn.HandshakeContext(ctx); err != nil {
			return
		}
		<-d.stop
	}()
	return &applicationWriteConn{Conn: a, blocked: d.blocked}, nil
}
func (d *blockedAuthDialer) ListenPacket(context.Context, M.Socksaddr) (net.PacketConn, error) {
	return nil, errors.New("unexpected UDP")
}

func TestCancelDuringAuthenticationWrite(t *testing.T) {
	p := newPKI(t)
	for _, mode := range []string{"caller", "client"} {
		t.Run(mode, func(t *testing.T) {
			d := &blockedAuthDialer{certificate: tlsCertificate(t, p.leaf), blocked: make(chan struct{}), stop: make(chan struct{})}
			defer close(d.stop)
			security, err := NewSecurity(SecurityOptions{ServerName: testName, Fingerprint: pin(p.leaf)})
			if err != nil {
				t.Fatal(err)
			}
			c, err := NewClient(context.Background(), ClientOptions{Server: M.ParseSocksaddr("127.0.0.1:12345"), Dialer: d, Security: security, Password: testPassword})
			if err != nil {
				t.Fatal(err)
			}
			defer c.Close()
			ctx, cancel := context.WithCancel(context.Background())
			defer cancel()
			done := make(chan error, 1)
			go func() {
				conn, err := c.DialContext(ctx, "tcp", M.ParseSocksaddr("127.0.0.1:80"))
				if conn != nil {
					conn.Close()
				}
				done <- err
			}()
			select {
			case <-d.blocked:
			case <-time.After(2 * time.Second):
				t.Fatal("authentication did not reach blocked write")
			}
			if mode == "caller" {
				cancel()
			} else {
				c.Close()
			}
			select {
			case err := <-done:
				if err == nil {
					t.Fatal("blocked write succeeded after cancellation")
				}
			case <-time.After(time.Second):
				t.Fatal("authentication write leaked after cancellation")
			}
		})
	}
}
