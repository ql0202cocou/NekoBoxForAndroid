package io.nekohasekai.sagernet.uitest

import android.widget.ImageButton
import androidx.camera.core.TorchState
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.ui.ScannerActivity
import io.nekohasekai.sagernet.ui.displayTorchState
import org.junit.Assert.*
import org.junit.Test

class ScannerFlashlightTest : UiTestBase() {
    @Test
    fun flashlightExposesItsNameActionAndReportedState() {
        val scanner = ui.launch<ScannerActivity>().get()
        val button = scanner.findViewById<ImageButton>(R.id.ivFlashlight)
        assertEquals("Flashlight", button.createAccessibilityNodeInfo().contentDescription)
        assertTrue(button.isClickable)
        for (state in listOf(null, TorchState.OFF, TorchState.ON, TorchState.OFF)) {
            button.displayTorchState(state)
            val node = button.createAccessibilityNodeInfo()
            assertEquals(state != null, node.isEnabled)
            assertEquals(state == TorchState.ON, node.isSelected)
            assertEquals(if (state == TorchState.ON) "On" else "Off", node.stateDescription)
        }
    }
}
