# 前端审计 #1(app 模块 + 资源/清单)

- 日期:2026-09-23;基线 commit `85ede82`(工作树干净)。
- 范围:`app/` 模块全部 213 个 Kotlin/Java 源文件 + `AndroidManifest.xml`、`res/`(约 210 个资源文件,values-* 翻译仅机器抽查格式)、`app/build.gradle.kts`、`buildSrc/Helpers.kt`、`proguard-rules.pro`。libcore(Go)属后端,不在本次范围(见 [backend-audit-4.md](backend-audit-4.md))。
- 维度:内存泄漏与生命周期、安全与隐私、崩溃与并发、综合质量。
- 方法:7 个并行分区审计(ui 顶层 / ui/profile / moe.matsuri.nb4a / fmt+group / bg+aidl / 根层+database+widget+ktx+utils+plugin / manifest+res),每条发现要求 file:line 证据;主代理对高危项、部分中危项及子代理间矛盾结论做了复核(见各条「复核」标注),其余为分区审计结论附证据、未逐项复核。
- 本次为只读审计,未修改任何代码。

## 处理结果(2026-09-23,同日完成)

除下列注明项外,高/中/低全部 58 项均已修复;观察项不动(证据不足或需产品决策)。修复后经 `app:compileOssDebugKotlin`、`app:testOssDebugUnitTest`、`app:lintOssRelease`(checkAllWarnings=warningsAsErrors)三道验证全绿。

例外与偏差:

- **中危第 3 项(PackageCache ANR)**:`AppListPreference`/`PackageCache` 侧已修(非阻塞 isLoaded + 占位 + 就绪刷新);`AboutFragment` 调用点经 material-about-library 3.2.0-rc01 字节码复核证伪——`getMaterialAboutList` 由库内 AsyncTask 后台线程调用,本就不在主线程,无需改。
- **低危 23(TempDatabase allowMainThreadQueries)**:跳过——内存 Room 作 PreferenceDataStore 本质要求主线程同步读,无安全改法。
- **低危 26(SagerNet 启动 IO)**:`:137 cleanWebview` 现状已在后台(审计行号漂移);`:117 handleWebviewDir` 跳过——语义要求先于进程内首次 WebView 使用完成,异步化的同步点落在 WebviewFragment,收益微小风险不对等。
- **低危 15(ConfigurationAdapter.getItem 兜底读库)**:改为只查内存缓存(未命中返 null);依据:列表与缓存的所有写入点在主线程同一 post 内成对更新,未命中只剩陈旧回调一种含义。
- 部分可选项未做:QRCodeDialog 位图逐像素填充仍在主线程(异步化需引入占位图+回调,改动大);UrlTestPreference 对话框取消路径的 EditText 残留仍在(窗口有限)。

同模式顺手修复(修复中发现,与已列条目同类):

- `SOCKSFmt.kt:15`、`HttpFmt.kt:10` 错误消息同样去掉了原始链接(补齐低危 28 的协议面)。
- `QuickEnableShortcut`/`QuickDisableShortcut` 补上与 QuickToggleShortcut 相同的 bind 失败 finish 兜底。
- `ConfigurationHolder` 点选路径(withLock 内回填 selectedView)补上与 bind 相同的 holder 复用防护。
- `BackupFragment` 离开页面清理同步覆盖 `cacheDir/share/`(配合 FileProvider 收窄后的新位置)。

高危项修法说明:`shortcuts.xml` 恢复 `targetPackage="moe.nb4a"`,并新增 `app/src/debug/res/xml/shortcuts.xml` 覆盖为 `moe.nb4a.debug`;res 不支持 manifest placeholder,日后改 PACKAGE_NAME 需手动同步这两个文件。

集成期修正(并行修复引入、验证时抓到):QRCodeDialog 缺类闭合括号;DataStore `userIndex` 改用 `Binder.getCallingUid() / 100000`(SDK 无 `UserHandle.getUserId`,该平台隐藏;语义与 `getCallingUserHandle().hashCode()` 一致);MainActivity 深链改用 `androidx.core.net.toUri()`(lint UseKtx)。

