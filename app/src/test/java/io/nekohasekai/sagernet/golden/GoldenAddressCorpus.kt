package io.nekohasekai.sagernet.golden

import com.google.gson.JsonParser
import java.io.File
import java.net.InetAddress

/**
 * JVM 上的数字地址解析（生产实现 parseNumericAddress 走 Os.inet_pton，JVM 上不能用）：先查基线的地址语料
 * （address/corpus.json 里设备上 parseNumericAddress 的结果），语料之外的输入交给 [StrictNumericAddress]。
 * [outsideCorpus] 记下查过的语料之外的输入。
 */
class GoldenAddressCorpus(file: File) {

    /** 语料：输入 → 设备上解析出的地址字节（十六进制），解析不了为 null。 */
    val entries: Map<String, String?> = run {
        check(file.isFile) { "${file.path} 不存在" }
        val root = JsonParser.parseString(file.readText()).asJsonObject
        root.getAsJsonArray("entries").associate { element ->
            val entry = element.asJsonObject
            val input = entry["input"].asString
            val result = entry.getAsJsonObject("results")["parseNumericAddress"]
                ?: error("${file.path}：输入 ${input.quoted()} 没有 parseNumericAddress 的结果")
            input to if (result.isJsonNull) null else result.asJsonObject["bytes"].asString
        }
    }

    val outsideCorpus = LinkedHashSet<String>()

    fun parse(text: String): InetAddress? {
        if (text in entries) return entries[text]?.let { hex -> InetAddress.getByAddress(hex.hexBytes()) }
        outsideCorpus += text
        return StrictNumericAddress.parse(text)
    }

    companion object {
        fun of(baseline: GoldenBaseline) = GoldenAddressCorpus(baseline.root.resolve("address/corpus.json"))

        /**
         * 基线构建会查到的语料之外的输入（分组 DNS 与 DNS 设置里出现、语料里没有的）→ 回退实现应给的地址字节
         * （十六进制，不是数字地址为 null），期望按 bionic inet_pton 的规则写。唯一的名单：GoldenAddressCorpusTest
         * 拿它核对回退实现，GoldenJvmBuildTest 跑完全部场景后核对查到的正好是这些，README 也引用这里。
         */
        val KNOWN_OUTSIDE: Map<String, String?> = linkedMapOf(
            "dns.example.org" to null,
            "dns.example.com" to null,
            "dns.example.net" to null,
            "auto" to null,
            "2001:db8::53" to "20010db8000000000000000000000053",
            "2001:db8::54" to "20010db8000000000000000000000054",
        )

        fun String.quoted() = "\"" + replace("\\", "\\\\").replace("\n", "\\n").replace("\t", "\\t") + "\""

        private fun String.hexBytes() = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}

/**
 * 按 bionic inet_pton（OpenBSD 的实现）的规则解析数字地址字面量，不查 DNS，不是数字地址时返回 null。
 * 先按 IPv4、再按 IPv6，同生产的 parseNumericAddress：
 * - IPv4：恰好四段 ASCII 十进制数字，以点分隔；每段的值 ≤ 255，前导零不限（按十进制，0177 是 177）。
 * - IPv6：每组 1–4 位十六进制，「::」至多一次，结尾可以是一个点分 IPv4；不接受 zone（%）、方括号与空白。
 *   IPv4 映射地址经 InetAddress.getByAddress 得 Inet4Address，与设备上相同。
 * 不能用 JDK 的 InetAddress.getByName 代替：它接受 5 位一组的 IPv6（如 02001:db8::1），bionic 拒绝。
 */
object StrictNumericAddress {

    fun parse(text: String): InetAddress? {
        (ipv4(text) ?: ipv6(text))?.let { return InetAddress.getByAddress(it) }
        return null
    }

    private fun ipv4(text: String): ByteArray? {
        val out = ByteArray(4)
        var octets = 0
        var value = 0
        var sawDigit = false
        for (ch in text) {
            when {
                ch in '0'..'9' -> {
                    value = value * 10 + (ch - '0')
                    if (value > 255) return null
                    if (!sawDigit) {
                        if (++octets > 4) return null
                        sawDigit = true
                    }
                    out[octets - 1] = value.toByte()
                }

                ch == '.' && sawDigit -> {
                    if (octets == 4) return null
                    value = 0
                    sawDigit = false
                }

                else -> return null
            }
        }
        return out.takeIf { octets == 4 && sawDigit }
    }

    private fun ipv6(text: String): ByteArray? {
        val out = ByteArray(16)
        var pos = 0
        var colonAt = -1
        var i = 0
        if (text.startsWith(":") && !text.startsWith("::")) return null
        if (text.startsWith("::")) {
            colonAt = 0
            i = 2
        }
        var groupStart = i
        var value = 0
        var digits = 0
        while (i < text.length) {
            val ch = text[i]
            val hex = Character.digit(ch, 16).takeIf { ch.code < 128 } ?: -1
            when {
                hex >= 0 -> {
                    if (++digits > 4) return null
                    value = (value shl 4) or hex
                }

                ch == ':' -> {
                    if (digits == 0) {
                        // 「::」：只能一次，且不能紧跟在开头的「::」之后
                        if (colonAt >= 0 || i == 0) return null
                        colonAt = pos
                    } else {
                        if (i + 1 >= text.length) return null
                        if (pos + 2 > 16) return null
                        out[pos++] = (value shr 8).toByte()
                        out[pos++] = value.toByte()
                        digits = 0
                        value = 0
                    }
                    groupStart = i + 1
                }

                ch == '.' && pos + 4 <= 16 -> {
                    // 结尾的点分 IPv4：从这一组开头重新按 IPv4 解析
                    val v4 = ipv4(text.substring(groupStart)) ?: return null
                    v4.copyInto(out, pos)
                    pos += 4
                    digits = 0
                    break
                }

                else -> return null
            }
            i++
        }
        if (digits > 0) {
            if (pos + 2 > 16) return null
            out[pos++] = (value shr 8).toByte()
            out[pos++] = value.toByte()
        }
        if (colonAt >= 0) {
            if (pos == 16) return null
            val tail = pos - colonAt
            for (k in 1..tail) {
                out[16 - k] = out[colonAt + tail - k]
                out[colonAt + tail - k] = 0
            }
            pos = 16
        }
        return out.takeIf { pos == 16 }
    }
}
