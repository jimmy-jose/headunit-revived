package org.xs.headunitlauncher.main

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import org.xs.headunitlauncher.App
import org.xs.headunitlauncher.R
import org.xs.headunitlauncher.utils.LauncherUtils

class LauncherPromptDialog : DialogFragment() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NO_TITLE, R.style.SafetyDialogTheme)
        isCancelable = true
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val view = inflater.inflate(R.layout.dialog_launcher_prompt, container, false)

        val btnSetDefault = view.findViewById<Button>(R.id.btn_set_default)
        val btnNotNow = view.findViewById<Button>(R.id.btn_not_now)
        val btnNever = view.findViewById<Button>(R.id.btn_never)

        btnSetDefault.setOnClickListener {
            dismiss()
            val context = requireContext()
            startActivity(LauncherUtils.createHomeSettingsIntent(context))
        }

        btnNotNow.setOnClickListener {
            dismiss()
        }

        btnNever.setOnClickListener {
            App.provide(requireContext()).settings.shouldPromptForLauncher = false
            dismiss()
        }

        return view
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.9).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    companion object {
        const val TAG = "LauncherPromptDialog"

        fun show(manager: FragmentManager) {
            if (manager.findFragmentByTag(TAG) == null) {
                LauncherPromptDialog().show(manager, TAG)
            }
        }
    }
}
