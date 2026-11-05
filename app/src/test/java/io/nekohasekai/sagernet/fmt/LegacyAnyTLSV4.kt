package io.nekohasekai.sagernet.fmt

import com.esotericsoftware.kryo.io.ByteBufferOutput
import moe.matsuri.nb4a.proxy.anytls.AnyTLSBean

// 冻结历史提交 116b7a59 的 v4 写入顺序，构造历史夹具未包含的覆写组合。
// RemovedMihomoUpgradeTest 先与三个不可变 v4 样本逐字节核对；不修改样本或用当前 reader 回填期望。
object LegacyAnyTLSV4 {
    fun bytes(bean: AnyTLSBean): ByteArray = KryoConverters.serialize(Writer(bean))

    private class Writer(private val bean: AnyTLSBean) : AbstractBean() {
        init {
            serverAddress = bean.serverAddress
            serverPort = bean.serverPort
            name = bean.name
            customOutboundJson = bean.customOutboundJson
            customConfigJson = bean.customConfigJson
        }

        override fun serialize(output: ByteBufferOutput) {
            output.writeInt(4)
            super.serialize(output)
            output.writeString(bean.password)
            output.writeString(bean.sni)
            output.writeString(bean.alpn)
            output.writeString(bean.certificates)
            output.writeString(bean.utlsFingerprint)
            output.writeBoolean(bean.allowInsecure)
            output.writeString(bean.echConfig)
            output.writeString(bean.certificateFingerprint)
            output.writeBoolean(bean.enableECH)
            output.writeBoolean(bean.compatibilityTest)
            output.writeInt(bean.certificatePolicy)
        }
    }
}
