package io.nekohasekai.sagernet.ui

import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.core.widget.addTextChangedListener
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
            setHomeAsUpIndicator(R.drawable.ic_navigation_close)
        }
        binding.stunTest.setOnClickListener {
            doTest()
        }
        // 用户重新输入时清掉空值错误
        binding.natStunServer.addTextChangedListener {
            binding.natStunServerLayout.error = null
        }
        binding.mainLayout.padForSystemBars()
        findViewById<AppBarLayout>(R.id.appbar).liftOnScrollOf(binding.mainLayout)
    }

    private fun doTest() {
        if (testJob?.isActive == true) return
        // 空值交给 libcore 会得到 `address  : missing port in address`，开始前先挡掉
        val server = binding.natStunServer.text.toString().trim()
        if (server.isEmpty()) {
            binding.natStunServerLayout.error = getString(R.string.nat_stun_server_empty)
            return
        }
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