## 结论概览

高危 1 项,中危 10 项,低危约 47 项,观察项约 30 项。无远程可触发的代码执行或凭证外泄;攻击面收敛较好(exported 组件、深链、插件签名、备份规则均有针对性缓解,见「已确认无问题」)。

## 高(1)

- **[高][功能失效] `res/xml/shortcuts.xml:8-10,17-19,26-28,35-37`** — 4 条静态快捷方式(toggle/enable/disable/scan)的 `<intent>` 全部缺 `android:targetPackage`。Launcher3 `ShortcutParser.parseIntent` 仅在 package 与 class 同时非空时才 `setClassName`,否则类名被丢弃,intent 退化为 `action=MAIN` 隐式 intent;startActivity 解析时自动附加 `CATEGORY_DEFAULT`,而 MainActivity 的 MAIN filter 只带 LAUNCHER/LEANBACK_LAUNCHER(`AndroidManifest.xml:81-90`),深链 filter 是 VIEW action——无任何组件匹配,点快捷方式必然无效。git 史:`3a2c863` 删除了原有的 `android:targetPackage="moe.nb4a"`(推测因 debug 包名带 `.debug` 后缀)。修法:恢复 targetPackage;res 不支持 manifest placeholder,可用 `src/debug/res` 变体覆盖(debug 用 `moe.nb4a.debug`)或接受硬编码 release 包名。**(已复核:XML 现状、git 史、Manifest MAIN filter 均确认)**

## 中(10)

