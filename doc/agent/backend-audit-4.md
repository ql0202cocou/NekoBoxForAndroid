# 第四次后端完整审计（libcore + bg）

> **状态：已全部修复（2026-09-20）**。中严重度 2 项 + 低严重度 24 项全部处理，
> 修复要点见文末「修复记录」；观察项为有意的设计取舍，按审计结论不动。
> 验证：`app:compileOssDebugKotlin` + `app:testOssDebugUnitTest` +
> `app:lintOssRelease` 全绿；libcore `go vet` / `go test -checklinkname=0` /
> sing-box 受影响包测试 / `check_versions` 全绿（sing-box 补丁集随之升为
> `1.14.1-neko-2`，引用点已同步）。修复后经 `/simplify` 复审（复用 / 简化 /
> 效率 / 层次四角度），修复记录已按复审后的最终形态更正。

- 日期：2026-09-20，基于 main 工作区（cc83b64 附近）。
- 范围：libcore 全部（顶层 Go API、sing-box neko 补丁面、libneko/stun/ech/device/procfs）+ app 的 `bg/`（含 `bg/proto/`）与 `group/` 订阅更新。
- 方法：6 个独立审计块并行通读（每块含调用链交叉验证），中严重度发现由主会话逐条读代码复核。本轮与历史审计结论无关，全部重新核实。
- 总结论：**未发现高严重度问题**。流量泄漏面（protect 覆盖 DNS/ECH/URLTest/直连出站）未发现绕过路径；补丁集与 NEKO.md / PATCHES.md 记录一致。2 项中严重度 + 一批低严重度，如下。

## 中严重度（已复核确认）

### M1. Stopping 窗口内启动意图被静默丢弃 — `bg/BaseService.kt:491`

`onStartCommand` 在 `state != State.Stopped` 时直接 `return START_NOT_STICKY`。Stopping 状态从 `changeState(Stopping)`（354）持续到 `changeState(Stopped)`（377），中间 `killProcesses()` 要等 looper、box、插件进程池，窗口可达数百毫秒以上，且协程挂起期间主线程可投递 `onStartCommand`。用户在此时点 FAB（`state.canStop` 为 false → 走 start）意图被丢，UI 停在 Stopped。
代码注释（368-372）已自证：通知销毁前服务仍前台，故窗口内 startForegroundService 的配对成立、平台不杀进程——这是有意的「前台保持到最后」缓解，但**意图丢失本身未处理**。
验证依据：主会话复读 BaseService.kt:327-501。
修复建议：把窗口内到达的 start 记为 pending，在 `changeState(State.Stopped)` 之后重放（restart 路径已有同款机制可参照）。

### M2. `uniquifyNames` 对不可信订阅 O(n²)，可长时间卡死更新 — `group/RawUpdater.kt:110-126`

同名节点第 j 个要循环 j 次（`removeSuffix` + 重新分配字符串），且循环内无 `ensureActive()`，协程取消打不断。去重（131 行起）排在 uniquify（127 行）之后，不能先削减同名输入。恶意/异常订阅（32MB 上限内可含数十万同名节点）可让 :bg 的 UpdateTask 或主进程手动更新占住 CPU，期间持有分组 updating 标记与跨进程文件锁。
验证依据：主会话复读 RawUpdater.kt:95-159。
修复建议：改用「基础名 → 已用序号」计数表一次遍历分配后缀（O(n)）。

## 低严重度 · 建议修复

### bg 服务层

