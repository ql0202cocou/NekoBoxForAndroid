# Kryo 兼容样本

节点、分组、订阅和备份都用同一套 Kryo 格式存储：每一层类先写自己的版本号（int），再调用
`super.serialize`，最后写自己的字段；读取时按版本号走分支（AGENTS.md「Code contracts」里 Kryo bean format 一条）。
本目录存放**历史提交的实现写出的真实字节**，`app/src/test/java/io/nekohasekai/sagernet/fmt/kryo/` 下的测试
用当前实现读取它们，逐字段核对。样本不是当前实现自己写出再读回的。

- `samples/<类名>.json`：每个类一份，`samples` 数组里每个样本记录：
  - `id`：`<类名>/<来源提交前 8 位>/<变体>`；
  - `class`：完整类名；`form`：`storage`（Room 列、备份里的格式）或 `share`（`ProxyGroup` 的分享链接格式，`export = true`）；
  - `source`：来源提交的完整哈希、所属版本 tag（该提交没有 tag 时是包含它的最早一个 tag；HEAD 尚未发版，为 `null`）、
    该提交 `nb4a.properties`（早期为 `sager.properties`）里的 `VERSION_NAME`；
  - `versions`：字节里各层写出的版本号（`AbstractBean` 指 `serialize` 之后的 extraVersion；嵌套的 bean / 订阅也列在内）；
  - `assigned`：生成时旧对象持有的全部字段值（`initializeDefaultValues` 之后再赋样例值；`default` 变体只做前者）；
  - `expected`：当前实现读出后**每个字段**应有的值，嵌套对象写成 `{"class", "fields"}`；
  - `base64`：字节；`current`：是否为 HEAD 写出的当前版本样本；
  - `knownIssue`（可选）：当前实现读不对的已知问题，见下文。
- `registry.json`：
  - `layers`：每一层的当前版本号（`current`）、有样本的版本（`sampled`）、没有样本的版本及原因（`unsampled`）；
  - `classes`：每个类从叶子到根依次写出的版本层；`skippedClasses`：不做的类及原因；
  - `sources`：每个来源提交的 tag、版本名、日期，以及为什么选它。

样例数据全部虚构：地址只用 example.com / example.net / example.org 的子域与 192.0.2.0/24、198.51.100.0/24、
203.0.113.0/24、2001:db8::/32；UUID、密码、密钥都是固定文本编出来的假值。

## 测试做什么

- `KryoCompatTest`
  - 当前实现经 `KryoConverters.deserialize`（分享链接、备份记录、Parcel 走的严格路径；Room 列转换器在它外面多一层出错兜底）
    读每个样本，所有字段与 `expected` 一致；
  - 读进来的对象用当前实现再写出：各层版本号都是 `registry.json` 登记的当前值，再读回来字段仍与 `expected` 一致；
  - `current` 样本读进来再写出，字节完全相同；
  - 样本里登记的 `versions` 与字节开头的版本号一致。
- `KryoCoverageTest`
  - 当前实现写出的每一层版本号与 `registry.json` 的 `current` 一致——升了版本号却没登记，在这里失败并指出层名；
  - 每层 0..current 的每个版本，要么有样本（且 `sampled` 与样本实际出现的版本一致），要么在 `unsampled` 里写明原因；
    当前版本必须有 `current` 样本；
  - `KryoConverters` 里每个返回具体 bean 的转换函数、加上 `ProxyEntity` / `ProxyGroup`，都已登记（或列入 `skippedClasses`），
    且各有旧实现写出的样本和当前版本样本。

## 期望值的来源

`expected` 由生成脚本按下面的规则逐字段写出，规则来自阅读 HEAD 的 `deserialize` / `initializeDefaultValues`，
没有运行当前实现：

1. 旧实现**写进字节**、且当前格式仍保存的字段：取旧对象的值，再按当前 `initializeDefaultValues` 规整
   （例如空白值补默认值、Trojan-Go 的 `none` 改为 `original`、ShadowTLS 的 `security` 恒为 `tls`）。
   哪些字段写进了字节，是用旧实现逐个扰动字段值、看字节是否变化得出的。
   「当前格式仍保存」指：StandardV2Ray 系按 `type` 只保留该传输方式的字段（ws：host / path / wsMaxEarlyData /
   earlyDataHeaderName；http、httpupgrade：host / path；grpc：path）、`security == "tls"` 时才保留 TLS 字段；
   SSH 按 `authType` 保留 password 或 privateKey / privateKeyPassphrase；Trojan-Go 只有 `ws` 保留 host / path。
