# 待处理问题与代码质量改进

本文件记录本次仓库审计发现的 bug、结构改进建议和死代码清理项。条目状态以以下审计基线为准；本次单独提交文档，不代表已对最新远程代码重新审计。后续修复或代码变更需要重新核对相应条目。

- 审计日期：2026-10-03。
- 审计仓库：`ql0202cocou/NekoBoxForAndroid`。
- 审计基线：`e7e2dbdd1ad3f940d78222eeadd2661aa6b5e205`，版本 1.7.8-a1。
- 审计范围：重点检查配置生成、协议转换、订阅更新、服务生命周期、数据访问、备份恢复、DNS 和流量统计；没有逐行审计全部 sing-box/gomobile 上游代码。
- 验证方式：静态代码路径追踪、引用检索，以及首轮 6 项局部算法、补查 4 项数据场景的本地复现。Python 模型用源码断言检查依据；补查的流量回写执行原 DAO 的 SQL，其余模型不等同于直接执行 Kotlin/Go、Android Parcel 或 Room。
- 验证限制：环境缺少 Android SDK、libcore.aar、Kotlin 编译器及可用的 Go 开发工具链。本次未运行 Gradle 构建、项目单元测试、真实 DNS 网络测试或 Android 实机测试。

优先级：P1 为应优先处理的服务可用性问题；P2 为特定场景下的正确性问题及相关结构改进；P3 为维护、清理和构建优化建议。`BUG` 条目记录具体缺陷，`QUALITY` 条目记录改进建议，不将所有设计取舍都视为已发生的故障。

## 验证状态汇总

优先级与验证状态分开管理。局部模型能说明算法或写入条件的后果；涉及 Android 生命周期、JNI 和两进程交错的条目，还需要在原实现中验证触发过程。

| 条目 | 本次已经完成的验证 | 仍需执行的验证 |
| --- | --- | --- |
| BUG-001 | Kotlin 配置构造到 Go 内核报错的路径追踪 | 实际内核创建、坏成员隔离和正常节点启动 |
| BUG-002–007 | 6 项局部算法/条件复现，加源码断言核对 | 执行原 Kotlin/Go 实现；DNS、内核流量与拖拽另需集成验证 |
| BUG-008 | 部分解码的分支追踪及 SQLite 孤儿记录模型 | 用实际 Android Parcel 构造损坏备份，执行 Room 恢复和列表展示 |
| BUG-009 | 停止与最终落库的路径追踪，执行原 DAO SQL 说明覆盖结果 | 在运行服务下控制恢复/最终落库顺序，验证停止完成的同步方案 |
| BUG-010 | Activity 生命周期、进程级协程和 Fragment 提交路径追踪 | 延迟初始化后退出、旋转、进入后台和打开新编辑器 |
| BUG-011/012 | 字段保留及过期响应合并的模型复现 | 真实保存和受控订阅响应的并发测试，验证显示与自动更新调度 |
| QUALITY-005/006 | 源码/测试/资源引用检索，检查框架与反射入口 | 分批删除后的 debug/release 编译与必要的协议运行验证 |
| 其余 QUALITY 条目 | 静态职责、依赖、工作流和数据契约检查 | 实施后对应的行为、构建或包体收益验证 |

没有任何条目被标记为“Android 实机验证通过”或“本次 Gradle/Go 项目测试通过”。下面的待验证项单独计数，不加入当前 12 个 BUG、10 个 QUALITY 的统计。

## 问题索引

| 编号 | 优先级 | 问题 | 状态 |
| --- | --- | --- | --- |
| BUG-001 | P1 | 未选中的坏选择器成员仍可能阻断整组启动 | 待修复 |
| BUG-002 | P2 | 嵌套代理链反转子链的跳转顺序 | 待修复 |
| BUG-003 | P2 | 正则表达式转小写改变匹配语义 | 待修复 |
| BUG-004 | P2 | 自定义 DNS 查询和地址重写没有完整遵循 IPv6 模式 | 待修复 |
| BUG-005 | P2 | 合法的无认证 HTTP 代理订阅被误拒 | 待修复 |
| BUG-006 | P2 | 链编辑器的数据交换与 RecyclerView 移动通知不一致 | 待修复 |
| BUG-007 | P2 | 多条链共用节点时，流量统计关联被覆盖 | 待修复 |
| BUG-008 | P2 | 跳过损坏分组后仍恢复其节点，产生孤儿记录 | 待修复 |
| BUG-009 | P2 | 备份恢复未等待旧服务停止，流量可能被旧值覆盖 | 待修复 |
| BUG-010 | P2 | 分组和路由编辑器初始化任务在页面销毁后继续提交界面 | 待修复 |
| BUG-011 | P2 | 修改订阅链接未清理旧订阅的流量和到期信息 | 待修复 |
| BUG-012 | P2 | 旧订阅下载在链接或分组类型改变后仍提交结果 | 待修复 |
| QUALITY-001 | P2 | 配置生成混合数据读取、运行时映射和界面副作用 | 待改进 |
| QUALITY-002 | P2 | 数据访问入口及线程契约分散 | 待改进 |
| QUALITY-003 | P2 | 持久化配置、统计状态和运行时数据混用可变对象 | 待改进 |
| QUALITY-004 | P3 | 协议类型与转换入口依赖多处手工同步 | 待改进 |
| QUALITY-005 | P3 | 未使用的 runOnLifecycleDispatcher 扩展函数 | 待清理 |
| QUALITY-006 | P3 | 20 个配置模型类未发现使用路径 | 待清理、编译验证 |
| QUALITY-007 | P3 | 全包 R8 保留规则阻止未使用代码清理 | 待优化 |
| QUALITY-008 | P2 | 已发现的关键逻辑缺少对应回归测试 | 待补充 |
| QUALITY-009 | P2 | 编辑器共享缓存的归属检查与读写不是完整契约 | 待改进 |
| QUALITY-010 | P2 | 备份导出缺少一致快照和可恢复的文件选择交接 | 待改进 |

## Bug

### BUG-001：未选中的坏选择器成员仍可能阻断整组启动

