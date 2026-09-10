package io.nekohasekai.sagernet.fmt.wireguard

// Go's base64.StdEncoding ignores CR/LF but requires padding and its standard
// alphabet. Exactly 32 bytes encode as 43 alphabet characters followed by '='.
// Do not require canonical trailing bits: the core does not use Strict().
@JvmOverloads
fun isWireGuardKey(value: String, allowEmpty: Boolean = false): Boolean {
    if (value.isEmpty()) return allowEmpty
    val text = value.replace("\r", "").replace("\n", "")
    return text.length == 44 && text.last() == '=' && text.dropLast(1).all {
        it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '+' || it == '/'
    }
}
