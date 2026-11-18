package libcore

import (
	"context"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing/common/buf"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
)

type trafficTestOutbound struct{ adapter.Outbound }

func (trafficTestOutbound) Tag() string { return "direct" }

type trafficTestPacketConn struct{ N.PacketConn }

func (trafficTestPacketConn) ReadPacket(buffer *buf.Buffer) (M.Socksaddr, error) {
	_, err := buffer.Write([]byte("abc"))
	return M.Socksaddr{}, err
}

// 应用自己的 HTTP 直拨沿用原始计数方向，不能因 router 上加过滤包装而悄悄丢统计。
func TestBoxTrafficTrackerKeepsProxyHTTPAccounting(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		io.WriteString(w, "synthetic response")
	}))
	defer server.Close()
	instance := newTestBox(t)
	defer instance.Close()
	instance.SetV2rayStats("direct", "c-1-mapping-2")
	if err := instance.Start(); err != nil {
		t.Fatal(err)
	}
	client, err := instance.newProxyHttpClient()
	if err != nil {
		t.Fatal(err)
	}
	defer client.CloseIdleConnections()
	response, err := client.Get(server.URL)
	if err != nil {
		t.Fatal(err)
	}
	defer response.Body.Close()
	if _, err := io.ReadAll(response.Body); err != nil {
		t.Fatal(err)
	}
	if got := instance.QueryStats("direct", "uplink"); got == 0 {
		t.Fatal("HTTP request was not counted")
	}
	if got := instance.QueryStats("direct", "downlink"); got < int64(len("synthetic response")) {
		t.Fatalf("HTTP response count = %d", got)
	}
}

func (trafficTestPacketConn) WritePacket(buffer *buf.Buffer, _ M.Socksaddr) error {
	buffer.Release()
	return nil
}

// 真正的统计服务和 Box 查询路径：普通入口、同前缀自定义入口照常计，映射回流在 TCP / UDP / flow 都不重复计。
func TestBoxTrafficTrackerIgnoresOnlyGeneratedMappingInbounds(t *testing.T) {
	instance := newTestBox(t)
	defer instance.Close()
	instance.SetV2rayStats("direct", "c-1-mapping-2\n")
	ctx := context.Background()
	for _, inbound := range []string{"tun-in", "c-1-mapping-2", "c-1-mapping-2-custom", ""} {
		t.Run(inbound, func(t *testing.T) {
			metadata := adapter.InboundContext{Inbound: inbound}
			client, peer := net.Pipe()
			defer client.Close()
			defer peer.Close()
			client.SetDeadline(time.Now().Add(5 * time.Second))
			peer.SetDeadline(time.Now().Add(5 * time.Second))
			conn := instance.statsTracker.RoutedConnection(ctx, client, metadata, nil, trafficTestOutbound{})
			done := make(chan error, 1)
			go func() {
				if _, err := peer.Write([]byte("abc")); err != nil {
					done <- err
					return
				}
				_, err := io.ReadFull(peer, make([]byte, 2))
				done <- err
			}()
			if _, err := io.ReadFull(conn, make([]byte, 3)); err != nil {
				t.Fatal(err)
			}
			if _, err := conn.Write([]byte("de")); err != nil {
				t.Fatal(err)
			}
			if err := <-done; err != nil {
				t.Fatal(err)
			}

			packet := instance.statsTracker.RoutedPacketConnection(ctx, trafficTestPacketConn{}, metadata, nil, trafficTestOutbound{})
			buffer := buf.New()
			if _, err := packet.ReadPacket(buffer); err != nil {
				t.Fatal(err)
			}
			buffer.Release()
			buffer = buf.As([]byte("de"))
			if err := packet.WritePacket(buffer, M.Socksaddr{}); err != nil {
				t.Fatal(err)
			}

			flow := instance.statsTracker.RoutedFlow(ctx, metadata, nil, trafficTestOutbound{})
			if flow != nil {
				flow.CountForward(7)
				flow.CountReverse(11)
			}
			wantUp, wantDown := int64(13), int64(15)
			if inbound == "c-1-mapping-2" {
				wantUp, wantDown = 0, 0
				if flow != nil || conn != client {
					t.Fatal("mapping traffic was wrapped")
				}
			}
			if got := instance.QueryStats("direct", "uplink"); got != wantUp {
				t.Fatalf("uplink = %d, want %d", got, wantUp)
			}
			if got := instance.QueryStats("direct", "downlink"); got != wantDown {
				t.Fatalf("downlink = %d, want %d", got, wantDown)
			}
		})
	}
}
