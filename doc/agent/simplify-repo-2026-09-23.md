# 全仓 /simplify 复审 — 2026-09-23

来源：分支 `refactor/simplify-repo`（基于 `8620bd2`），对自有代码（app、libcore 顶层、
buildSrc、buildScript，不含 vendored 目录与 `SingBoxOptions.java`）做复用 / 简化 /
效率 / 修复深度四个角度的复审。
状态：可保持行为的发现已在工作区修复（未提交）；下列各项**未改**，原因分两类：
会改变行为，或需要维护者拍板。

## 已修复的范围（摘要）

- 编辑器骨架（未保存确认框、菜单、脏标记、偏好页 fragment、节点选择器）收进
  `EditorActivity`；端口校验统一在 `ProfileSettingsActivity` 绑定一次。
- 连接测试共用 `ConfigurationFragment.runGroupTest`，删掉 ICMP 死分支、
  `canICMPing`、`UrlTest`；`canReloadSelector` 改用 `selectorGroupIdOf`，
  不再为比较分组 id 构建整份临时配置。
- `ConfigBuilder`：删 `builtProfiles`（可由 `globalOutbounds` 推出）、端口解析与
  IPv6 strategy 去重、选择器成员复用构建开头读到的分组行。
- `Logs.enabled` 与 initCore 的 logEnable 同值，关闭时不再抓调用栈、走 JNI；
  速度通知内容未变时不再 notify；翻页只在分组变化时写 `selectedGroup`。
- 解析：packetEncoding 映射、V2Ray transport 分支、Hysteria 公共参数、
  SingBoxOptionsUtil 域名分拣去重；Clash YAML 只载入一次；分享链接无空格时只解析一遍。
- UI helper：`Context.confirm`、`ContentResolver.displayName`、`Context.shareFile`、
  `exportToClipboard`、`moveUserOrder`；排序 / 规则开关写库走 `ProfileManager`。
- Go：删 `HTTPRequest.SetHeader`、`ResetAllConnections` 的恒真参数、
  `BoxInstance.Close` 不可达的 nil 判断；`io.go` 复用 `copyAndClose`；
  新增 `readAllLimited`。Shell：`env_gobin.sh`、`verify_sha256.sh`。

## 未改：会改变行为

1. **VPN 启动整段跑在 :bg 主线程**（`bg/BaseService.kt` `startRunner` 的
   `runOnMainDispatcher`）：`proxy.init()`（buildConfig + `newSingBoxInstance`，
   首启还等资产解压）和 `box.start()` 都会阻塞主 looper，大分组有 ANR 风险。
   改到后台调度器要复核线程假设。
2. **TrafficLooper 每 tick 对 `idMap` 全量发 `cbTrafficUpdate`**
   （`bg/proto/TrafficLooper.kt`），前台时每秒 N 次 binder 调用；
   `ConfigurationAdapter.onUpdated(TrafficData)` 又对可见行整体 `bind`。
   可改为只发变化项、只刷新流量文本。
3. **`GroupUpdater.forceResolve` 每个节点解析完都 `postReload`**，正在显示该组的
   `ConfigurationAdapter` 每次整组重读，O(N²)；也没有按域名去重。
4. **连接测试结束逐行写状态**（`runGroupTest` 的 `test.cancel`）：可改用现成的
   `ProfileManager.updateStatus(List)` 一个事务，但逐行 `onUpdated` 会顺带
   `undoManager.flush()`，批量写后只剩 `postReload`，滑动删除撤销窗口内的语义会变，
   需确认。
5. **批量导入逐个 `createProfile`**（`ConfigurationFragment.import`、`ScannerActivity`）：
   每个节点一次 nextOrder 查询 + 单行提交 + 监听扇出。
6. **测试任务捕获 Fragment / Activity**（`TestDialog` 被 appScope 上的长任务持有，
   最小化后的通知用 Activity context 构造），旋转或离开后旧 Activity 要等测试跑完才释放。
7. **`PackageCache.reload` 两次全量 PackageManager 查询**，需上机确认
   `getInstalledPackages` 的 `applicationInfo` 与 `getInstalledApplications` 等价。
8. **ping 测试的「分组 DNS，否则系统 DNS」回退**与 `GroupUpdater.lookupBlocking`
   重复且缺 10 秒上限；合并会给 ping 测试加上超时。
9. **Go `lookup.go` 与 Kotlin `makeDnsServer` 各自解析 nameserver**，前者不认
   quic / h3，这类分组 DNS 在订阅解析与 ping 测试里静默回退系统 DNS。
10. **faketcp 的 root 绕行判断**（`bg/VpnService.kt` 只看 `it[0]`）：有落地代理时
    faketcp 节点不在 `it[0]`，疑似漏判（未上机验证）。
11. **链的约束只在选择器里保证**（`ChainSettingsActivity.testProfileContains` 与
    `ConfigBuild.resolveChainInternal` 两份链遍历；前置 / 落地代理是链时构建报
    "can't reach"）。
12. **`GroupFragment` 再解析一遍 `Subscription-Userinfo`**，`RawUpdater` 已写入
    `bytesUsed` 等字段；只用字段渲染会改变显示单位。
13. **TrafficLooper 的 `setV2rayStats` 在 `box.start()` 之后追加 tracker**，是
    sing-box 补丁 "router: lock trackers" 存在的原因；提前到启动前可在下次 rebase
    时去掉该补丁。

## 未改：需要维护者决定

1. **`AppListActivity` / `AppManagerActivity` 约 85% 重复**，但已有分歧：反选时
   AppManager 遍历 `proxiedUids.clone()`，AppList 遍历活数组（共享 uid 的应用会被
   翻两次）。合并需要先定以哪一版为准。
2. **`libcore/nb4a.go` 的 `resourcePaths` linkname 实际无用**：唯一消费者是
   sing-box `constant.FindPath("rish")`（`set_system_proxy`），而 app 从不设置该选项。
   删除要连带删 `nb4a_test.go` 的金丝雀测试和 AGENTS.md 里的说明。
3. **`ProxyEntity` 的 6 个一行转发方法**（`displayType`→`protocolDisplayType` 等）：
   改成同名扩展函数要给约 12 个调用方文件加 import，收益小，暂留。
4. **`AssetsActivity` 导入与更新的发布流程**几乎相同，但失败时的异常类型和文案
   不同（`IllegalStateException` / `IOException`），合并会改一处的用户可见报错。
5. **`ExternalCore` 能力字段**：`externalPluginId` 是第二张插件 id 表，对 Hysteria 2
   与 `ExternalCore.pluginId` 不一致，需先确认哪一方是有意为之。