- 优先级：P1。
- 状态：待修复。
- 主要位置：[ConfigBuilder.kt](app/src/main/java/io/nekohasekai/sagernet/fmt/ConfigBuilder.kt#L759)，`precheck()`，尤其 L765–768；同文件 `requireBuildableHop()` 和 `buildInternalOutbound()` 位于 L721、L737。
- 验证状态：通过 Kotlin 配置构造到 Go 内核创建的代码路径确认；尚未进行整组启动实测。

内部节点的预检只检查部分限制并构造 Java 配置对象，没有执行 sing-box 的实际参数校验。配置对象能生成，不代表其协议参数可以被内核接受。

例如，Clash 导入的 Shadowsocks 节点使用 `cipher: not-a-cipher` 时，`clashCipher()` 会原样保留，地址和端口合法即可通过端点校验。[ShadowsocksFmt.kt](app/src/main/java/io/nekohasekai/sagernet/fmt/shadowsocks/ShadowsocksFmt.kt#L139) 的 L145 只复制 `bean.method`，因此预检仍然成功。真正的加密方法创建发生在 [Shadowsocks outbound.go](libcore/sing-box/protocol/shadowsocks/outbound.go#L43)，错误经 [sing-box/box.go](libcore/sing-box/box.go#L375) 返回，导致整个核心创建失败。

触发场景：选择器分组中选中一个正常节点，同时存在一个参数非法的未选中内部节点。正常节点也无法启动连接。路由规则的额外节点同样使用这条预检路径。

建议：建立统一的协议参数校验入口，覆盖加密方法、密钥、传输配置等；为自定义出站提供无需启动网络服务的内核校验路径。校验结果应携带节点标识和原因，构造失败时也应保持构建状态一致。

验收：选中正常节点、同组未选中节点包含非法 cipher 时，坏成员被跳过并提示，正常节点可以启动；直接选中坏节点时明确报错。对非法密钥和传输参数补充相同的回归案例。

### BUG-002：嵌套代理链反转子链的跳转顺序

- 优先级：P2。
- 状态：待修复。
- 主要位置：[ConfigBuilder.kt](app/src/main/java/io/nekohasekai/sagernet/fmt/ConfigBuilder.kt#L185)，`resolveChainInternal()`，L206–208。
- 验证状态：已完成最小算法复现；未进行真实代理链连接测试。

递归函数先取得子链的倒序结果，再拼入父链并对整体调用 `asReversed()`。子链内部的成员因此再次被反转。链编辑器允许无环的嵌套链，见 [ChainSettingsActivity.kt](app/src/main/java/io/nekohasekai/sagernet/ui/profile/ChainSettingsActivity.kt#L247)，这是可触发的正常输入。

复现输入：内链 `[A, B]`，外链 `[内链, C]`。

- 预期流量路径：`A → B → C`；构建器的倒序列表应为 `[C, B, A]`。
- 实际倒序列表：`[C, A, B]`，对应流量路径 `B → A → C`。

影响：使用错误的前置顺序，或者在子链依赖指定第一跳时连接失败。

建议：统一递归展开的顺序约定。可以先按用户顺序完整展开，在最外层反转一次；也可以倒序遍历当前链成员并直接拼接已经倒序的子链。

验收：单层链、两层及多层嵌套链均保持用户指定路径；同时验证前置/落地链、缺失成员和循环引用的既有处理。

### BUG-003：正则表达式转小写改变匹配语义

- 优先级：P2。
- 状态：待修复。
- 主要位置：[SingBoxOptionsUtil.kt](app/src/main/java/moe/matsuri/nb4a/SingBoxOptionsUtil.kt#L48)，`DomainRuleLists` 的 `regexp:` 分支。
- 验证状态：已完成匹配语义的最小算法复现；未执行 sing-box 原生规则测试。

对整个正则调用 `lowercase()` 会改写正则语法。`\D` 与 `\d`、`\W` 与 `\w` 含义相反；Unicode 属性表达式也可能被改坏。该转换同时用于路由和 DNS 规则。

复现输入：`regexp:^\D+\.example\.com$`。

- 预期：匹配 `abc.example.com`，不匹配 `123.example.com`。
- 实际：转换为 `^\d+\.example\.com$`，上述匹配结果相反。

影响：直连、代理或阻断规则命中错误。

建议：保留正则原文；需要忽略字母大小写时使用明确的正则标志。普通域名字段可以继续归一化。

验收：通过规则转换后的表达式保持原语义；覆盖 `\D`、`\W`、Unicode 属性及已有普通域名规则，并同时验证路由与 DNS 两条调用路径。

### BUG-004：自定义 DNS 查询和地址重写没有完整遵循 IPv6 模式

- 优先级：P2。
- 状态：待修复。
- 主要位置：[lookup.go](libcore/lookup.go#L98)，`lookupHost()`；[GroupUpdater.kt](app/src/main/java/io/nekohasekai/sagernet/group/GroupUpdater.kt#L96)，`rewriteAddress()`。
- 验证状态：已完成 A/AAAA 查询分支的最小算法复现；未进行真实 DNS 网络测试。

`lookupHost()` 先查询 A，只在 A 没有结果时查询 AAAA。双栈域名有 A 记录时，Kotlin 收到的列表只有 IPv4；`forceResolve()` 设置为“优先 IPv6”后，也无法选择从未返回的 IPv6 地址。

`rewriteAddress()` 只接收 `ipv6First` 布尔值，没有按 DISABLE/ONLY 模式过滤地址族。“仅 IPv6”下可能写回 IPv4；禁用 IPv6 时也可能写回只有 AAAA 的结果。地址已经改成字面 IP 后，后续域名解析策略不能修正这次选择。

复现条件：分组启用强制解析、配置节点 DNS、IPv6 模式为 PREFER，节点域名同时具有 A 和 AAAA。实际只查询 A 并写回 IPv4。

建议：向解析层传递完整地址族策略，或按模式取得需要的记录后过滤、排序。严格模式没有合适地址时保留域名并报告解析失败。

验收：覆盖 DISABLE、ENABLE、PREFER、ONLY 四种模式和单栈/双栈结果；自定义 DNS 与系统 DNS 路径保持一致，同时验证超时、取消和服务器回退。

### BUG-005：合法的无认证 HTTP 代理订阅被误拒

- 优先级：P2。
- 状态：待修复。
- 主要位置：[RawUpdater.kt](app/src/main/java/io/nekohasekai/sagernet/group/RawUpdater.kt#L85)。
- 验证状态：已完成拒绝条件的最小算法复现；未执行 Android 完整订阅更新流程。

更新器拒绝所有“非 YAML、全部为 HttpBean、用户名为空”的解析结果。这项针对到期提示 URL 的保护，也覆盖合法的无认证 HTTP 代理，没有利用显式端口、备注或原始文本形态进行区分。

复现输入：订阅只含 `http://192.0.2.1:8080#Office`，或该内容的 Base64 编码。它可通过 [HttpFmt.kt](app/src/main/java/io/nekohasekai/sagernet/fmt/http/HttpFmt.kt#L10) 的节点解析，但更新器随后报“没有找到节点”。同样的节点放入 Clash YAML 则不受此条件影响。

建议：在分享链接提取阶段区分整行节点链接与网页/说明文本中的 URL，将显式端口、节点备注等作为判断信息。保留到期提示页保护，避免仅凭无认证拒绝一整类代理。

验收：合法无认证 HTTP 节点的明文和 Base64 订阅可更新；提示文字、到期网页及只有普通网站 URL 的响应不替换已有节点。

### BUG-006：链编辑器的数据交换与 RecyclerView 移动通知不一致

- 优先级：P2。
- 状态：待修复。
- 主要位置：[ChainSettingsActivity.kt](app/src/main/java/io/nekohasekai/sagernet/ui/profile/ChainSettingsActivity.kt#L189)，`ProxiesAdapter.move()`。
- 验证状态：已完成列表变更的最小算法复现；尚未在实机上验证跨行拖拽动画。

实现交换两个位置的元素，却调用 `notifyItemMoved(from, to)`。该通知的契约是取出原元素、插入目标位置并顺移中间元素。相邻移动结果相同，非相邻移动结果不同。

复现：`[A, B, C]` 从第 1 行移到第 3 行。

- RecyclerView 移动通知对应 `[B, C, A]`。
- 实际数据和编辑缓存得到 `[C, B, A]`。

影响：界面显示的链顺序与保存的链顺序可能不同。

建议：以 `removeAt(from - 1)` 和 `add(to - 1, item)` 执行真正的移动，使数据修改、缓存更新和界面通知一致。

验收：覆盖上下两个方向的相邻与跨多行移动，检查即时显示、缓存、旋转恢复及最终保存顺序。

### BUG-007：多条链共用节点时，流量统计关联被覆盖

- 优先级：P2。
- 状态：待修复。
- 主要位置：[TrafficLooper.kt](app/src/main/java/io/nekohasekai/sagernet/bg/proto/TrafficLooper.kt#L216)，L226 和 L236；[TrafficUpdater.kt](app/src/main/java/io/nekohasekai/sagernet/bg/proto/TrafficUpdater.kt#L57)。
- 验证状态：已完成映射覆盖的最小算法复现；未进行真实流量统计测试。

多个 `trafficMap` 条目可以包含同一个节点，例如主链与路由目标链共用前置节点。初始化每次新建统计对象并写入 `idMap[ent.id]`，覆盖该节点先前的 tag 关联。统计器随后只取得 `idMap.values`，共用节点只累计最后保留的一个 tag 的流量。HashMap 遍历顺序还会影响保留哪条关联。

复现：链 1 和链 2 共用节点 A，分别有 100 和 200 字节流量。A 应累计 300，当前算法只得到 100 或 200。主要影响共用节点的累计值，各链实体仍可保留各自记录。

建议：分开管理“统计 tag 对应的节点集合”和“节点累计值”。每个 tag 的增量查询一次，再分发给关联节点；同一 tag/节点关联去重。

验收：共用前置、落地及链成员的流量正确累计，结果不依赖 map 遍历顺序；验证选择器切换、统计清零及最终持久化不会重计或回写旧值。

### BUG-008：跳过损坏分组后仍恢复其节点，产生孤儿记录

- 优先级：P2。
- 状态：待修复。
- 主要位置：[BackupRestore.kt](app/src/main/java/io/nekohasekai/sagernet/database/BackupRestore.kt#L57)，`decode()`，尤其 L78–95；`commit()` 的整表替换在 L133–138。
- 验证状态：通过解码与入库路径确认，使用 SQLite 模型复现孤儿记录；未执行 Android Parcel 损坏输入测试。

每个备份数组独立跳过无法解码的记录，只要非空数组还剩至少一条有效记录就允许继续。节点与分组解码完后没有校验 `profile.groupId` 是否存在于保留下来的分组集合。`commit()` 会把这些节点和剩余分组原样写入数据库；[ProxyEntity.kt](app/src/main/java/io/nekohasekai/sagernet/database/ProxyEntity.kt#L76) 也没有分组外键约束。

复现条件：备份有分组 10、20，各含一个有效节点；分组 20 的编码损坏，分组 10 有效。分组数组没有全损坏，因此通过校验；分组 20 的节点仍被插入，但其所属分组没有恢复。[GroupPagerAdapter.kt](app/src/main/java/io/nekohasekai/sagernet/ui/GroupPagerAdapter.kt#L32) 根据现有分组创建分页，这个节点无法从正常分组列表中访问。导入前的本地配置还会被整表替换。

建议：在任何写入之前验证恢复数据的引用完整性。明确选择中止导入、将相关节点一并跳过并准确计数，或放入一个可见的恢复分组；同样检查代理链、前置/落地节点和规则目标的关联，按所选导入范围决定修复策略。

验收：部分分组记录损坏时，没有节点被静默写入不存在的分组；告知实际受影响的记录。完全有效备份、空备份节和仅导入部分内容保持既有行为。

### BUG-009：备份恢复未等待旧服务停止，流量可能被旧值覆盖

- 优先级：P2。
- 状态：待修复。
- 主要位置：[BackupFragment.kt](app/src/main/java/io/nekohasekai/sagernet/ui/BackupFragment.kt#L208)，停止广播与立即执行 `finishImport()`；[TrafficLooper.kt](app/src/main/java/io/nekohasekai/sagernet/bg/proto/TrafficLooper.kt#L84)，`flushStats()`。
- 验证状态：追踪异步停止和最终持久化路径，并执行原 DAO SQL 复现覆盖结果；未测量 Android 两进程竞态的发生频率。

`SagerNet.stopService()` 只是发送 CLOSE 广播，见 [SagerNet.kt](app/src/main/java/io/nekohasekai/sagernet/SagerNet.kt#L352)。导入随即在另一协程中替换节点表，没有等待服务进入 Stopped。旧服务关闭 box、等待插件退出之后还会调用 `postFinalTraffic()`，见 [BaseService.kt](app/src/main/java/io/nekohasekai/sagernet/bg/BaseService.kt#L353)。这一写入可能晚于恢复事务。

备份保留节点 ID，而流量更新只按 ID 匹配，[ProxyEntity.kt](app/src/main/java/io/nekohasekai/sagernet/database/ProxyEntity.kt#L291) 使用 `UPDATE proxy_entities SET tx = :tx, rx = :rx WHERE id = :id`。例如恢复后 ID 1 的流量为 `(10, 20)`，旧服务最后写回 `(700, 900)`，恢复值被覆盖。如果两个安装的备份复用了相同 ID，旧流量还可能被计入另一个节点。这里只确认流量列受影响，没有据此声称协议配置被覆盖。

建议：恢复前等待可确认的服务停止及最终持久化完成，再允许整表替换；必要时为批量恢复引入写入代次，拒绝旧服务和旧测试任务对新数据的迟到写入。导入结束后的完整重启不能替代恢复前的同步。

验收：运行服务且启用流量统计时导入包含相同 ID 的备份，人为延迟内核/插件关闭，恢复后的流量始终保持备份值；停止失败或超时应在修改数据前报告。

### BUG-010：分组和路由编辑器初始化任务在页面销毁后继续提交界面

- 优先级：P2。
- 状态：待修复。
- 主要位置：[GroupSettingsActivity.kt](app/src/main/java/io/nekohasekai/sagernet/ui/GroupSettingsActivity.kt#L241)，初始化及 L269–274 的 token 更新、Fragment 提交；[RouteSettingsActivity.kt](app/src/main/java/io/nekohasekai/sagernet/ui/RouteSettingsActivity.kt#L210)，相同模式，L233–238。
- 验证状态：静态生命周期与协程路径确认；尚未在 Android 上执行延迟初始化、旋转或快速退出测试。

这两个编辑器通过 `runOnDefaultDispatcher` 读库、写共享缓存，再切回主线程提交 Fragment。该函数在 [Asyncs.kt](app/src/main/java/io/nekohasekai/sagernet/ktx/Asyncs.kt#L10) 使用进程级 `appScope`，不会随 Activity 销毁取消。初始化结束前退出、旋转或页面状态被保存后，旧任务仍能继续执行普通 `commit()`；对已销毁或已保存状态的 FragmentManager 提交会抛 `IllegalStateException`，这条任务没有异常处理。

另一个后果是旧任务继续写 `EditorCache` 并无条件更新会话 token。如果退出后已打开新编辑器，旧任务可能污染新页面缓存。现有 token 检查只在 `onCreate()` 调用，不能阻止迟到初始化。

建议：将页面初始化绑定到 `lifecycleScope`，数据库读取后、写缓存和提交界面前检查任务仍有效及会话归属。把初始化结果一次性发布，避免把半初始化缓存当成有效编辑状态；不要仅改成允许状态丢失的 Fragment 提交来掩盖问题。

验收：延迟数据库读取后快速退出、旋转、进入后台及打开另一编辑器，不发生过期 Fragment 提交，不污染新编辑器；正常重建仍保留未保存修改和文件/节点选择结果。

### BUG-011：修改订阅链接未清理旧订阅的流量和到期信息

- 优先级：P2。
- 状态：待修复。
- 主要位置：[GroupSettingsActivity.kt](app/src/main/java/io/nekohasekai/sagernet/ui/GroupSettingsActivity.kt#L293)，`saveAndExit()`；同文件 `ProxyGroup.serialize()` 位于 L76。
- 验证状态：通过保存、序列化及显示路径确认，完成字段变更模型复现；未执行界面显示测试。

订阅链接或分组类型变化时，只清空 `subscriptionUserinfo` 并把 `lastUpdated` 置零，没有清理 `bytesUsed`、`bytesRemaining`、`expiryDate`。`serialize()` 在原来的 SubscriptionBean 上改写链接，`GroupManager.updateGroup(..., preserveSubscriptionRuntime = false)` 也不会自动清空这些字段。

[GroupFragment.kt](app/src/main/java/io/nekohasekai/sagernet/ui/GroupFragment.kt#L480) 直接读取这些数值显示流量和到期时间，没有要求 `subscriptionUserinfo` 非空，也没有限制分组类型。因此链接 A 改成 B 后、B 首次更新成功之前，仍显示 A 的额度和到期日；转为普通分组时也可能保留这段显示。

建议：更换订阅身份时统一清理来源于该订阅的全部运行状态；普通分组明确移除或隐藏订阅元数据。相同链接下仅修改设置时，继续保留新鲜的运行状态。

验收：切换链接、远程链接切到本地文件、订阅组转普通组后立即检查显示；新链接更新失败时，也不显示旧订阅的额度或到期日。

### BUG-012：旧订阅下载在链接或分组类型改变后仍提交结果

- 优先级：P2。
- 状态：待修复。
- 主要位置：[RawUpdater.kt](app/src/main/java/io/nekohasekai/sagernet/group/RawUpdater.kt#L28)，请求所用的订阅快照；事务入口 L251–257、元数据合并 L296–318。
- 验证状态：追踪更新锁、编辑保存和事务路径，完成元数据合并模型复现；未进行真实下载与编辑并发测试。

更新器从开始时的 SubscriptionBean 下载，提交事务只检查分组 ID 仍然存在，没有检查当前分组仍为订阅类型、链接仍与此次下载一致。后面重新读取当前分组虽然保留了用户的新链接，却把旧响应的 `lastUpdated`、流量、到期信息和可能的远端名字/DNS 合并到新状态。节点的插入、修改、删除也已在同一事务中按旧响应执行。

触发场景：后台开始下载链接 A；用户把同一分组改成链接 B 并保存；A 的下载随后成功。数据库链接为 B，节点和元数据却来自 A。A 的完成时间覆盖修改链接时清零的 `lastUpdated`，[SubscriptionUpdater.kt](app/src/main/java/io/nekohasekai/sagernet/bg/SubscriptionUpdater.kt#L153) 按它计算是否到期，B 的首次自动更新可能被推迟。把分组改成普通类型时，旧下载也仍能改动节点。

`GroupUpdater` 的文件锁只互斥同组的更新任务，编辑器保存没有获取这把锁，因此它没有排除上述场景。分组卡片在同进程 `GroupUpdater.updating` 中有该 ID 时会隐藏编辑按钮，但后台任务在 `:bg` 运行，这个内存集合没有跨进程同步；已经打开的编辑器也没有保存前的更新状态检查。这与 BUG-011 不同：即使保存时已清空旧元数据，迟到的更新仍会把它写回来。

建议：事务开始时比较当前订阅身份或配置修订号，过期响应应整体放弃，不能先改节点再判断。界面明确提示设置变化使本次更新作废，并按新链接重新安排更新。订阅相关 DNS/解析设置变更也应有明确的失效策略。

验收：用可控响应延迟测试 A 下载期间切换到 B、转普通组、删除后重建分组；过期结果不能修改节点、名字、DNS、元数据或更新时刻，B 能按新设置更新。

## 结构与代码质量改进

### QUALITY-001：配置生成混合数据读取、运行时映射和界面副作用

- 优先级：P2。
- 状态：待改进。
- 位置：[ConfigBuilder.kt](app/src/main/java/io/nekohasekai/sagernet/fmt/ConfigBuilder.kt#L173)，尤其数据库读取 L183、构建编排 L339、映射处理 L667、Toast 提示 L860。
- 依据：静态职责与依赖分析；不将文件长度本身视为缺陷。

同一构建流程直接读取数据库和全局设置、展开链、查询插件、分配端口、修改 Bean 的映射字段、生成配置并显示 Toast。测试需要 Android 和全局环境，构建输入也不容易固定。构造成功与内核校验之间的缺口见 BUG-001。

建议：先读取一致的构建输入快照；分离链展开、校验、配置生成和运行时资源准备。生成结果携带诊断信息，由调用方决定提示和启动。固定输入下的链与规则转换应能脱离 Android 独立测试。

验收：配置生成阶段不直接显示界面提示；主要算法可用明确输入测试，插件启动与运行时端口映射具有独立职责。

### QUALITY-002：数据访问入口及线程契约分散

- 优先级：P2。
- 状态：待改进。
- 位置：[ProfileRepository.kt](app/src/main/java/io/nekohasekai/sagernet/database/ProfileRepository.kt#L10)、[GroupRepository.kt](app/src/main/java/io/nekohasekai/sagernet/database/GroupRepository.kt#L5)、[SagerDatabase.kt](app/src/main/java/io/nekohasekai/sagernet/database/SagerDatabase.kt#L79)、[ProfileSettingsActivity.kt](app/src/main/java/io/nekohasekai/sagernet/ui/profile/ProfileSettingsActivity.kt#L91)。
- 依据：Repository 多为转发，界面仍直接读取 DAO，线程切换由调用方负责；数据库启用 `allowMainThreadQueries()`。未据此断言已经发生 ANR。

事务、线程和更新通知的约定分散在界面、Manager、Repository 与 DAO 调用处，修改操作容易遗漏共同约束。现有薄封装没有提供完整的数据访问边界。

建议：逐步统一 UI 的数据读写入口，明确哪些操作是异步、哪些操作拥有事务、谁负责发送通知。保留必要的底层访问，先迁移耗时查询和批量写入，再评估取消主线程查询许可。

验收：新增界面数据操作遵循统一线程契约；关键事务与通知规则有明确归属，主要数据逻辑可以替换依赖进行测试。

### QUALITY-003：持久化配置、统计状态和运行时数据混用可变对象

- 优先级：P2。
- 状态：待改进。
- 位置：[ProxyEntity.kt](app/src/main/java/io/nekohasekai/sagernet/database/ProxyEntity.kt#L79)、[AbstractBean.java](app/src/main/java/io/nekohasekai/sagernet/fmt/AbstractBean.java#L28)、[RawUpdater.kt](app/src/main/java/io/nekohasekai/sagernet/group/RawUpdater.kt#L259)。
- 依据：持久化实体同时包含协议配置、排序、流量和测试状态；Bean 又承载运行时 `finalAddress/finalPort`。订阅更新需重新读取并手动合并字段，防止旧快照覆盖新状态。

现有部分字段更新和事务合并已经提供保护；结构上的问题是多个调用者仍共享大范围的可变对象，字段归属依赖调用者记忆。BUG-007 同样说明统计关联与持久化节点需要更清晰的模型。

建议：优先分离运行时映射、测试结果和统计对象，使协议配置作为明确的输入快照使用；保留按字段更新和事务约束。无需先大幅修改数据库表结构或序列化格式。

验收：编辑、订阅更新、测试和流量落库只修改其负责的字段；运行时端口映射不改写共享配置对象。

### QUALITY-004：协议类型与转换入口依赖多处手工同步

- 优先级：P3。
- 状态：待改进。
- 位置：[ProxyEntity.kt](app/src/main/java/io/nekohasekai/sagernet/database/ProxyEntity.kt#L79)、[ProtocolHandlers.kt](app/src/main/java/io/nekohasekai/sagernet/fmt/ProtocolHandlers.kt#L92)、[SingBoxOptions.java](app/src/main/java/moe/matsuri/nb4a/SingBoxOptions.java)。
- 依据：类型编号、多个可空 Bean 字段、反序列化、取 Bean、写 Bean、配置构建与显示映射需要同步维护。

新增协议或升级内核时，遗漏某个转换入口的风险较高；`type` 与可空 Bean 的一致性需要运行时检查。大型配置模型还混有旧内核格式，见 QUALITY-006。

建议：在保持已有编号与序列化兼容的前提下，收拢协议注册信息，并增加转换完整性检查或契约测试。按当前内核和应用实际需要维护配置模型。

验收：每个受支持协议在导入、实体转换、导出和构建路径都有明确实现与验证；缺失映射可在构建检查或测试阶段被发现。

### QUALITY-005：未使用的 runOnLifecycleDispatcher 扩展函数

- 优先级：P3。
- 状态：待清理。
- 位置：[Asyncs.kt](app/src/main/java/io/nekohasekai/sagernet/ktx/Asyncs.kt#L22)。
- 验证状态：应用源码、测试和资源中只有声明引用；普通扩展函数，未发现反射调用路径。

建议：删除 `Fragment.runOnLifecycleDispatcher()`，同步移除因此不再需要的 `Fragment`、`lifecycleScope` 导入，然后编译验证。

验收：该函数和失效导入移除，调用方编译及既有协程行为不受影响。

### QUALITY-006：20 个配置模型类未发现使用路径

- 优先级：P3。
- 状态：待清理、编译验证。
- 位置：[SingBoxOptions.java](app/src/main/java/moe/matsuri/nb4a/SingBoxOptions.java)。
- 验证状态：下列类名在应用源码、测试、XML 和 ProGuard 文件中仅出现于声明；未发现按类名反射加载或 Gson 动态选择这些类型的路径。属于强死代码候选，尚未删除并编译确认。

| 类名 | 基线声明行 |
| --- | --- |
| `OnDemandOptions` | 977 |
| `V2RayHTTPUpgradeOptions` | 2516 |
| `Inbound_RedirectOptions` | 2912 |
| `Inbound_TProxyOptions` | 2949 |
| `Inbound_SocksOptions` | 3031 |
| `Inbound_HTTPOptions` | 3070 |
| `Inbound_ShadowsocksOptions` | 3156 |
| `Inbound_VMessOptions` | 3203 |
| `Inbound_TrojanOptions` | 3246 |
| `Inbound_NaiveOptions` | 3293 |
| `Inbound_HysteriaOptions` | 3336 |
| `Inbound_ShadowTLSOptions` | 3395 |
| `Inbound_VLESSOptions` | 3444 |
| `Inbound_TUICOptions` | 3487 |
| `Inbound_Hysteria2Options` | 3536 |
| `Outbound_DirectOptions` | 3587 |
| `Outbound_WireGuardOptions` | 3896 |
| `Outbound_TorOptions` | 4111 |
| `Outbound_ShadowsocksROptions` | 4259 |
| `Outbound_URLTestOptions` | 4495 |

建议：按类逐项删除并编译验证，进一步检查因此失去引用的辅助类型。如果这些模型由生成器维护，应调整生成范围，避免后续再次引入。对应协议仍可能使用其他模型或普通 JSON，这份清单只针对这些 Java 类。

验收：清理后的 debug/release 构建通过，现有协议配置输出保持兼容；必要的框架、Gson 和 JNI 类型保留。

### QUALITY-007：全包 R8 保留规则阻止未使用代码清理

- 优先级：P3。
- 状态：待优化。
- 位置：[proguard-rules.pro](app/proguard-rules.pro#L4)，L4–5。
- 验证状态：根据现有 `-keep class ... { *; }` 规则确认；未测量 APK 体积变化。

两个应用包被全量保留，R8 无法正常删除其中未使用的类与成员，包括 QUALITY-005/006 的候选代码。这增加发布包冗余，并降低代码压缩的收益。

建议：按 Android 组件、Room、Gson 字段和 gomobile/JNI 的实际需要逐步缩小保留范围。每步检查 release 构建及实际运行；不要一次删除所有保留规则。

验收：release 构建正常，序列化、反射、JNI 和组件启动保持正确；检查 R8 的移除结果并测量实际包体变化。

### QUALITY-008：已发现的关键逻辑缺少对应回归测试

- 优先级：P2。
- 状态：待补充。
- 位置：`app/src/test/`、`libcore/*_test.go` 及相关业务模块。
- 验证状态：审计基线中应用单元测试文件 5 个、自有 libcore 测试文件 18 个；未发现覆盖 BUG-001–012 所述场景的应用测试。文件数不代表覆盖率，本次未运行现有测试。

建议：将 BUG-001–012 的具体场景转成执行原 Kotlin/Go 实现的回归测试。优先覆盖链展开顺序、正则语义、选择器坏成员隔离、IPv6 地址族策略、订阅格式差异、移动通知契约和多链统计聚合；补查问题增加 Parcel 部分损坏、Room 恢复与迟到写入、Activity 重建和订阅响应过期测试。测试固定输入及预期行为，不复制被测实现作为判定依据。

现有 [build-apk.yml](.github/workflows/build-apk.yml#L99) 已执行 `app:testOssDebugUnitTest` 与 `app:lintOssRelease`，[libcore.yml](.github/workflows/libcore.yml#L55) 已有 Go 测试及部分包的 `-race`；因此建议复用这些检查。当前工作流入口是手动预览/发布和可复用调用，仓库内没有 `pull_request` 检查入口。增加独立的 PR 检查流程，在所需工具链和 libcore 产物就绪后运行适用检查；签名、发布作业继续由发布流程负责。GitHub 是否已配置分支保护不在本次本地审计范围内。

验收：关键算法的测试可重复运行；CI 执行适用的 Kotlin/Go 测试。JNI、内核创建和拖拽显示另补集成或实机验证，并明确环境依赖。

### QUALITY-009：编辑器共享缓存的归属检查与读写不是完整契约

- 优先级：P2。
- 状态：待改进。
- 位置：[EditorActivity.kt](app/src/main/java/io/nekohasekai/sagernet/ui/EditorActivity.kt#L60)，`beginEditorSession()`；[EditorSession.kt](app/src/main/java/io/nekohasekai/sagernet/database/EditorSession.kt#L22)，claim/check/renew；[ProfileSettingsActivity.kt](app/src/main/java/io/nekohasekai/sagernet/ui/profile/ProfileSettingsActivity.kt#L180)，保存路径。
- 依据：token 检查发生在 `onCreate()`，`ownsEditorSession` 之后不重新计算；保存、偏好写入与异步选择结果没有统一的归属校验。这里记录契约缺口，不将未经实测的多窗口写错配置另列为确定 bug。

共享缓存包含编辑对象 ID、协议字段、分组和规则状态。检查 token 与读取/写入多个字段彼此分离，`renewEditorSession()` 也无条件写 token。页面局部的生命周期修复能解决 BUG-010，但无法自动保证所有迟到回调和保存操作都遵循同一归属约定。

建议：以每个编辑会话独立的状态对象或 ViewModel 承载草稿；需要继续共用存储时，把归属检查、快照读取和写入约束收拢到同一入口。保存目标从当前会话的稳定 ID 取得，避免依赖随时能被改写的全局 `editingId`。

验收：缓存已被新会话接管后，旧会话的初始化、选择回调、保存和删除全部被拒绝；当前会话重建及子编辑器返回保持正常。验证并发交错，而不只验证 token 比较函数。

### QUALITY-010：备份导出缺少一致快照和可恢复的文件选择交接

- 优先级：P2。
- 状态：待改进。
- 位置：[BackupFragment.kt](app/src/main/java/io/nekohasekai/sagernet/ui/BackupFragment.kt#L116)，`doBackup()` 的多表读取；同文件 L37–43、L66–69 的内存内容与文件选择回调；[Utils.kt](app/src/main/java/io/nekohasekai/sagernet/ktx/Utils.kt#L228)，空内容保护。
- 依据：导出顺序独立读取 profiles/groups/rules，没有共享的 SagerDatabase 读取事务；待写内容只存在 Fragment 实例字段。未执行跨进程快照或系统文件选择器恢复测试。

多张关联表的导出应来自同一数据库快照；后台更新或其他任务写入时，独立查询可能混合不同时间点的数据。settings 又来自独立数据库，需要明确允许怎样的一致性，不能只把所有查询放入某一库的事务就认为问题完全解决。

文件选择器前台期间 Fragment 重建会丢失 `content`。当前 `writeToDocument()` 正确拒绝空内容并提示错误，所以没有“把已有文件截成零字节并误报成功”的缺陷；仍有用户已经选择位置却无法完成这次导出的可恢复性缺口。

建议：在一个读取事务中生成关联配置的不可变快照，明确独立 settings 的快照边界；以私有临时文件和可恢复标识承接文件选择结果，成功、取消及过期时清理。恢复后写入最初选择的快照，避免临时重做备份而悄悄改变内容。

验收：导出期间执行订阅更新/数据变更，关联数据仍一致；选择文件期间旋转、进程重建后完成原导出，空内容保护继续有效，临时备份不会无限累积。

## 待验证项

### CANDIDATE-001：手动恢复与启动重放是否缺少共同互斥

- 状态：待验证，尚未列为确定 bug。
- 位置：[BackupFragment.kt](app/src/main/java/io/nekohasekai/sagernet/ui/BackupFragment.kt#L251)，`finishImport()`；[RestoreJournal.kt](app/src/main/java/io/nekohasekai/sagernet/database/RestoreJournal.kt#L54)，`withLock()` 和 `completePending()`。
- 已观察到的事实：启动重放持有跨进程文件锁；手动导入直接执行 `stage()` 和 `commit()`，没有获取相同锁。`BackupRestore.commit()` 的补偿快照也在写入事务之前独立读取。
- 待验证的后果：主进程导入已暂存备份时，`:bg` 启动可能重放同一备份，两个流程能否在提交、日志清理或失败补偿阶段相互干扰。重放相同内容本来具有幂等性，仅凭重复执行不能认定数据已损坏。
- 验证方案：给暂存、第一库提交、第二库提交和日志清理设置可控暂停点；在 `:bg` 启动重放时交错执行，再注入第二次提交失败/进程退出。检查两库最终内容、补偿基线、日志是否仍可重放，以及锁是否正确释放。
- 如确认缺陷：将手动恢复与启动重放纳入同一个跨进程操作边界，并明确与订阅/统计写入的协调；区别于 BUG-009 的停止完成问题。

### CANDIDATE-002：测试取消后初始化抛错是否留下临时资源

- 状态：待验证，尚未列为确定 bug。
- 位置：[TestInstance.kt](app/src/main/java/io/nekohasekai/sagernet/bg/proto/TestInstance.kt#L67)，取消回调和 L84–89 的初始化后清理；[BoxInstance.kt](app/src/main/java/io/nekohasekai/sagernet/bg/proto/BoxInstance.kt#L76)，`init()`、`closeAfterLateInit()` 和 `close()`。
- 已观察到的事实：取消回调可以先把 `closed` 置为 true 并清理资源；`closeAfterLateInit()` 只在 `init()` 正常返回后检查。若初始化继续创建临时文件，然后 `loadConfig()` 抛错，后面的检查被跳过，`use` 再调用 `close()` 也会因 CAS 判重而返回。进程池的异步清理可能覆盖部分时序，因此需要控制它是否已经结束。
- 待验证的后果：最后一次清理完成之后新增的临时 CA/配置文件是否残留。尚未确认存在插件进程或 native box 泄漏，不能把这些后果一并归入此项。
- 验证方案：暂停初始化，取消测试并等待进程池清理回调结束，恢复初始化创建文件，再让核心加载失败；断言临时目录和资源句柄已清理。覆盖正常初始化、加载失败、取消后加载成功及取消后加载失败四种情况。
- 如确认缺陷：使初始化失败及取消的全部出口都清理迟到资源，清理保留幂等性；补执行原实现的回归测试。

## 后续审计与验证范围

| 范围 | 当前覆盖情况 | 建议补查内容 |
| --- | --- | --- |
| 数据库迁移和备份兼容 | 已检查当前恢复流程，未遍历全部历史版本 | 历史 Room schema、Kryo 版本与协议类型编号；升级、降级和部分导入后的关联完整性 |
| 服务与资源生命周期 | 已追踪部分停止、流量和测试路径 | 快速启停、插件异常、系统销毁、网络切换及初始化失败后的资源释放 |
| Android 系统交互 | 主要为源码检查 | 实际文件提供方、URI 授权、进程重建、文件选择回调和后台任务行为 |
| 协议与内核兼容 | 重点检查应用配置转换及相关内核校验 | 协议输入/导出/构建样例矩阵，外置核心配置和必要的真实连接验证；上游内核全量审计另行确定范围 |
| 构建与代码清理 | 已检查工作流、引用及 R8 规则 | 在完整工具链运行检查、删除候选代码后的 release 验证和包体测量 |
| 用户本地文档 | 当前工作区没有仓库根目录的 `doc`/`docs`，未读取其内容 | 文档同步到工作区后再核对设计约定、协议约束及兼容性承诺 |

以上表示尚待覆盖的范围，不表示这些范围已经发现故障。

## 验证命令与材料交付

在工具链及构建依赖就绪后，从仓库根目录执行已有检查：

```bash
./gradlew app:testOssDebugUnitTest app:lintOssRelease
./run lib check_versions
./run lib test
```

Android 检查需要仓库匹配的 SDK、JDK、Gradle、`app/libs/libcore.aar` 和相关插件产物，准备方式参照 [build-apk.yml](.github/workflows/build-apk.yml#L99)。Go 检查需要匹配 `libcore/go.mod` 的工具链与依赖；涉及 Geo 测试时按 [libcore.yml](.github/workflows/libcore.yml#L55) 准备资产及 `NEKO_TEST_GEOIP_DB`、`NEKO_TEST_GEOSITE_DB`。这些是待执行命令，本次未运行它们；现有测试通过也不能替代尚未编写的回归用例。

对本地修改的 sing-box 包，在 `libcore/sing-box` 目录执行现有竞态检查：

```bash
go test -race ./dns/... ./route/... ./common/dialer/...
```

本次 Python 复现材料位于会话工作区的 `scratch/neko-review/reproduce.py` 和 `reproduce_more.py`，未包含在 Git 仓库中。问题输入、预期/实际行为及源码依据已记录在各条目里；若需要交付可直接运行的审计材料，应一并保存脚本并调整仓库路径。不要把这些模型作为原实现回归测试的替代品。

## 推荐处理顺序与记录约定

1. 优先修复 BUG-001，并建立构造与内核校验之间的共同契约。
2. 修复 BUG-002/003 的链顺序和规则语义问题。
3. 优先处理补查的 BUG-008–010/012，收紧恢复、页面任务和迟到响应的数据边界；处理 BUG-004–007/011，并补对应回归测试。
4. 逐步推进 QUALITY-001–004/009–010 的职责、数据及会话边界调整。
5. 清理 QUALITY-005/006，再按 QUALITY-007 优化 R8 保留范围。

各条目修复后应补充修复提交、执行的验证及结果，并更新状态。本文件的行号对应审计基线；后续修改时以函数或类名重新定位。

进入修复阶段时，每个条目补充以下字段；当前负责人均未分派，不能仅因修改了代码就标为已关闭：

```text
负责人：未分派
修复提交 / PR：待填写
回归用例及测试层级：待填写
执行环境 / 版本：待填写
实际执行命令及结果：待填写
兼容性与剩余限制：待填写
状态：待修复 / 修复中 / 待验证 / 已关闭
```

关闭条件：相关故障场景在原实现中验证修复，适用检查通过，必要的协议/备份兼容性验证完成。待验证项升级为 BUG 前，应补充触发条件、代码路径及验证结果；被否定时保留简短理由。

## 代码质量提升的实施建议

以小批次修复和可验证的职责调整推进。每个改动明确它消除的故障路径、负责的数据和验证结果；大范围重构之前，先用现有行为测试固定序列化兼容性和关键配置输出。

| 顺序 | 建议实施内容 | 验收依据 |
| --- | --- | --- |
| 1 | 将已发现的故障场景变成执行原实现的回归测试，随 BUG 修复提交；优先选择器参数校验、链展开、正则语义和恢复/订阅的迟到写入 | 修复前能重现错误，修复后通过；竞态测试控制交错时序，避免依赖偶发的等待时间 |
| 2 | 给页面初始化使用生命周期作用域，给订阅结果、服务统计和编辑草稿设置明确的有效期；提交前在相应事务或状态边界检查身份/代次 | 退出、旋转、换订阅链接和恢复数据后，旧任务无法提交旧状态，正常回调仍有效 |
| 3 | 收拢数据写入入口，明确配置、排序、流量、测试状态分别由谁更新；关联表读取采用一致快照，跨进程操作使用共同协调机制 | 界面编辑、订阅更新和统计落库交错执行时，各自不覆盖其他流程负责的字段；数据库事务不能被进程内锁替代 |
| 4 | 将配置构建改为读取输入快照后执行确定的转换；逐步抽出链展开、规则转换、协议校验，返回配置与诊断；由调用方执行提示和资源准备 | 核心算法无需真实 Activity、数据库和插件进程即可测试；相同输入产生相同输出，错误携带具体节点和原因 |
| 5 | 用每个编辑会话的独立草稿状态/ViewModel 管理对象 ID 与偏好；协议映射收拢到明确的类型描述与能力声明 | 编辑对象 ID 不随全局缓存变化；各协议支持或不支持的导入、导出、构建路径可检查，现有类型编号和 Kryo/备份兼容性保持不变 |
| 6 | 按 QUALITY-005/006 小批次删除无引用代码，再按实际反射、Gson、Room 和 JNI 使用收窄 R8 规则 | debug/release 构建及必要的运行验证通过；检查 R8 移除结果，实际测量包体变化后再宣称收益 |
| 贯穿以上各步 | 复用已有单测、lint、Go 测试与竞态检查，增加 PR 自动检查入口；静态分析规则先针对未使用代码、异常处理和协程误用逐步引入 | 新增回归用例进入 CI；检查结果和必要的运行验证可供审查，检查的基线不能掩盖新增问题 |

具体边界应优先落在现有文件和接口上：Repository 要提供实际的线程、事务和通知约定；页面任务与进程任务使用不同的作用域；配置 Bean 不承担可变运行时映射和统计职责。后续是否拆 Gradle 模块，应由可测试性、依赖隔离和构建收益决定。本次审计没有证明全面模块化或一次性改写整个协议模型是必要条件。

审计没有仅凭“搜不到普通调用”将 Android 生命周期方法、Room 转换器、AIDL 实现或 JNI 回调列为死代码。单个 `app` 模块本身没有被认定为结构错误；现有证据也不足以证明模块依赖存在循环或要求全面重写。
