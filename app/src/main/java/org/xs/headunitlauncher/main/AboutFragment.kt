package org.xs.headunitlauncher.main

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.google.android.material.appbar.MaterialToolbar
import org.xs.headunitlauncher.BuildConfig
import org.xs.headunitlauncher.R
import java.util.Calendar

class AboutFragment : Fragment() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_about, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val toolbar = view.findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener {
            findNavController().popBackStack()
        }

        val copyrightText = view.findViewById<TextView>(R.id.copyright_text)
        val currentYear = Calendar.getInstance().get(Calendar.YEAR)
        copyrightText.text = getString(R.string.copyright, currentYear)

        view.findViewById<Button>(R.id.feedback_button).setOnClickListener {
            val intent = createFeedbackIntent()
            val packageManager = requireContext().packageManager
            if (intent.resolveActivity(packageManager) != null) {
                startActivity(intent)
            } else {
                Toast.makeText(requireContext(), R.string.about_feedback_unavailable, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun createFeedbackIntent(): Intent {
        val body = buildString {
            appendLine(getString(R.string.about_feedback_body_intro))
            appendLine()
            appendLine("App Version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("Build Flavor: ${BuildConfig.FLAVOR}")
            appendLine("Manufacturer: ${Build.MANUFACTURER}")
            appendLine("Brand: ${Build.BRAND}")
            appendLine("Model: ${Build.MODEL}")
            appendLine("Device: ${Build.DEVICE}")
            appendLine("Product: ${Build.PRODUCT}")
            appendLine("Android Version: ${Build.VERSION.RELEASE}")
            appendLine("SDK: ${Build.VERSION.SDK_INT}")
            appendLine("Fingerprint: ${Build.FINGERPRINT}")
        }

        val mailtoUri = Uri.Builder()
            .scheme("mailto")
            .opaquePart("jimmy.jose96@gmail.com")
            .appendQueryParameter("subject", "HUL Feedback")
            .appendQueryParameter("body", body)
            .build()

        return Intent(Intent.ACTION_SENDTO).apply {
            data = mailtoUri
        }
    }
}
