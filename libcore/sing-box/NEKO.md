# Neko patches on sing-box

This directory is a vendored copy of upstream
[SagerNet/sing-box](https://github.com/SagerNet/sing-box) **v1.14.1** plus the
NekoBox patch set (`1.14.1-neko-1`). The patches originate from
`MatsuriDayo/sing-box` (`aed32ee3066cdbc7d471e3e0415c5134088962df`,
`1.12.19-neko-1`); upstream NekoBox is unmaintained, so this fork maintains and
rebases the patches itself. When rebasing onto a newer upstream sing-box
release, re-apply or drop each patch below after checking whether upstream has
fixed the issue.

Functional neko commits (on top of the 1.12.x upstream base):

| Commit | Title | Notes |
|---|---|---|
| `a863df9b` | add boxapi | v2ray stats API used by the Android app (`boxapi/`). 1.14: `adapter.ConnectionTracker` gained `RoutedFlow` (L3 forwarding) — implemented in `boxapi/v2ray_stats_service.go` mirroring `experimental/v2rayapi/stats.go` |
| `6b4fdc8c` | nekoutils: add geoip geosite | `nekoutils/srs.go` helpers for libcore assets; `route/rule/rule_set_local.go` resolves `geoip:` / `geosite:` paths through those helpers before ordinary file loading, without a file watcher |
| `7cf44f37` | nekoutils: add selector callback | `nekoutils/callback.go`, group selector callback |
| `721602a8` | dialer: add DoNotSelectInterface | dialer option to skip VPN interface selection |
| ~~`0bc13363`~~ | ~~temp fix gvisor close~~ | **removed 2026-08-28**: upstream sing-tun's `GVisor.Close()` already contains the same fix (`Attach(nil)` + `CleanupEndpoints().Abort()`), and the patch's `unsafe.Pointer` field punning no longer matches sing-tun's `GVisor` layout (extra fields), so the punned `stack` was always nil — dead code that would read a garbage pointer on the next layout change |
| `d294d39b` | outbound/vless: disable flow when mux is enable | still unaddressed upstream as of 1.14.1 |
| `44169a9b` | fix needCacheFile | `box.go` creates the cache service only when `experimental.cache_file` is present and enabled; a Clash API configuration alone does not enable it. 1.14.1: upstream itself removed the `|| options.PlatformLogWriter != nil` clause from `needClashAPI` (clash mode separation); the patch now only removes it from `needCacheFile` |

The `1.12.x-neko-1` commits only bump `constant/version.go` and carry no code
changes.

Additional patches maintained by this fork (not from MatsuriDayo):

| Patch | Notes |
|---|---|
| dns: rule action `fallback` | `option/rule_action.go` (`DNSRouteActionOptions.Fallback`, JSON `fallback`), `route/rule/rule_action.go` (`RuleActionDNSRoute.Fallback`), `dns/router.go`: when a DNS query routed by a rule with `fallback: true` fails, matching continues at the next DNS rule instead of returning the error. Any non-success rcode counts as a failure, so a split-horizon server is never the final word. Used by the Android app to implement ordered multi-server fallback for per-group proxy-server nameservers. Since the `domain_resolver` migration (2026-09-10), outbound resolution no longer walks DNS rules — it binds to libcore's `neko-sequential` transport instead — so this patch only serves user-hijacked queries that match a `dns-group-N` rule. **1.14 rebase**: the DNS router was rewritten around a rule-walk state machine shared by `Exchange` and `Lookup`; the patch now marks the walk's pending exchange (`dnsPendingExchange.fallback`) and, on failure (skipping context cancellation), advances `state.ruleIndex` and re-enters the walk in `resumeExchangeWithRules`. `exchangeWithRulesAsync`'s direct-`ExchangeAsync` fast path is bypassed for fallback rules. Armed/speculative race paths (unused by the app) do not fall back. Regression tests: `dns/router_fallback_test.go` (fork-added, part of the patch artifact). |
| router: lock `trackers` | `route/router.go`, `route/route.go`: upstream appends to `Router.trackers` without synchronization, and the Android app calls `AppendTracker` (via `SetV2rayStats`) while the box is already routing, so the append raced the per-connection reads in `RouteConnection`/`RoutePacketConnection` (slice growth tearing). Added a `sync.RWMutex` (`trackersAccess`): `AppendTracker` takes the write lock, the read paths take the read lock. 1.14: a third read site (L3 `NewTracker` closure building `tun.FlowTracker`s) is covered too. 1.14.1: upstream moved the forward log line inside the `NewTracker` closure (`metadataCopy`); the RLock now wraps the tracker reads after it. Drop if upstream adds its own locking. Regression/concurrency tests: `route/router_tracker_test.go` (fork-added, part of the patch artifact). |

## Replayable patch artifact

The whole patch set is materialized as a single replayable diff,
`libcore/patches/sing-box-v1.14.1-neko-1.diff`: a clean clone of upstream tag
v1.14.1 plus this file applied with `patch -p1` reproduces this directory
exactly. Excluded from the artifact (and from the verification comparison):
`.git`, the `clients/` git submodule placeholders (not carried here), and this
`NEKO.md` (a management document, not part of the patches). Everything else is
in the diff — modified upstream files, new files (`boxapi/`, `nekoutils/`, the
fork's `*_test.go` additions) and the `go.mod`/`go.sum` divergence documented
below. `./run lib check_versions` replays the artifact against a fresh clone
and fails on any drift.

Regenerate the artifact after EVERY change to this directory (from the repo
root):

```bash
TMP=$(mktemp -d)
git clone --depth 1 --branch v1.14.1 https://github.com/SagerNet/sing-box "$TMP/a"
cp -R libcore/sing-box "$TMP/b"
(cd "$TMP" && diff -ruN --exclude=.git --exclude=clients --exclude=NEKO.md a b) \
  > libcore/patches/sing-box-v1.14.1-neko-1.diff
```

The `a`/`b` directory names are load-bearing: they keep the diff header paths
deterministic (`patch -p1` strips them). `diff` exiting 1 just means
"differences found", which is the expected outcome here.

How to upgrade the base: clone upstream SagerNet/sing-box, merge or rebase the
patches onto the new tag, resolve conflicts, replace this directory with the
result (without `.git`), regenerate the patch artifact as above (renamed for
the new tag and patch-set version), and update this file. The 1.13.18 → 1.14.0
rebase extracted the patch set with `diff -ru` against the upstream tag and
re-applied it by hand — see git history. The 1.14.0 → 1.14.1 rebase used the
same `diff -ruN` extraction plus `patch`; only `box.go` (needCacheFile) and
`route/route.go` (NewTracker closure) needed manual conflict resolution.

## go.mod divergence from upstream

`check_versions` requires every module shared with `libcore/go.mod` to sit at
the same version in both files, so dependency bumps land here too. As of
2026-09-18 this go.mod diverges from the upstream v1.14.1 one:

- `go` directive `1.26.0` (upstream `1.25.5`) — forced by `x/sys` below
- `github.com/miekg/dns v1.1.73` (upstream v1.1.72)
- `golang.org/x/mod v0.38.0` (upstream v0.37.0)
- `golang.org/x/sys v0.48.0` (upstream v0.47.0) — declares `go 1.26.0`,
  which is what pushes the go directive up; upstream sing-box ≥ 1.14 requires
  Go 1.25+ and its own CI builds with Go 1.26.8, so 1.26 stays within the
  upstream-supported range

Apply such bumps with `go mod tidy` in this directory (it also drops stale
indirect entries), and re-run `./run lib check_versions` afterwards.
