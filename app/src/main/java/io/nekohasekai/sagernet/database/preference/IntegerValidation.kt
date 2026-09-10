package io.nekohasekai.sagernet.database.preference

// Validate before TextToInt/stringToInt can silently replace malformed input.
@JvmOverloads
fun isIntegerInRange(value: Any?, min: Int, max: Int, allowEmpty: Boolean = false): Boolean {
    val text = value as? String ?: return false
    if (text.isEmpty()) return allowEmpty
    if (text.any { it !in '0'..'9' }) return false
    return text.toIntOrNull()?.let { it in min..max } == true
}
