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
	"net"
	"time"
)

// Client is a STUN client, which can be set STUN server address and is used
// to discover NAT type.
type Client struct {
	serverAddr   string
	softwareName string
	logger       *Logger
	// Retransmission parameters set by SetRetransmission; zero values mean
	// the RFC 3489 defaults in net.go.
	retransmitCount   int
	retransmitTimeout time.Duration
	retransmitMax     time.Duration
	// deadline set by SetDeadline bounds all requests issued by this client
	// (shared across Discover and BehaviorTest calls); zero means no limit.
	deadline time.Time
}

// NewClient returns a client without network connection. The network
// connection will be build when calling Discover function.
func NewClient() *Client {
	c := new(Client)
	c.SetSoftwareName(DefaultSoftwareName)
	c.logger = NewLogger()
	return c
}

// SetServerAddr allows user to set the transport layer STUN server address.
func (c *Client) SetServerAddr(address string) {
	c.serverAddr = address
}

// SetSoftwareName allows user to set the name of the software, which is sent
// to the server as the SOFTWARE attribute of every request.
func (c *Client) SetSoftwareName(name string) {
	c.softwareName = name
}

// SetRetransmission overrides the RFC 3489 retransmission parameters: at
// most count requests are sent per test, starting with an interval of
// initialTimeout, doubling every retransmit until the interval reaches
// maxTimeout. Non-positive values restore the RFC 3489 defaults.
func (c *Client) SetRetransmission(count int, initialTimeout, maxTimeout time.Duration) {
	c.retransmitCount = count
	c.retransmitTimeout = initialTimeout
	c.retransmitMax = maxTimeout
}

// SetDeadline sets an overall deadline shared by all requests this client
// issues (Discover and BehaviorTest combined): each read is capped at the
// deadline and pending retransmissions are skipped once it has passed. A
// zero value disables the limit.
func (c *Client) SetDeadline(t time.Time) {
	c.deadline = t
}

// resolveServerAddr resolves the configured STUN server address, falling
// back to DefaultServerAddr when none is set.
func (c *Client) resolveServerAddr() (*net.UDPAddr, error) {
	if c.serverAddr == "" {
		c.SetServerAddr(DefaultServerAddr)
	}
	return net.ResolveUDPAddr("udp", c.serverAddr)
}

// connection creates a new UDP connection; the caller owns and closes it.
func (c *Client) connection() (net.PacketConn, error) {
	return net.ListenUDP("udp", nil)
}

// Discover contacts the STUN server and gets the response of NAT type, host
// for UDP punching.
func (c *Client) Discover() (NATType, *Host, bool, error) {
	serverUDPAddr, err := c.resolveServerAddr()
	if err != nil {
		return NATError, nil, false, err
	}
	conn, err := c.connection()
	if err != nil {
		return NATError, nil, false, err
	}
	defer conn.Close()
	return c.discover(conn, serverUDPAddr)
}

func (c *Client) BehaviorTest() (*NATBehavior, error) {
	serverUDPAddr, err := c.resolveServerAddr()
	if err != nil {
		return nil, err
	}
	conn, err := c.connection()
	if err != nil {
		return nil, err
	}
	defer conn.Close()
	return c.behaviorTest(conn, serverUDPAddr)
}