1. **`BoxInstance.close()` 异常路径半释放** — `bg/proto/BoxInstance.kt:136-148` + `bg/BaseService.kt:334-337`。`processes.close()` 未兜底（OOM 级异常），抛出时 `box.close()` 被跳过 → box 泄漏；三个调用方中仅 killProcesses 无 `runCatching`（destroyRunner:418、TestInstance:69 都有），异常直达 Main 未捕获 → :bg 崩溃。触发概率低、后果高。修复：与 destroyRunner 对齐包 `runCatching`。（已复核代码不对称属实）
2. **`SubscriptionUpdater` 重排异常可传播进 UI 协程** — `bg/SubscriptionUpdater.kt:47-66`：直连 `groupDao.subscriptions()`（不走 `guardedRead`）+ RemoteWorkManager binder 往返（`awaitSchedulingCompletion` 会恢复 ExecutionException），经 `GroupManager.iterator`（GroupManager.kt:39-41）传播到 `createGroup/updateGroup/deleteGroup` 调用方（如 GroupSettingsActivity）→ 崩溃，而分组编辑本身已落库。触发需 DB/binder 故障。修复：四个 listener override 内 `runCatching`，调度失败只记日志。（已复核传播链属实）
3. `GuardedProcessPool` looper 只 catch `IOException`（GuardedProcessPool.kt:118），池 Job 非 SupervisorJob（:139）：looper 体内 RuntimeException 会取消整池并崩 :bg。当前无 `onRestartCallback` 调用方，可达性低。
4. `TrafficLooper.loop()` 体无兜底 catch（TrafficLooper.kt:191-296）：`postNotificationSpeedUpdate`/DataStore 意外异常会逃逸出 appScope 协程。修复：循环体 `runCatching` + 日志。
5. `persistStats` 在 ACTION_SHUTDOWN 里主线程 `runBlocking` 无超时（BaseService.kt:424-431），DB 极端慢时有广播 ANR 风险。修复：包 `withTimeoutOrNull`。
6. `ServiceNotification.show()` 无 `destroyed` 检查（ServiceNotification.kt:198-226，`update()` 有）：当前时序安全，属脆弱点。修复：对齐 `update()`。
7. `SagerConnection.stateChanged` 用 `State.values()[state]` 无越界保护（SagerConnection.kt:73），跨进程版本漂移时 binder 线程 AIOOBE。修复：用 `runCatching` 兜底（同文件 `stateOrStopped` 已是该模式）。
8. `ProxyInstance.lastSelectorGroupId` 普通 var 跨线程读写（ProxyInstance.kt:17），同文件其他字段都有 `@Volatile`。
9. `GroupUpdater.forceResolve` 系统 DNS 回退 `InetAddress.getAllByName` 无超时不可取消（GroupUpdater.kt:80），整个更新挂住且持有跨进程文件锁。
10. `UpdateTask` 无 `NetworkType.CONNECTED` 约束（SubscriptionUpdater.kt:101-110），无网空跑。
11. `GroupInterfaceAdapter.showDialog` 协程取消时弹窗/observer 残留（GroupInterfaceAdapter.kt:29-51，有 activity destroy 兜底）；`onUpdateSuccess` 对超大 diff 全量 `joinToString` 进单对话框（:82-122），建议截断。

### bg/proto 与插件进程

12. `deleteCacheFiles` 可能在 Main 线程跑文件 I/O（BoxInstance.kt:137-139，`invokeOnCompletion` 在 Job 完成线程同步执行，guard looper 退出在 Main.immediate）。
13. TestInstance 取消落在 `launch()` 中途时，后写的插件配置文件残留（BoxInstance.kt:89-104 守卫与后续写非原子；进程与 box 不泄漏，仅 `cacheDir/tmpcfg` 残留）。修复：launch 末尾复查 `isClosed()`。
14. `ProxyInstance.close()` 自身不停 TrafficLooper，顺序契约全靠调用方自觉（ProxyInstance.kt:74-81，当前两个调用方都遵守；destroyRunner 路径靠 Go 侧 `lockIfOpen` 兜底）。建议在注释/接口上固化。

### libcore

