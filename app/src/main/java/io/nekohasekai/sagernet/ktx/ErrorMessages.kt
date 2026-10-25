package io.nekohasekai.sagernet.ktx

import androidx.annotation.StringRes
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.fmt.ClashImportException
import io.nekohasekai.sagernet.fmt.v2ray.InvalidXhttpExtraException
import io.nekohasekai.sagernet.fmt.v2ray.XhttpRejection

// 给用户看的错误文字。下面几种异常的消息是英文的计数 / 原因，留给日志；界面换成本地化文案，
// 只用计数、枚举与已过滤的键名路径，不带值。其余异常照旧取 readableMessage
val Throwable.userMessage: String
    get() = when (this) {
        is ClashImportException -> app.getString(
            R.string.clash_import_none, summary.total, summary.unknownType, summary.failed
        )

        is InvalidXhttpExtraException -> {
            val reason = app.getString(reason.textRes)
            app.getString(
                R.string.xhttp_extra_invalid,
                if (path.isEmpty()) reason else app.getString(R.string.xhttp_reject_at, shownPath, reason),
            )
        }

        else -> readableMessage
    }

@get:StringRes
private val XhttpRejection.textRes: Int
    get() = when (this) {
        XhttpRejection.UNSUPPORTED_MODE -> R.string.xhttp_reject_unsupported_mode
        XhttpRejection.EXTRA_TOO_LONG -> R.string.xhttp_reject_extra_too_long
        XhttpRejection.NOT_JSON_OBJECT -> R.string.xhttp_reject_not_json_object
        XhttpRejection.UNKNOWN_KEY -> R.string.xhttp_reject_unknown_key
        XhttpRejection.DUPLICATE_KEY -> R.string.xhttp_reject_duplicate_key
        XhttpRejection.DOWNLOAD_SETTINGS -> R.string.xhttp_reject_download_settings
        XhttpRejection.NESTED_EXTRA -> R.string.xhttp_reject_nested_extra
        XhttpRejection.HOST_IN_EXTRA -> R.string.xhttp_reject_host_in_extra
        XhttpRejection.PATH_IN_EXTRA -> R.string.xhttp_reject_path_in_extra
        XhttpRejection.MODE_IN_EXTRA -> R.string.xhttp_reject_mode_in_extra
        XhttpRejection.VALUE_TOO_LONG -> R.string.xhttp_reject_value_too_long
        XhttpRejection.VALUE_BAD_CHARACTERS -> R.string.xhttp_reject_value_bad_characters
        XhttpRejection.VALUE_UNSUPPORTED -> R.string.xhttp_reject_value_unsupported
        XhttpRejection.NOT_BOOLEAN -> R.string.xhttp_reject_not_boolean
        XhttpRejection.NOT_INTEGER -> R.string.xhttp_reject_not_integer
        XhttpRejection.OUT_OF_RANGE -> R.string.xhttp_reject_out_of_range
        XhttpRejection.NOT_OBJECT -> R.string.xhttp_reject_not_object
        XhttpRejection.XMUX_BOTH_LIMITS -> R.string.xhttp_reject_xmux_both_limits
        XhttpRejection.NOT_STRING -> R.string.xhttp_reject_not_string
        XhttpRejection.NOT_RANGE -> R.string.xhttp_reject_not_range
        XhttpRejection.TOO_MANY_HEADERS -> R.string.xhttp_reject_too_many_headers
        XhttpRejection.INVALID_HEADER_NAME -> R.string.xhttp_reject_invalid_header_name
        XhttpRejection.HEADER_HOST -> R.string.xhttp_reject_header_host
        XhttpRejection.DUPLICATE_HEADER -> R.string.xhttp_reject_duplicate_header
    }
