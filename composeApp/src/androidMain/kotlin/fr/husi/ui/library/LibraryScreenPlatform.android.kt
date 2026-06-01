package fr.husi.ui.library

import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import fr.husi.ui.configuration.ScannerActivity

@Composable
internal actual fun rememberLibraryScannerAction(): (() -> Unit)? {
    val context = LocalContext.current
    return remember(context) {
        {
            context.startActivity(Intent(context, ScannerActivity::class.java))
        }
    }
}
