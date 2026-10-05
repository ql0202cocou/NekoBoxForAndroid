# 配置输出基线（golden）

这里存的是在 Android 模拟器上采集的配置输出基线（plan.md R1a 第 1 步）。之后的每次改动（设置注入、外核生成器
改写序列化等）都重跑采集，用比较工具与这份基线对照；R1b 的 JVM 黄金测试也以这里的 `input.json` 为输入。产物里的
地址、凭据、密钥全部是虚构的（见「夹具」一节）。

K0（每种核心一个进程）有意改变了外核一侧：Xray、mihomo 的配置按「组」合并，产物格式升到 v2。当时的做法是先用
`./run golden compare --sing-box-only` 证明 sing-box 一侧与 R1a 的 v1 基线逐场景一致，再整体换成新采集的结果；
插件核心（Trojan-Go、Naive、Mieru、Hysteria 1）的配置原文与 v1 相同，Xray / mihomo 合并配置里每个跳实例的出站 /
代理与 v1 对应的单节点配置相同，只多了标识。

K0b（本机 socks 认证）又有意改变了两侧：sing-box 里接 Xray / mihomo 的本机 socks 出站带上本次构建的用户名 / 密码，
Xray 的各入站要求同一组凭据（`auth` / `accounts`，并关掉访问日志 `log.access`），mihomo 的各 listener 用 `users`
要求同一组凭据；产物格式升到 v3（`result.json` 的跳实例记录凭据，凭据登记进 `dynamic.secrets`）。做法是先用
`./run golden compare --ignore-local-auth` 证明去掉认证之后 293 个场景与 K0 的 v2 基线逐场景一致，再整体换成新采集
的结果，并再采集一次做完整比较。四种插件核心的入站认证没有核实过，配置与 v2 逐字节相同，sing-box 一侧接它们的
socks 出站也不带凭据。

采集入口只编进 debug 包（`app/src/debug/`）。采集入口与实测入口保留到 K1 阶段完成之后（维护者 2026-10-05 决定）：
R1b 的 JVM 黄金测试（见「JVM 黄金测试」一节）不覆盖 Android 一侧的外壳（DataStore 的默认值、Room 读取事务、
PackageCache、插件探测）与内置核心对合并配置的校验，这两样只有模拟器重新采集能对照。这份基线不随入口删除。

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
这套选 APK、安装与核对的逻辑在 `buildScript/golden/lib.sh`，`measure.sh`（见文末「K0 实测」）共用。

采集入口的约束：

- 只接受 adb shell（uid 2000）与 root 的调用；不启动 VPN / 代理服务，不发起网络连接。唯一启动的外部进程是内置
  Xray / mihomo 的校验入口（`run -test` / `-t`，只加载配置、不监听不连接，见「三种模式」），不启动外核本身。
- 防误伤：数据库里有不是采集入口建的节点 / 分组 / 规则时拒绝运行，除非带 `--allow-wipe`。采集结束后清空
  节点 / 分组 / 规则三张表，并把 `configuration.db` 的设置恢复成采集前的样子。
- 环境检查：装了 Trojan-Go / Naive / Mieru / Hysteria 的外部插件 app 时拒绝（结果会变，见下文）；
  设备上的 `libxray.so` / `libmihomo.so` 必须就是脚本刚装的 APK 里那份。

## 比较

```bash
./run golden compare <新采集目录> [基线目录]                  # 全部产物，基线默认本目录
./run golden compare --sing-box-only <新采集目录> [基线目录]  # 只比 sing-box 一侧
./run golden compare --ignore-local-auth <新采集目录> [基线目录]  # 全部产物，但先去掉本机 socks 认证
```

比较逻辑在测试源集的 `GoldenCompareTree`（由 `GoldenCompareBaselineTest` 调用），规则见 `GoldenCompare.kt` 开头。
`--sing-box-only` 只比 `input.json`（不含 `formatVersion`）、`sing-box.json`、`export.txt` 的第 0 段、`result.json`
里除 `external` 之外的部分（含 `dynamic` 的个数）；外核配置（`ext-*`、`export.txt` 第 1 段起、`external`）与带格式
版本号的 `address/corpus.json` 不比较，外核一侧的动态值不出现也不给警告。它用来证明只改外核一侧的改动（含产物格式
升级）没有动到 sing-box 一侧：两侧可以是不同格式版本的产物。

