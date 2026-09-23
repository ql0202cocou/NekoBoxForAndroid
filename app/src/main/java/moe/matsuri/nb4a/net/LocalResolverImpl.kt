package moe.matsuri.nb4a.net

import android.net.DnsResolver
import android.os.Build
import android.os.CancellationSignal
import android.system.ErrnoException
import androidx.annotation.RequiresApi
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.runOnIoDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import libcore.ExchangeContext
import libcore.LocalDNSTransport
import java.net.InetAddress
import java.net.UnknownHostException

object LocalResolverImpl : LocalDNSTransport {

    // new local

    private const val RCODE_NXDOMAIN = 3

    // 没有对应 errno 的本地解析失败统一报这个值；Go 侧包成 syscall.Errno，
    // 不与任何真实 errno 重合，只作「本地解析出错」的标记
    private const val ERRNO_UNKNOWN = 114514

    // API 37 deprecates getInstance() in favour of DnsResolver(Context, Looper); the old
    // entry point is the only one below 37 and still works there, so keep it until the
    // Looper semantics of the constructor are documented
    @Suppress("DEPRECATION")
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun resolver(): DnsResolver = DnsResolver.getInstance()

    override fun raw(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
    }

    override fun networkHandle(): Long {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return SagerNet.underlyingNetwork?.networkHandle ?: 0
        }
        return 0
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    override fun exchange(ctx: ExchangeContext, message: ByteArray) {
        val signal = CancellationSignal()
        ctx.onCancel(signal::cancel)

        // These run on the executor thread, outside the JNI call that gomobile turns
        // into a Go error — an escaping exception here has nobody to catch it. Same
        // guards as lookup() below.
        val callback = object : DnsResolver.Callback<ByteArray> {
            override fun onAnswer(answer: ByteArray, rcode: Int) {
                try {
                    ctx.rawSuccess(answer)
                } catch (e: Exception) {
                    Logs.w(e)
                    ctx.errnoCode(ERRNO_UNKNOWN)
                }
            }

            override fun onError(error: DnsResolver.DnsException) {
                try {
                    ctx.fail(error)
                } catch (e: Exception) {
                    Logs.w(e)
                }
            }
        }

        resolver().rawQuery(
            SagerNet.underlyingNetwork,
            message,
            DnsResolver.FLAG_NO_RETRY,
            Dispatchers.IO.asExecutor(),
            signal,
            callback
        )
    }

    // DnsResolver 回调报错：底层是 ErrnoException 就原样上报 errno，否则记日志报 ERRNO_UNKNOWN。
    // 参数用 Exception 而非 DnsResolver.DnsException（API 29），免得本函数也要标 @RequiresApi
    private fun ExchangeContext.fail(error: Exception) {
        val cause = error.cause
        if (cause is ErrnoException) {
            errnoCode(cause.errno)
        } else {
            Logs.w(error)
            errnoCode(ERRNO_UNKNOWN)
        }
    }

    override fun lookup(ctx: ExchangeContext, network: String, domain: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val signal = CancellationSignal()
            ctx.onCancel(signal::cancel)

            val callback = object : DnsResolver.Callback<Collection<InetAddress>> {
                override fun onAnswer(answer: Collection<InetAddress>, rcode: Int) {
                    try {
                        if (rcode == 0) {
                            ctx.success(answer.mapNotNull { it.hostAddress }.joinToString("\n"))
                        } else {
                            ctx.errorCode(rcode)
                        }
                    } catch (e: Exception) {
                        Logs.w(e)
                        ctx.errnoCode(ERRNO_UNKNOWN)
                    }
                }

                override fun onError(error: DnsResolver.DnsException) {
                    try {
                        ctx.fail(error)
                    } catch (e: Exception) {
                        Logs.w(e)
                        ctx.errnoCode(ERRNO_UNKNOWN)
                    }
                }
            }

            val type = when {
                network.endsWith("4") -> DnsResolver.TYPE_A
                network.endsWith("6") -> DnsResolver.TYPE_AAAA
                else -> null
            }
            if (type != null) {
                resolver().query(
                    SagerNet.underlyingNetwork,
                    domain,
                    type,
                    DnsResolver.FLAG_NO_RETRY,
                    Dispatchers.IO.asExecutor(),
                    signal,
                    callback
                )
            } else {
                resolver().query(
                    SagerNet.underlyingNetwork,
                    domain,
                    DnsResolver.FLAG_NO_RETRY,
                    Dispatchers.IO.asExecutor(),
                    signal,
                    callback
                )
            }
        } else {
            runOnIoDispatcher {
                // 老版本系统，继续用阻塞的 InetAddress
                try {
                    val u = SagerNet.underlyingNetwork
                    // 没有物理网络时直接报错，不回退到系统默认解析：VPN 激活期间
                    // 默认网络可能是 tun 自身，而本接口是 Go 核的 LocalDNSTransport，
                    // 重入隧道会形成依赖循环；物理网络解析失败同理不回退
                    if (u == null) {
                        ctx.errnoCode(ERRNO_UNKNOWN)
                        return@runOnIoDispatcher
                    }
                    val answer = u.getAllByName(domain)
                    ctx.success(answer.mapNotNull { it.hostAddress }.joinToString("\n"))
                } catch (e: UnknownHostException) {
                    ctx.errorCode(RCODE_NXDOMAIN)
                } catch (e: Exception) {
                    Logs.w(e)
                    ctx.errnoCode(ERRNO_UNKNOWN)
                }
            }
        }
    }

}