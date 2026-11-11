package io.github.nexalloy.revanced.whatsapp.core

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import io.github.nexalloy.hookMethod
import io.github.nexalloy.patch
import java.lang.ref.WeakReference
import java.lang.reflect.Method

internal object WaMessageStore {
    @Volatile
    private var store: WeakReference<Any>? = null
    private lateinit var byKey: Method

    val ready get() = ::byKey.isInitialized

    fun bind(method: Method) {
        byKey = method.apply { isAccessible = true }
    }

    fun capture(instance: Any) {
        if (store?.get() == null) store = WeakReference(instance)
    }

    fun message(key: Any?): Any? {
        if (key == null || !ready) return null
        val instance = store?.get() ?: return null
        return runCatching { byKey.invoke(instance, key) }.getOrNull()
    }
}

internal val WhatsAppMessageStore = patch(name = "<WhatsAppMessageStore>") {
    dependsOn(WhatsAppMessages)
    if (WaMessageStore.ready) return@patch
    val method = ::messageByKeyFingerprint.method
    WaMessageStore.bind(method)
    method.declaringClass.declaredConstructors.forEach { ctor ->
        ctor.hookMethod { after { param -> WaMessageStore.capture(param.thisObject) } }
    }
    var once: XC_MethodHook.Unhook? = null
    once = XposedBridge.hookMethod(method, object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            WaMessageStore.capture(param.thisObject)
            once?.unhook()
        }
    })
}