`--ignore-local-auth` 比较全部产物，但比较前从两侧去掉本机 socks 认证，去掉的正好是这些位置（实现在
`GoldenCompareTree.kt` 的 `GoldenLocalAuth`，别处同名的键照常比较）：sing-box 配置（`sing-box.json` 与 `export.txt`
第 0 段）里指向 127.0.0.1 的 socks 出站的 `username` / `password`；Xray 配置里各入站 `settings` 的 `auth` 与
`accounts`、`log` 的 `access`；mihomo 配置里各 listener 的 `users`（`export.txt` 的外核段按结构认核心）；`result.json`
里 `external[].hops[]` 的 `localAuth`，以及 `dynamic.secrets` 里这些凭据的值（取自跳实例的记录与 sing-box 配置里本机
socks 出站的凭据）。`input.json` 与 `address/corpus.json` 顶层的 `formatVersion` 不比较。它用来证明加认证的改动只多出
了认证：两侧可以一侧带认证（v3）、一侧不带（v2）。

## JVM 黄金测试

```bash
./gradlew app:testOssDebugUnitTest --tests '*GoldenJvm*' --tests '*GoldenAddressCorpusTest'
```

配置构建只消费注入的输入（`fmt/ConfigInput.kt`）之后，整条构建能在普通 JVM 上跑。`GoldenJvmBuildTest` 对本目录
每个场景的每种模式（293 × 3）：用 `input.json` 的 `groups` / `profiles` / `rules` 建内存数据源（查询语义同 DAO），
设置取 `effectiveSettings`（Clash API secret 只在运行模式且开了 Clash API 时取，同生产外壳），包名 UID 取
`packageUids`，插件状态按 `plugins` 回答（`missing` 即「plugin X is not installed」）；再走
「采集引用闭包（`ConfigSnapshot.collect`）→ 纯构建入口 `buildConfig(input)` → `ExternalRunPlan.from` →
`assemble`（运行 / 测速）或 `exportConfigText`（导出）」，按采集入口的格式写成一棵产物树（`GoldenJvmModes.kt`），
用 `GoldenCompareTree` 与本目录做全量比较。失败信息就是比较报告：场景、模式、文件、JSON 路径与两侧的值。

与模拟器采集的差别：

- 不经过 Android 外壳（`captureConfigInput`：DataStore、Room 读取事务、PackageCache）：输入由 `GoldenJvmModes.kt`
  按 `input.json` 拼出，与外壳共用的只有两处模式判断（要解析的包名 `packagesToResolve`、是否取 Clash API secret
  `needsClashApiSecret`）。
- 组装时不做插件安装确认（`BoxInstance` 传给 `assemble` 的 `beforeHop`），也没有内置 Xray / mihomo 的启动前校验
  （只进 `manifest.json`）。
- 平台换成假实现（`FakeConfigPlatform`）。端口从 50001 起递增、本机 socks 凭据用固定种子、测速控制 secret 是固定值：
  都是 `dynamic` 里按出现次序替换的值；测速是否开 mihomo 的控制器取构建结果的
  `delayTestOnMihomo`（构建按生产的 `mihomoDelayTestApplies` 用采集到的主分组行与设置算出）。
- 数字地址解析（生产走 `Os.inet_pton`）先查 `address/corpus.json`，语料之外的输入走 `StrictNumericAddress`
  （按 bionic `inet_pton` 的规则：IPv4 四段十进制、前导零按十进制，IPv6 每组至多 4 位十六进制、不接受 zone）。
  `GoldenAddressCorpusTest` 要求它在全部语料上与设备结果一致。基线构建会查到的语料之外的输入只列在
  `GoldenAddressCorpus.KNOWN_OUTSIDE`（连同回退实现应给的结果）：`GoldenAddressCorpusTest` 按它核对回退实现，
  `GoldenJvmBuildTest` 跑完全部场景后核对查到的正好是这些。多出新输入时测试失败，要么把它补进语料（重新采集），
  要么核对回退实现在它上的结果后加进名单。

只有一处按名单放宽（`GoldenJvmBuildTest` 的 `RELAXATIONS`，写明原因）：`mihomo-anytls-bad-certificate` 的三种模式里，
错误证书的 cause 链随平台的 X.509 实现而变（Android 的 Conscrypt 共 5 个 cause，JDK 共 3 个，`CertificateException`
的消息也不同）。顶层异常的类与消息、第一个 cause、第二个 cause 的类照常比较，只放宽 cause 的个数、第二个 cause 的
消息与第三个起的 cause；这三个文件的其余部分与其它产物都照常比较。名单上的放宽没有用到时测试同样失败，平台差异消失
后要删掉。不许为放宽改生产代码或整场景跳过。

