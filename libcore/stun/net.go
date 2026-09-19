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
	"bytes"
	"encoding/hex"
	"errors"
	"net"
	"time"
)

const (
	// RFC 3489 retransmission defaults, used unless the client overrides
	// them via SetRetransmission.
	numRetransmit  = 9
	defaultTimeout = 100
	maxTimeout     = 1600
	maxPacketSize  = 1024
)

func (c *Client) sendBindingReq(conn net.PacketConn, addr net.Addr, changeIP bool, changePort bool) (*response, error) {
	// Construct packet.
	pkt, err := newPacket()
	if err != nil {
		return nil, err
	}
	pkt.types = typeBindingRequest
	attribute := newSoftwareAttribute(c.softwareName)
	pkt.addAttribute(*attribute)
	if changeIP || changePort {
		attribute = newChangeReqAttribute(changeIP, changePort)
		pkt.addAttribute(*attribute)
	}
	// length of fingerprint attribute must be included into crc,
	// so we add it before calculating crc, then subtract it after calculating crc.
	pkt.length += 8
	attribute = newFingerprintAttribute(pkt)
	pkt.length -= 8
	pkt.addAttribute(*attribute)
	// Send packet.
	return c.send(pkt, conn, addr)
}

// RFC 3489: Clients SHOULD retransmit the request starting with an interval
// of 100ms, doubling every retransmit until the interval reaches 1.6s.
// Retransmissions continue with intervals of 1.6s until a response is
// received, or a total of 9 requests have been sent.
// The defaults can be overridden with SetRetransmission, and every read is
// additionally capped at the client deadline set with SetDeadline.
func (c *Client) send(pkt *packet, conn net.PacketConn, addr net.Addr) (*response, error) {
	// Serialize the request once: retransmissions below resend the same
	// bytes.
	pktBytes := pkt.bytes()
	c.logger.Info("\n" + hex.Dump(pktBytes))
	// Hand the connection back without the read deadlines set below, so
	// unrelated reads on the connection afterwards are not affected by
	// them.
	defer conn.SetReadDeadline(time.Time{})
	count := c.retransmitCount
	if count <= 0 {
		count = numRetransmit
	}
	timeout := c.retransmitTimeout
	if timeout <= 0 {
		timeout = defaultTimeout * time.Millisecond
	}
	maxT := c.retransmitMax
	if maxT <= 0 {
		maxT = maxTimeout * time.Millisecond
	}
	packetBytes := make([]byte, maxPacketSize)
	for i := 0; i < count; i++ {
		// Stop retransmitting once the overall deadline has passed.
		if !c.deadline.IsZero() && !time.Now().Before(c.deadline) {
			break
		}
		// Send packet to the server.
		length, err := conn.WriteTo(pktBytes, addr)
		if err != nil {
			return nil, err
		}
		if length != len(pktBytes) {
			return nil, errors.New("Error in sending data.")
		}
		readDeadline := time.Now().Add(timeout)
		if !c.deadline.IsZero() && c.deadline.Before(readDeadline) {
			readDeadline = c.deadline
		}
		err = conn.SetReadDeadline(readDeadline)
		if err != nil {
			return nil, err
		}
		if timeout < maxT {
			timeout *= 2
		}
		for {
			// Read from the port.
			length, raddr, err := conn.ReadFrom(packetBytes)
			if err != nil {
				var nerr net.Error
				if errors.As(err, &nerr) && nerr.Timeout() {
					break
				}
				return nil, err
			}
			p, err := newPacketFromBytes(packetBytes[0:length])
			if err != nil {
				// Ignore malformed (non-STUN) packets like mismatched
				// transIDs below: keep reading until timeout.
				continue
			}
			// If transId mismatches, keep reading until get a
			// matched packet or timeout.
			if !bytes.Equal(pkt.transID, p.transID) {
				continue
			}
			c.logger.Info("\n" + hex.Dump(packetBytes[0:length]))
			// RFC 5389 section 7: an error response is a valid reply to
			// the request, but it is not a successful binding response.
			if p.types == typeBindingErrorResponse {
				return nil, p.errorCode()
			}
			resp := newResponse(p, conn)
			resp.serverAddr = newHostFromStr(raddr.String())
			if resp.serverAddr == nil {
				return nil, errors.New("Server error: cannot parse server address.")
			}
			return resp, err
		}
	}
	return nil, nil
}
