package libcore

import (
	"context"
	"errors"
	"net"
	"testing"
	"time"

	dns "github.com/miekg/dns"
)

func TestStartLookupHostsCancel(t *testing.T) {
	packet, err := net.ListenPacket("udp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	ready := make(chan struct{})
	server := &dns.Server{PacketConn: packet, NotifyStartedFunc: func() { close(ready) }, Handler: dns.HandlerFunc(func(w dns.ResponseWriter, q *dns.Msg) {
		if q.Question[0].Name == "timeout.test." {
			return
		}
		reply := new(dns.Msg)
		reply.SetReply(q)
		if q.Question[0].Qtype == dns.TypeA {
			reply.Answer = []dns.RR{&dns.A{Hdr: dns.RR_Header{Name: q.Question[0].Name, Rrtype: dns.TypeA, Class: dns.ClassINET}, A: net.ParseIP("192.0.2.7")}}
		}
		if err := w.WriteMsg(reply); err != nil {
			t.Error(err)
		}
	})}
	go server.ActivateAndServe()
	<-ready
	defer server.Shutdown()
	address := "udp://" + packet.LocalAddr().String()

	t.Run("cancel while waiting", func(t *testing.T) {
		task := StartLookupHosts(address+"\n"+address, "timeout.test")
		time.AfterFunc(50*time.Millisecond, task.Cancel)
		start := time.Now()
		_, err := task.Await()
		if !errors.Is(err, context.Canceled) {
			t.Fatalf("expected cancel: %v", err)
		}
		if time.Since(start) > time.Second {
			t.Fatalf("cancel did not stop the lookup promptly: %v", time.Since(start))
		}
	})
	t.Run("success then cancel is harmless", func(t *testing.T) {
		task := StartLookupHosts(address, "fixture.test")
		got, err := task.Await()
		if err != nil || got != "192.0.2.7" {
			t.Fatalf("%q, %v", got, err)
		}
		task.Cancel()
		if again, err := task.Await(); err != nil || again != got {
			t.Fatalf("result changed after cancel: %q, %v", again, err)
		}
	})
	t.Run("cancel before await", func(t *testing.T) {
		task := StartLookupHosts(address, "timeout.test")
		task.Cancel()
		if _, err := task.Await(); !errors.Is(err, context.Canceled) {
			t.Fatalf("expected cancel: %v", err)
		}
	})
}
