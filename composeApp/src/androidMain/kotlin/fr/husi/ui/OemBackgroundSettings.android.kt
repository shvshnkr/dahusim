package fr.husi.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import fr.husi.platform.VendorBackgroundHint
import fr.husi.utils.simpleModeLog

internal object OemBackgroundSettings {

    fun openVendorBackground(context: Context, hint: VendorBackgroundHint) {
        when (hint) {
            VendorBackgroundHint.HuaweiLaunchManager -> openHuaweiLaunchManager(context)
            VendorBackgroundHint.XiaomiAutostart -> openXiaomiAutostart(context)
            VendorBackgroundHint.None -> openAppDetails(context)
        }
    }

    fun openHuaweiLaunchManager(context: Context) {
        val launchIntents = listOf(
            component(
                "com.huawei.systemmanager",
                "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
            ),
            component(
                "com.huawei.systemmanager",
                "com.huawei.systemmanager.appmanagement.appstartmgr.AppStartUpActivity",
            ),
            component(
                "com.huawei.systemmanager",
                "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity",
            ),
            component(
                "com.huawei.systemmanager",
                "com.huawei.systemmanager.optimize.process.ProtectActivity",
            ),
        )
        if (tryFirstAvailable(context, launchIntents, logOpened = "H41 huawei_launch_opened")) return
        simpleModeLog("SimpleMode", "H41 huawei_launch_intent_fallback app_details")
        openAppDetails(context)
    }

    fun openXiaomiAutostart(context: Context) {
        val packageName = context.packageName
        val launchIntents = listOf(
            component(
                "com.miui.securitycenter",
                "com.miui.permcenter.autostart.AutoStartManagementActivity",
            ),
            component(
                "com.miui.securitycenter",
                "com.miui.permcenter.autostart.AutoStartManagementActivity",
            ).apply { putExtra("extra_pkgname", packageName) },
            component(
                "com.miui.securitycenter",
                "com.miui.permcenter.permissions.PermissionsEditorActivity",
            ).apply { putExtra("extra_pkgname", packageName) },
            component(
                "com.miui.securitycenter",
                "com.miui.powercenter.PowerSettings",
            ),
            component(
                "com.miui.powerkeeper",
                "com.miui.powerkeeper.ui.HiddenAppsConfigActivity",
            ).apply { putExtra("package_name", packageName) },
        )
        if (tryFirstAvailable(context, launchIntents, logOpened = "H41 xiaomi_autostart_opened")) return
        simpleModeLog("SimpleMode", "H41 xiaomi_autostart_intent_fallback app_details")
        openAppDetails(context)
    }

    private fun component(packageName: String, className: String): Intent =
        Intent().setComponent(ComponentName(packageName, className))

    private fun tryFirstAvailable(
        context: Context,
        intents: List<Intent>,
        logOpened: String,
    ): Boolean {
        for (intent in intents) {
            if (startIfAvailable(context, intent, logOpened)) return true
        }
        return false
    }

    private fun startIfAvailable(context: Context, intent: Intent, logOpened: String): Boolean {
        val resolved = context.packageManager.resolveActivity(intent, 0) != null
        if (!resolved) return false
        return runCatching {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            val target = intent.component?.className.orEmpty()
            simpleModeLog("SimpleMode", "$logOpened target=$target")
            true
        }.getOrDefault(false)
    }

    private fun openAppDetails(context: Context) {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.fromParts("package", context.packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
