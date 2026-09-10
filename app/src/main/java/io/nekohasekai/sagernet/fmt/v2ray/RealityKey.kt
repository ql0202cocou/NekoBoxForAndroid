package io.nekohasekai.sagernet.fmt.v2ray

// REALITY key formats shared by both cores (sing-box common/tls/reality_client.go,
// Xray infra/conf REALITYConfig.Build): the public key is Go base64.RawURLEncoding
// of exactly 32 bytes, the short ID is hex-decoded into an 8-byte buffer (odd length
// is an error; sing-box checks the length only afterwards, so 17+ hex digits panic
// inside the core). Neither core trims whitespace.

private fun String.isRawUrlBase64Of(bytes: Int): Boolean =
    length == (4 * bytes + 2) / 3 && all {
        it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '_'
    }

fun isRealityPublicKey(value: String): Boolean = value.isRawUrlBase64Of(32)

// Empty means no short ID; both cores accept that.
fun isRealityShortId(value: String): Boolean =
    value.length <= 16 && value.length % 2 == 0 && value.all {
        it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F'
    }

// Xray-only post-quantum verify key: RawURLEncoding of a 1952-byte ML-DSA-65 public key.
fun isRealityMldsa65Verify(value: String): Boolean =
    value.isEmpty() || value.isRawUrlBase64Of(1952)

// Every REALITY-capable builder checks the key material before it reaches a core.
fun StandardV2RayBean.requireValidReality() {
    require(isRealityPublicKey(realityPubKey)) {
        "Invalid REALITY public key: expected 43 URL-safe Base64 characters (32 bytes)"
    }
    require(isRealityShortId(realityShortId)) {
        "Invalid REALITY short ID: expected up to 16 hex characters of even length"
    }
}
