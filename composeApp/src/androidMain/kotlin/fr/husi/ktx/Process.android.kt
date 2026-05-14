package fr.husi.ktx

import android.content.Intent
import com.jakewharton.processphoenix.ProcessPhoenix
import fr.husi.repository.resolveAndroidRepository
import fr.husi.ui.UiActivityTracker

actual fun restartApplication() {
    ProcessPhoenix.triggerRebirth(
        resolveAndroidRepository().context,
        Intent(resolveAndroidRepository().context, Class.forName("fr.husi.ui.MainActivity")),
    )
}

actual fun exitApplication() {
    if (!UiActivityTracker.finishAffinityOnMainThread()) {
        android.os.Process.killProcess(android.os.Process.myPid())
    }
}
