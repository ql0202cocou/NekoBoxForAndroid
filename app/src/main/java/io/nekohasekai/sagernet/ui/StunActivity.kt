package io.nekohasekai.sagernet.ui

import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.AppBarLayout
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.databinding.LayoutStunBinding
import io.nekohasekai.sagernet.ktx.readableMessage
import io.nekohasekai.sagernet.widget.liftOnScrollOf
import io.nekohasekai.sagernet.widget.padForSystemBars
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import libcore.Libcore

class StunActivity : ThemedActivity() {

    private lateinit var binding: LayoutStunBinding
    private var testJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = LayoutStunBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.apply {
            setTitle(R.string.stun_test)
            setDisplayHomeAsUpEnabled(true)
            setHomeAsUpIndicator(R.drawable.baseline_arrow_back_24)
        }
        binding.stunTest.setOnClickListener {
            doTest()
        }
        binding.mainLayout.padForSystemBars()
        findViewById<AppBarLayout>(R.id.appbar).liftOnScrollOf(binding.mainLayout)
    }

    private fun doTest() {
        if (testJob?.isActive == true) return
        val server = binding.natStunServer.text.toString()
        binding.waitLayout.isVisible = true
        binding.stunTest.isEnabled = false
        testJob = lifecycleScope.launch {
            val result = try {
                withContext(Dispatchers.Default) {
                    val response = Libcore.stunTest(server) ?: error("Empty STUN response")
                    if (response.success) {
                        response.text
                    } else {
                        throw Exception(response.text)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!isFinishing && !isDestroyed) {
                    binding.waitLayout.isVisible = false
                    binding.stunTest.isEnabled = true
                    testJob = null
                    AlertDialog.Builder(this@StunActivity)
                        .setTitle(R.string.error_title)
                        .setMessage(e.readableMessage)
                        .setPositiveButton(android.R.string.ok) { _, _ ->
                            finish()
                        }
                        .setOnCancelListener {
                            finish()
                        }
                        .runCatching { show() }
                }
                return@launch
            }
            binding.waitLayout.isVisible = false
            binding.natResult.text = result
            binding.stunTest.isEnabled = true
            testJob = null
        }
    }

}