2. 旧字节里没有的字段：该版本分支的迁移值，否则是当前默认值。迁移值：
   - Hysteria `version < 7`：`protocolVersion = 1`（`uploadMbps` / `downloadMbps` 的默认值随之为 10 / 50）；
   - Hysteria `version < 6`：`serverAddress` 末尾是多端口（含 `-` 或 `,`）时拆成地址与 `serverPorts`，否则
     `serverPorts = serverPort`；
   - Tuic `version < 2`：`protocolVersion = 4`；
   - 其余（SOCKS 的 `sUoT`、StandardV2Ray 的 ECH / mux / REALITY ML-DSA / 证书指纹、Subscription 的流量与到期、
     `nameserverFromSubscription`、ProxyEntity 的 `core`、ProxyGroup 的 nameserver / 选择器 / 前置落地等）取默认值。
3. 已经不存在的旧字段（StandardV2Ray v0–v2 的 `enablePqSignature`、`disabledDRS`）被读取分支跳过，不出现在 `expected`。

## 样本怎么来的

工具不进仓库（一次性），步骤如下，照做可以重新生成同样的字节：

1. **选提交。** 对每一层，用 `git log --follow` 找出写出版本号（`serialize` 里的 `writeInt(<字面量>)`）每次变化的提交 B，
   取 `B^`（该版本最后一次被写出的提交）。同一版本号内布局变过的也单独取（见覆盖表的说明）。另取最早的
   `9d78e4f2`（全部类）与 HEAD（全部类，当前版本样本）。所有提交都在一条首父链上（这些提交本身不是合并提交）。
2. **导出并编译那个提交的实现。** 用 `git show <提交>:<路径>` 导出：`fmt/` 与 `moe/matsuri/nb4a/proxy/` 下全部
   `*Bean.java`、`fmt/Serializable.kt`、`fmt/KryoConverters.java`、`ktx/Kryos.kt`、`database/SubscriptionBean.java`。
   序列化路径全部用原文件；只给与写出字节无关的依赖写最小的桩，凡是可能被序列化路径调用的桩一律抛异常，
   保证桩不会悄悄改变字节：
   - `JavaUtil`：只含从该提交 `JavaUtil.java` **原样抽出**的 `isNullOrBlank` / `isNotBlank` / `isEmpty`，`gson` 只用于 `toString`；
   - `NetsKt.wrapIPV6Host` / `unwrapIPV6Host`、`HysteriaFmtKt.isMultiPort`（只在显示地址与旧版本读取分支里用到）、
     `android.os.Parcel` 的方法：抛异常；`Logs` 出错时重新抛出；`android.os.Parcelable`、`androidx.annotation.NonNull`、
     `androidx.room.TypeConverter`：空声明；`GroupType`（历史上从未变过）、`applyDefaultValues`（与 `ktx/Formats.kt` 相同）；
   - `NekoBean` 换成序列化即抛异常的空壳，只为让 `KryoConverters` 编译；
   - `ProxyEntity` / `ProxyGroup` 是 Room 实体，依赖面太大：把该提交里 `serializeToBuffer` 方法**原样抽出**，放进一个字段声明
     相同的宿主类，`requireBean()` 直接返回样本 bean。
   编译用 Kotlin 2.4.20 编译器（`kotlin-compiler-embeddable`，Gradle 缓存里有）和 JDK 17 的 `javac --release 11`，
   依赖 kryo 5.6.2、gson、jetbrains annotations、kotlin-stdlib。
3. **写样本。** 一个只用反射的生成程序在该提交的类上运行：`new` 出对象，`initializeDefaultValues()`，再给每个字段赋上
   能互相区分的非默认值（该提交没有的字段跳过）；`default` 变体只做 `initializeDefaultValues()`。字节一律经该提交的
   `KryoConverters.serialize` 写出——即当时 Room 写 bean 列、备份 / Parcel 写实体走的同一个入口。随后逐个扰动字段值、
   重新序列化，记录哪些字段进入了字节；从 `serialize()` 写出部分的长度定位 extraVersion。
4. **生成 JSON。** 按「期望值的来源」写出 `expected`，并汇总 `registry.json`。JSON 缩进两格、末尾一个换行。

