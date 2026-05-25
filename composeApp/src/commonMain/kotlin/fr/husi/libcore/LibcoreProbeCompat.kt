package fr.husi.libcore

/**
 * Reflection shims for libcore Client methods that may be missing from an older [libcore.aar]
 * until `make libcore_android` is run.
 */
internal fun Client.newInstanceGroupURLTestCompat(
    config: String,
    groupTag: String,
    link: String,
    timeoutMs: Int,
): Map<String, Int>? = runCatching {
    val m = javaClass.getMethod(
        "newInstanceGroupURLTest",
        String::class.java,
        String::class.java,
        String::class.java,
        Int::class.javaPrimitiveType,
    )
    @Suppress("UNCHECKED_CAST")
    m.invoke(this, config, groupTag, link, timeoutMs) as Map<String, Int>
}.getOrNull()

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
