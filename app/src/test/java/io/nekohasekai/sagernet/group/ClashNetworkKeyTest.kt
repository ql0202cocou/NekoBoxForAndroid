package io.nekohasekai.sagernet.group

import io.nekohasekai.sagernet.fmt.ClashFieldReason
import io.nekohasekai.sagernet.fmt.ClashFieldResult
import io.nekohasekai.sagernet.fmt.ClashNodeFailure
import io.nekohasekai.sagernet.fmt.ClashNodeResult
import io.nekohasekai.sagernet.fmt.v2ray.StandardV2RayBean
import org.junit.Assert.assertEquals
import org.junit.Test

// network 的键名按 mihomo 匹配，值仍按原有传输规则判断；选中的 opts 与诊断必须一致。
class ClashNetworkKeyTest {
    private fun parsed(type: String, fields: String) = parseClash(loadClashYaml(
        "proxies:\n  - {name: test, type: $type, server: example.com, port: 443, uuid: 00000000-0000-4000-8000-000000000001, password: test-password, $fields}"
    ))

    @Test
    fun `各协议的 network 大小写键选择相同传输和参数`() {
        for (type in listOf("vmess", "vless", "trojan")) {
            for (key in listOf("network", "Network", "NETWORK")) {
                for ((network, opts, path) in listOf(
                    Triple("ws", "ws-opts: {path: /ws}", "/ws"),
                    Triple("grpc", "grpc-opts: {grpc-service-name: service}", "service"),
                    Triple("h2", "h2-opts: {path: /h2}", "/h2"),
                )) {
                    val result = parsed(type, "tls: true, $key: $network, $opts")
                    val bean = result.beans.single() as StandardV2RayBean
                    val trojanTcp = type == "trojan" && network == "h2"
                    assertEquals("$type $key $network", if (trojanTcp) "tcp" else if (network == "h2") "http" else network, bean.type)
                    assertEquals(if (trojanTcp) "" else path, bean.path)
                    val field = (result.nodes.single() as ClashNodeResult.Imported).fields.single { it.path == key }
                    assertEquals(when {
                        trojanTcp -> ClashFieldResult.IGNORED
                        network == "h2" -> ClashFieldResult.CONVERTED
                        else -> ClashFieldResult.KEPT
                    }, field.result)
                    assertEquals(when {
                        trojanTcp -> ClashFieldReason.UNKNOWN_VALUE
                        network == "h2" -> ClashFieldReason.EQUIVALENT_VALUE
                        else -> null
                    }, field.reason)
                }
            }
        }
    }

    @Test
    fun `标准键优先，不随其它大小写键的顺序切换传输`() {
        for (fields in listOf("network: ws, Network: xhttp", "Network: xhttp, network: ws")) {
            val result = parsed("vless", "$fields, ws-opts: {path: /ws}, xhttp-opts: {download-settings: {}}")
            val bean = result.beans.single() as StandardV2RayBean
            assertEquals("ws", bean.type)
            assertEquals("/ws", bean.path)
            val records = (result.nodes.single() as ClashNodeResult.Imported).fields.associateBy { it.path }
            assertEquals(ClashFieldResult.KEPT, records.getValue("network").result)
            assertEquals(ClashFieldReason.INACTIVE, records.getValue("xhttp-opts").reason)
        }
    }

    @Test
    fun `大小写键不能绕过传输与 TLS 的约束`() {
        for (fields in listOf("Network: h2, tls: false", "NETWORK: http, tls: true")) {
            val node = parsed("vless", fields).nodes.single() as ClashNodeResult.Failed
            assertEquals(ClashNodeFailure.TRANSPORT_TLS_MISMATCH, node.failure)
        }
    }
}