样例覆盖：StandardV2Ray 系每个来源提交都有 VMess 的 tcp / ws / http / grpc / quic（以及有 httpupgrade 之后的 httpupgrade）、
TLS 与非 TLS、ECH 开与关（有 ECH 之后）、VLESS、Trojan、HTTP、ShadowTLS 及各自的默认值样本；SSH 三种认证方式；
Mieru TCP / UDP；Trojan-Go ws / original；Hysteria 1 / 2 与 v5 的「多端口写在地址里」；订阅 lastUpdated / expiryDate
超过 int 的取值；实体里嵌 VMess / Shadowsocks / 链；分组的订阅型、基本型与分享格式。

**Kryo 版本：** 5526bf0（1.6.0 之前）以前 app 依赖的是 kryo 5.2.1，Gradle 缓存里没有，样本统一用 5.6.2 写出。
本格式只用到 `ByteBufferOutput` 的 `writeInt` / `writeLong` / `writeBoolean` / `writeString` / `writeVarInt` / `writeBytes`，
两个版本的这几种编码没有已知差异；但这一点没有用 5.2.1 实测核对。

## 覆盖表

「层」是写版本号的那一级；VMessBean、InternalBean 没有自己的版本号。来源提交见 `registry.json` 的 `sources`。

| 层 | 当前 | 有样本的版本（来源提交） | 没有样本的版本及原因 |
| --- | --- | --- | --- |
| AbstractBean（extraVersion） | 1 | v1（所有 bean 样本） | v0：早于本仓库历史；读取时不参与分支 |
| StandardV2RayBean | 6 | v0 布局 A `9d78e4f2`、`a82e5b4f`；v0 布局 B `8e976675`；v0 布局 C `680b362b`；v1 `607afa8c`；v2 `2c3a6164`；v3 布局 a `110f3b21`；v3 布局 b `aa275d5e`；v4 `04da8864`（另有 `bbbdf577` 实体内嵌）；v5 `329572d1`；v6 HEAD | — |
| TrojanBean | 2 | v2（每个 StandardV2Ray 来源提交、HEAD） | v0、v1：早于本仓库历史（读取分支是 StandardV2Ray 之前的旧布局） |
| HttpBean | 0 | v0（同上） | — |
| ShadowTLSBean | 0 | v0（同上） | — |
| ShadowsocksBean | 2 | v2 `9d78e4f2`、`bbbdf577`（实体内嵌）、HEAD | v0、v1：早于本仓库历史 |
| SOCKSBean | 2 | v1 `9d78e4f2`；v2 HEAD | v0：早于本仓库历史 |
| HysteriaBean | 8 | v5 `9d78e4f2`、`814025e9`（含多端口地址）；v6 `eab03deb`；v7 `329572d1`；v8 HEAD | v0–v4：早于本仓库历史 |
| MieruBean | 1 | v0 `19cb130f`（TCP 不写 mtu、UDP 写）；v1 HEAD | — |
| NaiveBean | 3 | v2 `9d78e4f2`；v3 HEAD | v0、v1：早于本仓库历史 |
| SSHBean | 0 | v0 `9d78e4f2`、HEAD | — |
| TrojanGoBean | 1 | v1 `9d78e4f2`、HEAD | v0：早于本仓库历史 |
| TuicBean | 4 | v1 `9d78e4f2`、`34a30127`；v2 `329572d1`；v4 HEAD | v0：早于本仓库历史；v3：历史上从未写出（d72b889 由 2 直接改为 4） |
| WireGuardBean | 3 | v2 `9d78e4f2`、`329572d1`；v3 HEAD | v0、v1：早于本仓库历史 |
| AnyTLSBean | 2 | v0 `5d74526b`；v1 `329572d1`；v2 HEAD | — |
| ChainBean | 1 | v1 `9d78e4f2`、`bbbdf577`（实体内嵌）、HEAD | v0：早于本仓库历史 |
| ConfigBean | 0 | v0 `9d78e4f2`、HEAD | — |
| NekoBean | 0 | 不做 | 字段初始化里有 `new JSONObject()`，mockable android.jar 的 org.json 在 JVM 上抛异常，构造不出来；自首个提交以来布局未变 |
| SubscriptionBean | 5 | v1 `9d78e4f2`、`3fac5afe`、`19cb130f`、`629cdae1`；v2 `9220b316`；v4 `3bd39b94`；v5 HEAD | v0：早于本仓库历史；v3：历史上从未写出（57f4507 由 2 直接改为 4） |
| SubscriptionBean（分享格式） | 0 | v0（每个分组分享格式样本） | — |
| ProxyEntity | 1 | v0 `9d78e4f2`、`bbbdf577`；v1 HEAD | — |
| ProxyGroup（存储格式） | 2 | v0 `9d78e4f2`、`3fac5afe`；v1 `19cb130f`；v2 `629cdae1`、`9220b316`、`3bd39b94`、HEAD | — |
| ProxyGroup（分享格式） | 2 | v0 `9d78e4f2`、`3fac5afe`、`19cb130f`、`629cdae1`；v1 `329572d1`；v2 HEAD | — |

