package io.github.nexalloy.revanced.facebook

/** A hook replacement must satisfy the target's declared return type. */
internal fun buildEmptyListReturn(returnType: Class<*>): Any? {
    val result = when {
        // Resolve the factory on Facebook's Class, not the module's class loader.
        returnType.name == "com.google.common.collect.ImmutableList" -> runCatching {
            returnType.getMethod("of").invoke(null)
        }.getOrNull()
        returnType.isAssignableFrom(ArrayList::class.java) -> arrayListOf<Any?>()
        else -> null
    }
    // Unknown concrete collection types must retain their original result.
    return result?.takeIf(returnType::isInstance)
}
