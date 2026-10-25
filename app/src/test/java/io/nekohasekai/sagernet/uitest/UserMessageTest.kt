package io.nekohasekai.sagernet.uitest

import androidx.test.core.app.ApplicationProvider
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.fmt.ClashImportException
import io.nekohasekai.sagernet.fmt.v2ray.InvalidXhttpExtraException
import io.nekohasekai.sagernet.fmt.v2ray.canonicalXhttpExtra
import io.nekohasekai.sagernet.group.RawUpdater
import io.nekohasekai.sagernet.ktx.userMessage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

/** 给用户看的错误文字（userMessage）：英文的异常消息换成本地化文案，只带计数与已过滤的键名，不带值 */
class UserMessageTest : UiTestBase() {

    private fun string(id: Int, vararg args: Any): String =
        ApplicationProvider.getApplicationContext<android.content.Context>().getString(id, *args)

    @Test
    fun clashImportWithNothingImportedShowsLocalizedCounts() {
        val e = assertThrows(ClashImportException::class.java) {
            runBlocking {
                RawUpdater.parseRaw(
                    "proxies:\n" +
                            "  - {name: node-zq20, type: snell, server: snell-zq21.example.com, port: 443, psk: psk-zq22}\n" +
                            "  - {name: node-zq23, type: socks5, server: 192.0.2.88, port: port-zq27}",
                )
            }
        }
        val message = e.userMessage
        assertEquals(string(R.string.clash_import_none, 2, 1, 1), message)
        for (secret in listOf("zq20", "zq21", "zq22", "zq23", "192.0.2.88", "zq27")) {
            assertFalse(secret, message.contains(secret))
        }
    }

    @Test
    fun xhttpRejectionOfTheWholeExtraHasNoKeyPath() {
        val e = assertThrows(InvalidXhttpExtraException::class.java) { canonicalXhttpExtra("[1]") }
        assertEquals(
            string(R.string.xhttp_extra_invalid, string(R.string.xhttp_reject_not_json_object)),
            e.userMessage,
        )
    }
}