诊断与警告基线里没有，另按 `app/src/test/resources/golden-jvm/expected-diagnostics.json` 断言（放在本目录之外：
`./run golden collect --golden` 会整体替换本目录）：只列有诊断（`ConfigBuildDiagnostic`，类型与字段）或警告
（`ConfigPlatform.warn` 的文本，带异常时接「: 异常类全名: 消息」）的场景与模式，每个场景的 `note` 写明依据；其余
场景与模式都断言为空。新增或改动场景后，期望要按代码逻辑逐条写，不能拿测试的实际输出回填。

另有几组针对性的 JVM 测试，同样取本目录的输入：`GoldenJvmIsolationTest`（采集之后清空数据源、改调用方对象，
构建产物不变；构建不改写调用方对象的 bean）、`GoldenJvmPluginStateTest`（基线采集条件之外的插件状态：插件都已安装
时选择器成员全部保留；外部插件 app 的 authority 是 Matsuri exe 前缀时 Hysteria 1 免映射、不是时报插件不受支持）。

两者各管一段：JVM 黄金测试覆盖纯构建、运行计划、组装与导出文本的全部产物和诊断，跑一遍约 3 秒，改配置生成时先跑它；
模拟器采集（`./run golden collect` + `./run golden compare`）还覆盖 Android 一侧的外壳（DataStore、Room 读取事务与
排序、PackageCache、插件探测与组装前的插件安装确认、`Os.inet_pton`、Conscrypt）与内置核心对每份合并配置的校验
（`externalChecks`），动到外壳、平台实现或外核配置时仍要重新采集比较。

## 目录结构（格式 v3）

```
manifest.json                                              采集元数据，不参与比较
scenarios/<场景 id>/input.json                              输入：设置、分组、节点、规则
scenarios/<场景 id>/<mode>/result.json                      mode 为 run、test、export
scenarios/<场景 id>/<mode>/sing-box.json                    run / test 成功时：sing-box 配置原文
scenarios/<场景 id>/<mode>/ext-<n>.<pluginId>.<json|yaml>   run / test 成功时：第 n 组外核的配置原文（n 从 0 起，按组的顺序）
scenarios/<场景 id>/export/export.txt                       export 成功时：exportConfig() 返回的整段原文
address/corpus.json                                        地址解析语料与旧实现的结果
```

「组」来自外核运行计划（`fmt/ExternalRunPlan.kt`）：一次构建里每个走外核的跳实例（某条链上的一个外核节点；同一个
节点在不同链里是不同的跳实例）按构建登记的顺序（`ConfigBuildResult.externalChains`：链的顺序，链内按跳的顺序）编号，Xray 的全部跳实例一组、mihomo 的全部跳实例一组，
插件核心每个跳实例一组；组的顺序按各组第一个跳实例。一组一份配置、一个进程。v1 是一个跳实例一份配置（`ext-<n>`
的 n 是跳实例序号），`result.json` 的 `external` 逐跳实例记录；其余布局与 v2 相同。v3（K0b）的布局与 v2 相同，
只是跳实例记录多了 `localAuth`、`dynamic.secrets` 多了本机 socks 凭据，配置里多了认证（见文首）。

场景 id 只用小写字母、数字和连字符。配置原文按 UTF-8 原样写入，一个字节都不改；其余 JSON 由采集入口
用 Gson 输出（缩进两格，末尾一个换行）。外核配置只有 mihomo 是 YAML。

## 三种模式

| mode | 走的路径 | 说明 |
| --- | --- | --- |
| `run` | 真实的 `BoxInstance.init()` | `buildConfig(profile)`，再由构建结果建外核运行计划，经运行 / 测速 / 导出共用的组装入口 `ExternalRunPlan.assemble` 逐个跳实例 `initPlugin`、生成每组的配置，最后做启动前校验：用内置 Xray / mihomo 的校验入口把每组合并配置加载一遍（与真实启动同一段代码；插件核心不校验）。只把 `loadConfig` 换成空操作（不建 libcore box），不调 `launch` |
| `test` | 同上，`buildConfig` 与 `mihomoTestController` 经反射调用一个真实 `TestInstance` 的实现 | 即 `buildConfig(profile, true)`；单节点 AnyTLS 走 mihomo 且分组没有前置 / 落地时，mihomo 配置带 Clash API 端口与随机 secret |
| `export` | 直接调 `ProxyEntity.exportConfig()` | sing-box 配置后接每组一段外核配置（同一个组装入口），段间一个空行 |

