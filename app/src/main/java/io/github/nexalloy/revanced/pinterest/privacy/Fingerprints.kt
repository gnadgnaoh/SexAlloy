package io.github.nexalloy.revanced.pinterest.privacy

import io.github.nexalloy.morphe.findClassDirect
import io.github.nexalloy.morphe.findMethodDirect
import io.github.nexalloy.morphe.findMethodListDirect
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.enums.StringMatchType
import java.lang.reflect.Modifier

internal const val NAVIGATION = "com.pinterest.navigation.Navigation"
internal const val NAVIGATION_IMPL = "com.pinterest.navigation.NavigationImpl"
internal const val SCREEN_LOCATION = "com.pinterest.framework.screens.ScreenLocation"
internal const val IN_APP_BROWSER = "com.pinterest.feature.browser.view.InAppBrowserFragment"
private const val STARTUP_ANCHOR_TAG = "TAG_APPSFLYER_INIT"
private const val APPS_FLYER = "com.appsflyer.AppsFlyerLib"
private const val ENGAGE_WORKER = "com.pinterest.engage.GoogleEngageWorker"
private const val EXTRA_TEXT = "android.intent.extra.TEXT"
private const val NEW_PLAIN_TEXT = "Landroid/content/ClipData;->newPlainText(Ljava/lang/CharSequence;Ljava/lang/CharSequence;)Landroid/content/ClipData;"
private const val PUT_STRING = "Landroid/content/Intent;->putExtra(Ljava/lang/String;Ljava/lang/String;)Landroid/content/Intent;"
private const val PUT_CHARS = "Landroid/content/Intent;->putExtra(Ljava/lang/String;Ljava/lang/CharSequence;)Landroid/content/Intent;"
private val THIRD_PARTY = listOf("androidx.", "ads_mobile_sdk.", "com.google.", "com.linecorp.", "com.facebook.")

private fun DexKitBridge.startupTagClass() = findClass {
    matcher { superClass = "java.lang.Enum"; addField { name = STARTUP_ANCHOR_TAG } }
}.single()

val startupTaskConstructorsFingerprint = findMethodListDirect {
    val tag = startupTagClass().name
    findClass { matcher { addField { type = tag }; addField { type = "java.lang.Runnable" } } }.single()
        .methods.filter { it.isConstructor && tag in it.paramTypeNames && "java.lang.Runnable" in it.paramTypeNames }
        .also { check(it.isNotEmpty()) { "no startup task constructors" } }
}

val appsFlyerImplFingerprint = findClassDirect {
    findClass { matcher { superClass = APPS_FLYER } }.single()
}

val engageWorkFingerprint = findMethodDirect {
    val worker = getClassData(ENGAGE_WORKER) ?: error("No $ENGAGE_WORKER in this build")
    worker.methods.single { it.isMethod && !Modifier.isStatic(it.modifiers) && it.paramCount == 0 && it.returnTypeName != "void" }
}

val navigationPostFingerprint = findMethodDirect {
    val factories = getClassData(NAVIGATION)!!.methods.filter { Modifier.isStatic(it.modifiers) && it.returnTypeName == NAVIGATION_IMPL }
    factories.flatMap { it.callers }.distinctBy { it.descriptor }
        .flatMap { caller -> caller.invokes.filter { it.paramTypeNames == listOf("java.lang.Object") && it.returnTypeName == "void" } }
        .groupBy { it.descriptor }.maxBy { it.value.size }.value.first()
}

val copyLinkScopesFingerprint = findMethodListDirect {
    findMethod { matcher { addInvoke(NEW_PLAIN_TEXT) } }
        .filter { m -> m.isMethod && THIRD_PARTY.none { m.className.startsWith(it) } }
        .also { check(it.isNotEmpty()) { "no copy scopes" } }
}

val shareLinkScopesFingerprint = findMethodListDirect {
    findMethod { matcher { usingEqStrings(EXTRA_TEXT) } }
        .filter { m ->
            m.isMethod && THIRD_PARTY.none { m.className.startsWith(it) } &&
                m.invokes.any { it.descriptor == PUT_STRING || it.descriptor == PUT_CHARS }
        }
        .also { check(it.isNotEmpty()) { "no share scopes" } }
}
