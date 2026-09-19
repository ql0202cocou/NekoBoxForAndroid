package libcore

import (
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"testing"

	"golang.org/x/sys/unix"
)

// POSIX record locks are per process, so exclusion can only be observed from a
// child: the parent holds the lock and asks the child to try a non-blocking one.
func TestAssetsLockExcludesOtherProcess(t *testing.T) {
	if path := os.Getenv("ASSETS_LOCK_CHILD"); path != "" {
		file, err := os.OpenFile(path, os.O_RDWR|os.O_CREATE, 0o600)
		if err != nil {
			t.Fatal(err)
		}
		defer file.Close()
		lock := unix.Flock_t{Type: unix.F_WRLCK, Whence: 0, Start: 0, Len: 0}
		if err := unix.FcntlFlock(file.Fd(), unix.F_SETLK, &lock); err != nil {
			os.Stdout.WriteString("blocked")
		} else {
			os.Stdout.WriteString("acquired")
		}
		return
	}
	lockPath := filepath.Join(t.TempDir(), "assets.lock")
	tryChild := func() string {
		cmd := exec.Command(os.Args[0], "-test.run=^TestAssetsLockExcludesOtherProcess$")
		cmd.Env = append(os.Environ(), "ASSETS_LOCK_CHILD="+lockPath)
		out, err := cmd.Output()
		if err != nil {
			t.Fatalf("child: %v %s", err, out)
		}
		switch {
		case strings.Contains(string(out), "blocked"):
			return "blocked"
		case strings.Contains(string(out), "acquired"):
			return "acquired"
		}
		t.Fatalf("unexpected child output %q", out)
		return ""
	}
	err := withAssetsLock(lockPath, func() error {
		if got := tryChild(); got != "blocked" {
			t.Fatalf("child got the lock while held: %s", got)
		}
		return nil
	})
	if err != nil {
		t.Fatal(err)
	}
	if got := tryChild(); got != "acquired" {
		t.Fatalf("lock not released: %s", got)
	}
	if got, want := tempName("/x/geoip.db", "tmp"), "/x/geoip.db.tmp."+strconv.Itoa(os.Getpid()); got != want {
		t.Fatalf("tempName %q, want %q", got, want)
	}
}
