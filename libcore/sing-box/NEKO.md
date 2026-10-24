# Neko patches on sing-box

This directory is a vendored copy of upstream
[SagerNet/sing-box](https://github.com/SagerNet/sing-box) **v1.14.4** plus the
NekoBox patch set (`1.14.4-neko-1`). The patches originate from
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
| `721602a8` | dialer: add DoNotSelectInterface | dialer option to skip VPN interface selection. Regression tests (with the neko-2 entry points below): `common/dialer/default_neko_test.go` (fork-added, part of the patch artifact) builds a `DefaultDialer` with a network strategy but no network manager, so any of the four entry points taking the interface-selection path panics on the nil manager |
| ~~`0bc13363`~~ | ~~temp fix gvisor close~~ | **removed 2026-08-28**: upstream sing-tun's `GVisor.Close()` already contains the same fix (`Attach(nil)` + `CleanupEndpoints().Abort()`), and the patch's `unsafe.Pointer` field punning no longer matches sing-tun's `GVisor` layout (extra fields), so the punned `stack` was always nil — dead code that would read a garbage pointer on the next layout change |
| `d294d39b` | outbound/vless: disable flow when mux is enable | still unaddressed upstream as of 1.14.4 (`protocol/vless/outbound.go` is byte-identical to 1.14.3) |
| `44169a9b` | fix needCacheFile | `box.go` creates the cache service only when `experimental.cache_file` is present and enabled; a Clash API configuration alone does not enable it. 1.14.1: upstream itself removed the `|| options.PlatformLogWriter != nil` clause from `needClashAPI` (clash mode separation); the patch now only removes it from `needCacheFile`. 1.14.2-neko-1: the same clause also moved into the condition that creates `trafficcontrol.Manager` + `clashmode.Manager`; libcore always passes `PlatformLogWriter`, so both were always created — one more tracker per connection plus retained closed-connection metadata, though the app only reads them with the Clash API on. The patch drops the clause there too (back to the 1.14.0 condition `needClashAPI \|\| needAPIService`); without a clash mode manager, `clash_mode` rule items simply never match, and the app never emits them. 1.14.3: upstream rewrote `New` / `Start` / `Close` around a lifecycle scope, but both patched conditions are unchanged lines and the hunks applied as they were. 1.14.4: `trafficcontrol.NewManager` lost its `outboundManager` argument on the line after the second patched condition, so that hunk was rejected and re-applied by hand; the condition itself is unchanged upstream. Pinned by `TestBoxInstanceOptionalServicesNeedExplicitConfig` in `libcore/box_test.go` (minimal config registers neither the cache service nor the traffic manager and leaves no `cache.db`; positive controls for `cache_file` / `clash_api`). Mutation check (repeated on 1.14.3): with either clause restored the other box tests still pass and only leave a `cache.db` in the working directory (the cache service then also restores the selector's stored choice on start); the new test fails on it |

Accepted upstream behavior (no patch):

- Doze and WireGuard: since 1.14.1 the WireGuard endpoint only reacts to
  `NetworkPause`/`NetworkWake`, while libcore's `Sleep()` sends `DevicePause`,
  so a WireGuard tunnel no longer goes down in doze. Kept as upstream: sending
  `NetworkPause` from `Sleep()` would also affect every other listener of that
  signal, and the battery cost is unmeasured (reasoned small: Go timers do not
  wake a deep-sleeping device, keepalives only go out in maintenance windows).
  Revisit with measurements if battery complaints show up.
  Re-checked on 1.14.4 (source): sing's default pause manager (sing
  5dc452e537bf) now keeps a single "active" channel closed only while
  neither the device nor the network is paused, and gained
  `WaitActiveContext`; the WireGuard endpoint still reacts only to
  `EventNetworkPause` / `EventNetworkWake`, but now runs `Down()` / `Up()`
  in a goroutine under a state lock, following `IsNetworkPaused()`
  (cc022098). The pinned wireguard-go (e4d85ef2ed38) now measures keypair
  and handshake ages in wall-clock time including sleep (keys older than
  `RejectAfterTime` after a long doze count as expired and trigger a new
  handshake) and its timers wait in `WaitActive` before firing instead of
  after. Neither closes a connection on `DevicePause`, so the conclusion
  above stands.
  Re-checked on 1.14.3 (source): sing's `service/pause` is byte-identical
  between the sing versions of 1.14.2 and 1.14.3, and the WireGuard endpoint
  (`transport/wireguard/endpoint.go` `onPauseUpdated`) still only handles
  `EventNetworkPause` / `EventNetworkWake`. `DevicePause` / `DeviceWake` still
  only flip the device-paused state that `WaitActive` (in sing-box only the
  WireGuard `ClientBind`'s receive back-off calls it — its `Send` stopped
  waiting in 1.14.3-neko-3 — plus every wireguard-go timer) and the urltest
  group's checks read;
  neither closes a connection. Idle-connection closing is new in 1.14.3
  (`Box.CloseIdleConnections` → `ReferenceManager.CloseIdleConnections`: idle
  mux sessions, idle QUIC/HTTP transport connections and idle DNS connections
  of every outbound / DNS server that implements
  `adapter.IdleConnectionKeeper`), but nothing inside sing-box calls it: only
  the official clients' `experimental/libbox/command_server.go` does, on
  `Wake()` after a sleep of at least two minutes (21ba30b8; 07130365 had
  called it on every `Pause()`), before `DeviceWake` (not on iOS). libcore
  does not call it, so in this app doze closes nothing; connections left
  stale by a long doze fail on their next use as before. Not adopted:
  harmless as is, and a time-based close on wake would need the same battery
  and reconnect measurements as above.
- Network pause and the interface monitor stub: since 1.14.2 (unchanged in
  1.14.3, where only cleanup moved into the start scope)
  `route/network.go` PostStart calls `dispatchInterfaceUpdateLocked`, which
  sends `NetworkPause` (and logs "missing default interface") when the
  default interface is nil, and only sends `NetworkWake` once one appears.
  libcore's `interfaceMonitorStub` always reports nil and drops callbacks
  (the default network is tracked on the Kotlin side), so the pause would
  latch: the WireGuard endpoint goes `Down()` and every wireguard-go timer
  blocks in `WaitActive`. Kept upstream unpatched; since 2026-10-20 libcore
  registers `noNetworkPauseManager` (`libcore/pause.go`) before `box.New`
  (`pause.WithDefaultManager` keeps an already registered manager): its
  `NetworkPause` / `NetworkWake` are no-ops, device pause passes through to
  the default manager. libcore has no other source of network pause, so this
  only drops the stub's spurious one. Before, `BoxInstance.Start()` sent
  `NetworkWake` after `Box.Start()` returned, which left the pause in effect
  during `Start`: the pause callbacks run synchronously, so the WireGuard
  endpoint went `Down()` inside `Start` and waited for wireguard-go timers
  and `ClientBind`'s back-off / `Send` parked in `WaitActive` (one `Send` even
  holding the device state lock), which only the wake after `Start` could
  release — `Start` never returned whenever a WireGuard endpoint could not
  connect at start, e.g. a chain hop behind an unreachable detour (reproduced
  on the host with one endpoint). Regression tests
  `TestBoxInstanceStartSwallowsNetworkPause` /
  `TestBoxInstanceStartLeavesNetworkAwake` (`libcore/box_test.go`) and
  `TestBoxInstanceWireGuardBehindDeadDetour` (`libcore/box_wireguard_test.go`;
  both new tests fail on the old code, the latter by `Start` not returning
  within 10 s). The one error line per start remains. Anything new in
  sing-box that calls `NetworkPause` must be checked against this: it is now
  swallowed too. Re-checked on 1.14.4: `route/network.go` only gained
  `FlushSystemDNSCache` (for inbounds that implement it; none on Android),
  the PostStart pause is unchanged. The endpoint's pause callback became
  asynchronous (see the doze entry), which would by itself remove the
  synchronous deadlock, but `noNetworkPauseManager` stays: it still drops
  the stub's spurious pause, and it embeds `pause.Manager`, so the new
  `WaitActiveContext` reaches the default manager unchanged.
