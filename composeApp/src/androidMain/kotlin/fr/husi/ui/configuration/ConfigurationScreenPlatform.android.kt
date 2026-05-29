package fr.husi.ui.configuration

import android.content.Intent
import androidx.compose.material3.DropdownMenuItem
import fr.husi.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import fr.husi.resources.Res
import fr.husi.resources.add_profile_methods_scan_qr_code
import org.jetbrains.compose.resources.stringResource

@Composable
actual fun ScannerDropdownMenuItem() {
    val launch = rememberProfileScannerAction()
    if (launch != null) {
        DropdownMenuItem(
            text = { Text(stringResource(Res.string.add_profile_methods_scan_qr_code)) },
            onClick = launch,
        )
    }
}

@Composable
actual fun rememberProfileScannerAction(): (() -> Unit)? {
    val context = LocalContext.current
    return {
        context.startActivity(
            Intent(context, ScannerActivity::class.java),
        )
    }
}