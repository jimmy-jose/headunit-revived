package org.xs.headunitlauncher.billing

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.xs.headunitlauncher.App
import org.xs.headunitlauncher.R
import org.xs.headunitlauncher.app.BaseActivity
import org.xs.headunitlauncher.main.MainActivity
import org.xs.headunitlauncher.utils.AppLog
import org.xs.headunitlauncher.utils.Settings
import org.xs.headunitlauncher.utils.SystemUI
import java.util.concurrent.TimeUnit

class BillingGateActivity : BaseActivity() {

    private lateinit var messageView: TextView
    private lateinit var progressView: ProgressBar
    private lateinit var buyButton: Button
    private lateinit var restoreButton: Button
    private lateinit var retryButton: Button
    private lateinit var exitButton: Button

    private val billingAccessManager by lazy { App.provide(this).billingAccessManager }
    private var hasNavigated = false

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.AppTheme)
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val appSettings = Settings(this)
        requestedOrientation = appSettings.screenOrientation.androidOrientation

        setContentView(R.layout.activity_billing_gate)
        applyInsets()
        SystemUI.apply(window, findViewById(R.id.billing_gate_root), appSettings.fullscreenMode)

        messageView = findViewById(R.id.billing_gate_message)
        progressView = findViewById(R.id.billing_gate_progress)
        buyButton = findViewById(R.id.billing_gate_buy_button)
        restoreButton = findViewById(R.id.billing_gate_restore_button)
        retryButton = findViewById(R.id.billing_gate_retry_button)
        exitButton = findViewById(R.id.billing_gate_exit_button)

        buyButton.setOnClickListener {
            billingAccessManager.launchPurchaseFlow(this)
        }
        restoreButton.setOnClickListener {
            billingAccessManager.restorePurchases()
        }
        retryButton.setOnClickListener {
            billingAccessManager.refreshAccessState()
        }
        exitButton.setOnClickListener {
            finishAffinity()
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                billingAccessManager.accessState.collect { state ->
                    render(state)
                    if (!hasNavigated && (state == AccessState.TRIAL_ACTIVE || state == AccessState.PURCHASED)) {
                        hasNavigated = true
                        launchTarget()
                    }
                }
            }
        }

        billingAccessManager.refreshAccessState()
    }

    override fun onResume() {
        super.onResume()
        billingAccessManager.refreshAccessState()
    }

    private fun applyInsets() {
        val root = findViewById<View>(R.id.billing_gate_root)
        val initialStart = root.paddingStart
        val initialTop = root.paddingTop
        val initialEnd = root.paddingEnd
        val initialBottom = root.paddingBottom

        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val systemBars = insets.getInsets(
                WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.navigationBars()
            )
            view.setPadding(
                initialStart + systemBars.left,
                initialTop + systemBars.top,
                initialEnd + systemBars.right,
                initialBottom + systemBars.bottom
            )
            insets
        }
    }

    private fun render(state: AccessState) {
        val cachedAccessAllowed = billingAccessManager.canUseAppCached()
        progressView.visibility = if (state == AccessState.CHECKING) View.VISIBLE else View.GONE

        messageView.text = when {
            state == AccessState.CHECKING -> getString(R.string.billing_status_checking)
            cachedAccessAllowed -> {
                val remainingMs = billingAccessManager.getTrialRemainingMs()
                if (remainingMs > 0L) {
                    getString(
                        R.string.billing_trial_active_message,
                        formatTrialRemaining(remainingMs)
                    )
                } else {
                    getString(R.string.billing_unlocked_message)
                }
            }
            state == AccessState.ERROR_RETRYABLE -> getString(R.string.billing_status_error_retryable)
            else -> getString(R.string.billing_locked_message)
        }

        buyButton.isEnabled = state != AccessState.CHECKING
        restoreButton.isEnabled = state != AccessState.CHECKING
        retryButton.isEnabled = state != AccessState.CHECKING
    }

    private fun launchTarget() {
        val targetIntent = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_TARGET_INTENT, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_TARGET_INTENT)
        } ?: Intent(this, MainActivity::class.java)

        targetIntent.putExtra(EXTRA_GATE_PASSED, true)
        targetIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)

        AppLog.i("BillingGateActivity: Access granted, launching ${targetIntent.component?.className ?: targetIntent.action}")
        startActivity(targetIntent)
        finish()
    }

    private fun formatTrialRemaining(remainingMs: Long): String {
        val totalMinutes = TimeUnit.MILLISECONDS.toMinutes(remainingMs).coerceAtLeast(0)
        val days = totalMinutes / (60 * 24)
        val hours = (totalMinutes % (60 * 24)) / 60
        val minutes = totalMinutes % 60
        return when {
            days > 0 -> getString(R.string.billing_trial_remaining_days_hours, days, hours)
            hours > 0 -> getString(R.string.billing_trial_remaining_hours_minutes, hours, minutes)
            else -> getString(R.string.billing_trial_remaining_minutes, minutes.coerceAtLeast(1))
        }
    }

    companion object {
        const val EXTRA_TARGET_INTENT = "org.xs.headunitlauncher.billing.EXTRA_TARGET_INTENT"
        const val EXTRA_GATE_PASSED = "org.xs.headunitlauncher.billing.EXTRA_GATE_PASSED"

        fun createIntent(context: Context, targetIntent: Intent): Intent {
            return Intent(context, BillingGateActivity::class.java).apply {
                putExtra(EXTRA_TARGET_INTENT, targetIntent)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                if (context !is Activity) {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
        }

        fun wasGatePassed(intent: Intent?): Boolean {
            return intent?.getBooleanExtra(EXTRA_GATE_PASSED, false) == true
        }
    }
}