- Idle connections of unreferenced outbounds and DNS servers (new in 1.14.3,
  `route/reference.go`, fd9a076d / 07130365). After start and on every
  URL-test-history or clash-mode update (a selector switch notifies the
  history, which `box.go` always registers), the reference manager walks
  what is in use: route rules up to the first unconditional terminal rule
  (else also the default outbound), endpoints, NTP detour, referrer inbounds
  / services, and the DNS servers reachable from the DNS rules (else the
  default server), the outbounds' `domain_resolver` servers and the default
  domain resolver, plus each such server's dependencies and detour. A
  selector contributes only its current choice (`Selector.References()`;
  1.14.4 dropped `Now()` and reads the selected outbound directly, same
  result; `route/reference.go` is byte-identical to 1.14.3); other outbounds contribute their dependencies (detour chain).
  Every outbound / DNS server implementing `adapter.IdleConnectionKeeper`
  gets `SetKeepIdleConnections(referenced)`: an unreferenced one closes its
  idle mux sessions / idle QUIC or HTTP transport connections / idle DNS
  connections at once and later ones as soon as their last stream ends;
  active connections are untouched. An unreferenced DNS server without
  idle-connection support is `Reset()` once when it becomes unreferenced on a
  later update. For this app's graph (source reading, unmeasured): the
  selector `proxy`'s unselected members (and hops used only by them) are
  unreferenced — those with mux, a v2ray HTTP / gRPC / QUIC transport,
  hysteria / hysteria2 / TUIC, ssh or snell drop idle connections; switching
  the selection flips both sides through the history hook (libcore's
  selection callback resets all connections anyway). The loopback socks
  outbounds to Xray / mihomo, `direct`, `block`, http and WireGuard implement
  no keeper and are unaffected; `dns-group-N` servers are referenced through
  their DNS rules and keep their detour referenced; `neko-sequential`
  servers are referenced as `domain_resolver`s and keep their members
  referenced. The app's DNS rules do not depend on the selection or a clash
  mode, so no DNS server changes state at runtime and the one-time
  `Reset()` does not fire. Kept as upstream.