与真实运行的差别只有两处：不建 libcore box；Trojan-Go / Naive / Mieru / Hysteria 插件在模拟器上都没装，
`initPlugin` 会对它们抛「插件未安装」，run / test 预先往 `pluginPath` 填占位结果绕过（生成配置本身
用不到插件路径；占位结果不算内置二进制，与真实运行一样不校验）。每种模式都从数据库重新取实体，互不影响。

启动前校验没过时 `init()` 抛出带节点名的错误，这个场景的这种模式就记成 `error`，与真实启动时用户看到的一样；
基线里原来成功的场景若因此失败，说明有合并配置被内置核心拒绝，是要报告的发现，不能改场景绕过。每次校验的
结论记进 `manifest.json` 的 `externalChecks`，不进 `result.json`。

## 依赖模拟器条件的结果

基线在只有内置 Xray 与 mihomo、没有任何外部插件 app 的模拟器上采集（`manifest.json` 的 `plugins` 记录了
实际状态），以下结果随这个条件而定：

- 选择器成员的规划检查（`ConfigBuild.checkMemberHop` 确认插件可用）会跳过需要插件 app 的成员，路由规则指向这类
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
| `formatVersion` | 3（K0b 起；v1、v2 见「目录结构」一节末尾） |
| `commit` | 采集所基于的提交 |
| `dirty` | 工作区的改动：`count` 是有改动的路径总数（含未跟踪文件，0 即干净），`top` 是排序后的前 50 条；`codeCount` / `codeTop` 是其中不在 `app/src/debug/`、`app/src/test/`、`buildScript/golden/`、`doc/` 之下的路径，`codeCount` 非 0 说明有可能影响配置输出的代码改动。（旧格式的 manifest 没有 `dirty`，只有全部路径的数组 `dirtyPaths`） |
| `app` | applicationId、versionName、versionCode、flavor、buildType |
| `cores.sing-box` | `Libcore.versionBox()` 的原文（逐行）与其中的版本号 |
| `cores.xray` / `cores.mihomo` | `version`、`pinnedSha256` 取自 `plugins.sh`（上游二进制）；`installedSha256` 是设备上那份文件的实算值（打包时经 AGP strip，与上游文件不同） |
| `plugins` | 六个外核插件 id 的状态：`builtin`、`missing` 或 `external:<包名>` |
| `system` | API 级别（`sdkInt`、`sdkIntFull`）、系统版本、build fingerprint、ABI、页大小、语言 |
| `scenarios` / `counts` | 场景数与各模式成功 / 失败的数量 |
| `externalChecks` | run / test 模式里启动前校验的次数（每组 Xray / mihomo 合并配置一次）：`checked`、`failed`、`inconclusive`（超时、被信号杀掉、进程起不来，照常继续）的总数，`byPlugin` 按插件 id 分开计（另有 `passed`），`problems` 逐条列出没过与没有结论的场景、模式、插件与原因。基线全部场景的合并配置（Xray 167 份、mihomo 87 份）都应被校验且全部通过 |
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

外核配置的 JVM 黄金测试也从这里取输入：`GoldenBaseline` 列出某个 pluginId 在 run / test 下的全部外核配置（一组一个
用例），每个用例按 `result.json` 的 `external` 重建这个模式的完整运行计划（bean 每个用例新建一份；拨号目标按记录的
`finalAddress` / `finalPort` 还原：本机地址是经映射、端口即映射端口，其余是不映射，记录的必须正是节点的
`serverAddress` / `serverPort`，服务器地址本身是本机的节点分不清两者，重建时直接报错；本机 socks 凭据用跳实例记录的 `localAuth`，计划自己检查 Xray / mihomo 的跳实例必须有、
插件核心的不能有；核对计划分出的组与标识和记录的一致；设置取 `effectiveSettings` 的 `logLevel`、
`ipv6Mode`、`globalAllowInsecure`），`GoldenExternalCoreCheck.assertMatchesBaseline(pluginId)` 经组装入口
`assemble` 重新生成，取这一组的配置与原文做结构比较。端口直接用记录的值；`tempFiles` 与本次 `cacheFile` 分到的路径
按动态路径比较。每个核心一个测试类（如 `GoldenMihomoTest`）。

