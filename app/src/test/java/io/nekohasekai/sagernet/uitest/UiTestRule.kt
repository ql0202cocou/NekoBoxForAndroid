package io.nekohasekai.sagernet.uitest

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Intent
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.bg.ServiceRegistry
import io.nekohasekai.sagernet.database.GroupManager
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.database.preference.PublicDatabase
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.utils.Theme
import moe.matsuri.nb4a.TempDatabase
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement
import org.robolectric.Robolectric
import org.robolectric.android.controller.ActivityController
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * UI 测试的公共环境。Robolectric 在同一 SDK 下复用沙箱类加载器，`object` 单例与 `by lazy` 的数据库
 * 会跨用例留存，所以每个用例前后都在这里归零：
 * - 前：区域 en-US / 时区 UTC、动画时长缩放 0、三个库清表、已知的内存状态与监听器表复位、安装未捕获异常收集器；
 * - 后：销毁用例启动的 Activity、等后台安静、还原区域与时区，并把收集到的未捕获异常（主线程与后台线程）
 *   一次性报出来——页面在后台回调里崩溃不能被悄悄吞掉。
 *
 * 用法：`@get:Rule val ui = UiTestRule()`，页面用 [launch] 启动（启动后已 settle）。
 */
class UiTestRule : TestRule {

    private val uncaught = CopyOnWriteArrayList<Pair<Thread, Throwable>>()
    private val controllers = ArrayList<ActivityController<*>>()

    override fun apply(base: Statement, description: Description) = object : Statement() {
        override fun evaluate() {
            val locale = Locale.getDefault()
            val zone = TimeZone.getDefault()
            val handler = Thread.getDefaultUncaughtExceptionHandler()
            var failure: Throwable? = null
            try {
                before()
                base.evaluate()
            } catch (t: Throwable) {
                failure = t
            }
            try {
                after()
            } catch (t: Throwable) {
                if (failure == null) failure = t else failure.addSuppressed(t)
            } finally {
                Thread.setDefaultUncaughtExceptionHandler(handler)
                Locale.setDefault(locale)
                TimeZone.setDefault(zone)
            }
            val collected = uncaught.toList()
            uncaught.clear()
            if (failure != null) {
                collected.forEach { failure.addSuppressed(it.second) }
                throw failure
            }
            if (collected.isNotEmpty()) {
                throw AssertionError(
                    "用例期间有 ${collected.size} 个未捕获异常：\n" + collected.joinToString("\n---\n") {
                        "[${it.first.name}] " + it.second.stackTraceToString()
                    }
                )
            }
        }
    }

    private fun before() {
        Locale.setDefault(Locale.US)
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        Logs.enabled = false
        // 动画时长缩放 0：animatorsEnabled() 为 false，所有动画直接跳到终态
        ValueAnimator::class.java.getMethod("setDurationScale", Float::class.javaPrimitiveType).invoke(null, 0f)

        resetDatabases()
        resetMemoryState()

        // SagerNet.onCreate 装的 CrashHandler 会拉起 ProcessPhoenix 重启进程，测试里换成收集器。
        // 应用启动时后台块（包缓存、通知渠道等）先于本规则执行，等它们结束再检查有没有崩过
        Thread.setDefaultUncaughtExceptionHandler { t, e -> uncaught += t to e }
        UiSettle.settle()
        val crashed = org.robolectric.Shadows.shadowOf(io.nekohasekai.sagernet.SagerNet.application)
            .peekNextStartedActivity()
        check(crashed == null) { "应用启动阶段崩溃过（CrashHandler 拉起了 $crashed）" }
    }

    private fun after() {
        for (controller in controllers.asReversed()) {
            runCatching {
                controller.pause().stop().destroy()
            }.onFailure { uncaught += Thread.currentThread() to it }
        }
        controllers.clear()
        UiSettle.settle()
    }

    /** 启动并显示一个 Activity（create → start → resume → visible），随后等界面安静 */
    fun <A : Activity> launch(type: Class<A>, intent: Intent? = null): ActivityController<A> {
        val controller = if (intent == null) Robolectric.buildActivity(type)
        else Robolectric.buildActivity(type, intent)
        controllers += controller
        controller.setup()
        UiSettle.settle()
        return controller
    }

    inline fun <reified A : Activity> launch(intent: Intent? = null): ActivityController<A> =
        launch(A::class.java, intent)

    /** 在后台线程跑 [block]：SagerDatabase 不开 allowMainThreadQueries，主线程不能查库 */
    fun <T> offMain(block: () -> T): T {
        var result: Result<T>? = null
        thread(name = "ui-test-bg") { result = runCatching(block) }.join()
        return result!!.getOrThrow()
    }

    private fun resetDatabases() {
        offMain {
            SagerDatabase.instance.clearAllTables()
            // 自增序号一并归零，节点 / 分组 id 才每个用例一样
            SagerDatabase.instance.openHelper.writableDatabase.execSQL("DELETE FROM sqlite_sequence")
        }
        PublicDatabase.kvPairDao.reset()
        TempDatabase.profileCacheDao.reset()
    }

    private fun resetMemoryState() {
        Theme.currentNightMode = -1
        ServiceRegistry.state = BaseService.State.Idle
        ProfileManager.liveTraffic.clear()
        GroupManager.userInterface = null
        // Application.onCreate 每个用例都会再加一次 SubscriptionUpdater（会去排 WorkManager），
        // 其余监听器本应由页面自己摘掉。整表清空，页面与夹具各自按需再加
        clearListeners(GroupManager, "listeners")
        clearListeners(ProfileManager, "listeners")
        clearListeners(ProfileManager, "ruleListeners")
    }

    private fun clearListeners(owner: Any, field: String) {
        val list = owner.javaClass.getDeclaredField(field).apply { isAccessible = true }.get(owner)
        (list as MutableList<*>).clear()
    }
}
