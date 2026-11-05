# NekoBox for Android

[![API](https://img.shields.io/badge/API-24%2B-brightgreen.svg?style=flat)](https://android-arsenal.com/api?level=24)
[![License: GPL-3.0](https://img.shields.io/badge/license-GPL--3.0-orange.svg)](https://www.gnu.org/licenses/gpl-3.0)

sing-box / universal proxy toolchain for Android.

一款使用 sing-box 的 Android 通用代理软件，内置 mihomo 和 Xray 插件.

## 下载与系统要求 / Download & Requirements

* Android 7.0（API 24）及以上，支持 arm64-v8a 与 x86_64 / Android 7.0 (API 24)+, arm64-v8a and x86_64
* 安装包见 [Releases](https://github.com/ql0202cocou/NekoBoxForAndroid/releases)，只发布 universal APK / Get the universal APK from [Releases](https://github.com/ql0202cocou/NekoBoxForAndroid/releases)
* 带 `-aN` / `-bN` 后缀的是预览版，标记为 Pre-release / Tags ending in `-aN` / `-bN` are previews, published as pre-releases

## 支持的代理协议 / Supported Proxy Protocols

* SOCKS (4/4a/5)
* HTTP(S)
* SSH
* Shadowsocks
* VMess
* Trojan
* VLESS
* AnyTLS
* ShadowTLS
* TUIC
* Hysteria 1/2
* WireGuard
* Trojan-Go (trojan-go-plugin)
* NaïveProxy (naive-plugin)
* Mieru (mieru-plugin)

## 内置核心 / Bundled Cores

| 核心 / Core | 版本 / Version | 用途 / Used for |
| --- | --- | --- |
| [sing-box](https://github.com/SagerNet/sing-box) | v1.14.4（附带本仓库补丁及 uTLS v1.8.8 / with local patches and uTLS v1.8.8） | 基础内核，承载多数协议；AnyTLS 自动选核的首选 / Base core for most protocols; preferred for automatic AnyTLS selection |
| [Xray-core](https://github.com/XTLS/Xray-core) | v26.10.21 | XHTTP 节点仅使用 Xray；带 REALITY 或 mldsa65Verify 的 VMess / VLESS / Trojan 优先使用；节点带证书指纹、Mux.Cool，或只有 Xray 认的 flow / 指纹时也走 Xray / Required for XHTTP profiles; preferred for VMess / VLESS / Trojan with REALITY or mldsa65Verify; also used when the profile has a certificate fingerprint, Mux.Cool, or a flow / fingerprint only Xray accepts |
| [mihomo](https://github.com/MetaCubeX/mihomo) | v1.19.33 | AnyTLS 可选核心；可按节点手动选择 / Optional AnyTLS core; can be selected per profile |

VMess / VLESS / Trojan 与 AnyTLS 节点可在编辑页手动指定核心；指定的核心承载不了节点的设置时报错并列出冲突字段，不再回落到其他核心。Trojan-Go、NaïveProxy、Mieru 以及 faketcp / wechat-video 模式的 Hysteria 1 需要另外安装对应插件。

The core can be chosen per profile for VMess / VLESS / Trojan and AnyTLS; if the chosen core cannot run the profile's settings, it fails with the conflicting fields listed instead of falling back to another core. Trojan-Go, NaïveProxy, Mieru and Hysteria 1 in faketcp / wechat-video mode need their plugin installed separately.

普通 AnyTLS 节点自动选核优先 sing-box，使用内置兼容实现，支持整证书固定、uTLS 和 ECH；仍可手动选择 mihomo。
升级时所有现存 AnyTLS 节点（含原手动核心与兼容测试节点）一次性恢复自动选核，节点参数、流量与链式引用不变；之后可重新手动选择核心。
证书策略独立保存：旧 sing-box 节点保留自定义信任证书及域名、有效期校验；旧 mihomo 和兼容测试节点保留首张 PEM 固定。新节点默认固定，显式证书指纹优先。
信任策略通过 `sn://anytls-v2?` 分享，旧客户端会拒绝此类链接。订阅刷新优先保留用户手动修改的策略。旧 mihomo 节点带有本地 SOCKS 出站覆写时，自动选核会选择 mihomo。旧 sing-box 节点的自定义出站 JSON 继续由原生 `anytls` 解释，可导出通用配置。使用 `neko-anytls` 扩展的节点会拒绝通用配置导出，固定策略也可选择 mihomo 后导出。
详见 [AnyTLS 支持说明](libcore/anytlscompat/README.md)。

Ordinary AnyTLS profiles can now select sing-box's built-in compatibility implementation, with whole-certificate pinning,
uTLS and ECH. Automatic selection prefers sing-box; mihomo remains selectable. Upgrading resets every existing AnyTLS profile, including manual core
choices and former test profiles, to automatic selection once, preserving profile parameters, traffic and chain references.
Manual core selection remains available afterwards. Certificate policy is stored independently: former sing-box profiles retain
custom trust certificates with hostname and validity checks; former mihomo and test profiles retain first-PEM pinning.
New profiles default to pinning, and explicit fingerprints take priority. Profiles with trust certificates cannot silently switch
to mihomo pinning. Trust-policy links use `sn://anytls-v2?`, which older clients reject. Subscription updates preserve manual policy edits. Automatic selection uses mihomo when a migrated override targets its local SOCKS hop. Existing sing-box profiles with native outbound JSON overrides use
the native `anytls` outbound and can export their configuration. Profiles using the `neko-anytls` extension reject portable
export. Pinning profiles can also select mihomo for export. See the [support notes](libcore/anytlscompat/README.md).

## 支持的订阅格式 / Supported Subscription Format

* 一些广泛使用的格式 (如 Shadowsocks, ClashMeta 和 v2rayN)
* sing-box 出站

仅支持解析出站，即节点。分流规则等信息会被忽略。

* Some widely used formats (like Shadowsocks, ClashMeta and v2rayN)
* sing-box outbound

Only resolving outbound, i.e. nodes, is supported. Information such as diversion rules are ignored.

## 构建 / Build

需要 / Requirements:

* JDK 17+
* Android SDK（platform 37、build-tools 36.0.0），通过 `local.properties` 的 `sdk.dir` 或 `ANDROID_HOME` 指定 / set via `sdk.dir` in `local.properties` or `ANDROID_HOME`
* NDK 25.0.8775105
* Go 1.26.x，用 1.26 线最新的补丁版本（含安全修复）；系统 Go 更新时在命令前加 `GOTOOLCHAIN=go1.26.<最新补丁>`，如 `GOTOOLCHAIN=go1.26.10` / Go 1.26.x, on the latest 1.26 patch release (security fixes); with a newer system Go, prefix commands with `GOTOOLCHAIN=go1.26.<latest patch>`, e.g. `GOTOOLCHAIN=go1.26.10`

```bash
./run lib core      # 构建 libcore.aar / build libcore.aar
./run lib assets    # 下载 geo 数据库 / download geo databases
./run lib plugins   # 下载 Xray / mihomo 核心 / download Xray / mihomo cores
./gradlew app:assembleOssRelease
```

未配置签名时生成未签名的 APK。/ Without signing configured, the APK is left unsigned.

## Credits

Core:

- [SagerNet/sing-box](https://github.com/SagerNet/sing-box)

Android GUI:

- [shadowsocks/shadowsocks-android](https://github.com/shadowsocks/shadowsocks-android)
- [SagerNet/SagerNet](https://github.com/SagerNet/SagerNet)

Web Dashboard:

- [Yacd-meta](https://github.com/MetaCubeX/Yacd-meta)

应用图标 / App icon:

应用图标是 [OpenMoji](https://openmoji.org/)（仓库 [hfg-gmuend/openmoji](https://github.com/hfg-gmuend/openmoji)）的棺材 emoji ⚰️（U+26B0），按 [CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/) 许可使用。`app/src/main/res/drawable/ic_launcher_foreground.xml`、`app/src/main/res/drawable/ic_launcher_monochrome.xml`、`app/src/main/res/mipmap-*/ic_launcher.png` 与快捷设置磁贴、通知小图标用的 `app/src/main/res/drawable/ic_box.xml`、`app/src/main/res/drawable/ic_box_off.xml` 是它的派生作品，同样按 CC BY-SA 4.0 发布；仓库其余部分仍按 GPL-3.0 发布。

All emojis designed by [OpenMoji](https://openmoji.org/) – the open-source emoji and icon project. License: [CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/)

The app icon is OpenMoji's coffin emoji ⚰️ (U+26B0) from [hfg-gmuend/openmoji](https://github.com/hfg-gmuend/openmoji). `app/src/main/res/drawable/ic_launcher_foreground.xml`, `app/src/main/res/drawable/ic_launcher_monochrome.xml`, `app/src/main/res/mipmap-*/ic_launcher.png` and the quick settings tile / notification icons `app/src/main/res/drawable/ic_box.xml`, `app/src/main/res/drawable/ic_box_off.xml` are adaptations of it and are licensed under CC BY-SA 4.0 as well; the rest of the repository remains under GPL-3.0.

## Fork 信息 / Fork Information

本仓库是 [MatsuriDayo/NekoBoxForAndroid](https://github.com/MatsuriDayo/NekoBoxForAndroid) 的 fork，上游放弃维护后本仓库由本人独立维护，仅在此 README 中说明 fork 关系，未在 GitHub 上建立 fork 关联。这个项目是我的个人项目，今后也不打算推广本项目的任何成果，这个项目仅仅只是方便我自己在手机上使用 Github 、 OpenRouter 等等开发者服务，请见谅。
