# NekoBox 安卓版

基于 sing-box 的 Android 通用代理客户端，内置 Xray 核心。

## 下载与系统要求

* Android 7.0（API 24）及以上，支持 arm64-v8a 和 x86_64。
* 前往[发布页](https://github.com/ql0202cocou/NekoBoxForAndroid/releases)下载通用 APK。
* 版本号带 `-aN` 或 `-bN` 后缀的是预览版。

## 界面与导航

* 底部提供配置、分组、路由、设置四个入口，支持点击或按住左右拖选；松手切换，拖出导航栏松手则取消。
* 设置分为七个分类，返回首页时恢复原滚动位置，连续切换分类后也会保留。
* 页面采用不透明的左右平移，进入分类与返回方向相反；支持从右向左布局，并遵循系统动画时长设置。

## 代理协议与订阅

支持 SOCKS（4/4a/5）、HTTP(S)、SSH、Shadowsocks、VMess、VLESS、Trojan、AnyTLS、ShadowTLS、TUIC、Hysteria 1/2 和 WireGuard。

Trojan-Go、NaïveProxy、Mieru 分别需要安装 `trojan-go-plugin`、`naive-plugin`、`mieru-plugin`；Hysteria 1 的 faketcp / wechat-video 模式也需要外部插件。

支持 Shadowsocks、ClashMeta、v2rayN 等常见订阅格式及 sing-box 出站。仅导入节点，忽略分流规则等其他配置。

## 内置核心

| 核心 | 版本 | 用途 |
| --- | --- | --- |
| [sing-box](https://github.com/SagerNet/sing-box) | v1.14.4，附带本仓库补丁及 uTLS v1.8.8 | 承载多数协议及全部 AnyTLS 节点 |
| [Xray-core](https://github.com/XTLS/Xray-core) | v26.11.9 | XHTTP 必需；优先承载带 REALITY 或 mldsa65Verify 的 VMess / VLESS / Trojan，并支持 Xray 特有参数 |

VMess / VLESS / Trojan 可手动指定核心；若所选核心不支持节点设置，会列出冲突字段，不自动回落到其他核心。

AnyTLS 支持整证书固定、独立证书信任策略、uTLS 和 ECH。升级迁移、分享链接、订阅更新及导出限制见 [AnyTLS 支持说明](libcore/anytlscompat/README.md)。

## 构建

环境要求：

* JDK 17 及以上。
* Android SDK 平台 37、构建工具 36.0.0，通过 `local.properties` 的 `sdk.dir` 或 `ANDROID_HOME` 指定路径。
* NDK 25.0.8775105。
* Go 1.26.x，使用该版本线的最新补丁；系统 Go 版本更高时，通过 `GOTOOLCHAIN` 指定 1.26 工具链。

```bash
./run lib core      # 构建 libcore.aar
./run lib assets    # 下载地理数据库
./run lib plugins   # 下载 Xray 核心
./gradlew app:assembleOssRelease
```

未配置签名时生成未签名的 APK。

## 致谢与许可

* 核心：[SagerNet/sing-box](https://github.com/SagerNet/sing-box)、[XTLS/Xray-core](https://github.com/XTLS/Xray-core)。
* Android 界面：[shadowsocks/shadowsocks-android](https://github.com/shadowsocks/shadowsocks-android)、[SagerNet/SagerNet](https://github.com/SagerNet/SagerNet)。
* 网页仪表板：[Yacd-meta](https://github.com/MetaCubeX/Yacd-meta)。

应用、快捷设置磁贴和通知图标改编自 [OpenMoji](https://openmoji.org/) 的表情（U+26B0，[原作仓库](https://github.com/hfg-gmuend/openmoji)）。原作及本项目的衍生图标均按 [CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/) 许可发布；仓库其余部分按 [GPL-3.0](https://www.gnu.org/licenses/gpl-3.0) 发布。

## 项目来源

本项目基于 [MatsuriDayo/NekoBoxForAndroid](https://github.com/MatsuriDayo/NekoBoxForAndroid)，上游长时间不维护后由本人 fork 后独立维护的一个小众分支，未在 GitHub 上建立派生仓库关联。

这是个人使用的项目，主要方便在手机上访问 GitHub、OpenRouter 等开发者服务，无任何推广计划。
