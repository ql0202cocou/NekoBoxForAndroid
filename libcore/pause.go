package libcore

import (
	"context"

	"github.com/sagernet/sing/service"
	"github.com/sagernet/sing/service/pause"
)

// 网络暂停在 libcore 里没有来源：interfaceMonitorStub 永远报不出默认接口（默认网络由 Kotlin 侧跟踪），
// sing-box 的 NetworkManager 因此在每次启动的 PostStart 必然 NetworkPause 一次，而 NetworkWake 只在拿到默认接口时
// 才发。暂停回调在 Box.Start 里同步执行：WireGuard 端点随之 device.Down()，要等 wireguard-go 的定时器与
// ClientBind 的重连退避返回，它们却在 WaitActive 里等网络恢复——Start 返回之前不会恢复，于是互相卡死，
// Start 永不返回（WireGuard 的前一跳连不上时必现）。所以 box.New 之前登记一个吞掉网络暂停 / 恢复的管理器
// （pause.WithDefaultManager 沿用已登记的）；设备暂停（Sleep / Wake）原样交给内层的默认管理器
func registerNoNetworkPauseManager(ctx context.Context) {
	inner := service.FromContext[pause.Manager](pause.WithDefaultManager(ctx))
	service.MustRegister[pause.Manager](ctx, &noNetworkPauseManager{Manager: inner})
}

type noNetworkPauseManager struct {
	pause.Manager
}

func (m *noNetworkPauseManager) NetworkPause() {}

func (m *noNetworkPauseManager) NetworkWake() {}