`GoldenExternalWiringTest` 直接在基线上核对两端接得上：每个场景每种模式里，sing-box 配置中每个指向本机的 socks
出站端口都恰好是某份外核配置里一个入站的端口（反过来每个外核入站也恰好被一个 socks 出站用到）；该入站绑定的出站拨向
的地址端口等于跳实例的映射目标（hysteria 1 免映射时按 `serverPorts` 拨号，只核对地址）；映射目标是本机时，sing-box
配置里有监听这个端口的映射入站。Xray 另核对第一个出站是 blackhole、每个入站恰有一条规则且指向存在的出站，mihomo
另核对 `rules` 是 `MATCH,REJECT`、每个 listener 的 `proxy` 指向存在的代理、没有全局的 `authentication` /
`skip-auth-prefixes`。认证上两端也要一致：接 Xray / mihomo 的 socks 出站与对应的入站都有凭据且相同、非空，Xray 入站的
`auth` 是 `"password"`、`accounts` 恰好一项、`udp` 仍为 true，mihomo listener 的 `users` 恰好一项；接插件核心的
socks 出站与入站都没有；同一份 sing-box 配置里本机凭据只有一组；运行 / 测速另核对跳实例记录的 `localAuth` 就是入站要求
的。导出模式没有 `external` 记录，外核配置从 `export.txt` 的各段里取，并核对 Xray、mihomo 各最多一段。

## result.json

```
{
  "status": "ok" | "error",
  "error": { "class", "message", "causes": [ { "class", "message" } ] },     仅 error
  "exportName": "…",                                                          仅 export 且 ok
  "build": { "mainEntId", "selectorGroupId", "profileTagMap": { "节点 id": tag },
             "trafficMap": { tag: [节点 id] }, "boxIndexNames": {}, "boxTagNames": {} },   仅 run / test 且 ok
  "external": [ { "file", "pluginId", "controller": { "port", "secret" } | null, "tempFiles": [],
                  "hops": [ { "index", "chainIndex", "profileId", "port", "finalAddress", "finalPort",
                              "inboundTag", "outboundTag",
                              "localAuth": { "username", "password" } | null } ] } ],    仅 run / test 且 ok，一组一项，按组的顺序
  "dynamic": { "ports": [], "paths": [], "secrets": [] }
}
```

- `error`：构建路径抛出的异常就是这个场景的旧行为，记异常类全名、`message`（不取本地化消息）与 cause 链。
- `build` 里的映射按键排序输出；`trafficMap` 的值保持原列表顺序。
- `external[]` 一组一项：`file` 是这组的配置原文，`hops` 是组里的跳实例（`index` 是计划内序号，`chainIndex` 是
  所在链在构建登记的全部链里的序号（没有外核节点的链也占一个），`port` 是本机 socks 端口，`finalAddress` /
  `finalPort` 记跳实例的拨号目标：经映射时是 `127.0.0.1` 与映射入站的端口；不映射时是节点的 `serverAddress` 与
  `serverPort`，其中 hysteria 1（免映射的最先拨号的一跳）实际按 `serverPorts` 拨号，`finalPort` 只是节点的
  `serverPort` 字段，不参与拨号）。
  `inboundTag` / `outboundTag` 是跳实例在 Xray 配置里的入站 / 出站 tag、在 mihomo 配置里的 listener / 代理名
  （`in-<index>` / `out-<index>`）；插件核心的配置沿用单节点格式，不带标识，记为 null。`localAuth` 是这个跳实例的
  外核入站要求的本机 socks 凭据（与 sing-box 里接它的 socks 出站带的是同一组），入站不认证的（插件核心）记为 null。
- `external[].controller` 是写进这份 mihomo 配置的测速控制端口与 secret（其余组为 null）；`tempFiles` 是组装期间经
  `cacheFile` 领到、且出现在这份外核配置里的文件（hysteria 1 的 CA），采集结束即删除。
- `dynamic` 列出本模式这一次构建里每次运行都可能不同的全部取值，比较工具只按它替换：
  - `ports`：`mkPort()` 分到的端口。先按 sing-box 配置的结构找——指向 127.0.0.1 的 socks 出站的
    `server_port`（按 outbounds 顺序），再是 tag 含 `-mapping-` 的映射入站的 `listen_port`（按 inbounds
    顺序）；run / test 再补上运行计划里各跳实例的本机端口与测速控制端口。两次采集同一位置一一对应。
  - `paths`：临时文件的绝对路径（run / test 来自 `tempFiles`，export 按 cacheDir 前缀从原文里找）。
  - `secrets`：测速时随机生成的 mihomo Clash API secret；本次构建随机生成的本机 socks 凭据（用户名、密码各一项）。
    run / test 取自构建结果与运行计划，export 按 sing-box 配置的结构找（指向 127.0.0.1 的 socks 出站的 `username` /
    `password`）。比较时按出现次序换成 `SECRET#n`。
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
分组 DNS、同核心多节点（`multi-` 前缀）。新增场景只往表里加；场景 id 入库后不要改名。目前 293 个场景
（279 个按单项特性分类的，加 14 个 `multi-` 场景）。

