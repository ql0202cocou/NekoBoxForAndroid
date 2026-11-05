# NekoBox for Android

[![Android](https://img.shields.io/badge/Android-7.0%2B-brightgreen.svg)](https://developer.android.com/about/versions/nougat)
[![License: GPL-3.0-or-later](https://img.shields.io/badge/license-GPL--3.0--or--later-orange.svg)](LICENSE)

基于 **sing-box**、内置 **Xray-core** 的 Android 通用代理客户端，支持多种代理协议、订阅管理、路由分流和链式代理。

An Android proxy client powered by **sing-box**, with **Xray-core** bundled for compatible profiles. Supports multiple protocols, subscriptions, routing and proxy chains.

**[下载 / Releases](https://github.com/ql0202cocou/NekoBoxForAndroid/releases)** · [构建 / Build](#build) · [测试 / Tests](#tests) · [开发文档 / Documentation](#documentation)

## 项目说明 / About this fork

本仓库源自 [MatsuriDayo/NekoBoxForAndroid](https://github.com/MatsuriDayo/NekoBoxForAndroid)，在上游停止维护后由维护者独立维护，未在 GitHub 上建立 fork 关联。这是一个个人项目，主要用于在手机上访问 GitHub、OpenRouter 等开发者服务，没有推广计划。

This is an independently maintained fork of [MatsuriDayo/NekoBoxForAndroid](https://github.com/MatsuriDayo/NekoBoxForAndroid), continued after upstream maintenance ended. It is not linked through GitHub's fork network. It is a personal project for accessing developer services such as GitHub and OpenRouter on a phone, with no plans to promote it.

## 下载与使用 / Download & usage

| 项目 / Item | 要求 / Details |
| --- | --- |
| 系统 / OS | Android 7.0（API 24）及以上 / Android 7.0 (API 24) or later |
| 架构 / ABI | `arm64-v8a`、`x86_64`；不提供 32 位构建 / No 32-bit builds |
| 安装包 / APK | [Releases](https://github.com/ql0202cocou/NekoBoxForAndroid/releases) 仅发布包含以上两种架构的 universal APK / Releases contain only the universal APK for both ABIs |
| 预览版 / Previews | 版本号以 `-aN` 或 `-bN` 结尾，标记为 Pre-release / Versions ending in `-aN` or `-bN` are marked as pre-releases |

1. 下载并安装 APK，准备自己的节点配置或订阅链接。 / Install the APK and prepare your profile or subscription link.
2. 从剪贴板、二维码或文件导入节点，或添加并更新订阅分组。 / Import profiles from the clipboard, a QR code or a file, or add and update a subscription group.
3. 选择节点并启动连接；使用 VPN 模式时，按系统提示授予 VPN 权限。 / Select a profile and connect; approve Android's VPN permission prompt when using VPN mode.

## 功能与协议 / Features & protocols

- **连接模式 / Connection modes**：VPN 与仅代理模式。 / VPN and proxy-only modes.
- **节点管理 / Profile management**：订阅更新、分组、连接测试和链式代理。 / Subscription updates, groups, connection tests and proxy chains.
- **路由与 DNS / Routing & DNS**：域名、IP 和应用规则，直连与远程 DNS、路由资源管理。 / Domain, IP and app rules, direct and remote DNS, and routing asset management.
- **备份 / Backups**：备份和恢复分组、节点、路由规则及设置。 / Back up and restore groups, profiles, routing rules and settings.

| 支持方式 / Availability | 协议 / Protocols |
| --- | --- |
| 内置 / Built in | SOCKS 4/4a/5、HTTP(S)、SSH、Shadowsocks、VMess、VLESS、Trojan、AnyTLS、ShadowTLS、TUIC、Hysteria 1/2、WireGuard |
| 另装插件 / Separate plugin | Trojan-Go（`trojan-go-plugin`）、NaïveProxy（`naive-plugin`）、Mieru（`mieru-plugin`） |
| 特殊模式 / Special modes | Hysteria 1 的 `faketcp` / `wechat-video` 模式需要 Hysteria 1 插件 / Hysteria 1's `faketcp` and `wechat-video` modes require its plugin |

### 订阅格式 / Subscription formats

支持 Shadowsocks、Clash / Clash Meta、v2rayN 等常见订阅格式，以及 sing-box 出站配置。

Supports common subscription formats such as Shadowsocks, Clash / Clash Meta and v2rayN, as well as sing-box outbounds.

**订阅导入只解析节点（出站），不会导入原配置中的分流规则等设置。** 支持某个协议不代表支持该协议的所有传输、TLS 或复用组合；节点设置会按核心能力检查。

**Subscription import extracts profiles (outbounds) only; routing rules and other settings from the source configuration are ignored.** Protocol support does not imply support for every transport, TLS or multiplexing combination; profile settings are checked against core capabilities.

## 内置核心 / Bundled cores

| 核心 / Core | 当前版本 / Version | 用途 / Role |
| --- | --- | --- |
| [sing-box](https://github.com/SagerNet/sing-box) | `1.14.4-neko-2`，基于上游 `v1.14.4`，使用 uTLS `v1.8.8` / Based on upstream `v1.14.4`, with uTLS `v1.8.8` | 基础核心，承载多数协议和 AnyTLS / Base core for most protocols and AnyTLS |
| [Xray-core](https://github.com/XTLS/Xray-core) | `v26.10.31` | 承载需要 Xray 能力的 VMess / VLESS / Trojan 节点 / Runs VMess, VLESS and Trojan profiles requiring Xray capabilities |

### 核心选择 / Core selection

- VMess、VLESS、Trojan 支持自动选核，也可在节点编辑页手动指定 sing-box 或 Xray。 / VMess, VLESS and Trojan support automatic selection or a manual choice of sing-box or Xray in the profile editor.
- 自动模式按节点的全部有效字段匹配核心。XHTTP 由 Xray 承载；REALITY 或 `mldsa65Verify` 生效时优先尝试 Xray，其余优先尝试 sing-box。证书固定、Mux.Cool、flow 和指纹等字段也参与匹配。 / Automatic selection checks all active profile requirements. XHTTP uses Xray; active REALITY or `mldsa65Verify` prefers Xray, otherwise sing-box is tried first. Certificate pinning, Mux.Cool, flow and fingerprints also affect compatibility.
- 手动指定的核心无法承载配置时，会列出冲突字段并拒绝运行；自动模式下所有候选核心都不兼容时也会报错。 / An incompatible manual choice is rejected with the conflicting fields listed. Automatic selection also fails if no candidate can run the full configuration.
- AnyTLS 统一由 sing-box 承载。其他协议按内置实现或对应插件运行。 / AnyTLS always runs on sing-box. Other protocols use their built-in implementation or the required plugin.

### AnyTLS 兼容说明 / AnyTLS compatibility

AnyTLS 内置兼容实现支持整证书 SHA-256 固定、独立证书信任策略、uTLS 和 ECH。使用时需注意：

- 升级会将旧的手动 mihomo AnyTLS 节点恢复为自动选核，保留节点参数、证书策略、流量与链式引用。
- 仍指向旧 mihomo 本地 SOCKS 跳的自定义覆写会报错，需要删除或改写。
- 信任策略通过 `sn://anytls-v2?` 分享，旧客户端会拒绝此类链接；订阅刷新优先保留用户手动修改的策略。
- 旧原生 sing-box 节点的自定义出站 JSON 继续由原生 `anytls` 解释，可导出通用配置；使用 `neko-anytls` 扩展的配置不能导出为通用 sing-box 配置。

The built-in AnyTLS implementation supports whole-certificate SHA-256 pinning, independent trust policies, uTLS and ECH. Upgrades reset former manual mihomo choices to automatic while preserving parameters, certificate policies, traffic and chain references. Overrides targeting the old local SOCKS hop must be removed or rewritten. Trust-policy links use `sn://anytls-v2?`, which older clients reject; subscription updates preserve manual policy edits. Existing native sing-box outbound JSON overrides retain native `anytls` behavior and portable export. Configurations using `neko-anytls` cannot be exported as portable sing-box configurations.

完整行为与限制见 [AnyTLS 支持说明 / AnyTLS support notes](libcore/anytlscompat/README.md)。

<a id="build"></a>

## 从源码构建 / Build from source

以下命令在 Linux / macOS 的 Bash 环境中执行，工作目录为仓库根目录。 / Run these commands in Bash on Linux or macOS, from the repository root.

### 构建环境 / Prerequisites

| 工具 / Tool | 要求 / Requirement |
| --- | --- |
| JDK | 17 或以上，`JAVA_HOME` 指向所用 JDK / 17 or later, with `JAVA_HOME` set |
| Android SDK | Platform 37（SDK 包名 `platforms;android-37.0`）、Build Tools `36.0.0` |
| Android NDK | **`25.0.8775105`**；构建脚本检查确切版本 / The scripts require this exact version |
| Go | `1.26.x`；当前 CI 使用 `1.26.10` / CI currently uses `1.26.10` |
| Gradle | 使用仓库自带 Wrapper（`9.8.2`），无需另装 / Use the bundled wrapper; no separate installation needed |
| 命令行工具 / CLI tools | Git、Bash、`curl`、`unzip`、`realpath`；版本一致性检查还需要 `patch` / The version check also requires `patch` |

### 获取源码与配置 SDK / Checkout & SDK setup

需先安装 JDK、Go 和 Android SDK Command-line Tools，并确保 `java`、`go` 在 `PATH` 中。 / Install the JDK, Go and Android SDK Command-line Tools first, with `java` and `go` on `PATH`.

```bash
git clone https://github.com/ql0202cocou/NekoBoxForAndroid.git
cd NekoBoxForAndroid

# 改为自己的 SDK 路径 / Replace with your SDK path
export ANDROID_HOME=/absolute/path/to/android-sdk

# 与当前 CI 的 Go 版本保持一致 / Match the current CI Go version
export GOTOOLCHAIN=go1.26.10

"$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" \
  "platforms;android-37.0" "build-tools;36.0.0" "ndk;25.0.8775105"
```

Gradle 也支持通过 `local.properties` 的 `sdk.dir` 定位 SDK，但核心构建脚本不读取该文件，建议统一设置 `ANDROID_HOME`。

Gradle also accepts `sdk.dir` in `local.properties`, but the native core scripts do not read that file; set `ANDROID_HOME` for both.

### 准备核心与资源 / Prepare cores & assets

```bash
./run lib core      # 构建并复制到 app/libs/libcore.aar / Build and install libcore.aar
./run lib assets    # 下载 geoip / geosite 数据库 / Download geo databases
./run lib plugins   # 下载内置 Xray / Download the bundled Xray core
```

以上步骤需要联网获取依赖和发布资源。Xray 版本及二进制 SHA-256 固定在下载脚本中；geo 数据库取上游最新发布并核对该发布附带的校验值，因此不同日期构建的数据库可能不同。`lib plugins` 只准备内置 Xray，不包含上文需要单独安装的插件。

These steps download dependencies and release assets. Xray's version and binary SHA-256 hashes are pinned in the script. Geo databases use the latest upstream releases and their published checksums, so builds on different dates may contain different databases. `lib plugins` prepares only bundled Xray, not the separately installed plugins listed above.

### 生成 APK / Assemble APKs

```bash
# 本地调试 / Local debugging
./gradlew app:assembleOssDebug

# 发布构建 / Release build
./gradlew app:assembleOssRelease
```

产物分别位于 `app/build/outputs/apk/oss/debug/` 和 `app/build/outputs/apk/oss/release/`，包含 `arm64-v8a`、`x86_64` 分包及 universal APK；GitHub Releases 只发布 universal APK。

Outputs are under `app/build/outputs/apk/oss/debug/` and `app/build/outputs/apk/oss/release/`, including per-ABI and universal APKs. GitHub Releases publish only the universal APK.

未配置签名时，Debug 使用默认调试签名，Release 生成未签名 APK。自定义签名需在仓库根目录放置 `release.keystore`，并通过 `local.properties` 或环境变量同时提供 `KEYSTORE_PASS`、`ALIAS_NAME`、`ALIAS_PASS`；只提供部分字段会使构建失败。配置后，Debug 和 Release 都会使用该签名。

Without custom signing, Debug uses the default debug key and Release is unsigned. For custom signing, place `release.keystore` in the repository root and provide all three values—`KEYSTORE_PASS`, `ALIAS_NAME` and `ALIAS_PASS`—through `local.properties` or environment variables. Partial configuration fails the build. When configured, the key is used for both Debug and Release.

<a id="tests"></a>

## 测试与检查 / Tests & checks

完成上述构建准备后，在仓库根目录执行： / After preparing the cores and assets above, run from the repository root:

```bash
# Go 测试使用已下载的真实 geo 数据库 / Use the downloaded geo databases
export NEKO_TEST_GEOIP_DB="$PWD/app/src/main/assets/sing-box/geoip.db"
export NEKO_TEST_GEOSITE_DB="$PWD/app/src/main/assets/sing-box/geosite.db"
./run lib test

# Android JVM 单测与 Lint / Android JVM tests and lint
./gradlew app:testOssDebugUnitTest app:lintOssRelease --continue

# 版本与补丁一致性检查（联网核对上游源码） / Version and patch checks (fetches upstream source)
./run lib check_versions
```

`lib test` 使用与 AAR 一致的构建标签和链接参数。Android JVM 测试包含 Robolectric 与截图比较；Robolectric 运行时由 Gradle 下载并校验，测试阶段从本地读取。截图基线与配置输出基线的更新方式见下方文档。

`lib test` uses the same build tags and link flags as the AAR. Android JVM tests include Robolectric and screenshot comparisons. Gradle downloads and verifies the Robolectric runtime, which tests then read locally. See the documents below before updating screenshot or configuration baselines.

<a id="documentation"></a>

## 项目结构与开发文档 / Layout & documentation

| 路径 / Path | 内容 / Contents |
| --- | --- |
| [app/](app/) | Android 界面、配置生成、服务和测试 / Android UI, configuration generation, services and tests |
| [libcore/](libcore/) | Go 核心封装与 Android 绑定 / Go core integration and Android bindings |
| [libcore/sing-box/](libcore/sing-box/) | 含本仓库补丁的 sing-box 源码 / Vendored sing-box with local patches |
| [buildScript/](buildScript/) | `./run` 调用的构建、资源下载和检查脚本 / Build, asset download and check scripts invoked by `./run` |
| [buildSrc/](buildSrc/) | SDK、构建变体、签名与 APK 命名配置 / SDK, variants, signing and APK naming |
| [nb4a.properties](nb4a.properties) | 应用包名、版本名和版本号 / Package name and version metadata |
| [.github/workflows/](.github/workflows/) | CI 构建、检查与发布 / CI builds, checks and releases |

- [sing-box 补丁说明 / sing-box patch notes](libcore/sing-box/NEKO.md)
- [AnyTLS 行为与限制 / AnyTLS behavior and limits](libcore/anytlscompat/README.md)
- [AnyTLS 对照验证 / AnyTLS compatibility oracle](experiments/anytls-compat/README.md)
- [UI 截图基线 / UI screenshot baselines](app/src/test/screenshots/README.md)
- [配置输出基线 / Configuration output baselines](app/src/test/resources/golden/README.md)
- [序列化兼容性样本 / Serialization compatibility fixtures](app/src/test/resources/kryo/README.md)

## 致谢与许可 / Credits & licensing

本项目的源代码许可见 [LICENSE](LICENSE)（GPL-3.0-or-later）；第三方组件遵循各自的许可证。 / See [LICENSE](LICENSE) for the project's GPL-3.0-or-later terms; third-party components retain their own licenses.

- **核心 / Cores**：[SagerNet/sing-box](https://github.com/SagerNet/sing-box)、[XTLS/Xray-core](https://github.com/XTLS/Xray-core)。
- **Android 界面 / Android GUI**：[shadowsocks/shadowsocks-android](https://github.com/shadowsocks/shadowsocks-android)、[SagerNet/SagerNet](https://github.com/SagerNet/SagerNet)、[MatsuriDayo/NekoBoxForAndroid](https://github.com/MatsuriDayo/NekoBoxForAndroid)。
- **Web 面板 / Web dashboard**：[Yacd-meta](https://github.com/MetaCubeX/Yacd-meta)。
- **AnyTLS 兼容实现 / AnyTLS compatibility**：来源、改动范围及依赖许可见 [NOTICE](libcore/anytlscompat/NOTICE.md)。 / See the notice for sources, adaptation details and dependency licenses.

### 应用图标 / App icon

应用图标改编自 [OpenMoji](https://openmoji.org/)（[hfg-gmuend/openmoji](https://github.com/hfg-gmuend/openmoji)）的棺材 emoji ⚰️（U+26B0），按 [CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/) 许可使用。以下图标资源为其派生作品，同样按 CC BY-SA 4.0 发布：

The app icon is adapted from OpenMoji's coffin emoji ⚰️ (U+26B0), licensed under CC BY-SA 4.0. The following adaptations use the same license:

- `app/src/main/res/drawable/ic_launcher_foreground.xml`
- `app/src/main/res/drawable/ic_launcher_monochrome.xml`
- `app/src/main/res/mipmap-*/ic_launcher.png`
- `app/src/main/res/drawable/ic_box.xml`
- `app/src/main/res/drawable/ic_box_off.xml`

All emojis designed by [OpenMoji](https://openmoji.org/) – the open-source emoji and icon project. License: [CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/).
