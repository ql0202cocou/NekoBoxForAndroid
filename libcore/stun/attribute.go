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
	"hash/crc32"
	"net"
)

type attribute struct {
	types  uint16
	length uint16
	value  []byte
}

func newAttribute(types uint16, value []byte) *attribute {
	att := new(attribute)
	att.types = types
	// RFC 5389 section 15: the length is the size of the value in bytes,
	// NOT including the padding; the value is stored padded to a multiple
	// of 4 so it can be serialized as-is.
	att.length = uint16(len(value))
	att.value = padding(value)
	return att
}

func newFingerprintAttribute(packet *packet) *attribute {
	crc := crc32.ChecksumIEEE(packet.bytes()) ^ fingerprint
	buf := make([]byte, 4)
	binary.BigEndian.PutUint32(buf, crc)
	return newAttribute(attributeFingerprint, buf)
}

func newSoftwareAttribute(name string) *attribute {
	return newAttribute(attributeSoftware, []byte(name))
}

func newChangeReqAttribute(changeIP bool, changePort bool) *attribute {
	value := make([]byte, 4)
	if changeIP {
		value[3] |= 0x04
	}
	if changePort {
		value[3] |= 0x02
	}
	return newAttribute(attributeChangeRequest, value)
}

//	0                   1                   2                   3
//	0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
//
// +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
// |x x x x x x x x|    Family     |         X-Port                |
// +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
// |                X-Address (Variable)
// +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
//
//	Figure 6: Format of XOR-MAPPED-ADDRESS Attribute
func (v *attribute) xorAddr(transID []byte) *Host {
	// 4 bytes header + 4 bytes IPv4 or 16 bytes IPv6 address;
	// the value comes from the server, so reject malformed lengths.
	if len(v.value) < 8 || len(v.value) > 20 {
		return nil
	}
	xorIP := make([]byte, 16)
	for i := 0; i < len(v.value)-4; i++ {
		xorIP[i] = v.value[i+4] ^ transID[i]
	}
	family := uint16(v.value[1])
	port := binary.BigEndian.Uint16(v.value[2:4])
	// Truncate if IPv4, otherwise net.IP sometimes renders it as an IPv6 address.
	if family == attributeFamilyIPv4 {
		xorIP = xorIP[:4]
	}
	x := binary.BigEndian.Uint16(transID[:2])
	return &Host{family, net.IP(xorIP).String(), port ^ x}
}

//	0                   1                   2                   3
//	0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
//
// +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
// |0 0 0 0 0 0 0 0|    Family     |           Port                |
// +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
// |                                                               |
// |                 Address (32 bits or 128 bits)                 |
// |                                                               |
// +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
//
//	Figure 5: Format of MAPPED-ADDRESS Attribute
func (v *attribute) rawAddr() *Host {
	// 4 bytes header + at least 4 bytes address; the value comes
	// from the server, so reject malformed lengths.
	if len(v.value) < 8 {
		return nil
	}
	host := new(Host)
	host.family = uint16(v.value[1])
	host.port = binary.BigEndian.Uint16(v.value[2:4])
	value := v.value
	// Truncate a copy if IPv4, otherwise net.IP sometimes renders it as an
	// IPv6 address; never mutate the attribute itself.
	if host.family == attributeFamilyIPv4 {
		value = value[:8]
	}
	host.ip = net.IP(value[4:]).String()
	return host
}