`multi-` 场景专门覆盖「一次构建里同一种外核（Xray 或 mihomo）有多个节点」，也就是 K0 把同核心节点合进一份配置、
一个进程时要改变的情形；在 K0 之前采集，记下每个节点各占一个进程、各有一份配置的旧输出。来路包括：选择器分组里
多个 Xray / mihomo 成员（分别选中内核、Xray、mihomo 成员）、路由规则指向多个外核节点、一条链里有多个同核心节点、
同一个外核节点出现在两条链里（都不是最先拨号的一跳；顺序相同与相反各一个）、选择器的前置与落地各是外核节点（落地在
每个成员的链里各出现一次）、选择器成员本身是链、外核节点与 Trojan-Go / Naive / Mieru 混在同一条链里（含选择器成员
是这类链）、分组前置是外核链、规则目标所在分组带外核前置。节点用各自的服务器域名与 SNI，便于在合并后的配置里分辨。

夹具（`GoldenFixtures.kt`）完全虚构且确定：地址只用 example.com / example.net / example.org 的子域与
192.0.2.0/24、198.51.100.0/24、203.0.113.0/24、2001:db8::/32；服务器端口都在 30000 以下（避开系统分配的
临时端口）；UUID、密码、REALITY / WireGuard 密钥、ECH 配置等由固定文本编码而来，证书是自签的虚构证书
（CN=golden.example.com）。节点、分组、规则都按显式 id 写入；每个场景开始前把构建会读到的设置全部显式写好
（含 Clash API secret 的固定假值）。采集入口在每种模式后核对设置表、每个场景结束时核对数据库，发现被构建
改写就让整次采集失败。

## K0 实测（measure）

K0（每种核心一个进程）动手前后的对照数据：用一个外核节点很多的选择器分组启动服务，记录外核进程数随时间的变化、
系统对 phantom process 的清理、应用重启外核的日志、内存与冷启动耗时。只测量，不改变任何运行行为；结果不进仓库。

```bash
export ANDROID_HOME=$HOME/Library/Android/sdk
ANDROID_SERIAL=emulator-5554 ./run golden measure --out <空目录>            # 默认：38 Xray + 2 mihomo + 2 sing-box，取样 120 秒
ANDROID_SERIAL=emulator-5554 ./run golden measure --xray 8 --out <空目录>   # 低于 32 个上限的对照
```

选项：`--xray` / `--mihomo` / `--singbox <N>`（各类节点个数，默认 38 / 2 / 2）、`--duration <秒>`（默认 120）、
`--interval <秒>`（取样间隔，默认 3）、`--mem-at <秒>`（第一次记内存，默认 30，结束前再记一次）、
`--mode vpn|proxy`（默认 vpn）、`--log-level <N>`（默认 1，见下）、`--settle <秒>`（拉起界面后等多久再启动，默认 5）、
`--out`、`--allow-wipe`、`--no-build`、`--no-install`（与采集相同）。`./run golden measure --help` 列出全部选项。
汇总要用 `python3`（只用标准库）。

### 脚本做了什么

1. 构建、按清单选 APK、安装、核对 `versionCode`（同采集）。
2. 只读地记下设备条件：系统版本与 API 级别、内存、CPU 数、`device_config get activity_manager max_phantom_processes`
   （`null` 为默认 32，全系统合计）、`settings get global settings_enable_monitor_phantom_procs`（`false` 时系统不杀）。
   VPN 模式下把本应用的 `ACTIVATE_VPN` appop 设为 `allow`（等同用户在授权框点了确定），结束时恢复原值。
3. `am force-stop` → `content call … --method measurePrepare`：防误伤检查，记下原有设置，写入夹具与实测设置 →
   再 `am force-stop`，清空应用日志 `cache/neko.log`：主进程与 `:bg` 都按实测设置重新起来（日志等级在进程启动时读）。
4. `am start -W` 拉起主界面，等 `--settle` 秒，记下启动前全系统进程数，开始在后台收 logcat（main / system / events /
   crash，`-v epoch`）。
