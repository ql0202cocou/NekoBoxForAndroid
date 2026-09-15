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
| dns: rule action `fallback` | `option/rule_action.go` (`DNSRouteActionOptions.Fallback`, JSON `fallback`), `route/rule/rule_action.go` (`RuleActionDNSRoute.Fallback`), `dns/router.go`: when a DNS query routed by a rule with `fallback: true` fails, matching continues at the next DNS rule instead of returning the error. Any non-success rcode counts as a failure, so a split-horizon server is never the final word. Used by the Android app to implement ordered multi-server fallback for per-group proxy-server nameservers. Since the `domain_resolver` migration (2026-09-10), outbound resolution no longer walks DNS rules — it binds to libcore's `neko-sequential` transport instead — so this patch only serves user-hijacked queries that match a `dns-group-N` rule. **1.14 rebase**: the DNS router was rewritten around a rule-walk state machine shared by `Exchange` and `Lookup`; the patch now marks the walk's pending exchange (`dnsPendingExchange.fallback`) and, on failure (skipping context cancellation), advances `state.ruleIndex` and re-enters the walk in `resumeExchangeWithRules`. `exchangeWithRulesAsync`'s direct-`ExchangeAsync` fast path is bypassed for fallback rules. Armed/speculative race paths (unused by the app) do not fall back. |
| router: lock `trackers` | `route/router.go`, `route/route.go`: upstream appends to `Router.trackers` without synchronization, and the Android app calls `AppendTracker` (via `SetV2rayStats`) while the box is already routing, so the append raced the per-connection reads in `RouteConnection`/`RoutePacketConnection` (slice growth tearing). Added a `sync.RWMutex` (`trackersAccess`): `AppendTracker` takes the write lock, the read paths take the read lock. 1.14: a third read site (L3 `NewTracker` closure building `tun.FlowTracker`s) is covered too. 1.14.1: upstream moved the forward log line inside the `NewTracker` closure (`metadataCopy`); the RLock now wraps the tracker reads after it. Drop if upstream adds its own locking. |

How to upgrade the base: clone upstream SagerNet/sing-box, merge or rebase the
patches onto the new tag, resolve conflicts, replace this directory with the
result (without `.git`), and update this file. The 1.13.18 → 1.14.0 rebase
extracted the patch set with `diff -ru` against the upstream tag and re-applied
it by hand — see git history. The 1.14.0 → 1.14.1 rebase used the same
`diff -ruN` extraction plus `patch`; only `box.go` (needCacheFile) and
`route/route.go` (NewTracker closure) needed manual conflict resolution.
