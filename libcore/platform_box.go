package libcore

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"libcore/device"
	"libcore/procfs"
	"net/netip"
	"strings"
	"syscall"

	"github.com/matsuridayo/libneko/neko_log"
	"github.com/sagernet/sing-box/adapter"
	sblog "github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing-box/option"
	tun "github.com/sagernet/sing-tun"
	"github.com/sagernet/sing/common/logger"
	N "github.com/sagernet/sing/common/network"
	"golang.org/x/sys/unix"
)

var boxPlatformInterfaceInstance adapter.PlatformInterface = &boxPlatformInterfaceWrapper{}

type boxPlatformInterfaceWrapper struct{}

func (w *boxPlatformInterfaceWrapper) Initialize(n adapter.NetworkManager) error {
	return nil
}

func (w *boxPlatformInterfaceWrapper) UsePlatformAutoDetectInterfaceControl() bool {
	return true
}

func (w *boxPlatformInterfaceWrapper) AutoDetectInterfaceControl(fd int) (err error) {
	defer device.DeferPanicToError("boxPlatformInterface.AutoDetectInterfaceControl", func(err_ error) { err = err_ })

	// call protect_path
	if !isBgProcess.Load() {
		// Log but don't return the error: the main-process URL test dials
		// directly when the VPN is not running, and a missing protect socket
		// is the normal case then — failing the dial would break the test.
		if path := protectSocketPath.Load(); path != nil {
			if err := sendFdToProtect(fd, *path); err != nil {
				warnProtectFailed(fd, err)
			}
		} else {
			warnProtectFailed(fd, errors.New("protect socket path not initialized"))
		}
		return nil
	}
	// bg process call VPNService
	return boxIntf().AutoDetectInterfaceControl(int32(fd))
}

func (w *boxPlatformInterfaceWrapper) UsePlatformInterface() bool {
	return true
}

func (w *boxPlatformInterfaceWrapper) OpenInterface(options *tun.Options, platformOptions option.TunPlatformOptions) (ret tun.Tun, err error) {
	defer device.DeferPanicToError("boxPlatformInterface.OpenInterface", func(err_ error) { err = err_ })

	if len(options.IncludeUID) > 0 || len(options.ExcludeUID) > 0 {
		return nil, errors.New("android: unsupported uid options")
	}
	if len(options.IncludeAndroidUser) > 0 {
		return nil, errors.New("android: unsupported android_user option")
	}
	a, _ := json.Marshal(options)
	b, _ := json.Marshal(platformOptions)
	tunFd, err := boxIntf().OpenTun(string(a), string(b))
	if err != nil {
		return nil, fmt.Errorf("intfBox.OpenTun: %w", err)
	}
	// The original fd is owned by the Kotlin side (closed via conn.close());
	// dup it so the sing-box tun owns its own copy and manages its lifecycle.
	// Use F_DUPFD_CLOEXEC so plugin binaries exec'd by :bg don't inherit it.
	tunFd, err = unix.FcntlInt(uintptr(tunFd), unix.F_DUPFD_CLOEXEC, 0)
	if err != nil {
		return nil, fmt.Errorf("F_DUPFD_CLOEXEC: %w", err)
	}
	//
	options.FileDescriptor = int(tunFd)
	tunStack, err := tun.New(*options)
	if err != nil {
		unix.Close(tunFd)
		return nil, err
	}
	return tunStack, nil
}

func (w *boxPlatformInterfaceWrapper) ProcessPlatformOptions(options option.TunPlatformOptions) error {
	return nil
}

func (w *boxPlatformInterfaceWrapper) UsePlatformDefaultInterfaceMonitor() bool {
	return true
}

func (w *boxPlatformInterfaceWrapper) CreateDefaultInterfaceMonitor(l logger.Logger) tun.DefaultInterfaceMonitor {
	return &interfaceMonitorStub{}
}

func (w *boxPlatformInterfaceWrapper) UsePlatformNetworkInterfaces() bool {
	return false
}

func (w *boxPlatformInterfaceWrapper) NetworkInterfaces() ([]adapter.NetworkInterface, error) {
	return nil, errors.New("not implemented")
}

// Android not using

func (w *boxPlatformInterfaceWrapper) UnderNetworkExtension() bool {
	return false
}

func (w *boxPlatformInterfaceWrapper) NetworkExtensionIncludeAllNetworks() bool {
	return false
}

func (w *boxPlatformInterfaceWrapper) ClearDNSCache() {
}

func (w *boxPlatformInterfaceWrapper) RequestPermissionForWIFIState() error {
	return nil
}

func (w *boxPlatformInterfaceWrapper) ReadWIFIState(ctx context.Context) adapter.WIFIState {
	defer device.DeferPanicToError("boxPlatformInterface.ReadWIFIState", nil)

	// Format is "ssid,bssid"; split from the end since the SSID may contain commas
	// while the BSSID is a MAC address and never does.
	state := boxIntf().WIFIState()
	sep := strings.LastIndex(state, ",")
	if sep < 0 {
		return adapter.WIFIState{}
	}
	return adapter.WIFIState{
		SSID:  state[:sep],
		BSSID: state[sep+1:],
	}
}

