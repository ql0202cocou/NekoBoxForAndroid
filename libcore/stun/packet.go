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
	"crypto/rand"
	"encoding/binary"
	"errors"
	"fmt"
	"math"
)

type packet struct {
	types      uint16
	length     uint16
	transID    []byte // 4 bytes magic cookie + 12 bytes transaction id
	attributes []attribute
}

func newPacket() (*packet, error) {
	v := new(packet)
	v.transID = make([]byte, 16)
	binary.BigEndian.PutUint32(v.transID[:4], magicCookie)
	_, err := rand.Read(v.transID[4:])
	if err != nil {
		return nil, err
	}
	v.attributes = make([]attribute, 0, 10)
	v.length = 0
	return v, nil
}

func newPacketFromBytes(packetBytes []byte) (*packet, error) {
	if len(packetBytes) < 20 {
		return nil, errors.New("Received data length too short.")
	}
	if len(packetBytes) > math.MaxUint16+20 {
		return nil, errors.New("Received data length too long.")
	}
	pkt := new(packet)
	pkt.types = binary.BigEndian.Uint16(packetBytes[0:2])
	pkt.length = binary.BigEndian.Uint16(packetBytes[2:4])
	pkt.transID = packetBytes[4:20]
	pkt.attributes = make([]attribute, 0, 10)
	// RFC 5389 section 6: every STUN message carries the magic cookie in
	// the transaction id field. The requests built by newPacket always set
	// it and a server echoes the transaction id verbatim, so a packet
	// without the cookie is not a reply to one of our requests.
	if binary.BigEndian.Uint32(packetBytes[4:8]) != magicCookie {
		return nil, errors.New("Received packet magic cookie mismatch.")
	}
	// This client only ever sends binding requests, so the only valid
	// incoming messages are binding responses and binding error responses
	// (RFC 5389 section 7); reject everything else.
	switch pkt.types {
	case typeBindingResponse, typeBindingErrorResponse:
	default:
		return nil, fmt.Errorf("Received packet is not a binding response (type 0x%04x).", pkt.types)
	}
	packetBytes = packetBytes[20:]
	// The header length covers the attributes only (the 20-byte header is
	// excluded); reject truncated packets and ignore trailing garbage.
	if int(pkt.length) > len(packetBytes) {
		return nil, errors.New("Received data length mismatch.")
	}
	packetBytes = packetBytes[:pkt.length]
	// pos+4 <= len: a zero-length attribute exactly at the end is valid.
	for pos := 0; pos+4 <= len(packetBytes); {
		types := binary.BigEndian.Uint16(packetBytes[pos : pos+2])
		length := int(binary.BigEndian.Uint16(packetBytes[pos+2 : pos+4]))
		end := pos + 4 + length
		if end > len(packetBytes) {
			return nil, errors.New("Received data format mismatch.")
		}
		value := packetBytes[pos+4 : end]
		attribute := newAttribute(types, value)
		pkt.addAttribute(*attribute)
		pos += int(align(uint16(length))) + 4
	}
	return pkt, nil
}

func (v *packet) addAttribute(a attribute) {
	v.attributes = append(v.attributes, a)
	v.length += align(a.length) + 4
}

func (v *packet) bytes() []byte {
	packetBytes := make([]byte, 4)
	binary.BigEndian.PutUint16(packetBytes[0:2], v.types)
	binary.BigEndian.PutUint16(packetBytes[2:4], v.length)
	packetBytes = append(packetBytes, v.transID...)
	for _, a := range v.attributes {
		buf := make([]byte, 2)
		binary.BigEndian.PutUint16(buf, a.types)
		packetBytes = append(packetBytes, buf...)
		binary.BigEndian.PutUint16(buf, a.length)
		packetBytes = append(packetBytes, buf...)
		packetBytes = append(packetBytes, a.value...)
	}
	return packetBytes
}

func (v *packet) getMappedAddr() *Host {
	return v.getRawAddr(attributeMappedAddress)
}

// errorCode returns an error describing the ERROR-CODE attribute of an
// error response (RFC 5389 section 15.6), falling back to a generic error
// when the attribute is missing or malformed.
func (v *packet) errorCode() error {
	for _, a := range v.attributes {
		if a.types != attributeErrorCode {
			continue
		}
		// 2 bytes reserved, 1 byte class (hundreds), 1 byte number, then
		// the reason phrase.
		if a.length < 4 {
			break
		}
		code := int(a.value[2]&0x07)*100 + int(a.value[3])
		// value 按 4 字节对齐补过 0，原因短语只取到 length 为止
		reason := string(a.value[4:a.length])
		if name, ok := errorCodeStr[code]; ok {
			return fmt.Errorf("Server error: %d %s: %s", code, name, reason)
		}
		return fmt.Errorf("Server error: %d: %s", code, reason)
	}
	return errors.New("Server error: binding error response.")
}

func (v *packet) getChangedAddr() *Host {
	return v.getRawAddr(attributeChangedAddress)
}

func (v *packet) getOtherAddr() *Host {
	return v.getRawAddr(attributeOtherAddress)
}

func (v *packet) getRawAddr(attribute uint16) *Host {
	for _, a := range v.attributes {
		if a.types == attribute {
			return a.rawAddr()
		}
	}
	return nil
}

func (v *packet) getXorMappedAddr() *Host {
	addr := v.getXorAddr(attributeXorMappedAddress)
	if addr == nil {
		addr = v.getXorAddr(attributeXorMappedAddressExp)
	}
	return addr
}

func (v *packet) getXorAddr(attribute uint16) *Host {
	for _, a := range v.attributes {
		if a.types == attribute {
			return a.xorAddr(v.transID)
		}
	}
	return nil
}