- REALITY client: fixed self-reported version, and no X25519MLKEM768.
  `common/tls/reality_client.go` `ClientHandshake` (v1.14.2; unchanged in v1.14.3 and v1.14.4 — 1.14.4 only brackets an IPv6 server name in the camouflage request sent after a failed verification) filters
  `X25519MLKEM768` out of the uTLS ClientHello's supported groups and key
  shares, then rebuilds the handshake state; and it writes a constant client
  version into the REALITY session id (`SessionId[0..2] = 1, 8, 1`, i.e.
  1.8.1), whatever sing-box's own version is. Measured on an emulator
  (2026-10-06, the vendored 1.14.2-neko-1 built from libcore's module graph,
  client and server in separate processes): with the chrome fingerprint the
  ClientHello offers only `X25519 P256 P384` (plus GREASE), and the chrome,
  firefox and safari fingerprints all fail against a v26.9.30 server.
  Re-measured the same way on 2026-10-16 against a v26.10.10 server (the
  vendored 1.14.3-neko-1 command line, built from libcore's module graph): same
  ClientHello, same result for all three fingerprints, with or without the
  server's `minClientVer` at 1.8.0; Xray clients with chrome, firefox or safari
  connect to the same server.
  Effects on Xray's REALITY server:
  - Xray v26.3.27 accepts it by default and rejects it once the operator sets
    `minClientVer` to 26.3.27 or higher. Xray v26.7.11 to v26.7.28 defaulted
    `minClientVer` to 26.3.27 (source only, commit af7eb680).
  - Since Xray v26.9.8 (xtls/reality 8cdf7bf9) the server requires an
    `X25519MLKEM768` key share placed before `X25519` (source; the measured
    rejections all lacked the key share), with no operator switch: v26.9.30
    rejects sing-box by default, and still rejects it with
    `minClientVer` lowered to 1.8.0 (below the reported 1.8.1). Xray clients
    whose fingerprint lacks `X25519MLKEM768` (ios, edge, qq, ...) are rejected
    the same way, old or new client.
  - A rejected hello is treated as a stranger and forwarded to the camouflage
    site. sing-box then fails with `reality verification failed` when the
    camouflage site's certificate is trusted (the usual case for a public
    target), or with an x509 error (`certificate signed by unknown
    authority`) when it is not.
  The hybrid post-quantum key exchange is never offered either. Kept upstream
  unpatched: the version bytes are part of the REALITY handshake, and
  reporting a newer one would claim to be a client this code is not. The app
  handles it on the Kotlin side (a REALITY node Xray can fully express runs
  on Xray; the sing-box error gets an explanatory hint that also asks for a
  fingerprint with `X25519MLKEM768`). On every sing-box upgrade re-read
  `common/tls/reality_client.go` (the version bytes, the curve / key share
  filter, the `reality verification failed` text), compare it with the newest
  Xray server's requirements on the ClientHello (key shares, default
  `minClientVer`), and revisit this entry and the app's hint; the canary test
  `libcore/reality_client_canary_test.go` fails when those pieces change.

The `1.12.x-neko-1` commits only bump `constant/version.go` and carry no code
changes.

Additional patches maintained by this fork (not from MatsuriDayo):

