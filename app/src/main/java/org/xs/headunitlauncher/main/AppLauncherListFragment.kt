package org.xs.headunitlauncher.main

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.xs.headunitlauncher.R
import org.xs.headunitlauncher.utils.LauncherUtils
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton

class AppLauncherListFragment : Fragment() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyView: TextView
    private lateinit var toolbar: MaterialToolbar
    private lateinit var adapter: AppAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.fragment_app_launcher_list, container, false)
        recyclerView = view.findViewById(android.R.id.list)
        emptyView = view.findViewById(R.id.empty_apps_text)
        toolbar = view.findViewById(R.id.toolbar)

        adapter = AppAdapter(requireContext()) { app ->
            try {
                LauncherUtils.launchApp(requireContext(), app.componentName)
            } catch (e: Exception) {
                Toast.makeText(requireContext(), R.string.failed_open_app, Toast.LENGTH_SHORT).show()
            }
        }

        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter
        val padding = resources.getDimensionPixelSize(R.dimen.list_padding)
        recyclerView.setPadding(padding, padding, padding, padding)
        recyclerView.clipToPadding = false

        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        toolbar.title = getString(R.string.apps)
        toolbar.setNavigationOnClickListener {
            findNavController().popBackStack()
        }

        loadApps()
    }

    private fun loadApps() {
        val apps = LauncherUtils.queryLaunchableApps(requireContext())
        adapter.setApps(apps)
        val isEmpty = apps.isEmpty()
        recyclerView.visibility = if (isEmpty) View.GONE else View.VISIBLE
        emptyView.visibility = if (isEmpty) View.VISIBLE else View.GONE
    }

    private class AppViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val launchButton: MaterialButton = itemView.findViewById(R.id.app_launch_button)
    }

    private class AppAdapter(
        private val context: Context,
        private val onLaunch: (LauncherUtils.LaunchableApp) -> Unit
    ) : RecyclerView.Adapter<AppViewHolder>() {
        private val apps = mutableListOf<LauncherUtils.LaunchableApp>()

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppViewHolder {
            val view = LayoutInflater.from(context).inflate(R.layout.list_item_app, parent, false)
            return AppViewHolder(view)
        }

        override fun onBindViewHolder(holder: AppViewHolder, position: Int) {
            val app = apps[position]
            val isTop = position == 0
            val isBottom = position == itemCount - 1
            val bgRes = when {
                isTop && isBottom -> R.drawable.bg_setting_single
                isTop -> R.drawable.bg_setting_top
                isBottom -> R.drawable.bg_setting_bottom
                else -> R.drawable.bg_setting_middle
            }
            holder.itemView.setBackgroundResource(bgRes)
            holder.launchButton.text = app.label
            holder.launchButton.setOnClickListener {
                onLaunch(app)
            }
        }

        override fun getItemCount(): Int = apps.size

        fun setApps(items: List<LauncherUtils.LaunchableApp>) {
            apps.clear()
            apps.addAll(items)
            notifyDataSetChanged()
        }
    }
}
