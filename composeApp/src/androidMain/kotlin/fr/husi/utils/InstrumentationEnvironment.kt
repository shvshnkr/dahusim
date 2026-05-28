package fr.husi.utils

object InstrumentationEnvironment {
    val isInstrumented: Boolean by lazy {
        runCatching {
            val registry = Class.forName("androidx.test.platform.app.InstrumentationRegistry")
            val instrumentation = registry.getMethod("getInstrumentation").invoke(null)
            instrumentation != null
        }.getOrDefault(false)
    }
}