15. 自定义 CA 加载与 HTTP 客户端无就绪门控：`assetsReady` 只有 `NewSingBoxInstance` 等（nb4a.go:124-140 vs box.go:83-85）；进程启动后立即发起的订阅更新（RawUpdater/AssetsActivity 经 `newHttpClient`）可能抢在 `updateRootCACerts` 完成前做 TLS。窗口毫秒级、可自愈。修复：HTTP 客户端路径同样等待，或 ca.pem 加载移回 InitCore 同步段。
16. sing-box 补丁 `route/route.go:170-174,304-308` 两处 `RLock/RUnlock` 未用 defer（第三处 533 用了）：tracker panic 时写锁永久饥饿。补丁内风格不一致，改 defer 即可。
17. sing-box 补丁 `DialParallelInterface`/`ListenSerialInterfacePacket` 入口不检查 `DoNotSelectInterface`（common/dialer/default.go:297,379）：当前唯一调用链 strategy 传 nil，无现实绕过；上游未来新增非 nil strategy 调用点会静默失效。建议入口加 `strategy = nil` 兜底。
18. sing-box 补丁 `StatsService()` 可能返回 typed-nil（boxapi/v2ray_stats_service.go:140-143 + v2ray_server.go:99）：当前唯一调用点恒 `Enabled: true` 不触发。建议 `if s.ss == nil { return nil }`。
19. neko_log 打开日志文件失败后静默丢日志：`f` 为 typed-nil 仍入 `writers`（libneko/neko_log/log.go:26,68,95），`SetupLog` 的 err 在 nb4a.go:118 被忽略。修复：err != nil 不入 writers + 记录返回值。
20. `echTransport.DialTLSContext` 的 `net.Dialer` 无独立超时，与同文件注释宣称的 "bounds mirror NewHttpClient" 不符（http_h3.go:39-52；实际有 waitCtx/reqCtx 兜底，仅注释夸大）。
21. `protect_server` `getOneFd` 在 `len(msgs) != 1` 分支不关闭已接收 fd（protect_server_linux.go:31-33；对端恒发 1 fd，实际不可达，纯防御）。
22. procfs 对 IPv4-mapped IPv6 源地址查 `/proc/net/tcp6`（procfs.go:110-114）→ 按应用分流对该连接失效；tun 入站出现 4-in-6 源地址的可达性存疑（推测）。建议 `Unmap()` 归一化。
23. `Sleep()`/`Wake()` 不检查 box state 直接 emit pause 事件（box.go:197-211），与兄弟方法的 `lockIfOpen` 不一致；Kotlin 侧时序下未确认可触发（推测）。
24. `TrySocks5` 直连回退不走 protect（http.go:137）：VPN 运行且本地 socks 不可达时，"direct" 流量被自身 tun 捕获经代理出站；触发条件苛刻，行为与上游一致。建议注释写明。

## 观察项（记录，暂不建议动）

- `TrafficLooper.stopLoop()` 主线程 `cancelAndJoin` 可被卡死的 Go 调用阻塞（BaseService.kt:333），是先停 loop 再关 box 的已知权衡。
- `connectingJob` 在 Main.immediate 上跑阻塞性 gomobile 调用（BaseService.kt:541-547），上游继承的架构现状。
- DNS fallback 补丁不覆盖「NOERROR + 空应答」（dns/router.go:799-800）：NEKO.md 如实记录的设计边界。
- `NewSingBoxInstance` 等 `assetsReady` 无超时（assets_lock.go:26 的 F_SETLKW 理论上可无限阻塞；对端仅自身进程，持锁极短）。
- geoCache 失效判定靠 path/size/mtime 三元组（geo_cache.go:55-56），同秒同尺寸替换会沿用旧缓存，纯理论。
- `Executable.killAll` 读 exe 名到 kill 之间的 PID 复用竞态（Executable.kt:20-42），窗口极小。
- stun test2 `fakeFullCone` 判定为「IP 或端口相同」（stun/discover.go:116-119），RFC 5780 下同端口不同 IP 的合法服务端会误报警告行，不影响 NAT 类型判定。
- `GuardedProcessPool.processCount` 只增不减，命名易误导（语义为「是否起过插件」）。
- ECH 密钥经非认证系统 DNS 抓取（ech/ech.go:67），中间人可剥离降级——ECH + 非 DoT/DoH 的固有限制。

