package fr.husi.ui.library

import androidx.compose.runtime.Composable

@Composable
internal expect fun rememberLibraryScannerAction(): (() -> Unit)?