| Patch | Notes |
|---|---|
| dns: rule action `fallback` | `option/rule_action.go` (`DNSRouteActionOptions.Fallback`, JSON `fallback`), `route/rule/rule_action.go` (`RuleActionDNSRoute.Fallback`), `dns/router.go`: when a DNS query routed by a rule with `fallback: true` fails, matching continues at the next DNS rule instead of returning the error. Any non-success rcode counts as a failure, so a split-horizon server is never the final word. Used by the Android app to implement ordered multi-server fallback for per-group proxy-server nameservers. Since the `domain_resolver` migration (2026-09-10), outbound resolution no longer walks DNS rules — it binds to libcore's `neko-sequential` transport instead — so this patch only serves user-hijacked queries that match a `dns-group-N` rule. **1.14 rebase**: the DNS router was rewritten around a rule-walk state machine shared by `Exchange` and `Lookup`; the patch now marks the walk's pending exchange (`dnsPendingExchange.fallback`) and, on failure (skipping context cancellation), advances `state.ruleIndex` and re-enters the walk in `resumeExchangeWithRules`. `exchangeWithRulesAsync`'s direct-`ExchangeAsync` fast path is bypassed for fallback rules. Armed/speculative race paths (unused by the app) do not fall back. **Legacy mode (neko-3)**: any DNS rule action carrying `strategy` puts the whole router in legacy DNS mode (`resolveLegacyDNSMode`), and every `dns-group-N` rule the app emits carries one, so the app always runs `exchangeLegacy` (also behind `ExchangeAsync`) and the legacy `Lookup` loop, never the rule walk. neko-1/neko-2 patched only the walk, so fallback was dead in the app; neko-3 adds the same check to both legacy paths (`isDNSFallbackRule`, failure = error or non-success rcode, skipped once ctx is done; in `Lookup` a non-success rcode already surfaces as `RcodeError`) and `continue`s, letting `matchDNS` resume at the next rule and finally the default server. Regression tests: `dns/router_fallback_test.go` (fork-added, part of the patch artifact); the `*Legacy*` cases build app-shaped rules (with `strategy`), assert legacy mode first, and fail with the neko-3 checks removed. |
| ~~router: lock `trackers`~~ | **removed in 1.14.2-neko-1**: `route/router.go`, `route/route.go` had wrapped upstream's unsynchronized `Router.trackers` in a `sync.RWMutex` (`trackersAccess`) because the app used to call `AppendTracker` (via `SetV2rayStats`) while the box was already routing. The app now installs the stats service before `box.start()` (`ProxyInstance.launch`), and libcore's `SetV2rayStats` ignores any call once the instance has left `boxNew` (`TestBoxInstanceSetV2rayStatsAfterStart`), so no append can race routing. Both files are back to upstream verbatim; the fork test `route/router_tracker_test.go` is gone with the patch. Re-add the lock only if a caller must append trackers after start. |
| ~~router: tracker read locks unlock via `defer` (neko-2)~~ | **removed in 1.14.2-neko-1** together with the lock above (it only restructured the `trackersAccess.RLock()` sites). |
| dialer: interface-selection entry points honor `DoNotSelectInterface` (neko-2; `ICMPControl` since 1.14.4-neko-1) | `common/dialer/default.go`: `DialParallelInterface`/`ListenSerialInterfacePacket` are public and accept an explicit `strategy`; `DialContext`/`ListenPacket` check `DoNotSelectInterface` first, but these two did not, so a future upstream caller passing a non-nil strategy would silently get interface selection — which conflicts with Android VPN protect semantics (libcore sets `DoNotSelectInterface = true` in `init`). Both now fall back to the plain dial/listen path when `DoNotSelectInterface` is set. 1.14.4 replaced `DialerForICMPDestination` (plain dialers) with `ICMPControl()`, which appends the serial interface-binding control whenever a network strategy is set (`protocol/direct/outbound.go`, ICMP echo through `direct`); it now returns the plain control when `DoNotSelectInterface` is set. `TestDoNotSelectInterfaceICMPControl` (in the same fork test file) calls the returned control with a strategy but no network manager (the selection path dereferences the manager before it touches the socket, so no socket is needed); with the check removed it panics on the nil manager. The app sets no network strategy, so this is a guard, not a behavior change. |
| boxapi: `StatsService()` returns a nil interface when disabled (neko-2) | `boxapi/v2ray_server.go` (`SbV2rayServer` is never registered as an `adapter.V2RayServer` / lifecycle service; libcore only reads `StatsService()` and `QueryStats`, so its `Start() error` / `Close() error` no-ops were left as they are by the 1.14.3 lifecycle refactor): `NewSbStatsService` returns nil when `!Enabled`; boxing that into `adapter.ConnectionTracker` produced a non-nil interface wrapping a nil `*SbStatsService`, which would panic on the first routed connection after `AppendTracker`. `StatsService()` now returns a nil interface when the service is nil, and libcore's `SetV2rayStats` skips a nil tracker (`AppendTracker` itself does not reject nil). |
| SOCKS5 outbound declares an unspecified UDP ASSOCIATE source (1.14.2-neko-2) | `protocol/socks/outbound.go`. **Why**: sing's SOCKS5 client (`protocol/socks/handshake.go:90-107`, sing `87c33f17688f`, unchanged since v0.7.18 and still in sing 5dc452e537bf of 1.14.4) rewrites the UDP ASSOCIATE request's DST from the destination: a private (`IsPrivate`) IPv4 target is declared as `[::1]:0`, a private IPv6 target as `127.0.0.1:0` (public IPv4 → `0.0.0.0:0`, everything else → `[::]:0`). The datagrams actually leave from the dialing local address (127.0.0.1 towards the app's loopback external-core inbounds). Xray's socks inbound, rewritten in v26.6.x (≥ v26.6.22, source; measured on v26.9.30), accepts datagrams only from the declared source IP (the TCP peer's IP when `0.0.0.0` / `::` / a domain is declared); v26.3.27 and mihomo v1.19.32 do not check. One inbound UDP session opens one ASSOCIATE, declared from its *first* packet's destination, so a session whose first packet goes to 10/8, 172.16/12 or 192.168/16 is dropped as a whole — every later packet to any destination included — and Xray logs nothing, even at debug level. (Private IPv6 only worked by accident: `127.0.0.1` happens to be the real source.) **What**: for SOCKS5, `ListenPacket` and `DialContext(udp)` (so also `udp_connect` and UDP DNS through the outbound as a detour) go through the outbound's own `associate`, a copy of the UDP branch of sing's `Client.DialContext` that hands `ClientHandshake5` the unspecified address, written as `[::]:0`; the original destination still goes to `socks.NewAssociatePacketConn`, which uses it for `Write` and `RemoteAddr()`. Replacing the destination before calling sing's client would have been shorter but would point `Write` / `RemoteAddr()` at `[::]:0`. **Scope**: every SOCKS5 outbound, including user-made SOCKS5 nodes: for a public IPv4 target the declared DST changes from `0.0.0.0:0` (atyp 1) to `[::]:0` (atyp 4) — sing already declared `[::]:0` for domain, loopback and public IPv6 targets, so servers that worked with sing-box UDP already handle it. UoT (CONNECT, no ASSOCIATE), socks4 / 4a (still `socks4: udp unsupported`) and TCP are unchanged; the number of ASSOCIATE requests is unchanged (one per session). **Evidence**: emulator loopback against Xray v26.9.30, v26.3.27 and mihomo v1.19.32 (captured requests; failure before / success after; all three servers × three targets, ASSOCIATE count and lifetime; `udp_connect` keeps writing to the original target). Regression test `protocol/socks/outbound_neko_test.go` (fork-added, part of the patch artifact): a fake SOCKS5 server asserts the declared DST is `[::]:0` and the first datagram's header is the original destination, for 8 targets × `ListenPacket` / `DialContext(udp)`; 10 subtests fail without the patch. The same file pins the error paths copied from sing (both entry points): `TestUDPAssociateAuthFailureClosesTCP` (the server answers the RFC 1929 auth with `01 01`; the call fails and the TCP control connection is closed) and `TestUDPAssociateContextDoneClosesTCP` (the server never answers the ASSOCIATE request; the call returns `context.DeadlineExceeded` and the TCP connection is closed); both fail when the `tcpConn.Close()` after a failed handshake and the context handling are removed. **Upstream**: sing's private-address branch looks inverted (and same-family loopback would still be wrong for a remote server). Not reported upstream (this project reports nothing upstream); drop this patch if a future sing declares an unspecified (or the real local) address. |
| WireGuard start error carries no key material (1.14.3-neko-2) | `transport/wireguard/endpoint.go` `Start` (its only user is the `wireguard` endpoint, `protocol/wireguard/endpoint.go`). **Why**: when `IpcSet` fails, upstream returns `E.Cause(err, "setup wireguard: \n", ipcConf)`, and `ipcConf` is the whole UAPI text: the hex of the private key and of every peer's public key and pre-shared key (plus endpoints and allowed IPs). libcore's `Start` error goes into the app's log and into the start-failure message on screen (`BoxInstance` → `BaseService`), so one malformed key (e.g. 31 bytes, which `NewEndpoint`'s base64 decoding accepts) put every key of the profile into the log and the UI. **What**: the error is now `E.Cause(err, "setup wireguard")`. The pinned wireguard-go (`sagernet/wireguard-go` v0.0.8-0.20261022215841-e4d85ef2ed38 since 1.14.4, `device/uapi.go` unchanged from 6731c7387c81, `IpcSetOperation`) names the failing field (`failed to set private_key`, `failed to get peer by public key`, `failed to set preshared key`) and wraps `loadExactHex`'s errors (`hex string does not fit the slice`; `encoding/hex`'s odd-length / invalid-byte errors cannot occur because the values are this file's own `hex.EncodeToString` output) without the value; the values it does echo (endpoint, allowed IP, keepalive, replace flags, unknown key names, an unparsable line — impossible here, every generated line has `=`) are not secret. `IpcSetOperation` also logs the same text (`device.log.Errorf`, forwarded to the endpoint's logger), which is therefore clean too. Diagnostics lost: with several peers the error no longer shows which peer failed. **Verification**: regression test `transport/wireguard/endpoint_neko_test.go` (fork-added, part of the patch artifact) builds the endpoint through `NewEndpoint` in system mode (without gVisor the system device does nothing until `Start`, so no root and no build tag), swaps in a fake TUN device, and makes `IpcSet` fail with a 31-byte private / public / pre-shared key in turn; it asserts that the `Start` error names `setup wireguard` and the failing field and that neither the error nor the endpoint's log contains any of the three keys (hex or base64). With upstream's line restored all three subtests fail. The package is in `libcore.yml`'s race step. **Upstream**: unfixed as of 2026-10-24 — the same line is in v1.14.3, v1.14.4, and was in the `stable` and `testing` branches and v1.15.0-alpha.12 on 2026-10-16. Not reported upstream (this project reports nothing upstream). On every upgrade re-check that line and the `IpcSetOperation` error texts of the pinned wireguard-go (a `%v` / `%q` of a key value would leak again); drop this patch once upstream stops echoing `ipcConf`. |
| WireGuard uses `StdNetBind` only for a fixed `listen_port` (1.14.3-neko-3) | `transport/wireguard/endpoint.go` `Start` → `stdNetBindListener`. **Why**: upstream picks wireguard-go's `conn.StdNetBind` whenever the endpoint's dialer implements `dialer.UDPListener`, i.e. for every endpoint without `detour`. On linux / android its `BatchSize()` is `conn.IdealBatchSize` (128), and the pinned wireguard-go (`sagernet/wireguard-go` v0.0.8-0.20260929150556-6731c7387c81; unchanged in e4d85ef2ed38 of 1.14.4: `IdealBatchSize` is still 128 and the routines' preallocation is the same) preallocates a full batch per routine for the device's lifetime: `RoutineReadFromTUN` takes 128 outbound elements of `MaxMessageSize` (65535, on android too: `device/queueconstants_android.go`) from sing's buffer pool, and each of the two `RoutineReceiveIncoming` (IPv4, IPv6) takes 128 `GetMessageBuffer()`s — about 24 MB of Go heap per endpoint. A selector group of 50 WireGuard members thus allocated ~1.2 GB per start (host heap profile while running: 789 MB in `PopulatePools.func3` + 399 MB in `buf.newDefaultAllocator`). The pages start out untouched, but the live heap doubles the GC goal (GOGC=100), so a stopped run's buffers are not collected for a while, and once they are, their spans are zeroed on reuse and become resident; android builds of Go also default to `MADV_FREE` (`runtime/runtime1.go` sets `madvdontneed=1` only for `GOOS == "linux"`), so released pages keep counting in RSS. Emulator (Android 17, 4 GB), 50-member selector, start / stop rounds: `:bg` RSS 283 → 325 → 351 MB, then 1.56 / 2.12 / 2.4 GB in rounds 4–6 (proxy-only; VPN mode alike, start timeout in round 5). Not a reference leak: after a stop goroutines and sockets are back at baseline and two GCs bring the heap back to ~4 MB. **What**: only an endpoint with `listen_port` (it must listen on that port) keeps `StdNetBind`; every other endpoint takes the `ClientBind` path that endpoints with `detour` already used (batch 1: one 64 KB buffer each way). The app never emits `listen_port` (`WireGuardFmt.kt`). Lost on those endpoints: `StdNetBind`'s recvmmsg / sendmmsg batching and UDP GSO / GRO, its `UDPEgressPool`, and the powerreport IO counters (only libbox / boxdd register a `powerreport.Manager`, libcore does not). Socket protection is unchanged: `ClientBind` dials through the same `DefaultDialer`, whose `DialContext` / `ListenPacket` append `autoDetectBindFunc` just as `UDPListenerControl()` does; network changes reach it the way they reach detour hops (`ResetAllConnections` → `ResetNetwork` → `InterfaceUpdated` → `BindUpdate`). **Verification**: `transport/wireguard/bind_neko_test.go` (fork-added, part of the patch artifact): `TestNekoStdNetBindOnlyWithListenPort` pins the choice; `TestNekoEndpointWithoutListenPortSkipsBatchBuffers` starts 8 endpoints with a `UDPListener` dialer and a fake TUN and bounds the heap growth to 1 MB each (≈ 8 MB each with upstream's line restored). `libcore/box_wireguard_test.go` `TestBoxInstanceWireGuardEndpointsHeap` checks the same through libcore (10 endpoints: +4.7 MB; 244 MB with upstream's line). Emulator, pure-Go libcore test binary, 50 endpoints, 6 rounds: RSS 95 → 128 MB instead of 135 MB → 1.9 GB. The app on the rebuilt AAR (same emulator, 51-member selector including one member reachable through a host WireGuard server, 6 rounds per mode): `:bg` RSS 278 → 288 MB proxy-only and 282 → 292 MB in VPN mode (before: 283 MB → 2.4 GB, start timeout in round 5), the reachable member passed traffic in every round, and a 100 MB download through it ran at 5.5 MB/s both before and after (emulator-bound, so the lost batching is not visible there; real-device throughput not measured). **Upstream**: same selection in v1.14.3 and v1.14.4; not reported upstream (this project reports nothing upstream). On every upgrade re-check the bind selection in `Start` and wireguard-go's `IdealBatchSize`, `MaxSegmentSize` and per-routine preallocation; drop this patch if upstream stops preallocating a full batch per endpoint. |
| WireGuard `ClientBind`: no wait on a failed `Send`, atomic `conn` (1.14.3-neko-3; back-off part dropped in 1.14.4-neko-1) | `transport/wireguard/client_bind.go`; the main path since the patch above (before, only endpoints with `detour` used it). **Why**: on a failed connect, `Send` called `pauseManager.WaitActive()` and `time.Sleep(time.Second)`; `Send` also runs while wireguard-go holds the device state lock (`upLocked` → keepalive → handshake initiation), so every endpoint that cannot connect stalled its start by about a second and blocked `Down()` meanwhile (50 endpoints behind an unreachable detour: `Start` 35 s). The receive back-off made the same two calls, neither returned on `Close()`, while `Down()` / `BindUpdate` wait for the receive routine to exit, so stopping was serialized on those sleeps too. The connect fast path reads `c.conn` without the lock that guards its write: `-race` reports it in `connect` / `receive` / `Send` (pre-existing, on the detour path as well). **What**: `Send` returns the connect error at once (wireguard-go's handshake retries are timer-driven, nothing spins); `conn` is an `atomic.Pointer[wireConn]`. 1.14.3-neko-3 also replaced the receive back-off with a `backoff()` helper that ended on close; 1.14.4 fixed that upstream (cc022098: `WaitActiveContext(c.bindCtx)`, cancelled by `Close()`, then a one-second wait that also ends on `c.done`), so the helper was dropped and upstream's code is used. The same upstream commit only removed `WaitActive` from `Send`, keeping the one-second sleep, and left the plain `conn` pointer, so those two parts stay. 50 endpoints behind an unreachable detour (measured on 1.14.3-neko-3): start 27 ms, close 3 ms. **Verification**: `transport/wireguard/bind_neko_test.go`: `TestNekoClientBindSendFailsFast` (fails with upstream's `Send` restored, re-checked on 1.14.4), `TestNekoClientBindReceiveStopsOnClose` (each active and device-paused; now pins upstream's own back-off) and `TestNekoClientBindConcurrentSendReceiveClose` (meaningful under `-race`, which reports the plain pointer). The package is in `libcore.yml`'s race step. **Upstream**: as above in v1.14.4; not reported upstream. On every upgrade re-check `connect`, `receive` and `Send` for these points; drop the `Send` part once upstream stops sleeping there, and the `conn` part once the fast path is synchronized. |

## Replayable patch artifact

The whole patch set is materialized as a single replayable diff,
`libcore/patches/sing-box-v1.14.4-neko-1.diff`: a clean clone of upstream tag
v1.14.4 plus this file applied with `patch -p1` reproduces this directory
exactly. Excluded from the artifact (and from the verification comparison):
`.git`, the top-level `clients/` git submodule placeholders (not carried here;
deleted from the upstream clone before diffing — `--exclude=clients` would also
drop `docs/clients/`, which is compared like everything else), and this
`NEKO.md` (a management document, not part of the patches). `check_versions`
also pins the tag to upstream commit `c005f35300ce76427dc285481ec5695f5b179cd2`. Everything else is
in the diff — modified upstream files, new files (`boxapi/`, `nekoutils/`, the
fork's `*_test.go` additions) and the `go.mod`/`go.sum` divergence documented
below. `./run lib check_versions` replays the artifact against a fresh clone
and fails on any drift.

Regenerate the artifact after EVERY change to this directory (from the repo
root):

```bash
TMP=$(mktemp -d)
git clone --depth 1 --branch v1.14.4 https://github.com/SagerNet/sing-box "$TMP/a"
rm -rf "$TMP/a/clients"
cp -R libcore/sing-box "$TMP/b"
(cd "$TMP" && command diff -ruN --exclude=.git --exclude=NEKO.md a b) \
  > libcore/patches/sing-box-v1.14.4-neko-1.diff
```

The `a`/`b` directory names are load-bearing: they keep the diff header paths
deterministic (`patch -p1` strips them). `command diff` bypasses shell aliases
such as `diff --color`, which would leak into the file headers. `diff` exiting 1 just means
"differences found", which is the expected outcome here.

How to upgrade the base: clone upstream SagerNet/sing-box, merge or rebase the
patches onto the new tag, resolve conflicts, replace this directory with the
result (without `.git`), regenerate the patch artifact as above (renamed for
the new tag and patch-set version), and update this file. The 1.13.18 → 1.14.0
rebase extracted the patch set with `diff -ru` against the upstream tag and
re-applied it by hand — see git history. The 1.14.0 → 1.14.1 rebase used the
same `diff -ruN` extraction plus `patch`; only `box.go` (needCacheFile) and
`route/route.go` (NewTracker closure) needed manual conflict resolution. The
1.14.1 → 1.14.2 rebase applied `1.14.1-neko-3` with `patch -p1` without any
reject, offset or fuzz (the only overlapping upstream file, `box.go`, changed
outside the patched hunk); `1.14.2-neko-1` is that set minus the two
`trackersAccess` patches (see the table above). `1.14.2-neko-2` adds the SOCKS5
UDP ASSOCIATE source patch on the same base; the artifact was renamed with it
(`sing-box-v1.14.2-neko-1.diff` → `sing-box-v1.14.2-neko-2.diff`).

The 1.14.2 → 1.14.3 rebase (2026-10-11) applied `1.14.2-neko-2` to a clean
v1.14.3 clone with `patch -p1` without any reject (offsets only, in
`box.go`, `common/dialer/default.go`, `dns/router.go`,
`protocol/group/selector.go` and `protocol/vless/outbound.go`, all of which
upstream also changed; every hunk was checked against the tables above).
The patched `go.mod` / `go.sum` were discarded and the divergence below
re-derived on the upstream files. The vendored tree itself needed no
source change beyond `constant/version.go` (`1.14.3-neko-1`): it builds and
the three fork test files pass unchanged. The API changes landed in libcore
instead: 1.14.3 replaced `adapter.Lifecycle` with
`Start(stage, *adapter.Scope)` (cleanups via `scope.Add`, `Close()` no
longer part of the interface; a691a440) and dropped the managers'
unimplemented `Remove` (a364ff47), so libcore's `platformLocalDNSTransport`
/ `SequentialTransport` and the stubs in `dns_sequential_test.go` take the
scope and lost their no-op `Close` / `Remove`. libcore's `go.mod` follows
the bumped shared modules (and gains `sing-snell` as a new indirect
requirement of the vendored tree). `1.14.3-neko-1` carries exactly the
`1.14.2-neko-2` patch set; the artifact was renamed with it
(`sing-box-v1.14.2-neko-2.diff` → `sing-box-v1.14.3-neko-1.diff`).
`1.14.3-neko-2` adds the WireGuard start-error patch on the same base; the
artifact was renamed with it (`sing-box-v1.14.3-neko-1.diff` →
`sing-box-v1.14.3-neko-2.diff`). `1.14.3-neko-3` adds the two WireGuard bind
patches (`StdNetBind` only for a fixed `listen_port`; `ClientBind` back-off and
`conn`) on the same base; the artifact was renamed with it
(`sing-box-v1.14.3-neko-2.diff` → `sing-box-v1.14.3-neko-3.diff`).

The 1.14.3 → 1.14.4 rebase (2026-10-24) applied `1.14.3-neko-3` to a clean
v1.14.4 clone with `patch -p1`. Two hunks were rejected and re-applied by
hand: in `box.go` (needCacheFile: the neighboring `trafficcontrol.NewManager`
line changed) and two in `transport/wireguard/client_bind.go`, where upstream
fixed the receive back-off itself (cc022098) — the fork's `backoff()` helper
was dropped for upstream's code, the `Send` and atomic `conn` parts kept (see
the table). Every other patched file that upstream also changed
(`common/dialer/default.go`, `dns/router.go`, `option/rule_action.go`,
`route/rule/rule_action.go`, `protocol/group/selector.go`,
`transport/wireguard/endpoint.go`) applied cleanly and was checked against the
tables above; the new ICMP entry point got the `DoNotSelectInterface` check.
The patched `go.mod` / `go.sum` were discarded and the divergence re-derived on
the upstream files (the same five entries). libcore needed no source change:
its `go.mod` follows the bumped shared modules (`quic-go`, `sing`, `sing-quic`,
`sing-tun`, `sing-vmess`, `wireguard-go`), and `go mod tidy` there also moved
`golang.org/x/crypto` to the direct requirements (a libcore test imports it).
The fork tests and the `libcore.yml` race step pass; one mutation check each
for the ICMP guard and the `Send` part. `1.14.4-neko-1` is that set; the
artifact was renamed with it (`sing-box-v1.14.3-neko-3.diff` →
`sing-box-v1.14.4-neko-1.diff`).

## go.mod divergence from upstream

`check_versions` requires every module shared with `libcore/go.mod` to sit at
the same version in both files, so dependency bumps land here too. As of
2026-10-24 this go.mod diverges from the upstream v1.14.4 one (re-derived
on the 1.14.4 files: the same set as on 1.14.2 and 1.14.3, because upstream
left all five entries where they were):

- `go` directive `1.26.0` (upstream `1.25.5`) — forced by `x/sys` below
- `github.com/miekg/dns v1.1.73` (upstream v1.1.72) — libcore requires it
  directly
- `golang.org/x/mod v0.38.0` (upstream v0.37.0) — required by
  `miekg/dns v1.1.73`
- `golang.org/x/sys v0.48.0` (upstream v0.47.0) — libcore requires it
  directly; it declares `go 1.26.0`, which is what pushes the go directive
  up; upstream sing-box ≥ 1.14 requires Go 1.25+ and its own CI builds with
  Go 1.26.8, so 1.26 stays within the upstream-supported range
- `golang.org/x/tools v0.47.0 // indirect` dropped (upstream lists it; `go mod
  tidy` removes it as unused)

Apply such bumps with `go mod tidy` in this directory (it also drops stale
indirect entries), and re-run `./run lib check_versions` afterwards.
