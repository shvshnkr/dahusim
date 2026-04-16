package fr.husi.utils

/**
 * Switch to bypass mode and add every Russian-ecosystem app to the per-app bypass list.
 *
 * Only the Android target actually enumerates packages; other platforms make this a no-op so the
 * shared "Enable Russia mode" action stays available.
 */
expect suspend fun enableRussianPerAppBypass(): Int
