# Local patches on gomobile

This directory is a vendored copy of upstream
[MatsuriDayo/gomobile](https://github.com/MatsuriDayo/gomobile) (`master2`
branch, imported in `f6c0a71`), installed as `gomobile-matsuri` /
`gobind-matsuri` by `libcore/init.sh`. When rebasing onto upstream, re-apply
or drop each patch below after checking whether upstream has fixed the issue.

Local patches:

| Commit | Title | Notes |
|---|---|---|
| `5746ece` | gomobile init: skip upstream gobind install when GOBIND is set | `cmd/gomobile/init.go`: `runInit` unconditionally ran `go install golang.org/x/mobile/cmd/gobind@latest`, but this fork's `bind` honors the `GOBIND` env var and `libcore/init.sh` always passes `GOBIND=gobind-matsuri`, so the upstream binary was never used. Upstream x/mobile now requires Go >= 1.26, and with `GOTOOLCHAIN=local` (actions/setup-go v6+) the install became a hard CI failure. The install is skipped when `GOBIND` is already set. Rebase note: keep the `GOBIND` honor logic in `bind` and this guard together; dropping only one half silently reintroduces the useless `@latest` install. |
