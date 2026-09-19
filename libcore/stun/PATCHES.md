# Local patches on go-stun

This directory is a vendored copy of upstream
[ccding/go-stun](https://github.com/ccding/go-stun), pinned to commit
`877ebaff7ba7b0f0ce44e92d785075647545e777` (see `README`). Upstream is
unmaintained since 2016, so this fork fixes bugs locally instead of tracking
upstream. The upstream `packet_test.go` / `utils_test.go` were not vendored;
this fork has its own tests in `stun_test.go`.

The fork's history is squashed, so patches are listed by topic rather than by
commit. When rebasing onto upstream (or replacing this directory), re-apply or
drop each patch below after checking whether upstream has fixed the issue.

Security patches:

- **Attribute length validation** — `attribute.go`: `rawAddr` / `xorAddr`
  reject server-supplied values with malformed lengths (`< 8`, or `> 20` for
  XOR addresses) instead of slicing out of bounds or reading garbage.
- **Packet length defense** — `packet.go` `newPacketFromBytes`: rejects
  packets whose header length exceeds the data actually received, truncates
  trailing garbage, and does the offset arithmetic in `int` instead of the
  overflow-prone `uint16` of upstream.
- **Fake full cone detection** — `discover.go`, `net.go`, `tests.go`: a NAT
  that rewrites the source IP/port of STUN responses (so the endpoint never
  changes) no longer fails with "Server error: response IP/port"; the
  `Discover` caller gets a `fakeFullCone` flag instead. Added the missing
  `serverAddr` / `mappedAddr` nil checks that made this detectable without
  panicking.
- **Magic cookie and message type validation** — `packet.go`
  `newPacketFromBytes`: rejects packets without the RFC 5389 magic cookie
  (`0x2112A442`) and any message type other than binding response / binding
  error response (upstream accepted anything carrying a matching transaction
  id). `net.go` turns a binding error response into a real error describing
  the ERROR-CODE attribute (code table in `const.go`) instead of treating it
  as a successful response with no mapped address.
- **Semantic IP comparison** — `utils.go` `ipEqual` (`net/netip`), used by the
  fake full cone checks in `discover.go` and by `addrCompare` in `tests.go`:
  IPv6 textual variants (compressed vs. full, IPv4-mapped) of one address no
  longer compare as different.

Functional patches:

- **Timeout budget** — `client.go` `SetDeadline` / `SetRetransmission`,
  `net.go` `send`: retransmission count/timeouts are configurable and every
  read is capped at an overall client deadline, so a black-holed server can
  no longer block a test for over a minute. `libcore/stun.go` sets a 25s
  budget for the whole `StunTest` run.
- **Read deadline cleanup** — `net.go` `send`: clears the read deadlines it
  set before returning the connection to the caller.
- **RFC 5389 padding/length fixes** — `attribute.go` `newAttribute` stores the
  value length *without* padding (upstream included it), `utils.go` `padding`
  returns a fresh slice instead of appending into the caller's backing array,
  and `attribute.go` `rawAddr` no longer mutates the attribute value when
  truncating IPv4.
- **Local address detection** — `utils.go` `isLocalAddress`: compares against
  the socket's own local address first (when it is specified) instead of only
  iterating interface addresses.
- **Relaxed read loop** — `net.go`: malformed packets sharing our socket are
  ignored until timeout instead of aborting the request (upstream returned
  the parse error immediately).
- **Dead code removal** — dropped `NewClientWithConnection`, `Keepalive`,
  `SetVerbose` / `SetVVerbose`, `SetServerHost`, the `conn` field, the
  deprecated `NATSymetric*` constant aliases, `packet.getSourceAddr`, and the
  unused `Logger` methods (`Debug`/`Debugf`/`Infof`/`Infoln`/`SetDebug`/
  `SetInfo`).
- **API cleanups** — `Discover` returns `(NATType, *Host, bool, error)` with
  the error last (Go convention); the only caller is `libcore/stun.go`
  (`StunTest`), as this package is not part of the gomobile-bound API
  surface. `doc.go` example matches the real signature.
- **Logger without lock copying** — `log.go`: `Logger` holds a named
  `*log.Logger` field instead of embedding `log.Logger` by value (which
  copies its mutex — `go vet copylocks`) or by pointer (which would promote
  the `Fatal*`/`os.Exit` methods into this package's API). The debug/info
  flags are `atomic.Bool`.
- **Robustness details** — `net.go`: the request is serialized once per
  `send` instead of on every retransmission, and timeout detection uses
  `errors.As` instead of a hard `err.(net.Error)` assertion. `response.go`
  `newResponse` uses field-name initialization instead of a positional
  struct literal.
