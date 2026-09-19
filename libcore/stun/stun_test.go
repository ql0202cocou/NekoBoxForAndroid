// Copyright 2016 Cong Ding
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package stun

import (
	"encoding/binary"
	"net"
	"strings"
	"testing"
	"time"
)

// fakeStunServer is a minimal UDP STUN server for tests: every received
// datagram is passed to handler, and the returned bytes are sent back from
// the receiving socket (a nil return drops the request).
type fakeStunServer struct {
	conn *net.UDPConn
}

func newFakeStunServer(t *testing.T, ip string, handler func(req []byte, raddr *net.UDPAddr) []byte) *fakeStunServer {
	t.Helper()
	conn, err := net.ListenUDP("udp", &net.UDPAddr{IP: net.ParseIP(ip)})
	if err != nil {
		t.Fatalf("listen: %v", err)
	}
	t.Cleanup(func() { conn.Close() })
	go func() {
		buf := make([]byte, maxPacketSize)
		for {
			n, raddr, err := conn.ReadFromUDP(buf)
			if err != nil {
				return
			}
			if resp := handler(buf[:n], raddr); resp != nil {
				conn.WriteToUDP(resp, raddr)
			}
		}
	}()
	return &fakeStunServer{conn: conn}
}

func (s *fakeStunServer) addr() *net.UDPAddr {
	return s.conn.LocalAddr().(*net.UDPAddr)
}

// stunResponse builds a STUN message of the given type, echoing the
// transaction id (including the magic cookie) of the request.
func stunResponse(req []byte, types uint16, attrs ...[]byte) []byte {
	var body []byte
	for _, a := range attrs {
		body = append(body, a...)
	}
	resp := make([]byte, 20)
	binary.BigEndian.PutUint16(resp[0:2], types)
	binary.BigEndian.PutUint16(resp[2:4], uint16(len(body)))
	copy(resp[4:20], req[4:20])
	return append(resp, body...)
}

// stunAttribute serializes one attribute with RFC 5389 padding.
func stunAttribute(types uint16, value []byte) []byte {
	attr := make([]byte, 4)
	binary.BigEndian.PutUint16(attr[0:2], types)
	binary.BigEndian.PutUint16(attr[2:4], uint16(len(value)))
	attr = append(attr, value...)
	for len(attr)%4 != 0 {
		attr = append(attr, 0)
	}
	return attr
}

// addressAttr builds a MAPPED-ADDRESS style attribute for an IPv4 address.
func addressAttr(types uint16, ip net.IP, port uint16) []byte {
	value := make([]byte, 8)
	value[1] = attributeFamilyIPv4
	binary.BigEndian.PutUint16(value[2:4], port)
	copy(value[4:8], ip.To4())
	return stunAttribute(types, value)
}

// errorCodeAttr builds an ERROR-CODE attribute (RFC 5389 section 15.6).
func errorCodeAttr(code int, reason string) []byte {
	value := []byte{0, 0, byte(code / 100), byte(code % 100)}
	value = append(value, reason...)
	return stunAttribute(attributeErrorCode, value)
}

// requestsChangeBoth reports whether the request carries a CHANGE-REQUEST
// attribute asking for both IP and port change.
func requestsChangeBoth(req []byte) bool {
	if len(req) < 20 {
		return false
	}
	length := int(binary.BigEndian.Uint16(req[2:4]))
	body := req[20:]
	if length > len(body) {
		return false
	}
	body = body[:length]
	for pos := 0; pos+4 <= len(body); {
		types := binary.BigEndian.Uint16(body[pos : pos+2])
		length := int(binary.BigEndian.Uint16(body[pos+2 : pos+4]))
		if pos+4+length > len(body) {
			return false
		}
		if types == attributeChangeRequest && length >= 4 && body[pos+7]&0x06 == 0x06 {
			return true
		}
		pos += int(align(uint16(length))) + 4
	}
	return false
}

// newTestClient returns a client with fast retransmission parameters so the
// rejection tests time out quickly instead of running the RFC 3489 schedule.
func newTestClient() *Client {
	c := NewClient()
	c.SetRetransmission(3, 20*time.Millisecond, 40*time.Millisecond)
	return c
}

func newTestConn(t *testing.T) *net.UDPConn {
	t.Helper()
	conn, err := net.ListenUDP("udp", &net.UDPAddr{IP: net.ParseIP("127.0.0.1")})
	if err != nil {
		t.Fatalf("listen: %v", err)
	}
	t.Cleanup(func() { conn.Close() })
	return conn
}

func TestBindingResponseAccepted(t *testing.T) {
	server := newFakeStunServer(t, "127.0.0.1", func(req []byte, raddr *net.UDPAddr) []byte {
		return stunResponse(req, typeBindingResponse,
			addressAttr(attributeMappedAddress, net.ParseIP("11.22.33.44"), 5566))
	})
	resp, err := newTestClient().sendBindingReq(newTestConn(t), server.addr(), false, false)
	if err != nil {
		t.Fatalf("sendBindingReq: %v", err)
	}
	if resp == nil {
		t.Fatal("expected a response, got nil")
	}
	if resp.mappedAddr == nil {
		t.Fatal("expected a mapped address, got nil")
	}
	if ip := resp.mappedAddr.IP(); ip != "11.22.33.44" {
		t.Errorf("mapped IP = %s, want 11.22.33.44", ip)
	}
	if port := resp.mappedAddr.Port(); port != 5566 {
		t.Errorf("mapped port = %d, want 5566", port)
	}
	if resp.serverAddr == nil {
		t.Error("expected a server address, got nil")
	}
}