## 已审计确认无问题的关键面（摘要）

- box.go 锁序（`b.access → mainInstanceAccess`）、`lockIfOpen` 关闭门控、double Start/Close 均有测试 pinning；http_h3 race 逻辑闭环；dns_box 恰好一次结算；protect 客户端/服务端 5s 预算、fail-closed；certs.go linkname 对照 Go 1.26/1.27 源码核实。
- DoNotSelectInterface 在 init 写一次后只读；DNS、ECH、URLTest、直连出站拨号均在 protect 覆盖内（除上述低项 17/24 的潜在入口）。
- sing-box DNS fallback 状态机无死循环、无共享可变状态，6 个回归测试覆盖；NEKO.md 与 diff 逐条一致。
- destroyRunner 双路径幂等、tun fd 竞争处理、广播接收器 signature 权限、FGS 类型合规（targetSdk 36）、GuardedProcessPool 取消窗口全覆盖、TestInstance 端口/文件防碰撞、订阅解析容错与声明行为一致（SafeConstructor、32MB 上限、坏节点跳过、全灭时 `error("Not found")` 防清空分组）。
- SubscriptionUpdater 永不反注册属有意且无害；WorkManager 唯一任务名 + UPDATE 策略幂等。

## 下一步

无遗留修复项。若未来要做：观察项里的 `TrafficLooper.stopLoop` 超时收敛、
`connectingJob` 移出 Main.immediate 属架构级改动，需单独评估。

## 修复记录（2026-09-20，与发现编号对应）

- **M1**：`BaseService.Data` 新增主线程 confined 的 `pendingStart`，是停止期间
  启动意图的唯一载体：`stopRunner(restart)` 入口先把 `restart` 记进去再做
  Stopping 判重（已在 Stopping 时只留意图），`onStartCommand` 与 `reload()` 在
  Stopping 时同样置位，尾部 `changeState(Stopped)` 后读取并清零、重放
  `startRunner()`。无防御性清零（不变式保证进入 Stopped 前已消费）。
- **M2**：`uniquifyNames` 改原地改名（返回 Unit）：`HashSet` 记已占用名字 +
  `HashMap<基础名, 下一序号>`，一次遍历 O(n)，语义与原版逐项对拍一致（含节点
  本名带 " (1)" 的情形）；未采纳「先去重再 uniquify」（会改变 duplicate 报告
  语义，超出最小修复）。
- **低 1**：`BoxInstance.close()` 契约改为「从不抛出」：`processes.close()` 与
  `box.close()` 各自 runCatching，前者失败则直接补 `deleteCacheFiles()`；
  `killProcesses` / `destroyRunner` 因此不再各包一层 runCatching（TestInstance
  的取消回调保留其 runCatching，那是取消回调自身的契约）。
- **低 2**：兜底上提到 `GroupManager.iterator`：逐 listener try/catch（CE 重抛，
  Exception 记日志），与 `DefaultNetworkListener.notifyListener` 同一策略，
  覆盖全部 5 个 listener；`SubscriptionUpdater` 不再需要 `reconfigureSafely`。
- **低 3**：`GuardedProcessPool` looper 外层 catch 从 IOException 放宽到
  Exception（CE 先重抛），非 IO 异常包成 IOException 走既有 `onFatal →
  stopRunner`。未用「逐迭代吞掉继续守护」——`start()` 失败后 continue 会让
  guard 永远挂在 `exitChannel.receive()`；池 Job 保持 `Job()`（SupervisorJob
  只影响兄弟取消，挡不住逃逸异常崩溃 :bg）。
- **低 4**：`TrafficLooper.loop` 循环体抽成局部 `loopOnce()`，`while` 里
  try/catch 一次（CE 重抛，Exception 记日志）后统一 `delay`，body 不再各处
  重复 delay。
