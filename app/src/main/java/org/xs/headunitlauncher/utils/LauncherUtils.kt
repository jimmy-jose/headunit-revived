package org.xs.headunitlauncher.utils

import android.app.role.RoleManager
import android.content.Context.LAUNCHER_APPS_SERVICE
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.PackageInfo
import android.os.Process
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.drawable.Drawable
import android.os.Build
import android.provider.Settings
import org.xs.headunitlauncher.utils.AppLog
import java.text.Collator
import java.util.Locale

object LauncherUtils {

    data class LaunchableApp(
        val label: String,
        val componentName: ComponentName,
        val icon: Drawable
    )

    fun isDefaultHomeApp(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = context.getSystemService(RoleManager::class.java)
            if (roleManager != null && roleManager.isRoleAvailable(RoleManager.ROLE_HOME)) {
                return roleManager.isRoleHeld(RoleManager.ROLE_HOME)
            }
        }

        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
        }
        val resolveInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.resolveActivity(
                intent,
                PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong())
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        } ?: return false

        return resolveInfo.activityInfo?.packageName == context.packageName
    }

    fun createHomeSettingsIntent(context: Context): Intent {
        val settingsIntent = Intent(Settings.ACTION_HOME_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        val canResolve = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.resolveActivity(settingsIntent, PackageManager.ResolveInfoFlags.of(0)) != null
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.resolveActivity(settingsIntent, 0) != null
        }

        return if (canResolve) {
            settingsIntent
        } else {
            Intent(Settings.ACTION_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
    }

    fun queryLaunchableApps(context: Context): List<LaunchableApp> {
        val appsByComponent = linkedMapOf<String, LaunchableApp>()
        val packageManager = context.packageManager

        val launcherAppsResults = collectLauncherApps(context)
        AppLog.i(
            "LauncherUtils: LauncherApps returned %d apps%s",
            launcherAppsResults.size,
            sampleAppsSuffix(launcherAppsResults)
        )
        launcherAppsResults.forEach { app ->
            appsByComponent[app.componentName.flattenToShortString()] = app
        }

        val launcherResolveInfos = collectResolveInfos(packageManager, Intent.CATEGORY_LAUNCHER)
        AppLog.i(
            "LauncherUtils: CATEGORY_LAUNCHER query returned %d activities%s",
            launcherResolveInfos.size,
            sampleResolveInfosSuffix(launcherResolveInfos)
        )
        launcherResolveInfos
            .forEach { resolveInfo ->
                resolveInfo.toLaunchableApp(context)?.let { app ->
                    appsByComponent[app.componentName.flattenToShortString()] = app
                }
            }

        val leanbackResolveInfos = collectResolveInfos(packageManager, Intent.CATEGORY_LEANBACK_LAUNCHER)
        AppLog.i(
            "LauncherUtils: CATEGORY_LEANBACK_LAUNCHER query returned %d activities%s",
            leanbackResolveInfos.size,
            sampleResolveInfosSuffix(leanbackResolveInfos)
        )
        leanbackResolveInfos
            .forEach { resolveInfo ->
                resolveInfo.toLaunchableApp(context)?.let { app ->
                    appsByComponent[app.componentName.flattenToShortString()] = app
                }
            }

        val installedApplications = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            packageManager.getInstalledApplications(0)
        }

        installedApplications
            .asSequence()
            .filterNot { it.packageName == context.packageName }
            .filter { it.enabled }
            .mapNotNull { applicationInfo ->
                packageManager.getLaunchIntentForPackage(applicationInfo.packageName)
                    ?: packageManager.getLeanbackLaunchIntentForPackage(applicationInfo.packageName)
            }
            .mapNotNull { launchIntent ->
                val componentName = launchIntent.component ?: return@mapNotNull null
                val applicationInfo = try {
                    packageManager.getApplicationInfo(componentName.packageName, 0)
                } catch (_: Exception) {
                    null
                } ?: return@mapNotNull null

                val label = packageManager.getApplicationLabel(applicationInfo).toString().trim()
                if (label.isEmpty()) return@mapNotNull null

                LaunchableApp(
                    label = label,
                    componentName = componentName,
                    icon = packageManager.getApplicationIcon(applicationInfo)
                )
            }
            .forEach { app ->
                appsByComponent[app.componentName.flattenToShortString()] = app
            }
        AppLog.i(
            "LauncherUtils: installed-app launch intent scan saw %d enabled packages and %d unique apps so far",
            installedApplications.count { it.packageName != context.packageName && it.enabled },
            appsByComponent.size
        )

        if (appsByComponent.isEmpty()) {
            val packageActivityResults = collectPackageActivities(packageManager, context.packageName)
            AppLog.i(
                "LauncherUtils: package activity scan returned %d apps%s",
                packageActivityResults.size,
                sampleAppsSuffix(packageActivityResults)
            )
            packageActivityResults
                .forEach { app ->
                    appsByComponent[app.componentName.flattenToShortString()] = app
                }
        } else {
            AppLog.i(
                "LauncherUtils: skipping package activity scan because %d launchable apps were already discovered",
                appsByComponent.size
            )
        }

        val collator = Collator.getInstance(Locale.getDefault())
        val finalApps = appsByComponent
            .values
            .asSequence()
            .filter { it.componentName.packageName != context.packageName }
            .sortedWith(compareBy(collator) { it.label.lowercase(Locale.getDefault()) })
            .toList()
        AppLog.i(
            "LauncherUtils: final unique launchable app count=%d%s",
            finalApps.size,
            sampleAppsSuffix(finalApps)
        )
        return finalApps
    }

    fun createLaunchIntent(componentName: ComponentName): Intent {
        return Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
            this.component = componentName
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        }
    }

    fun launchApp(context: Context, componentName: ComponentName) {
        val packageManager = context.packageManager
        val launchIntent = try {
            context.startActivity(createLaunchIntent(componentName))
            return
        } catch (_: Exception) {
            packageManager.getLaunchIntentForPackage(componentName.packageName)
                ?: packageManager.getLeanbackLaunchIntentForPackage(componentName.packageName)
                ?: throw ActivityNotFoundException(componentName.flattenToShortString())
        }

        context.startActivity(launchIntent)
    }

    private fun collectResolveInfos(packageManager: PackageManager, category: String): List<ResolveInfo> {
        val queryIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(category)
        }

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.queryIntentActivities(queryIntent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            packageManager.queryIntentActivities(queryIntent, 0)
        }
    }

    private fun collectPackageActivities(
        packageManager: PackageManager,
        ownPackageName: String
    ): List<LaunchableApp> {
        val installedPackages = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getInstalledPackages(PackageManager.PackageInfoFlags.of(PackageManager.GET_ACTIVITIES.toLong()))
        } else {
            @Suppress("DEPRECATION")
            packageManager.getInstalledPackages(PackageManager.GET_ACTIVITIES)
        }

        return installedPackages
            .asSequence()
            .filterNot { it.packageName == ownPackageName }
            .filter { it.applicationInfo?.enabled == true }
            .flatMap { packageInfo -> packageInfo.toLaunchableApps(packageManager).asSequence() }
            .toList()
    }

    private fun collectLauncherApps(context: Context): List<LaunchableApp> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return emptyList()

        val launcherApps = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            context.getSystemService(LauncherApps::class.java)
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(LAUNCHER_APPS_SERVICE) as? LauncherApps
        } ?: return emptyList()

        val activities = try {
            launcherApps.getActivityList(null, Process.myUserHandle())
        } catch (_: Exception) {
            emptyList()
        }

        return activities
            .asSequence()
            .mapNotNull { activityInfo ->
                val componentName = activityInfo.componentName ?: return@mapNotNull null
                val label = activityInfo.label?.toString()?.trim().orEmpty()
                if (label.isEmpty()) return@mapNotNull null

                LaunchableApp(
                    label = label,
                    componentName = componentName,
                    icon = activityInfo.getBadgedIcon(0)
                )
            }
            .filter { it.componentName.packageName != context.packageName }
            .toList()
    }

    private fun PackageInfo.toLaunchableApps(packageManager: PackageManager): List<LaunchableApp> {
        val appInfo = applicationInfo ?: return emptyList()
        val defaultLabel = packageManager.getApplicationLabel(appInfo).toString().trim()
        val defaultIcon = packageManager.getApplicationIcon(appInfo)

        return (activities ?: emptyArray())
            .asSequence()
            .filter { it.enabled && it.exported }
            .map { activityInfo ->
                val componentName = ComponentName(activityInfo.packageName, activityInfo.name)
                val label = try {
                    activityInfo.loadLabel(packageManager).toString().trim()
                } catch (_: Exception) {
                    defaultLabel
                }.ifEmpty { defaultLabel }

                val icon = try {
                    activityInfo.loadIcon(packageManager)
                } catch (_: Exception) {
                    defaultIcon
                }

                LaunchableApp(
                    label = label,
                    componentName = componentName,
                    icon = icon
                )
            }
            .filter { app ->
                val explicitIntent = Intent(Intent.ACTION_MAIN).apply {
                    component = app.componentName
                    addCategory(Intent.CATEGORY_LAUNCHER)
                }
                val leanbackIntent = Intent(Intent.ACTION_MAIN).apply {
                    component = app.componentName
                    addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                }
                canResolveActivity(packageManager, explicitIntent) ||
                    canResolveActivity(packageManager, leanbackIntent) ||
                    packageManager.getLaunchIntentForPackage(app.componentName.packageName)?.component == app.componentName ||
                    packageManager.getLeanbackLaunchIntentForPackage(app.componentName.packageName)?.component == app.componentName
            }
            .toList()
    }

    private fun canResolveActivity(packageManager: PackageManager, intent: Intent): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.resolveActivity(intent, PackageManager.ResolveInfoFlags.of(0)) != null
        } else {
            @Suppress("DEPRECATION")
            packageManager.resolveActivity(intent, 0) != null
        }
    }

    private fun sampleAppsSuffix(apps: List<LaunchableApp>): String {
        if (apps.isEmpty()) return ""
        val sample = apps.take(5).joinToString { "${it.label} (${it.componentName.flattenToShortString()})" }
        return " sample=[$sample]"
    }

    private fun sampleResolveInfosSuffix(resolveInfos: List<ResolveInfo>): String {
        if (resolveInfos.isEmpty()) return ""
        val sample = resolveInfos.take(5).joinToString { resolveInfo ->
            val info = resolveInfo.activityInfo
            if (info == null) {
                "<null>"
            } else {
                "${info.packageName}/${info.name}"
            }
        }
        return " sample=[$sample]"
    }

    private fun ResolveInfo.toLaunchableApp(context: Context): LaunchableApp? {
        val resolvedActivity = activityInfo ?: return null
        val label = loadLabel(context.packageManager).toString().trim()
        val icon = loadIcon(context.packageManager)
        if (label.isEmpty()) return null

        return LaunchableApp(
            label = label,
            componentName = ComponentName(resolvedActivity.packageName, resolvedActivity.name),
            icon = icon
        )
    }
}
