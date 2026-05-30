package fr.husi.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import fr.husi.utils.simpleModeLog

internal object OemBackgroundSettings {

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
        for (intent in launchIntents) {
            if (startIfAvailable(context, intent, label = intent.component?.className.orEmpty())) {
                return
            }
        }
        simpleModeLog("SimpleMode", "H41 huawei_launch_intent_fallback app_details")
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.fromParts("package", context.packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    private fun component(packageName: String, className: String): Intent =
        Intent().setComponent(ComponentName(packageName, className))

    private fun startIfAvailable(context: Context, intent: Intent, label: String): Boolean {
        val resolved = context.packageManager.resolveActivity(intent, 0) != null
        if (!resolved) return false
        return runCatching {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            simpleModeLog("SimpleMode", "H41 huawei_launch_opened target=$label")
            true
        }.getOrDefault(false)
    }
}
