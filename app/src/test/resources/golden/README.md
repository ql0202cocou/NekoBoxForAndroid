# 旧配置输出基线（golden）

这里存的是重构配置生成代码之前、在 Android 模拟器上采集的旧输出（plan.md R1a 第 1 步）。之后的每次改动
（设置注入、外核生成器改写序列化、K0 合并外核进程等）都重跑采集，用比较工具与这份基线对照；R1b 的 JVM
黄金测试也以这里的 `input.json` 为输入。产物里的地址、凭据、密钥全部是虚构的（见「夹具」一节）。

采集入口只编进 debug 包（`app/src/debug/`），R1b 的 JVM 黄金测试建成后删除；这份基线不随之删除。

## 重新采集

```bash
export ANDROID_HOME=$HOME/Library/Android/sdk
ANDROID_SERIAL=emulator-5554 ./run golden collect            # 写到新建的临时目录
ANDROID_SERIAL=emulator-5554 ./run golden collect --golden   # 写到本目录（保留本 README，其余整体替换）
ANDROID_SERIAL=emulator-5554 ./run golden collect --out <目录> # 写到指定的空目录
```

脚本（`buildScript/golden/collect.sh`）依次：核对 `app/executableSo/<abi>/` 下的 Xray / mihomo 与
`buildScript/lib/plugins.sh` 固定的 sha256 → 构建并安装 oss debug 包（`adb install -r`，保留数据）→
执行

```bash
adb shell content call --uri content://moe.nb4a.debug.golden --method collect \
  --extra commit:s:<提交> --extra dirtyCount:i:<N> --extra dirtyTop:s:<前若干条> \
  --extra codeDirtyCount:i:<M> --extra codeDirtyTop:s:<前若干条> \
  --extra xrayVersion:s:<…> --extra xrayPinnedSha256:s:<…> --extra xrayPackagedSha256:s:<…> \
  --extra mihomoVersion:s:<…> --extra mihomoPinnedSha256:s:<…> --extra mihomoPackagedSha256:s:<…> \
  [--extra allowWipe:b:true]
```

（`<提交>` 是 `git rev-parse HEAD`；`<N>` 是 `git status` 列出的有改动路径（含未跟踪文件）总数，`<前若干条>`
是排序后前 50 条换行分隔再整体 Base64（没有时为 `-`）；`<M>` 与其后一项是同样的统计，只算不在
`app/src/debug/`、`app/src/test/`、`buildScript/golden/`、`doc/` 之下的路径。路径可能多达数千条，全部放进
命令行会超出长度上限，所以只传总数与前若干条；`PackagedSha256` 取自本次安装的 APK。call 同步返回，`status=ok` 才算成功，失败时 `message`
给出原因）→ 用
`adb exec-out run-as moe.nb4a.debug tar -cf - -C files golden-out` 取回 → 写到目标目录。
其它选项：`--no-build`（不构建，直接装上一次构建的 APK）、`--no-install`（用设备上已装的包）、`--allow-wipe`。

APK 只按 `app/build/outputs/apk/oss/debug/output-metadata.json`（Gradle 每次构建写出的清单）选：取设备 ABI 对应的
那一项，没有就取 universal，文件名以清单为准，不按目录里的文件名猜（目录里常留着旧版本的 APK）。`--no-build` 与
`--no-install` 同样按清单选（后者只用它核对内置核心的哈希）。安装后、触发采集前，脚本还会核对设备上 debug 包的
`versionCode` 与清单一致。清单不存在、没有对应条目、清单里的文件不存在、设备上的版本号不一致，都会返回非零并打印原因。

采集入口的约束：

- 只接受 adb shell（uid 2000）与 root 的调用；不启动 VPN / 代理服务，不启动任何外核进程，不发起网络连接。
- 防误伤：数据库里有不是采集入口建的节点 / 分组 / 规则时拒绝运行，除非带 `--allow-wipe`。采集结束后清空
  节点 / 分组 / 规则三张表，并把 `configuration.db` 的设置恢复成采集前的样子。
- 环境检查：装了 Trojan-Go / Naive / Mieru / Hysteria 的外部插件 app 时拒绝（结果会变，见下文）；
  设备上的 `libxray.so` / `libmihomo.so` 必须就是脚本刚装的 APK 里那份。

## 目录结构（格式 v1）

```
manifest.json                                              采集元数据，不参与比较
scenarios/<场景 id>/input.json                              输入：设置、分组、节点、规则
scenarios/<场景 id>/<mode>/result.json                      mode 为 run、test、export
scenarios/<场景 id>/<mode>/sing-box.json                    run / test 成功时：sing-box 配置原文
scenarios/<场景 id>/<mode>/ext-<n>.<pluginId>.<json|yaml>   run / test 成功时：第 n 个外核配置原文（n 从 0 起，按生成顺序）
scenarios/<场景 id>/export/export.txt                       export 成功时：exportConfig() 返回的整段原文
address/corpus.json                                        地址解析语料与旧实现的结果
```

