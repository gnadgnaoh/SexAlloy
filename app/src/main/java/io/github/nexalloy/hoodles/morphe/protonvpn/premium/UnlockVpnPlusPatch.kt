package io.github.nexalloy.hoodles.morphe.protonvpn.premium

import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XposedBridge
import io.github.nexalloy.enumValueOf
import io.github.nexalloy.hookMethod
import io.github.nexalloy.patch
import io.github.nexalloy.scopedHook
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.util.Collections

private const val MAX_TIER = 3
private const val PAID_TIER_NAME = "vpn2022"

val UnlockVpnPlus = patch(
    name = "Unlock VPN Plus",
    description = "Spoofs the highest plan tier for all UI unlocks, while routing connections through free servers for server-side compatibility. Also skips the upgrade onboarding dialog after login.",
) {
    val serverClass = classLoader.loadClass("com.protonvpn.android.servers.Server")
    val isFreeServer: Method = serverClass.getMethod("isFreeServer")
    fun Any.isFree() = isFreeServer.invoke(this) as Boolean

    VpnUserConstructorFingerprint.hookMethod {
        before { param ->
            param.args[1] = 1
            param.args[11] = MAX_TIER
        }
    }

    VpnUserGetUserTierFingerprint.hookMethod(XC_MethodReplacement.returnConstant(MAX_TIER))
    VpnUserGetMaxTierFingerprint.hookMethod(XC_MethodReplacement.returnConstant(MAX_TIER)) // boxed java.lang.Integer
    VpnUserIsFreeUserFingerprint.hookMethod(XC_MethodReplacement.returnConstant(false))
    VpnUserIsUserPlusOrAboveFingerprint.hookMethod(XC_MethodReplacement.returnConstant(true))
    VpnUserGetUserTierNameFingerprint.hookMethod(XC_MethodReplacement.returnConstant(PAID_TIER_NAME))
    HasAccessToServerFingerprint.hookMethod(XC_MethodReplacement.returnConstant(true))
    HaveAccessWithFingerprint.hookMethod(XC_MethodReplacement.returnConstant(true))
    ServerGroupGetAvailableFingerprint.hookMethod(XC_MethodReplacement.returnConstant(true))

    ServerListFilterFingerprint.hookMethod {
        before { param ->
            val server = param.args[6] ?: return@before
            if (!server.isFree()) param.result = false
        }
    }

    GetBestScoreServerFingerprint.hookMethod {
        before { param ->
            val servers = param.args[0] as? Iterable<*> ?: return@before
            val freeServers = ArrayList<Any>()
            for (server in servers) {
                if (server != null && server.isFree()) freeServers.add(server)
            }
            if (freeServers.isNotEmpty()) param.args[0] = freeServers
        }
    }

    IsFeatureFlagEnabledFingerprint.hookMethod(XC_MethodReplacement.returnConstant(true))

    GetNetShieldAvailabilityFingerprint.method.let { method ->
        val available = method.returnType.enumValueOf("AVAILABLE")
            ?: error("NetShieldAvailability.AVAILABLE not found")
        method.hookMethod(XC_MethodReplacement.returnConstant(available))
    }

    GetFilterButtonsFingerprint.hookMethod(
        XC_MethodReplacement.returnConstant(Collections.emptyList<Any>())
    )

    val standardProfileType = classLoader
        .loadClass("com.protonvpn.android.profiles.ui.ProfileType")
        .enumValueOf("Standard")
        ?: error("ProfileType.Standard not found")
    ProfileAvailableTypesFingerprint.hookMethod {
        before { param ->
            param.result = arrayListOf<Any>(standardProfileType)
        }
    }

    val getVpnCountries = ServerManager2GetVpnCountriesFingerprint.method
    val getFreeCountries = getVpnCountries.declaringClass
        .getDeclaredMethod("getFreeCountries", *getVpnCountries.parameterTypes)
        .apply { isAccessible = true }
    ProfileCountriesFingerprint.hookMethod(scopedHook(getVpnCountries) {
        before { param ->
            try {
                param.result = getFreeCountries.invoke(param.thisObject, *param.args)
            } catch (e: InvocationTargetException) {
                param.throwable = e.cause ?: e
            }
        }
    })

    runCatching {
        UpgradeOnboardingLaunchFingerprint.hookMethod(XC_MethodReplacement.DO_NOTHING)
    }.onFailure { e ->
        XposedBridge.log("Proton VPN: UpgradeOnboardingLaunch not hooked, onboarding dialog not skipped: $e")
    }
}
