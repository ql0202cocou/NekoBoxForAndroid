package libcore

import (
	"syscall"

	"golang.org/x/sys/unix"
)

// KillProcessByProcFD 由 Android 12 以上调用。fd 是调用方核实进程身份时持有的 /proc/PID 目录。
// 进程退出后此句柄失效，即使内核已复用原 PID 也不会把信号送给其它进程。
func KillProcessByProcFD(fd int32) int32 {
	if err := unix.PidfdSendSignal(int(fd), unix.SIGKILL, nil, 0); err != nil {
		return int32(err.(syscall.Errno))
	}
	return 0
}
