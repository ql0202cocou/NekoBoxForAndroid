# Local patches on libneko

This directory is a vendored copy of upstream
[matsuridayo/libneko](https://github.com/matsuridayo/libneko) (imported in
`f6c0a71`). Upstream is unmaintained, but this fork has applied local fixes on
top of it. When rebasing onto upstream (or replacing this directory), re-apply
or drop each patch below after checking whether upstream has fixed the issue.

Local patches (in commit order):

| Commit | Title | Notes |
|---|---|---|
| `405f4e4` | ServeProtect returns error instead of log.Fatal | `protect_server/protect_server_linux.go`, `protect_server/protect_server_other.go`: `ServeProtect` signature changed from `io.Closer` to `(io.Closer, error)`; a failed `net.ListenUnix` now returns the error instead of killing the process. **API change** — call sites in libcore (`nb4a.go` / `protect.go`) depend on it, so a rebase must keep both halves in sync. |
| `744ac62` | protect handshake deadline + speedtest transport check | `protect_server_linux.go`: 5s `SetDeadline` on the accepted connection so a peer that connects but never sends cannot park the goroutine (and its fds) forever; also logs the `SetsockoptInt(SO_MARK)` error. `speedtest/speedtest.go`: `UrlTestStandard_Handshake` type-asserts `client.Transport` to `*http.Transport` and returns an error instead of panicking. |
| `5215997` | neko_log: serialize writer with a mutex | `neko_log/log.go`: added `sync.Mutex` to `logWriter` guarding `Write`/`Truncate`/`Close`. The flock in `Write` only excludes other processes — flock on the same fd is a no-op in-process — so concurrent goroutines could interleave writes or race a truncation. |
| `a02fc6f` | protectCtl callback returns error | `protect_server_linux.go`, `protect_server_other.go`: `protectCtl` signature changed from `func(fd int)` to `func(fd int) error`; Android protect failures are now logged instead of silently ignored. **API change** — same rebase caveat as `405f4e4`. |
| `145313b` | receive protect fd through ReadMsgUnix | `protect_server_linux.go`: `getOneFd` now reads SCM_RIGHTS via `UnixConn.ReadMsgUnix` (runtime poller) instead of a raw `syscall.Recvmsg` on the accepted socket, which is non-blocking and returned EAGAIN whenever the peer had not sent yet (the 5s deadline never applied). The poller also receives with `MSG_CMSG_CLOEXEC`, so the fd cannot leak into a plugin process forked meanwhile. Also: removed the reflect-based `GetFdFromConn` (broke with stdlib layout changes), switched `Accept` to `AcceptUnix`, checked `ParseSocketControlMessage`/`ParseUnixRights` errors, and close extra fds when the count is wrong. |
| — | neko_log: drop a failed log file from writers | `neko_log/log.go`: when the log file open fails, `f` is a nil `*os.File`; installing it into `writers` produced a typed-nil that the `w == nil` guard in `Write` cannot catch (the interface is non-nil), so file logging was silently lost. `f` is now appended to `writers` only when non-nil. (Uncommitted when recorded — fill in the hash on commit.) |
| — | protect_server: close rights fds on bad message count | `protect_server/protect_server_linux.go`: `getOneFd` returned immediately when the control-message count was not 1, leaking any SCM_RIGHTS fds those messages already carried; it now walks the messages and closes their fds, mirroring the existing `len(fds) != 1` branch. (Uncommitted when recorded — fill in the hash on commit.) |

The four `protect_server` patches stack on top of each other; rebase them as a
unit (or diff this directory against upstream and re-apply the result).
Upstream libneko has had no commits since the vendoring, so a plain file
comparison against the upstream repo is the easiest way to recover the patch
set.