5. `measureStart`：与界面启动按钮同一路径（`VpnRequestActivity.StartService`）——VPN 模式先确认
   `VpnService.prepare()` 为空（已授权），再 `SagerNet.startService()`，记下发出请求的时刻后立即返回。
   从后台启动前台服务在 Android 12 起受限，这里有两重保证：请求由主界面在前台的主进程发出（与用户点按钮时相同的
   进程状态），VPN 模式另有系统对持有 `ACTIVATE_VPN` 的应用的豁免。被拒时 `startService()` 返回空，入口报错。
6. 每 `--interval` 秒一次：同一条 `adb shell` 里取设备 uptime 与 `ps -A`，再调 `measureStatus` 取服务状态，直到
   `--duration`。到 `--mem-at` 秒与结束前各记一次内存：经 `run-as` 读主进程、`:bg` 与全部外核子进程的
   `/proc/<pid>/smaps_rollup`。取样结束后读一次 `dumpsys activity processes` 里系统登记的 phantom process。
7. `measureFinish`：发 `Action.CLOSE` 广播停止服务（与通知栏停止按钮同一路径），等到 `Stopped`（30 秒），再清空节点 /
   分组 / 规则三张表、按标记文件恢复设置。没停下来时不清夹具，脚本 `am force-stop` 后再调一次。脚本退出（含失败、
   Ctrl-C）时总会走这一步。之后再取一次进程表核对外核残留，取回 `neko.log`，汇总。

### 实测入口的约束

- 与采集入口同一个 provider（`GoldenCollectProvider` 的 `measure*` 方法，实现在 `GoldenMeasure.kt`），只编进 debug 包，
  只接受 adb shell 与 root 的调用；与采集互斥（同一时刻只跑一个 call）。
- 防误伤：数据库里有不是实测入口建的节点 / 分组 / 规则时拒绝，除非带 `--allow-wipe`；服务正在运行、或采集被打断留下了
  标记文件时也拒绝。夹具写入前建标记文件 `files/golden-measure.owned`（内容是原有设置表），清理完删除；中途被打断时，
  下次运行据它认出夹具并按它恢复设置。
- 设置：整表清空后只写实测需要的键——`serviceMode`、`logLevel`、选中的分组与节点、两个 DNS（`https://dns.example.net/dns-query`
  与 `https://192.0.2.53/dns-query`，连不上，不向真实服务器发查询）——外加进程启动时会写回的 `legacyAssetsMigrated`；
  其余取代码默认值。
- 日志等级默认 1（warn）：0 会让 `Logs` 整体关闭，看不到 `GuardedProcessPool` 的「was killed / restart process」。
  这也会让 Xray / mihomo 以 warning 级别输出日志，与等级 0 的用户略有不同。
- 夹具：一个选择器分组（id 1），成员依次是 VLESS + REALITY（`xtls-rprx-vision`，uTLS chrome，走 Xray）、AnyTLS（走
  mihomo）、sing-box 内核节点（Shadowsocks 与 VMess + WS + TLS 交替），选中第一个成员。服务器地址按顺序取
  192.0.2.0/24、198.51.100.0/24、203.0.113.0/24 的 IP 字面量，凭据沿用采集夹具的虚构值。`measurePrepare` 会用生产代码
  构建一次选中节点的配置，核对外核数量与参数一致，否则报错（并照常清理）。

### 结果目录

```
result.json      汇总（字段见下）
raw/             原始数据：device.txt、host.txt、prepare/start/finish(.txt|.json)、samples.txt、mem-*.txt、
                 phantom-table.txt、ps-before.txt、ps-after.txt、logcat.txt、neko.log、am-start.txt、top-activity.txt
```

`result.json`（格式 v1，时间 `tMs` 都是相对发出启动请求的毫秒数，负数为请求之前）：

