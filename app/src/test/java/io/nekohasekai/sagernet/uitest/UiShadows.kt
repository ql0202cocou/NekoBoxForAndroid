package io.nekohasekai.sagernet.uitest

import android.content.Intent
import android.content.ServiceConnection
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowContextImpl
import java.util.concurrent.Executor

/*
 * UI 测试用的 Robolectric 影子类。JVM 上没有 gojni 原生库，go.Seq / libcore.Libcore 的静态初始化
 * 原本会 System.loadLibrary，这里改成空操作；页面只会调到下面列出的几个方法，调到别的会
 * 走 Robolectric 对原生方法的默认返回值，新页面需要更多方法时在此补，不要放开原生库的加载。
 */

@Implements(className = "go.Seq", isInAndroidSdk = false)
class ShadowGoSeq {
    companion object {
        @JvmStatic
        @Implementation
        fun __staticInitializer__() {
        }

        @JvmStatic
        @Implementation
        fun setContext(context: Any?) {
        }
    }
}

@Implements(className = "libcore.Libcore", isInAndroidSdk = false)
class ShadowLibcore {
    companion object {
        @JvmStatic
        @Implementation
        fun __staticInitializer__() {
        }

        @JvmStatic
        @Implementation
        @Suppress("LongParameterList", "UNUSED_PARAMETER")
        fun initCore(
            process: String?, cachePath: String?, internalAssets: String?, externalAssets: String?,
            maxLogSizeKb: Int, logEnable: Boolean, a: libcore.NB4AInterface?, b: libcore.BoxPlatformInterface?,
            c: libcore.LocalDNSTransport?,
        ) {
        }

        @JvmStatic
        @Implementation
        fun nekoLogPrintln(s: String?) {
        }

        @JvmStatic
        @Implementation
        fun forceGc() {
        }

        @JvmStatic
        @Implementation
        fun versionBox(): String = "test"
    }
}

/**
 * 服务绑定一律失败：Room 的多实例失效服务与 SagerConnection 都不会连上，服务状态恒为 Idle
 * （Robolectric 默认会用空组件名回调 onServiceConnected，Room 随即崩溃）。
 * 「已连接」的画面由用例直接调用 MainActivity.stateChanged 得到。
 */
@Implements(className = ShadowContextImpl.CLASS_NAME, isInAndroidSdk = false)
class ShadowNoBindContextImpl : ShadowContextImpl() {
    @Implementation
    override fun bindService(intent: Intent, conn: ServiceConnection, flags: Int): Boolean = false

    @Implementation
    override fun bindService(intent: Intent, flags: Int, executor: Executor, conn: ServiceConnection): Boolean = false
}
