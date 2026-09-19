package libcore

import "sync/atomic"

// intfBox / intfNB4A 由 InitCore 在进程启动时写入一次，之后被 box 的任意
// goroutine 读取（如 AutoDetectInterfaceControl），两侧没有共同的锁，
// 故用 atomic.Value 发布。读取一律经 boxIntf / nb4aIntf；未初始化时返回
// nil，与原来的零值行为一致。
var intfBox atomic.Value  // BoxPlatformInterface
var intfNB4A atomic.Value // NB4AInterface

func boxIntf() BoxPlatformInterface {
	v, _ := intfBox.Load().(BoxPlatformInterface)
	return v
}

func nb4aIntf() NB4AInterface {
	v, _ := intfNB4A.Load().(NB4AInterface)
	return v
}

// useProcfs / isBgProcess 同样由 InitCore 写一次、之后跨 goroutine 只读。
var useProcfs atomic.Bool
var isBgProcess atomic.Bool

type NB4AInterface interface {
	UseOfficialAssets() bool
	Selector_OnProxySelected(selectorTag string, tag string)
}

type BoxPlatformInterface interface {
	AutoDetectInterfaceControl(fd int32) error
	OpenTun(singTunOptionsJson, tunPlatformOptionsJson string) (int, error)
	UseProcFS() bool
	FindConnectionOwner(ipProtocol int32, sourceAddress string, sourcePort int32, destinationAddress string, destinationPort int32) (int32, error)
	PackageNameByUid(uid int32) (string, error)
	WIFIState() string
}
