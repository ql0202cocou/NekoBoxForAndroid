package libcore

import (
	"os"
	"strconv"

	"golang.org/x/sys/unix"
)

// Replaceable assets are written by two processes: :bg extracts them from the APK
// at InitCore, the main process imports or downloads them from AssetsActivity.
// Both publish through the same POSIX record lock (Java's FileChannel.lock is an
// fcntl lock too; flock(2) would be invisible to it) and through per-process
// temporary names, so one side can never rename the other's half-written file.
func withAssetsLock(lockPath string, fn func() error) error {
	file, err := os.OpenFile(lockPath, os.O_RDWR|os.O_CREATE, 0o600)
	if err != nil {
		return err
	}
	defer file.Close()
	lock := unix.Flock_t{Type: unix.F_WRLCK, Whence: 0, Start: 0, Len: 0}
	// F_SETLKW blocks until the lock is granted instead of failing fast.
	// Acceptable here because the only peers are this app's own processes and
	// the kernel releases the lock when the holder dies, so a wait either ends
	// quickly or outlives the crashed peer.
	if err := unix.FcntlFlock(file.Fd(), unix.F_SETLKW, &lock); err != nil {
		return err
	}
	// closing the descriptor releases the record lock
	return fn()
}

// tempName keeps concurrent writers of the same destination apart.
func tempName(destination string, suffix string) string {
	return destination + "." + suffix + "." + strconv.Itoa(os.Getpid())
}
