# Deferred review findings — 5bbd7be

状态：**5 项全部已于 2026-09-20 处理完毕**。
验证依据：`app:compileOssDebugKotlin`、`app:testOssDebugUnitTest`、
`app:lintOssRelease`（checkAllWarnings = warningsAsErrors）均通过（未提交，改动在工作区）。

## 处理结果

1. **端点校验统一**：`fmt/ProtocolHandlers.kt` 新增 `AbstractBean.requireValidEndpoint()`
   （hysteria 改验 `serverPorts` 端口跳跃、`ConfigBean` 豁免），接入两处漏斗：
   `RawUpdaterClash.parseClash`（删除 `checkClashPort`，消除它与 `parseClashProxy`
   平行的重复类型表）和 `ktx/Formats.kt#parseProxies`（新增 `filterValidEndpoint`，
   坏节点按日志丢弃）。两处都跑在 `initializeDefaultValues()` 之前。
   `parseClashWireGuard` 不再预调 `applyDefaultValues()`（归一化由尾部统一完成），
   否则缺 port 会被预填的 1080 瞒过校验。行为变化：parseProxies 入口缺
   address/port 的节点由静默填默认值改为丢弃。
2. **parseTrojanGo 顺序陷阱**：`TrojanGoFmt.kt` 的 `optInt("remote_port", serverPort)`
   改为 `optInt("remote_port", 0)`；缺 `remote_port` 时触发既有的 `1..65535`
   范围校验报错，不再被 `applyDefaultValues()` 预填的 1080 蒙混。
3. **parseJSON 出口收敛**：`RawUpdaterJson.kt` 的 6 处 `return` 改为向 `proxies`
   累加，尾部统一 `initializeDefaultValues()`；叶子 parser 自带的
   `applyDefaultValues()` 保留（幂等），归一化不再是各叶子的隐性契约。
4. **清选择收敛**：新增 `ProfileManager.clearSelectedProxyIfGone()` 与
   `GroupManager.resetSelectedGroupIfGone()`（裸读 configurationStore，避开
   `selectedGroup` 委托属性的惰性默认值取锁路径），替换 `deleteProfileRow` /
   `clearGroup` / `deleteGroups` / `RawUpdater.doUpdate` /
   `BackupRestore.fixDanglingSelections` 五处内联实现。`deleteGroups` 的清选择
   同时从事务前挪到事务成功后，与 RawUpdater 已记载的语义一致。
5. **lockFile 可重入**：`ktx/FileLocks.kt` 按 canonical path 加 JVM 内
   `ReentrantLock` guard；同线程重入直接执行、不再重复加 fcntl 锁，同 JVM
   其他线程改为排队等待（此前抛 `OverlappingFileLockException`），跨进程
   fcntl 语义不变。

## 原始发现（2026-09-20 存档，已处理）

Historical findings moved from AGENTS.md on 2026-09-20. Raised by the
`/simplify` review of `5bbd7be`; each was deliberately deferred because the
fix changes behaviour or reaches well outside that commit.

- Endpoint validation is hand-written per parser (`HysteriaFmt` x4,
  `TrojanGoFmt`, `RawUpdaterWireGuard`, `RawUpdaterClash.checkClashPort`,
  `V2RayFmt` x2). `checkClashPort`'s type list duplicates `parseClashProxy`'s
  dispatch table, so a protocol added to one but not the other silently
  regresses to the failure these guards exist to prevent. A single
  `requireValidEndpoint(bean)` in `fmt/ProtocolHandlers.kt`, called from
  `parseClash` and `ktx/Formats.kt#parseProxies`, would cover every funnel --
  but it has to run *before* `initializeDefaultValues()`, which is what turns a
  missing host/port into `127.0.0.1:1080`.
- `JSONObject.parseTrojanGo` is an instance of that ordering trap already in
  the tree: it calls `applyDefaultValues()` *before* assigning fields, so
  `serverPort` is 1080 by the time the new `serverPort !in 1..65535` guard
  runs and a missing `remote_port` still passes.
- `group/RawUpdaterJson.kt#parseJSON` has five early `return listOf(...)` that
  all bypass the trailing `proxies.forEach { it.initializeDefaultValues() }`,
  so normalization is a contract each leaf parser must remember. Three honour
  it (`parseHysteria2Json`, `parseHysteria1Json`, `JSONObject.parseShadowsocks`
  all end in `.applyDefaultValues()`); `parseTrojanGo` and the `ConfigBean`
  branch call it first instead. Routing the early returns through one exit
  would remove the contract.
- "Clear the selection when its row is gone" is written out five times
  (`ProfileManager.deleteProfileRow`, `GroupManager.clearGroup`,
  `GroupManager.deleteGroups`, `RawUpdater.doUpdate`,
  `BackupRestore.fixDanglingSelections`). The last one is the general form
  (it tests whether the row still exists rather than matching a delete list)
  and is `private`; lifting it to `ProfileManager` would let the rest call it.
- `ktx/FileLocks.kt#lockFile` uses `FileChannel.lock()`, which is not reentrant
  within a JVM (`OverlappingFileLockException`). Anything running under
  `RestoreJournal.completePending` therefore must not touch a property that can
  reach `GroupManager.createInitialGroup()` -- and `DataStore.selectedGroup`'s
  lazy default does exactly that. `BackupRestore.fixDanglingSelections` dodges
  it by reading `configurationStore.getLong(Key.PROFILE_GROUP, -1)` directly;
  the constraint itself lives only in a comment. Either make `lockFile`
  reentrant per canonical path, or drop the lock-taking side effect from the
  property getter.