| 字段 | 内容 |
| --- | --- |
| `args` | 本次参数 |
| `app` | 包名、APK、versionCode、提交与工作区改动数、Xray / mihomo 版本（取自 `plugins.sh`） |
| `device` | 系统版本、API 级别、build 类型、fingerprint、型号、ABI、`memTotalKb`、`cpus`；`maxPhantomProcesses` / `monitorPhantomProcs` 的原值与生效值；`processCountBeforeStart`（发出启动前 `ps -A` 的进程数）；`vpnAppopBefore`；`topActivityAtStart`（发出启动时前台的界面） |
| `fixture` | `measurePrepare` 的返回：各类成员数、选中节点、按生产代码构建出的外核跳实例分布 `externalIndex` 与按运行计划应起的进程数 `externalProcesses`（都按插件 id 计） |
| `start` | `connectedMs`：从发出启动到服务报 `Connected` 的毫秒数（主进程收到状态回调的时刻）；`transitions`：取样期间收到的全部状态变化（含消息）；没连上时 `failure` 给出状态与原因；请求本身失败时 `error` |
| `processes.xray` / `.mihomo` | `expected`（按运行计划应有的进程数，取自 `fixture.externalProcesses`；K0 起 Xray、mihomo 各 1 个）、`nodes`（节点数）、`max` 与 `maxAtMs`、`final`（最后一个样本）、`secondHalfMin` / `secondHalfMax`（后半段的范围）、`distinctPids`（取样期间出现过的不同 pid）、`pidsBeyondExpected`、`pidsNewAfterConnected`（连接后才出现的 pid，即重启出来的）、`pidsGoneBeforeEnd`。取样间隔内生灭的进程看不到，这些数只是下限 |
| `processes.bgPids` / `mainPids` | 取样期间出现过的 `:bg` / 主进程 pid（多于一个说明进程重启过） |
| `phantom` | 取样期间 logcat 里 ActivityManager 的 `Killing PhantomProcessRecord … : <原因>`：`killCount`、其中本应用外核的 `killCountOurCores`、按进程名与原因的计数、首末次时刻、`bursts`（2 秒内的算一批，带设备 epoch 秒与被杀 pid）与 `burstIntervalsMs`；`amKillEvents`（events 缓冲区的 `am_kill`）；`knownAtEnd` / `knownAtEndOurCores`（取样结束时系统登记的 phantom process 数）；`sampleLines`（含 phantom 字样的原文，前 12 条） |
| `appLogs` | `neko.log` 里 `GuardedProcessPool` 的日志：`counts`（`startProcess`、`killed`、`unexpectedExit`、`exitsTooFast`、`restartProcess`、`stopGuard`，各分 Xray / mihomo）、`samples`（每类前 5 条原文）、`events`（时刻按 Go 日志前缀算，只精确到秒） |
| `memory` | 两次内存快照（`t<秒>` 与 `end`）：`main`、`bg`、`xray`、`mihomo`、`externalCores`（含正在 fork 的子进程）、`total` 各给 `count`、`pids`、`pssKb`、`rssKb`、`swapPssKb`；`memAvailableKb`。PSS 已按共享页摊分，可直接相加；RSS 合计重复计入了共享的代码页，只作参考 |
| `final` | 取样结束时的服务状态、是否仍在运行、取样期间是否停过及停止消息（例如外核反复退出太快被守护放弃） |
| `stop` | 停止是否成功、停止前状态、`stopMs`、是否强行停止了应用、恢复的设置条数、三张表是否已清空、`leftoverProcesses`（停止 2 秒后残留的外核进程数） |
| `samples` | 每个样本：`tMs`、`systemProcesses`（全系统进程数）、`xray` / `mihomo`（pid 列表）、`mainPid`、`bgPid`、`forkingChildren`（fork 之后、exec 之前仍叫「包名:bg」的子进程，即正在启动的外核）、`orphanCores`（父进程不是 `:bg` 的外核进程数）、`state` |

### 读数时注意

- 系统并不在外核进程一超过上限就清理：只在内部事件触发 CPU 统计扫描时才登记新的 phantom process 并按上限清理
  （在 API 37 模拟器上观察到大约每 5 分钟一轮，一轮里可能连续扫描几次）。120 秒的窗口可能一次扫描都没碰上，这时
  `phantom.knownAtEnd` 为 0；要看清理周期，用 `--duration` 跨过至少一轮。
- 冷启动指：主进程与 `:bg` 是本次 `am force-stop` 后新起的，服务从未启动过，外核进程不存在；`:bg` 在拉起主界面时
  已由界面绑定服务而启动，不计入 `connectedMs`。
- `connectedMs` 含外核的启动前校验与启动就绪等待（`BoxInstance.init` / `launch`）。二者各自的耗时记在 `raw/neko.log`
  的 Info 行里：`<插件 id>: config of <N> hops checked in <毫秒> ms`（每组一行）与
  `external cores: <N> local inbounds ready in <毫秒> ms`；汇总不解析它们。K0b 起就绪等待对内置 Xray / mihomo 的每个入站做带用户名 / 密码的 SOCKS5
  握手（凭据取自运行计划）；Xray 配置关掉了访问日志（`log.access` 为 `none`），探测与正常连接都不再留 accepted 行。