- **[中][并发] `ui/ConfigurationHolder.kt:138-179`** — `bind()` 把选中/运行状态计算放到 `runOnDefaultDispatcher` 再回主线程写视图与监听器,全程未校验 holder 是否已复用到其他节点;`GroupFragment.kt:479` 对同类回填有 id 防护,此处缺失。快速滑动/刷新时错误行短暂显示为选中,残留监听器还可能用旧 entity 弹分享菜单。修法:回填前比较实体身份。
- **[中][并发/崩溃] `ui/ConfigurationFragment.kt:577-597`** — `runGroupTest` 的 mainJob 在 `appScope`(无 CoroutineExceptionHandler)跑 `getProfilesByGroup`,若抛异常:①直达默认 uncaught handler 崩溃;②`test.cancel()` 不再执行,进程级 `runningTest`(:76)卡在 true,此后所有 ping/url 测试被静默拒绝直至重启。修法:取列表包 try/catch,走与 cancel 相同的复位路径。
- **[中][性能/ANR] `utils/PackageCache.kt:95-104`** — `awaitLoadSync()` 缓存未就绪时 `runBlocking` 等待或同步执行重量级 `getInstalledPackages`;两个主线程调用点:`ui/AboutFragment.kt:98`(冷启动恢复到 About 页)与 `widget/AppListPreference.kt:26`(路由编辑器绑定 summary)。冷启动窗口内可卡主线程数秒。修法:未就绪先返回占位,就绪后 `notifyChanged()`。(两分区独立报告同一根因,已合并)
- **[中][崩溃] `ui/profile/ChainSettingsActivity.kt:82`** — 基类 `ProfileSettingsActivity.kt:101` 在编辑会话被接管时 `finish()` 并 return,`setSupportActionBar` 未执行(主题 NoActionBar,supportActionBar 为 null);子类 `onCreate` 继续执行 `supportActionBar!!` → 必现 NPE。触发:编辑器后台销毁重建且缓存已被另一编辑器 claim。修法:super 之后检查 `isFinishing`/`ownsEditorSession`。**(已复核基类早退路径,子类继续执行为子代理结论)**
- **[中][并发] `ui/profile/ChainSettingsActivity.kt:242-244 vs :184-197`** — `proxyList`(ArrayList 无同步)并发读写:`selectProfileForAdd` 回调在 `Dispatchers.Default` 迭代(:242),ItemTouchHelper 在主线程改同一列表(:183-197),回调运行期间拖拽可致 `ConcurrentModificationException`。修法:校验移到主线程或先快照。
- **[中][崩溃] `ui/profile/ProfileSettingsActivity.kt:321,:343`** — 菜单回调 `activity.proxyEntity!!`;`proxyEntity` 是 lazy,旋转恢复路径首次求值发生在菜单点击时,若编辑期间节点被订阅自动更新删除,`getById` 返回 null → NPE(`saveAndExit` 对 null 有优雅处理,这两处没有)。修法:`?: return` + toast。
- **[中][健壮性] `moe/matsuri/nb4a/proxy/PreferenceBinding.kt:86`** — `pf!!.findPreference(...)!!` 双断言:忘调 `setPreferenceFragment` 或 XML key 与 cacheName 不一致时 NPE,错误信息不可定位。当前唯一调用方顺序正确(隐患非现行 bug)。修法:`checkNotNull` + 带 key 名的 `error(...)`。
- **[中][隐患] `moe/matsuri/nb4a/net/LocalResolverImpl.kt:153-158`** — pre-Q(API<29)设备 `underlyingNetwork` 为 null 或物理网络解析失败时回退到系统默认解析;VPN 激活期间默认网络即 tun 自身,而该接口正是 Go 核的 LocalDNSTransport,本地解析可能重入隧道形成依赖循环。无法在仓库内确认 Go 侧 pre-Q 调用时序,定为隐患。修法:`u == null` 时直接报错不回退。
- **[中][隐私] `res/xml/wireguard_preferences.xml:25-29`** — WireGuard `privateKey` 的 `EditTextPreference` 带 `app:useSimpleSummaryProvider="true"`,私钥明文回显在编辑器摘要(肩窥/截图/最近任务快照可见)。全仓其他敏感字段(ss/vmess/trojan/ssh 密码、uuid、psk、peerPreSharedKey)均不回显,此项是唯一例外。修法:去掉 useSimpleSummaryProvider。**(已复核)**
- **[中][安全] `ui/MainActivity.kt:143-146` + `AndroidManifest.xml:95-142`** — MainActivity exported + 17 个 BROWSABLE 深链 scheme。导入前确认弹窗与解析 try/catch 已到位;残留:`onNewIntent` 不校验 `intent.action`,任何带 data 的 intent 都触发导入弹窗(可被恶意应用反复骚扰);`sn://subscription?url=` 不校验 url scheme,http 明文订阅地址可写入配置。修法:开头加 `if (intent.action != Intent.ACTION_VIEW) return`,订阅 URL 做 http(s) 白名单。

## 低(按区域)

### ui 顶层(12)

1. `GroupFragment.kt:271,126` 主线程 Room 查询(靠 `allowMainThreadQueries` 不崩)。
2. `ConfigurationFragment.kt:116-118` 搜索清空时主线程同步 `reloadProfiles()` 阻塞读库。
3. `ConfigurationFragment.kt:387,575` 菜单回调主线程 `GroupManager.currentGroup()`(同步 + 阻塞 DAO)。
4. `ConfigurationAdapter.kt:40` `getItem()` 缓存未命中时 bind 路径主线程同步读库(兜底路径)。
5. `AssetsActivity.kt:279`、`LogcatFragment.kt:67`、`AppManagerActivity.kt:93` 小文件主线程 IO。
6. `ConfigurationHolder.kt:87-90` `reloadAccess.tryLock()` 后无 finally,异常则锁永不释放,点选节点不再触发 reload。
7. `EditorActivity.kt:137` `profilePicker` 回调 `it.data!!`,RESULT_OK 但 data 为 null 时 NPE。
8. `ConfigurationFragment.kt:196` `fragment.adapter!!`,ViewPager 预载间隙 NPE。
9. `ConfigurationAdapter.kt:66-69` 裸吞 NPE(注释称 group 删除场景),可能掩盖真实 bug。
10. `WebviewFragment.kt:140-147` 菜单 close 销毁 WebView 后页面永久空白,`action_set_url` 静默失效。
11. `BackupFragment.kt:83-91` 含全部节点凭证的备份 JSON 明文残留 `cacheDir`(仅下次分享时清理旧文件);FileProvider 授权 + cacheDir 私有性兜底,风险有限。
12. `GroupFragment.kt:386` 空 OnClickListener 死代码(上游遗留)。

