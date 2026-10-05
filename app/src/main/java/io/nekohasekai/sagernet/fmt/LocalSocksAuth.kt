package io.nekohasekai.sagernet.fmt

import java.security.SecureRandom
import java.util.Random

/**
 * 本机 socks 认证的一组凭据（RFC 1929 用户名 / 密码）：sing-box 连外核本机 socks 入站时出示，外核入站据此认证。
 * 一次配置构建（buildConfig 的一次调用）最多生成一组，这次构建里入站支持认证的外核（[ExternalCore.inboundAuth]）
 * 的全部跳实例共用它；sing-box 的 socks 出站与外核的入站都取 [ConfigBuildResult.localAuth] 这同一个值。
 * 只用纯 Kotlin 类型，JVM 单测可以直接构造。toString 不带出用户名与密码，免得经异常消息或日志漏出。
 */
class LocalSocksAuth(val username: String, val password: String) {

    init {
        // 消息里不能带值
        require(username.isNotEmpty() && password.isNotEmpty()) { "local socks credentials must not be empty" }
        require(username.utf8Size() <= MAX_BYTES && password.utf8Size() <= MAX_BYTES) {
            "local socks credentials must be at most $MAX_BYTES bytes each"
        }
    }

    /** 按值遮蔽：text 里出现的这组用户名与密码都换成 ***，与文本格式、键名无关。 */
    fun redact(text: String): String = text.replace(password, REDACTED).replace(username, REDACTED)

    override fun equals(other: Any?): Boolean =
        other is LocalSocksAuth && other.username == username && other.password == password

    override fun hashCode(): Int = 31 * username.hashCode() + password.hashCode()

    override fun toString(): String = "LocalSocksAuth(***)"

    companion object {
        // RFC 1929 的长度字段只有一个字节，sing-box 的 socks 客户端不接受更长的值
        const val MAX_BYTES = 255

        private const val REDACTED = "***"

        // 用户名是 u 加 16 个、密码是 p 加 32 个小写十六进制字符：这个字母表在 JSON 与 YAML 里都不需要引号或转义
        private const val USERNAME_RANDOM_BYTES = 8
        private const val PASSWORD_RANDOM_BYTES = 16

        private val secureRandom by lazy { SecureRandom() }

        /** 生产实现：随机数来自 SecureRandom。buildConfig 默认用它。 */
        fun random(): LocalSocksAuth = generate(secureRandom)

        /** 用给定的随机源生成一组；测试传固定种子的源即可复现。 */
        fun generate(random: Random): LocalSocksAuth =
            LocalSocksAuth("u" + random.hex(USERNAME_RANDOM_BYTES), "p" + random.hex(PASSWORD_RANDOM_BYTES))

        private fun Random.hex(bytes: Int): String {
            val buffer = ByteArray(bytes)
            nextBytes(buffer)
            return buffer.joinToString("") { "%02x".format(it) }
        }

        private fun String.utf8Size() = toByteArray(Charsets.UTF_8).size
    }
}