func TestBindingErrorResponse(t *testing.T) {
	server := newFakeStunServer(t, "127.0.0.1", func(req []byte, raddr *net.UDPAddr) []byte {
		return stunResponse(req, typeBindingErrorResponse,
			errorCodeAttr(errorBadRequest, "Bad Request"))
	})
	resp, err := newTestClient().sendBindingReq(newTestConn(t), server.addr(), false, false)
	if err == nil {
		t.Fatal("expected an error for a binding error response, got nil")
	}
	if !strings.Contains(err.Error(), "400") {
		t.Errorf("error %q does not contain the error code 400", err)
	}
	if resp != nil {
		t.Errorf("expected no response, got %v", resp)
	}
}

// A packet the client must not accept is ignored until the request times out,
// so sendBindingReq reports no response and no error in every case here.
func TestMalformedResponsesRejected(t *testing.T) {
	mapped := func() []byte {
		return addressAttr(attributeMappedAddress, net.ParseIP("11.22.33.44"), 5566)
	}
	for _, tc := range []struct {
		name  string
		reply func(req []byte) []byte
	}{
		{"bad magic cookie", func(req []byte) []byte {
			resp := stunResponse(req, typeBindingResponse, mapped())
			// Corrupt the magic cookie but keep the 12-byte transaction id
			// intact, so only the cookie check can reject this packet.
			binary.BigEndian.PutUint32(resp[4:8], 0xdeadbeef)
			return resp
		}},
		{"transaction id mismatch", func(req []byte) []byte {
			resp := stunResponse(req, typeBindingResponse, mapped())
			// Flip the last transaction id byte; the magic cookie stays valid.
			resp[19] ^= 0xff
			return resp
		}},
		{"unexpected message type", func(req []byte) []byte {
			// A valid cookie and transaction id, but a message type this
			// client never receives.
			return stunResponse(req, typeSendResponse, mapped())
		}},
	} {
		t.Run(tc.name, func(t *testing.T) {
			server := newFakeStunServer(t, "127.0.0.1", func(req []byte, raddr *net.UDPAddr) []byte {
				return tc.reply(req)
			})
			resp, err := newTestClient().sendBindingReq(newTestConn(t), server.addr(), false, false)
			if err != nil {
				t.Fatalf("expected the packet to be ignored, got error: %v", err)
			}
			if resp != nil {
				t.Errorf("expected no response, got %v", resp)
			}
		})
	}
}

// TestDiscoverFullCone runs the whole Discover flow against a fake server:
// the main socket answers test1 with MAPPED-ADDRESS and CHANGED-ADDRESS, and
// the change-both request (test2) is answered from the changed address, as a
// real server would.
func TestDiscoverFullCone(t *testing.T) {
	// A single loopback address is all this test can bind portably, so
	// the changed address differs from the main one only in port. RFC
	// 5780 full cone detection requires the test2 response to come from a
	// different IP AND port, hence Discover must flag fakeFullCone here.
	alt, err := net.ListenUDP("udp", &net.UDPAddr{IP: net.ParseIP("127.0.0.1")})
	if err != nil {
		t.Fatalf("listen: %v", err)
	}
	t.Cleanup(func() { alt.Close() })
	altAddr := alt.LocalAddr().(*net.UDPAddr)
	server := newFakeStunServer(t, "127.0.0.1", func(req []byte, raddr *net.UDPAddr) []byte {
		resp := stunResponse(req, typeBindingResponse,
			addressAttr(attributeMappedAddress, net.ParseIP("11.22.33.44"), 5566),
			addressAttr(attributeChangedAddress, altAddr.IP, uint16(altAddr.Port)))
		if requestsChangeBoth(req) {
			alt.WriteToUDP(resp, raddr)
			return nil
		}
		return resp
	})
	client := newTestClient()
	client.SetServerAddr(server.addr().String())
	nat, host, fakeFullCone, err := client.Discover()
	if err != nil {
		t.Fatalf("Discover: %v", err)
	}
	if nat != NATFull {
		t.Errorf("NAT type = %v, want %v", nat, NATFull)
	}
	if !fakeFullCone {
		t.Error("fakeFullCone = false, want true (test2 response came from the same IP)")
	}
	if host == nil {
		t.Fatal("expected a host, got nil")
	}
	if host.IP() != "11.22.33.44" || host.Port() != 5566 {
		t.Errorf("host = %v, want 11.22.33.44:5566", host)
	}
}

func TestIpEqual(t *testing.T) {
	cases := []struct {
		a, b string
		want bool
	}{
		{"1.2.3.4", "1.2.3.4", true},
		{"1.2.3.4", "1.2.3.5", false},
		// IPv4-mapped IPv6 denotes the same address.
		{"::ffff:1.2.3.4", "1.2.3.4", true},
		// Compressed vs. full IPv6 notation.
		{"2001:db8::1", "2001:0db8:0000:0000:0000:0000:0000:0001", true},
		{"2001:db8::1", "2001:db8::2", false},
		{"::1", "::1", true},
		// Unparsable input falls back to string comparison.
		{"not-an-ip", "not-an-ip", true},
		{"not-an-ip", "1.2.3.4", false},
	}
	for _, c := range cases {
		if got := ipEqual(c.a, c.b); got != c.want {
			t.Errorf("ipEqual(%q, %q) = %v, want %v", c.a, c.b, got, c.want)
		}
	}
}