### ui/profile(5)

13. `ProfileSettingsActivity.kt:319-336` `action_create_shortcut` 分支未显式返回 true,launcher 不支持时菜单事件被当未处理。
14. `ConfigEditActivity.kt:92-97` 放弃编辑后 `EditorCache.dirty` 残留,父编辑器返回守卫误报未保存。
15. `ChainSettingsActivity.kt:373` `bindingAdapterPosition` 可为 `NO_POSITION`,「替换」静默退化为「追加」。
16. `ProfileSettingsActivity.kt:218-223,:259` 菜单可见性/回调主线程 Room 查询(既定模式,量级小)。
17. `ConfigEditActivity.kt:103-129` insert/undo/redo 多处 `catch (e: Exception) {}` 空吞。

### moe.matsuri.nb4a(9)

18. `ui/UrlTestPreference.kt:25` 对话框 dismiss 后字段仍持有 EditText(连带视图树与 Activity context),窗口有限。
19. `ui/ColorPickerPreference.kt:95-97` `persistInt` 先于 `callChangeListener` 且忽略其返回值,违反 Preference 契约(当前唯一监听器恒 true,无实际影响)。
20. `utils/Util.kt:178` + `group/RawUpdater.kt:72` `URLDecoder.decode` 对非法 `%` 序列抛 IAE,畸形 content-disposition 会拖垮整个订阅更新;`decodeFilename` 正则贪婪 `.+` 吞掉后续参数。
21. `proxy/PreferenceBinding.kt:57` `?: return` 死代码(`getField` 找不到抛异常而非返回 null)。
22. `ui/ExtendedKeyboard.kt:43` `submitList` 依赖未校验的 lateinit(当前唯一调用方顺序正确)。
23. `TempDatabase.kt:16` `allowMainThreadQueries()` 豁免主线程 IO 检查(内存库代价小,但后续加表失去保护)。
24. `proxy/config/ConfigBean.java:45` 显示名用对象身份哈希,进程重启后 "Custom N" 名字漂移。
25. `ui/MTUPreference.kt:37-38` 非法 MTU 输入静默丢弃,无任何提示。
26. `SagerNet.kt:117,:137` Application.onCreate 主线程文件 IO(webview 目录探测/清理)。

### fmt + group(7)

27. `fmt/ProtocolHandlers.kt:118-134` + `database/ProxyEntity.kt:210` `displayType()` 的 `!!` 在 bean 缺失时先抛 NPE,掩盖 `requireBean()` 的描述性异常(损坏备份/空 Parcel 场景)。
28. `V2RayFmt.kt:98,147`、`TrojanFmt.kt:10`、`TrojanGoFmt.kt:14`、`HysteriaFmt.kt:17-19,63`、`TuicFmt.kt:14-16`、`NaiveFmt.kt:15` 错误消息带完整原始链接(含凭证);`ShadowsocksFmt.kt:26-28` 注释明确的既定方针是「不带链接」,其余协议不一致。当前唯一调用点只记 `simpleName`,暂无实际泄漏,但依赖每个调用点自觉。
29. `HysteriaFmt.kt:124`、`TuicFmt.kt:75`、`SOCKSFmt.kt:50`、`TrojanGoFmt.kt:55`、`V2RayFmt.kt:511` `toUri()` 端口越界抛 IAE,损坏数据在「分享」点击路径崩溃;`HttpFmt.kt:50` 已有范围防御,其余协议不一致。
30. `group/RawUpdater.kt:332,367` 整段 base64 编码的 Clash YAML 订阅落入链接列表解析,报 "no proxies found"(上游继承的兼容缺口)。
31. `fmt/mieru/MieruBean.java:61` Kryo null 字符串时 `protocol.equals` NPE 逃出 `deserializeLenient` 的 `catch (KryoException)`,整行查询失败(深层损坏场景);改 `"UDP".equals(protocol)`。
32. `fmt/KryoConverters.java:56` `bytes == null` 提前返回不 `initializeDefaultValues()`,后续字段访问 NPE(仅内部 Intent 路径)。
33. `group/RawUpdater.kt:215-238` 订阅更新日志记 displayName,无名节点退化为 `address:port` 落 neko.log,`redactSecrets` 不覆盖裸 host:port。

