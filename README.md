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
| [sing-box](https://github.com/SagerNet/sing-box) | v1.14.2（附带本仓库补丁 / with local patches） | 承载其余一切，可选核心时优先 / Runs everything else; preferred whenever the core can be chosen |
| [Xray-core](https://github.com/XTLS/Xray-core) | v26.9.30 | 带 REALITY 或 mldsa65Verify 的 VMess / VLESS / Trojan 优先使用；节点带证书指纹、Mux.Cool，或只有 Xray 认的 flow / 指纹时也走 Xray / Preferred for VMess / VLESS / Trojan with REALITY or mldsa65Verify; also used when the profile has a certificate fingerprint, Mux.Cool, or a flow / fingerprint only Xray accepts |
| [mihomo](https://github.com/MetaCubeX/mihomo) | v1.19.32 | 带证书指纹或 certificates 的 AnyTLS；只有 mihomo 认的指纹也走 mihomo / AnyTLS with a certificate fingerprint or certificates; also used for a fingerprint only mihomo accepts |

VMess / VLESS / Trojan 与 AnyTLS 节点可在编辑页手动指定核心；指定的核心承载不了节点的设置时报错并列出冲突字段，不再回落到其他核心。Trojan-Go、NaïveProxy、Mieru 以及 faketcp / wechat-video 模式的 Hysteria 1 需要另外安装对应插件。

The core can be chosen per profile for VMess / VLESS / Trojan and AnyTLS; if the chosen core cannot run the profile's settings, it fails with the conflicting fields listed instead of falling back to another core. Trojan-Go, NaïveProxy, Mieru and Hysteria 1 in faketcp / wechat-video mode need their plugin installed separately.

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
* Go 1.26.x；系统 Go 更新时在命令前加 `GOTOOLCHAIN=go1.26.0` / with a newer system Go, prefix commands with `GOTOOLCHAIN=go1.26.0`

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

应用图标是 [OpenMoji](https://openmoji.org/)（仓库 [hfg-gmuend/openmoji](https://github.com/hfg-gmuend/openmoji)）的棺材 emoji ⚰️（U+26B0），按 [CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/) 许可使用。`app/src/main/res/drawable/ic_launcher_foreground.xml`、`app/src/main/res/drawable/ic_launcher_monochrome.xml`、`app/src/main/res/mipmap-*/ic_launcher.png` 与快捷设置磁贴的 `app/src/main/res/drawable/ic_box.xml`、`app/src/main/res/drawable/ic_box_off.xml` 是它的派生作品，同样按 CC BY-SA 4.0 发布；仓库其余部分仍按 GPL-3.0 发布。

All emojis designed by [OpenMoji](https://openmoji.org/) – the open-source emoji and icon project. License: [CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/)

The app icon is OpenMoji's coffin emoji ⚰️ (U+26B0) from [hfg-gmuend/openmoji](https://github.com/hfg-gmuend/openmoji). `app/src/main/res/drawable/ic_launcher_foreground.xml`, `app/src/main/res/drawable/ic_launcher_monochrome.xml`, `app/src/main/res/mipmap-*/ic_launcher.png` and the quick settings tile icons `app/src/main/res/drawable/ic_box.xml`, `app/src/main/res/drawable/ic_box_off.xml` are adaptations of it and are licensed under CC BY-SA 4.0 as well; the rest of the repository remains under GPL-3.0.

## Fork 信息 / Fork Information

本仓库是 [MatsuriDayo/NekoBoxForAndroid](https://github.com/MatsuriDayo/NekoBoxForAndroid) 的 fork，上游放弃维护后本仓库由本人独立维护，仅在此 README 中说明 fork 关系，未在 GitHub 上建立 fork 关联。这个项目是我的个人项目，今后也不打算推广本项目的任何成果，这个项目仅仅只是方便我自己在手机上使用 Github 、 OpenRouter 等等开发者服务，请见谅。
