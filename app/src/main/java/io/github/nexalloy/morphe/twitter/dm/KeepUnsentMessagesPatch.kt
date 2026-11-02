package io.github.nexalloy.morphe.twitter.dm

import io.github.nexalloy.hookMethod
import io.github.nexalloy.patch
import java.util.concurrent.ConcurrentHashMap

private const val OWN_DELETE_WINDOW_MS = 10 * 60 * 1000L

private object OwnDeletes {
    private val pending = ConcurrentHashMap<String, Long>()

    private fun key(sequence: Any?) = sequence?.toString()?.filter(Char::isDigit).orEmpty()

    fun remember(sequences: Collection<*>) {
        val until = System.currentTimeMillis() + OWN_DELETE_WINDOW_MS
        sequences.forEach { key(it).takeIf(String::isNotEmpty)?.let { k -> pending[k] = until } }
    }

    fun mine(sequences: Collection<*>): Boolean {
        if (pending.isEmpty() || sequences.isEmpty()) return false
        val now = System.currentTimeMillis()
        pending.values.removeIf { it < now }
        return sequences.all { pending.containsKey(key(it)) }
    }
}

val KeepUnsentMessages = patch(
    name = "Keep unsent messages",
    description = "Messages others unsend for everyone stay on your phone. Messages you unsend from this phone are still removed.",
) {
    ::ownDeletePayloadFingerprint.clazz.declaredConstructors.forEach { ctor ->
        ctor.hookMethod {
            after { param -> param.args.filterIsInstance<Collection<*>>().forEach(OwnDeletes::remember) }
        }
    }
    val skipped = ::messageDeleteSkippedFingerprint.field.apply { isAccessible = true }.get(null)
    val handler = ::messageDeleteHandlerFingerprint.method
    val actionIndex = handler.parameterTypes.indexOfFirst { it.name == DELETE_ACTION }
    val listIndex = handler.parameterTypes.indexOfFirst { Collection::class.java.isAssignableFrom(it) }
    handler.hookMethod {
        before { param ->
            if ((param.args[actionIndex] as? Enum<*>)?.name != DELETE_FOR_ALL) return@before
            val sequences = param.args[listIndex] as? Collection<*> ?: return@before
            if (OwnDeletes.mine(sequences)) return@before
            param.result = skipped
        }
    }
}
