package io.nekohasekai.sagernet.fmt

import java.util.Base64

// K1 之前的实现写出的 StandardV2Ray v6 bean 字节，补 Kryo 兼容样本没有的取值（样本都填了 uTLS 指纹，ws 都有头名）。
// 取自旧的黄金基线：K1 重新采集之前，1.8.0-a1 起的实现在模拟器上写出的 input.json（场景 id 见各项），
// 内容是夹具的虚构数据。每次取都是新数组
object LegacyV6Bytes {

    private fun decode(b64: String): ByteArray = Base64.getDecoder().decode(b64)

    // xray-vless-tls-tcp：VLESS、tcp、TLS（非 REALITY）、没填指纹、没开 mux
    val vlessTlsTcp: ByteArray
        get() = decode(
            "BgAAAHZsZXNzLmV4YW1wbGUuY2/tuwEAAKU2YjFkMmYzYS00YzVlLTRmN2EtOWIwYy0xZDJlM2Y0YTViNmOB/////3Rj8HRs83ZsZXNzLmV4YW1w" +
                "bGUuY2/taDIsaHR0cC8xLrGBAIGBgQCBAAAAAAAAAAAAAAEAAACBgQEAAABnb2xkZW4teHJheS10bPOBgQ==",
        )

    // xray-vless-ws-maxearlydata：VLESS、ws、TLS、wsMaxEarlyData = 1024、没有头名、路径 /golden-ws?x=1、没填指纹
    val vlessWsEarlyData: ByteArray
        get() = decode(
            "BgAAAHZsZXNzLmV4YW1wbGUuY2/tuwEAAKU2YjFkMmYzYS00YzVlLTRmN2EtOWIwYy0xZDJlM2Y0YTViNmOB/////3fzY2RuLmV4YW1wbGUub3Ln" +
                "L2dvbGRlbi13cz94PbEABAAAgXRs82Nkbi5leGFtcGxlLm9y54GBAIGBgQCBAAAAAAAAAAAAAAEAAACBgQEAAABnb2xkZW4teHJheS13cy1l5IGB",
        )

    // xray-vless-mux：VLESS、tcp、TLS、没填指纹、开了 h2mux
    val vlessMux: ByteArray
        get() = decode(
            "BgAAAHZsZXNzLmV4YW1wbGUuY2/tuwEAAKU2YjFkMmYzYS00YzVlLTRmN2EtOWIwYy0xZDJlM2Y0YTViNmOB/////3Rj8HRs83ZsZXNzLmV4YW1w" +
                "bGUuY2/tgYEAgYGBAIEAAAAAAQAAAAAAAAAAAIGBAQAAAGdvbGRlbi14cmF5LW11+IGB",
        )
}