场景 id 只用小写字母、数字和连字符。配置原文按 UTF-8 原样写入，一个字节都不改；其余 JSON 由采集入口
用 Gson 输出（缩进两格，末尾一个换行）。外核配置只有 mihomo 是 YAML。

## 三种模式

| mode | 走的路径 | 说明 |
| --- | --- | --- |
| `run` | 真实的 `BoxInstance.init()` | `buildConfig(profile)`，再对 `externalIndex` 逐项 `initPlugin` 并调 `core.config(port, cacheFile, null)`。只把 `loadConfig` 换成空操作（不建 libcore box），不调 `launch` |
| `test` | 同上，`buildConfig` 与 `mihomoTestController` 经反射调用一个真实 `TestInstance` 的实现 | 即 `buildConfig(profile, true)`；单节点 AnyTLS 走 mihomo 且分组没有前置 / 落地时，mihomo 配置带 Clash API 端口与随机 secret |
| `export` | 直接调 `ProxyEntity.exportConfig()` | |

与真实运行的差别只有两处：不建 libcore box；Trojan-Go / Naive / Mieru / Hysteria 插件在模拟器上都没装，
`initPlugin` 会对它们抛「插件未安装」，run / test 预先往 `pluginPath` 填占位结果绕过（`core.config` 本身
用不到插件路径）。每种模式都从数据库重新取实体，互不影响。

## 依赖模拟器条件的结果

基线在只有内置 Xray 与 mihomo、没有任何外部插件 app 的模拟器上采集（`manifest.json` 的 `plugins` 记录了
实际状态），以下结果随这个条件而定：

- 选择器分组预检（`ConfigBuild.precheck` 的 `requirePlugin`）会跳过需要插件 app 的成员，路由规则指向这类
  节点时同样被跳过（规则落入「出站不存在」）；导出模式不查插件，照常构建。这是旧行为，照实记录。
- Hysteria 1 插件节点作为链上最先拨号的一跳时免映射：判断依据是外部插件 app 是否存在及其来源。
- 按应用分流的规则只用 UID 由平台固定的系统包（`android` 与 `com.android.providers.settings` 为 1000、
  `com.android.phone` 为 1001、`com.android.shell` 为 2000）和一个不存在的包名；实际解析结果记在
  `input.json` 的 `packageUids`。
- 临时文件路径（`/data/user/0/moe.nb4a.debug/cache/…`）带应用的数据目录。
- 设备语言只影响 Toast 文案，不进任何产物；`manifest.json` 记下了 `system.locale`。

## manifest.json

| 字段 | 内容 |
| --- | --- |
| `formatVersion` | 1 |
| `commit` | 采集所基于的提交 |
| `dirty` | 工作区的改动：`count` 是有改动的路径总数（含未跟踪文件，0 即干净），`top` 是排序后的前 50 条；`codeCount` / `codeTop` 是其中不在 `app/src/debug/`、`app/src/test/`、`buildScript/golden/`、`doc/` 之下的路径，`codeCount` 非 0 说明有可能影响配置输出的代码改动。（旧格式的 manifest 没有 `dirty`，只有全部路径的数组 `dirtyPaths`） |
| `app` | applicationId、versionName、versionCode、flavor、buildType |
| `cores.sing-box` | `Libcore.versionBox()` 的原文（逐行）与其中的版本号 |
| `cores.xray` / `cores.mihomo` | `version`、`pinnedSha256` 取自 `plugins.sh`（上游二进制）；`installedSha256` 是设备上那份文件的实算值（打包时经 AGP strip，与上游文件不同） |
| `plugins` | 六个外核插件 id 的状态：`builtin`、`missing` 或 `external:<包名>` |
| `system` | API 级别（`sdkInt`、`sdkIntFull`）、系统版本、build fingerprint、ABI、页大小、语言 |
| `scenarios` / `counts` | 场景数与各模式成功 / 失败的数量 |
| `collectedAt` | 采集时间（UTC）；两次采集之间只有它允许不同 |

## input.json