func (w *boxPlatformInterfaceWrapper) SystemCertificates() []string {
	return nil
}

func (w *boxPlatformInterfaceWrapper) UsePlatformConnectionOwnerFinder() bool {
	return true
}

func (w *boxPlatformInterfaceWrapper) FindConnectionOwner(request *adapter.FindConnectionOwnerRequest) (owner *adapter.ConnectionOwner, err error) {
	defer device.DeferPanicToError("boxPlatformInterface.FindConnectionOwner", func(err_ error) { err = err_ })

	var network string
	switch request.IpProtocol {
	case syscall.IPPROTO_TCP:
		network = N.NetworkTCP
	case syscall.IPPROTO_UDP:
		network = N.NetworkUDP
	default:
		return nil, fmt.Errorf("unknown ip protocol: %d", request.IpProtocol)
	}
	var uid int32
	if useProcfs.Load() {
		sourceAddr, err := netip.ParseAddr(request.SourceAddress)
		if err != nil {
			return nil, err
		}
		uid = procfs.ResolveSocketByProcSearch(network, netip.AddrPortFrom(sourceAddr, uint16(request.SourcePort)), netip.AddrPort{})
		if uid == -1 {
			return nil, errors.New("procfs: not found")
		}
	} else {
		u, err := boxIntf().FindConnectionOwner(request.IpProtocol, request.SourceAddress, request.SourcePort, request.DestinationAddress, request.DestinationPort)
		if err != nil {
			return nil, err
		}
		uid = u
		if uid < 0 {
			// INVALID_UID: keep parity with the procfs path, otherwise
			// PackageNameByUid(-1) would misattribute the connection
			return nil, errors.New("connection owner: not found")
		}
	}
	owner = &adapter.ConnectionOwner{UserId: uid}
	if packageName, err := boxIntf().PackageNameByUid(uid); err == nil && packageName != "" {
		owner.AndroidPackageNames = []string{packageName}
	}
	return owner, nil
}

func (w *boxPlatformInterfaceWrapper) UsePlatformWIFIMonitor() bool {
	return true
}

func (w *boxPlatformInterfaceWrapper) UsePlatformNotification() bool {
	return false
}

func (w *boxPlatformInterfaceWrapper) SendNotification(notification *adapter.Notification) error {
	return nil
}

func (w *boxPlatformInterfaceWrapper) CancelNotification(identifier string, typeID int32) error {
	return nil
}

func (w *boxPlatformInterfaceWrapper) MyInterfaceAddress() []netip.Addr {
	return nil
}

func (w *boxPlatformInterfaceWrapper) UsePlatformNeighborResolver() bool {
	return false
}

func (w *boxPlatformInterfaceWrapper) StartNeighborMonitor(listener adapter.NeighborUpdateListener) error {
	return errors.New("not implemented")
}

func (w *boxPlatformInterfaceWrapper) CloseNeighborMonitor(listener adapter.NeighborUpdateListener) error {
	return nil
}

func (w *boxPlatformInterfaceWrapper) UsePlatformShell() bool {
	return false
}

func (w *boxPlatformInterfaceWrapper) CheckPlatformShell() error {
	return nil
}

func (w *boxPlatformInterfaceWrapper) OpenShellSession(user *adapter.PlatformUser, command string, env []string, term string, rows int32, cols int32) (adapter.ShellSession, error) {
	return nil, errors.New("not implemented")
}

func (w *boxPlatformInterfaceWrapper) LookupUser(username string) (*adapter.PlatformUser, error) {
	return nil, errors.New("not implemented")
}

func (w *boxPlatformInterfaceWrapper) LookupSFTPServer() (string, error) {
	return "", errors.New("not implemented")
}

func (w *boxPlatformInterfaceWrapper) ReadSystemSSHHostKey() ([]byte, error) {
	return nil, errors.New("not implemented")
}

func (w *boxPlatformInterfaceWrapper) TailscaleHostname() string {
	return ""
}

func (w *boxPlatformInterfaceWrapper) UsePlatformBridge() bool {
	return false
}

func (w *boxPlatformInterfaceWrapper) CreateBridge(options adapter.BridgeOptions) (adapter.BridgeSession, error) {
	return nil, errors.New("not implemented")
}

// 日志

type boxPlatformLogWriterWrapper struct {
}

var boxPlatformLogWriter sblog.PlatformWriter = &boxPlatformLogWriterWrapper{}

func (w *boxPlatformLogWriterWrapper) DisableColors() bool { return true }

func (w *boxPlatformLogWriterWrapper) WriteMessage(level uint8, message string) {
	defer device.DeferPanicToError("boxPlatformLogWriter.WriteMessage", nil)

	if !strings.HasSuffix(message, "\n") {
		message += "\n"
	}
	neko_log.LogWriter.Write([]byte(message))
}