「早于本仓库历史」：本仓库的 git 历史从 NekoBox 的首个提交 `7d9798c`（2023-03-15）开始，那时这一层已经写出更高的版本号；
这些低版本分支是为上游 SagerNet / Matsuri 的旧数据保留的，本仓库里没有写它们的实现，不伪造样本。

同一版本号内布局变过的地方：

- StandardV2Ray v0：布局 A（`7d9798c`–`e805fe7^`）没有 ECH 块、grpc 只写 path；布局 B（`e805fe7`–`fb55430^`）grpc 没有
  `break`，落入新加的 httpupgrade 分支，写 path、host、path；布局 C（`fb55430`–`b34c012^`）加了 ECH 块，版本号仍是 0。
  当前读取对 v0 用预读字节区分有无 ECH 块，A / B / C 的样本都覆盖了这一判断（含 VMess、Trojan、HTTP、ShadowTLS 四个子类）。
- StandardV2Ray v3：布局 a（`912a066`–`4326aab^`）grpc 仍落入 httpupgrade 分支；布局 b（`4326aab`–`b39ac9a^`）grpc 已加
  `break`，只写 path，版本号仍是 3。
- 其余带条件写出的字段（Mieru v0 的 mtu、SSH 的认证字段、Trojan-Go 的 ws 字段、StandardV2Ray v0–v2 的 ECH 子字段）
  都有取不同分支的样本。

## 已知问题（`knownIssue`）

下面三个样本当前实现读不对（字段错位，且不抛异常）。测试要求它们**仍然**读不对；修好后测试会失败，提示删掉标记、
把它们并入正常样本：

- `VMessBean/9d78e4f2/vmess-grpc-tls`、`VMessBean/a82e5b4f/vmess-grpc-tls`：v0 布局 A 的 grpc 只写 path，而
  `version < 4` 分支按布局 B 多读两个字符串。e805fe7（1.2.9）起读写同时改成落入 httpupgrade 分支，所以 ≤1.2.8 写出的
  grpc 节点（例如老备份）从那时起就读错了。
- `VMessBean/aa275d5e/vmess-grpc-tls-ech`：v3 布局 b 的 grpc 只写 path，同样被多读两个字符串；该布局只存在于
  `4326aab` 到 `b39ac9a` 之间，其间 `aa275d5e` 发过预览版 `pre-1.4.1-20251021-1`。

## 升版本号时怎么补样本

给某一层（例如 `TuicBean`）升版本号、改字段时：

1. **先**在改代码之前，用升级前的实现（当前 HEAD）导出这一层旧版本的样本：照「样本怎么来的」第 2–4 步，或在 JVM 单测里
   直接用当前实现写出（此时它就是「旧实现」），把字节、`assigned`、`source`（当前提交与版本）记下来；已有的 `current`
   样本就是升级前实现写出的，可以直接把它们的 `current` 改为 `false`，作为旧版本样本保留。
2. 再改代码：新版本号、新字段的写入，以及 `deserialize` 里旧版本的分支。
3. 把 `registry.json` 里这一层的 `current` 改为新版本号，把旧版本号加进 `sampled`。
4. 给保留下来的旧样本按新的读取约定改写 `expected`（新字段写成该分支给出的默认值或迁移值），逐字段写明，不要拿读出来的
   结果回填。
5. 用新实现导出新版本的 `current` 样本（全字段与默认值各一个）。

漏了第 1 或第 3 步，`KryoCoverageTest` 会失败并指出缺的是哪一层、哪个版本；漏了第 4 步，`KryoCompatTest` 会失败。
新增一个用这套格式存储的类时，在 `registry.json` 的 `classes` / `layers` 登记并补样本。