| 字段 | 内容 |
| --- | --- |
| `formatVersion`、`id`、`description` | |
| `mainProfileId` | 被选中运行的节点 id |
| `settings` | 构建前 `KeyValuePair` 表的原样内容（按键排序，`{key, type, value}`）。每个场景先清空整张表，只写场景声明的键，外加进程启动时会写回的 `legacyAssetsMigrated` |
| `effectiveSettings` | 构建实际读到的值（未写的键取 `DataStore` 默认值），含 `SingBoxOptionsUtil.domainStrategy` 对 `dns-remote` / `dns-direct` / `server` 的结果 |
| `groups` | 分组的全部字段（按 id） |
| `profiles` | 节点实体的标量字段，加 `beanColumn`（存 bean 的列）、`beanClass` 与 `beanKryoBase64`：从表里直接读出的 bean 字节（与写进数据库的字节相同），标准 Base64、不换行 |
| `rules` | 规则的全部字段（按 id；`packages` 保持存储顺序） |
| `packageUids` | 规则里出现的包名 → `PackageCache` 解析出的 UID（未安装为 null） |
| `plugins` | 同 `manifest.json` |

`GoldenInputTest` 遍历本目录的 `input.json`，用生产代码（`ProxyEntity.putByteArray`）把每个 bean 反序列化，
核对类型，并核对重新序列化得到同样的字节。

## result.json

```
{
  "status": "ok" | "error",
  "error": { "class", "message", "causes": [ { "class", "message" } ] },     仅 error
  "exportName": "…",                                                          仅 export 且 ok
  "build": { "mainEntId", "selectorGroupId", "profileTagMap": { "节点 id": tag },
             "trafficMap": { tag: [节点 id] }, "boxIndexNames": {}, "boxTagNames": {} },   仅 run / test 且 ok
  "external": [ { "file", "chainIndex", "profileId", "pluginId", "port", "finalAddress", "finalPort",
                  "controller": { "port", "secret" } | null, "tempFiles": [] } ],          仅 run / test 且 ok，按生成顺序
  "dynamic": { "ports": [], "paths": [], "secrets": [] }
}
```

- `error`：构建路径抛出的异常就是这个场景的旧行为，记异常类全名、`message`（不取本地化消息）与 cause 链。
- `build` 里的映射按键排序输出；`trafficMap` 的值保持原列表顺序。
- `external[].controller` 是传给 `core.config` 的测速控制端口与 secret；`tempFiles` 是 `core.config` 期间经
  `cacheFile` 领到、且出现在这份外核配置里的文件（hysteria 1 的 CA），采集结束即删除。
- `dynamic` 列出本模式这一次构建里每次运行都可能不同的全部取值，比较工具只按它替换：
  - `ports`：`mkPort()` 分到的端口。先按 sing-box 配置的结构找——指向 127.0.0.1 的 socks 出站的
    `server_port`（按 outbounds 顺序），再是 tag 含 `-mapping-` 的映射入站的 `listen_port`（按 inbounds
    顺序）；run / test 再补上 `externalIndex` 里的本机端口与测速控制端口。两次采集同一位置一一对应。
  - `paths`：临时文件的绝对路径（run / test 来自 `tempFiles`，export 按 cacheDir 前缀从原文里找）。
  - `secrets`：测速时随机生成的 mihomo Clash API secret。
  - 夹具与设置固定下来的值（mixed 端口、预先写好的 Clash API secret、节点的服务器端口等）不算动态值。

## address/corpus.json

`ktx/Nets.kt` 等处的地址判断 / 解析函数在一组语料上的旧结果：`functions` 说明每个函数用在哪里，`entries`
逐条给出输入与各函数的返回值（抛异常时记 `{exception, message}`）。`parseNumericAddress` 走
`Os.inet_pton`、`isWireGuardLocalAddressList` 走 `InetAddress.getByName`，普通 JVM 上不能当对照；
以后换成纯 Kotlin 实现时，拿这份结果在 JVM 上逐条比对。语料含普通 / 越界 / 缺段 / 多段的 IPv4、带前导零
的 IPv4、各种写法的 IPv6（含 zone、IPv4 映射、方括号）、host:port、CIDR、域名、空白与非法输入。

## 场景与夹具

场景表在 `app/src/debug/java/io/nekohasekai/sagernet/golden/collect/GoldenScenarios.kt`，按类别分组：
单节点（sing-box、Xray、mihomo、插件核心）、自定义配置、链、分组前置 / 落地、选择器、路由规则、设置变体、
分组 DNS。新增场景只往表里加；场景 id 入库后不要改名。

夹具（`GoldenFixtures.kt`）完全虚构且确定：地址只用 example.com / example.net / example.org 的子域与
192.0.2.0/24、198.51.100.0/24、203.0.113.0/24、2001:db8::/32；服务器端口都在 30000 以下（避开系统分配的
临时端口）；UUID、密码、REALITY / WireGuard 密钥、ECH 配置等由固定文本编码而来，证书是自签的虚构证书
（CN=golden.example.com）。节点、分组、规则都按显式 id 写入；每个场景开始前把构建会读到的设置全部显式写好
（含 Clash API secret 的固定假值）。采集入口在每种模式后核对设置表、每个场景结束时核对数据库，发现被构建
改写就让整次采集失败。
