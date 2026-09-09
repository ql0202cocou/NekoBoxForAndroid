package io.nekohasekai.sagernet.ktx

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.R

fun Context.alert(text: String): AlertDialog {
    return MaterialAlertDialogBuilder(this).setTitle(R.string.error_title)
        .setMessage(text)
        .setPositiveButton(android.R.string.ok, null)
        .create()
}

fun Fragment.alert(text: String) = requireContext().alert(text)

fun AlertDialog.tryToShow() {
    // Dialog wraps the context it was given in a ContextThemeWrapper: unwrap to
    // the hosting Activity before asking whether it is still alive (a plain
    // `context as Activity` threw here and the dialog never showed)
    val activity = generateSequence(context) { (it as? ContextWrapper)?.baseContext }
        .filterIsInstance<Activity>().firstOrNull()
    if (activity != null && (activity.isFinishing || activity.isDestroyed)) return
    try {
        show()
    } catch (e: Exception) {
        Logs.e(e)
    }
}
