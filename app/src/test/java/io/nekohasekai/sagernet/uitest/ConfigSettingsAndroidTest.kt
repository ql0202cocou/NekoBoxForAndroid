package io.nekohasekai.sagernet.uitest

import android.os.Process
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.fmt.ConfigBuildMode
import io.nekohasekai.sagernet.fmt.ConfigSettings
import io.nekohasekai.sagernet.fmt.fromDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.junit.Assert.*
import org.junit.Test
import org.robolectric.shadows.ShadowBinder
import org.robolectric.shadows.ShadowProcess

class ConfigSettingsAndroidTest : UiTestBase() {
    @Test
    fun `空 Clash secret 只在启用的运行模式生成且并发首次读取一致`() = ui.bg {
        for (enabled in listOf(false, true)) {
            DataStore.enableClashAPI = enabled
            DataStore.resetClashApiSecret()
            for (mode in listOf(ConfigBuildMode.TEST, ConfigBuildMode.EXPORT)) {
                assertNull(ConfigSettings.fromDataStore(mode).clashApiSecret)
                assertEquals("", DataStore.configurationStore.getString(Key.CLASH_API_SECRET))
            }
            if (!enabled) assertNull(ConfigSettings.fromDataStore(ConfigBuildMode.RUN).clashApiSecret)
        }
        val values = coroutineScope {
            List(16) { async(Dispatchers.IO) { ConfigSettings.fromDataStore(ConfigBuildMode.RUN).clashApiSecret!! } }.awaitAll()
        }
        assertEquals(1, values.toSet().size)
        assertTrue(values.first().matches(Regex("[a-f0-9]{32}")))
        assertEquals(values.first(), DataStore.configurationStore.getString(Key.CLASH_API_SECRET))
    }

    @Test
    fun `多用户默认端口取本应用用户而非 Binder 来电用户且显式设置优先`() {
        val uid = Process.myUid()
        try {
            ShadowBinder.setCallingUid(10123)
            for (user in listOf(0, 10, 999)) {
                ShadowProcess.setUid(user * 100000 + 10123)
                DataStore.configurationStore.remove(Key.MIXED_PORT)
                assertEquals(2080 + user, DataStore.mixedPort)
                DataStore.mixedPort = 9000
                assertEquals(9000, DataStore.mixedPort)
            }
        } finally {
            ShadowProcess.setUid(uid)
            ShadowBinder.reset()
        }
    }
}
