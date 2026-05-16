package fr.husi.libcore

/**
 * [Client.urlFetch] exists in libcore Go sources; older [libcore.aar] may lack the JNI binding.
 * Reflection keeps compile working and picks up the method after `make libcore_android`.
 */
internal fun Client.urlFetchCompat(
    tag: String,
    link: String,
    timeoutMs: Int,
    maxBody: Int,
): String? = runCatching {
    val m = javaClass.getMethod(
        "urlFetch",
        String::class.java,
        String::class.java,
        Int::class.javaPrimitiveType,
        Int::class.javaPrimitiveType,
    )
    m.invoke(this, tag, link, timeoutMs, maxBody) as String
}.getOrNull()