### bg + aidl(2)

34. `bg/BaseService.kt:79-93` Doze 广播分支在 :bg 主线程直接执行阻塞 JNI(`box.sleep()/wake()` 取 Go 侧 `b.access` 锁、`Libcore.resetAllConnections()` 遍历关闭全部连接);同文件 `:100/:105` 与 `:454` 注释自认该调用阻塞并刻意移下主线程,唯独此分支没有。修法:包 `runOnDefaultDispatcher`。**(已复核)**
35. `bg/SagerConnection.kt:71-72` `if (state < 0) return` 死代码(`State.fromOrdinal` 已把越界回落为 Stopped)。

### 根层 + database + widget + ktx + utils(8)

36. `database/ProfileManager.kt:52-58` listener 遍历无异常隔离;`GroupManager.kt:48-58` 明确注释过同一理由,应对齐。
37. `widget/QRCodeDialog.kt:67-68,:101` `arguments!!` 与 `(activity as MainActivity)` 防御缺口;QR 位图逐像素填充在主线程(大屏约百万次循环)。
38. `widget/StatsBar.kt:145-149` URL 测试失败分支把状态重置为「测试中」,失败后永久显示错误文案(疑似复制粘贴);完成回调无生命周期判断。
39. `widget/FabProgressBehavior.kt:23` `getDependencies(child).single()` 在 anchor 缺失/多依赖时 layout 期抛异常(当前唯一使用点安全)。
40. `ktx/Utils.kt:253` `displayName(uri)` 对无路径段 content Uri 抛 `NoSuchElementException`;`AssetsActivity.kt:132` 未捕获,恶意文档提供方可致崩溃。
41. `database/DataStore.kt:89-90` `getCallingUserHandle().hashCode()` 依赖「hashCode==userId」未契约化实现(注释自称只验证到 Nougat,API 31 起 deprecated)。
42. `QuickToggleShortcut.kt:63` `bindService` 返回 false 时无界面 Activity 永不 finish(几乎不会发生)。
43. `ktx/Utils.kt:85` `Preference.remove()` 的 `parent!!` 在 preference 未挂树时 NPE(当前调用点均在树内)。

### res / manifest(4)

44. `res/xml/cache_paths.xml:3` FileProvider `<cache-path path="/"/>` 暴露整个 cache 目录;当前唯一调用方是用户主动分享,但日后写入 cache 的敏感文件(如日志)自动落入可分享范围,建议收窄子目录。
45. `res/layout/layout_chain_settings.xml:25-39` `appbar_scrolling_view_behavior` 用在 LinearLayout 子元素上,死属性。
46. `res/menu/add_profile_menu.xml:96-99` `action_misc` `showAsAction="always"` 但 `android:title=""`,常驻按钮无无障碍标签。
47. `res/layout/layout_edit_config.xml:46-108` 4 个功能 ImageView 与 `layout_group_item.xml:78-88` 的 options 按钮缺 `contentDescription`。

## 观察项(证据不足或属设计取舍,未计入问题)

### ui 顶层
- `SettingsPreferenceFragment.kt:123-126` 分应用代理开关拨「关」被拒绝弹回,只能进 AppManager 用 Disable 关闭;与上游一致,疑似有意 UX。
- `ProfileListFragment.kt:111-115` onResume 在特定恢复路径可抛 UninitializedPropertyAccessException,缺复现条件。
- `MainActivity.kt:149,167-175` 深链订阅允许 http 明文链接,有确认弹窗,上游兼容行为。
- `GroupPagerAdapter.kt:29,58,68` 监听器后台线程触发后跨线程访问 View,靠 post 兜底,未见崩溃路径。
- `GroupFragment.kt:94`、`RouteFragment.kt:56` onSwiped 用 `bindingAdapterPosition` 未判 `NO_POSITION`。
- 分享日志可能含订阅地址/节点信息,用户主动触发,核心侧是否脱敏超出本目录。

