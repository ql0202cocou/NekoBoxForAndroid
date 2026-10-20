package libcore

import (
	"bytes"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"runtime"
	"testing"
	"time"
)

// 合法长度（32 字节）的 WireGuard 密钥，fill 不同则密钥不同
func wireGuardTestKey(fill byte) string {
	return base64.StdEncoding.EncodeToString(bytes.Repeat([]byte{fill}, 32))
}

// count 个 WireGuard 端点（对端都在本机没有监听的端口上），detour 非空时都经它拨号；
// extraOutbounds 追加在 direct 之后
func wireGuardTestConfig(t *testing.T, count int, detour string, keepalive int, extraOutbounds ...map[string]any) string {
	t.Helper()
	var endpoints []map[string]any
	for i := 0; i < count; i++ {
		peer := map[string]any{
			"address":     "127.0.0.1",
			"port":        40000 + i,
			"public_key":  wireGuardTestKey(byte(0x80 + i)),
			"allowed_ips": []string{"0.0.0.0/0", "::/0"},
		}
		if keepalive > 0 {
			peer["persistent_keepalive_interval"] = keepalive
		}
		endpoint := map[string]any{
			"type":        "wireguard",
			"tag":         fmt.Sprintf("wg-%d", i),
			"address":     []string{"10.66.0.2/32"},
			"mtu":         1408,
			"private_key": wireGuardTestKey(byte(0x10 + i)),
			"peers":       []any{peer},
		}
		if detour != "" {
			endpoint["detour"] = detour
		}
		endpoints = append(endpoints, endpoint)
	}
	outbounds := []any{map[string]any{"type": "direct", "tag": "direct"}}
	for _, outbound := range extraOutbounds {
		outbounds = append(outbounds, outbound)
	}
	config, err := json.Marshal(map[string]any{
		"log":       map[string]any{"level": "panic"},
		"endpoints": endpoints,
		"outbounds": outbounds,
	})
	if err != nil {
		t.Fatal(err)
	}
	return string(config)
}

// 在 limit 内等 fn 返回；超时即判定卡死（卡住的协程留在原地，测试照常失败）
func runWithin(t *testing.T, what string, limit time.Duration, fn func() error) {
	t.Helper()
	result := make(chan error, 1)
	go func() { result <- fn() }()
	select {
	case err := <-result:
		if err != nil {
			t.Fatalf("%s: %v", what, err)
		}
	case <-time.After(limit):
		t.Fatalf("%s did not return within %v", what, limit)
	}
}

// WireGuard 作链式代理的后一跳、前一跳连不上（这里是本机拒绝连接的 SOCKS）时，Start 与 Close 都要返回。
// 原来 PostStart 那次 NetworkPause 让端点在 Start 里 Down，而 Up 时发 keepalive 的 ClientBind.Send 正持着
// 设备状态锁在 WaitActive 里等网络恢复，Start 永不返回（见 pause.go）
func TestBoxInstanceWireGuardBehindDeadDetour(t *testing.T) {
	config := wireGuardTestConfig(t, 2, "dead-socks", 25, map[string]any{
		"type": "socks", "tag": "dead-socks", "server": "127.0.0.1", "server_port": 1,
	})
	instance, err := NewSingBoxInstance(config, nil)
	if err != nil {
		t.Fatal(err)
	}
	runWithin(t, "Start", 10*time.Second, instance.Start)
	runWithin(t, "Close", 10*time.Second, instance.Close)
}

func heapInuseAfterGC() uint64 {
	runtime.GC()
	runtime.GC()
	var stats runtime.MemStats
	runtime.ReadMemStats(&stats)
	return stats.HeapInuse
}

// 不设 detour 的 WireGuard 端点不能再按 StdNetBind 的批量（128 × 64 KB，发送与 IPv4 / IPv6 接收各一份）
// 预占缓冲：原来每个端点约 24 MB，选择器分组里几十个成员同时启动就是上 GB 的堆，反复启停后 :bg 内存失控
// （vendored sing-box 的 1.14.3-neko-3 补丁，见 libcore/sing-box/NEKO.md）
func TestBoxInstanceWireGuardEndpointsHeap(t *testing.T) {
	const endpointCount = 10
	before := heapInuseAfterGC()
	instance, err := NewSingBoxInstance(wireGuardTestConfig(t, endpointCount, "", 0), nil)
	if err != nil {
		t.Fatal(err)
	}
	defer instance.Close()
	if err := instance.Start(); err != nil {
		t.Fatal(err)
	}
	// 设备 Up、接收协程拿缓冲都在 Start 返回之后异步发生
	time.Sleep(time.Second)
	after := heapInuseAfterGC()
	runtime.KeepAlive(instance)
	var grown uint64
	if after > before {
		grown = after - before
	}
	if limit := uint64(endpointCount) * 2 << 20; grown > limit {
		t.Fatalf("%d WireGuard endpoints grew the heap by %d MB after Start, over 2 MB each", endpointCount, grown>>20)
	}
	t.Logf("%d WireGuard endpoints: heap +%.1f MB", endpointCount, float64(grown)/(1<<20))
}