- **低 5**：`persistStats` 包 `withTimeoutOrNull(3000)`。
- **低 6/7/8/14**：`destroyed` 检查统一进 `useBuilder`（同一把 buildLock），
  `show()`/`update()` 不再各查一遍；字段声明提到 `init` 之前——主线程无协程
  上下文时构造（onStartCommand 的三条错误路径）`runOnMainDispatcher` 会同步
  执行 init 里的 `show()`，原先「在 show() 里查 destroyed」会在未初始化字段上
  NPE。ordinal 解码收敛为 `State.fromOrdinal`（`stateChanged`、
  `stateOrStopped`、MainActivity 三处共用）；`lastSelectorGroupId` 加
  @Volatile；`ProxyInstance.close()` 中文注释固化顺序契约。
- **低 9**：改 `lookupBlocking(domain, resolve)`：appScope 上异步 + 10s
  `withTimeoutOrNull`，超时 `cancel()` 掉还没开跑的 lookup；系统解析器回退与
  FakeDNS 的 `underlyingNetwork.getAllByName`（同样不可取消的阻塞 JNI）两条
  路径共用（`cancel()` 触发的 lint MemberExtensionConflict 按 destroyRunner
  先例 @Suppress）。
- **低 10**：UpdateTask 加 `NetworkType.CONNECTED` 约束。
- **低 11**：`showDialog` 主线程分发前 `isCancelled` 守卫，`invokeOnCancellation`
  在 `show()` 之后注册（已取消则立即回调），不再需要捕获可空 dialog 变量；
  `onUpdateSuccess` 名单先 `take(50)` 再格式化，超出部分用字符串资源
  `group_diff_more`（en / zh-rCN / zh-rTW / zh-rHK）折成一行。
- **低 12/13**：`GuardedProcessPool.close(scope, onClosed)` 在 guard 全部退出后
  于 scope 的调度器上执行回调，`BoxInstance.close` 传 `::deleteCacheFiles`，
  线程由 scope 一处决定（原「invokeOnCompletion 在 Main 触发」的推理不成立：
  返回的 Job 本就在 IO 上完成）；`launch()` 末尾补查 `isClosed()` 清理残留
  插件配置。
- **低 15**：`waitAssetsReady()` 抽成共用函数，`NewHttpClient` 与
  `NewSingBoxInstance` 都走它。
- **低 16**：`route.go` 两处 RLock 改闭包内 defer（临界区不变）。
- **低 17**：`DialParallelInterface`/`ListenSerialInterfacePacket` 入口在
  `DoNotSelectInterface` 时直接回落普通拨号（而非置 nil strategy——会被
  `d.networkStrategy` 回读击穿）。
- **低 18**：`StatsService()` nil 检查返回 nil interface。
- **低 19**：neko_log 仅 `f != nil` 才入 writers；`InitCore` 记录 SetupLog 错误。
- **低 20**：`newHTTPDialer()` 供 `NewHttpClient` 与 echTransport 两处共用，
  拨号超时由构造保证一致。
- **低 21**：`getOneFd` 先收齐全部控制消息的 SCM_RIGHTS fd 再做唯一一次数量
  校验，不对则 `closeFds` 全部关闭（合并原 `len(msgs) != 1` / `len(fds) != 1`
  两分支）。
- **低 22**：procfs 源地址 `Unmap()` 归一化后再判断 Is6。
- **低 23**：`Sleep()`/`Wake()` 走 `lockIfOpen`。
- **低 24**：TrySocks5 直连回退加中文注释说明语义（行为不变）。
- vendored 记录：sing-box 补丁集升 `1.14.1-neko-2`（NEKO.md、version.go、
  check_versions.sh、patches 文件名、根 AGENTS.md 已同步）；libneko PATCHES.md
  新增 2 条（提交时补哈希）；procfs 文件头分叉记录新增第 4 条。
