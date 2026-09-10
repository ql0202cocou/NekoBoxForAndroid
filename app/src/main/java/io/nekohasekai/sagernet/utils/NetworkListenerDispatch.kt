package io.nekohasekai.sagernet.utils

// A subscriber failure must not kill the process-wide network actor or prevent
// the remaining subscribers from receiving this and subsequent network events.
internal fun <T> notifyNetworkListeners(
    listeners: Collection<(T) -> Unit>,
    value: T,
    onFailure: (Exception) -> Unit,
) {
    for (listener in listeners) {
        try {
            listener(value)
        } catch (e: Exception) {
            onFailure(e)
        }
    }
}