### ui/profile
- `NaiveSettingsActivity.kt:53-59` 放弃修改路径同样切换 `DataStore.isExpert` 彩蛋,疑似有意。
- `WireGuardSettingsActivity.kt:23` `pf!!`/`!!` 双重断言,XML 加载失败为前提,几乎不可达。

### nb4a
- `NativeInterface.kt:77` wifiState 逗号拼接 SSID,含逗号的 SSID 可能错位,取决于 Go 侧解析方式。
- `utils/Util.kt:51` zlibCompress 缓冲 `input.size * 4` 在 >~512MB 输入 int 溢出,实际路径达不到。
- `SingBoxOptions.java:109`/`Util.mergeJSON` 非法自定义 JSON 序列化期抛异常,`BaseService.kt:593` 有 Throwable 兜底,可接受。
- `proxy/anytls/AnyTLSFmt.kt:66` 无端口 anytls 链接默认成 80,与 withHttpScheme 设计一致。
- `SendLog.redactSecrets` 正则不匹配 `passwd`/`passwords` 等变体键名,启发式已知取舍。

### fmt + group
- `RawUpdaterWireGuard.kt:6-55` 导入不校验 localAddress/privateKey 格式,与「导入宽容、核心校验」策略一致,是否加校验属产品决定。
- `moe/matsuri/nb4a/utils/NGUtil.kt:19` `Logs.i("Parse int failed $e")` 消息含端口片段,泄漏面小。
- `database/SubscriptionBean.java:16,33,36` token/username/protocols 从不序列化,`MainActivity.kt:193` 读到的恒为 null,疑似 legacy 字段。
- `group/GroupInterfaceAdapter.kt:123-138` onUpdateSuccess 协程 delay(1000) 短暂持有 context,有 isFinishing/isDestroyed 检查。
- `group/GroupUpdater.kt:167` http:// 明文订阅仅 byUser=true 弹确认,WorkManager 自动更新静默走明文(用户自行配置,设计取舍)。
- `fmt/gson/GsonConverters.java:26,32` 损坏 JSON 列直接抛异常,与 Kryo 列 lenient 策略不一致(自写自读,正常不触发)。
- `RawUpdater.kt:335` + `ClashYaml.kt:15` 32MB YAML 全量建树,低端机 OOM 余量风险;别名炸弹已由 SnakeYAML 默认上限挡住,缺实测。

### bg
- 三个 Quick*Shortcut 透明 Activity 等 `onServiceConnected` 无超时兜底,:bg 崩溃循环时残留(概率极低)。
- `Executable.kt:20-40` killAll 覆盖不到 `su -c` 启动的 faketcp 插件进程,仅在 :bg 被 SIGKILL 且用 faketcp 时残留 root 孤儿进程。
- `ProtocolHandlers.kt:578` `su -c` 多参数形态依赖 su 实现传递全部参数,Magisk 可用,其他 su 未逐一验证。
- 主线程单行 Room 读若干处(ProxyInstance.kt:18、BaseService.kt:514/564、TileService.kt:32),既有模式,毫秒级。

### 根层 + database
- `ProfileManager.liveTraffic`(ProfileManager.kt:229)服务运行期间删节点残留条目,仅清理统计/停服时回收,量级小。
- `SagerNet.migrateLegacyAssets`(SagerNet.kt:179-206)从旧外部目录拷贝无大小上限,Android ≤10 上他应用可植入超大文件占存储;一次性迁移,仅影响 ≤1.7.3 升级用户。
- `AlertDialogFragment.onDismiss`(插件契约)按钮点击后补发一次 RESULT_CANCELED,同一 requestKey 两条结果;当前无实际消费方。
- DataStore 变更监听在写入线程同步触发,MainActivity 回调直接操作 UI;现有写入点均在主线程,未来后台线程写相关键即跨线程 UI。
- `Logs.mkTag`(ktx/Logs.kt:16)固定取 `stackTrace[4]`,栈帧不足时越界(实际路径均满足)。
- `readBytesLimited`(ktx/InputLimits.kt:15-19)对返回 0 的病态 InputStream 空转(真实流不会)。

