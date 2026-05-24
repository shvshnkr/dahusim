package fr.husi.simplemode

import android.app.KeyguardManager
import fr.husi.repository.resolveAndroidRepository

internal actual fun isKeyguardBlockingVpnDialog(): Boolean {
    val ctx = resolveAndroidRepository().context
    val km = ctx.getSystemService(KeyguardManager::class.java) ?: return false
    return km.isKeyguardLocked
}