### res / manifest
- 备份策略:含凭证的 `sager_net.db` 排除在云备份外、含在 device-transfer 中;`configuration.db` 进云备份,自定义 DoH 地址(可能含 per-user id)随之进 Google 备份,InstallMarker 已重置 Clash secret,属有意取舍。
- `network_security_config.xml` 全局禁明文仅放行 loopback(给面板用),正确;副作用是应用内 OkHttp 拉 http:// 订阅被拒(Go 侧 HTTP 客户端不受此约束)。
- `proguard-rules.pro:18` `-dontobfuscate` + 全量 keep,release 只压缩不混淆,GPL 项目推测有意。
- `buildSrc/Helpers.kt:144` 签名凭证存在时 debug 构建也用 release key,本机 debug 与正式版共享 signature 级权限信任域,便利性设计。
- 布局/菜单引用 4 处第三方库内部资源(camera-scan、material-about、appcompat、material),已核实当前版本存在,升级库版本可能静默失效。
- `QUERY_ALL_PACKAGES`(per-app proxy 必需)上架 Play 需申报。

## 已排除 / 误报记录

- **「外部插件无签名校验」证伪**:初判认为 `PluginManager.initNativeFaster` 只校验路径不校验来源;复核确认执行路径为 `PluginManager.initNative → Plugins.getPlugin → getPluginExternal`,后者在 `moe/matsuri/nb4a/plugin/Plugins.kt:77-78` 已按签名 SHA-256 白名单(`isTrustedPlugin`,:142-149,含密钥轮换 history)过滤,任何未信任包不会进入 init。**不成立。**
- PackageCache 主线程 ANR 由两分区独立报告,根因相同,已合并为一条中危。
- bg 层与 [backend-audit-4.md](backend-audit-4.md) 已修复项逐项复核确认修复仍在,不重复报告。
- 子代理自查排除的假阳性:通知渠道 `connection-test` 已在 SagerNet.kt:286 注册;androidx.preference 1.2.1 `widget_frame` 确为 LinearLayout(解包 AAR 验证,ColorPickerPreference 强转安全);MTUPreference 有 defaultValue,summary 不会显示 "null";BootReceiver exported 但全为保护广播 + action 白名单;服务内部广播 RECEIVER_EXPORTED + signature 权限鉴权正确。
- 已确认无问题的重点面:不可信输入限界(HTTP 32MB、JSON 深度 64 层、zlib 8MB、Kryo 长度校验、SnakeYAML SafeConstructor);端点校验闭环(四条订阅入口均 requireValidEndpoint);Serializable 版本契约全部对称;编辑器密码统一掩码摘要(WireGuard 私钥为唯一例外,已列中危);IPC 权限(服务 exported=false,TileService 持系统权限);插件命令行不含凭证;导出配置剔除 secret;22 个 values-* 翻译占位符与默认 values 0 处不匹配。

## 覆盖与盲区

- 覆盖:213/213 源文件全读;Manifest 381 行全读;layout 38、menu 14、xml 22 全读;drawable/mipmap 抽查 + 引用完整性脚本全量校验;翻译机器抽查。
- 盲区:`SingBoxOptions.java` 各字段与 sing-box JSON schema 的逐字段对账未做(上游生成代码语义);32MB Clash 订阅峰值内存未实测;各 su 实现兼容性未逐一验证;部分低危项为单审计员结论,未交叉复核。
- 下一步:建议按严重度处理——高危 shortcuts(变体覆盖或恢复包名)、M2/M4/M6 崩溃类优先;中危隐私项(WireGuard 私钥摘要)改动一行即可。处理后在原记录更新状态。
