package io.github.nexalloy.revanced.zalo.calls

import android.app.Activity
import android.app.Notification
import android.content.Context
import app.morphe.extension.shared.Logger
import io.github.nexalloy.PatchExecutor
import io.github.nexalloy.callMethodOrNull
import io.github.nexalloy.getLongFieldOrNull
import io.github.nexalloy.getObjectFieldOrNull
import io.github.nexalloy.hookMethod
import io.github.nexalloy.patch
import java.io.File
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap

/**
 * Auto-record Zalo one-to-one call audio, ported from the Zalo Patch
 * `CallRecordingFeature`.
 *
 * ## What it does
 * Hooks the bundled ZRTC native bridge (`com.vng.zing.vn.zrtc.PeerJNI`) and the
 * `CallCallback` lifecycle. When a call's audio streams connect it calls the
 * native `zrtc_peer_start_record_audio(peer, true, path)` to capture a WAV into
 * Zalo's cache; when the call ends it stops capture and hands the WAV to
 * [CallRecordingOutput], which repairs the header, transcodes to M4A and writes
 * it to the shared MediaStore (`Recordings/Zalo Call Recordings`).
 *
 * ## Why this differs from the Zalo Patch original
 * Zalo Patch is a standalone LSPosed module: capture ran in the Zalo process but
 * finalization (transcode, MediaStore, notifications, a browse UI) ran in the
 * module's own process via a broadcast protocol. NexAlloy patch code already runs
 * INSIDE `com.zing.zalo`, which holds the RECORD_AUDIO and storage permissions, so
 * the whole pipeline runs in-process. The cross-process pieces
 * (`CallRecordingImportProtocol/Receiver`, `CallRecordingNotifier`,
 * `CallRecordingsActivity`, `ConfigProvider`) are intentionally dropped.
 *
 * ## Obfuscation
 * The primary path uses only non-obfuscated names and needs no fingerprints. The
 * peer-manager re-bind and the in-call-activity trigger use obfuscated symbols
 * pinned in [CallRecordingSymbols]; if they stop resolving, those fallbacks are
 * skipped and recording still works from the primary path.
 *
 * ## Legal note
 * Call recording is regulated differently across jurisdictions and often requires
 * consent of all parties. This patch is default-off (`use = false`).
 */
val AutoRecordCalls = patch(
    name = "Auto-record call audio",
    description = "Records Zalo one-to-one voice/video call audio to " +
        "Recordings/Zalo Call Recordings as M4A. Runs the native ZRTC recorder. " +
        "Default off. Recording calls may require the consent of all parties " +
        "where you live — check local law before enabling.",
    use = false,
) {
    CallRecorder.install(this)
}

private object CallRecorder {
    private const val PARTNER_ID_METHOD = "zrtc_call_config_set_partner_id"
    private const val MAKE_CALL = "zrtc_peer_make_call"
    private const val INCOMING_CALL = "zrtc_peer_incoming_call"
    private const val REGISTER_CALLBACK = "zrtc_peer_register_callback"
    private const val START_RECORD = "zrtc_peer_start_record_audio"
    private const val IS_IN_CALL = "zrtc_peer_is_in_call"

    private val SESSIONS: MutableMap<Any, Session> =
        Collections.synchronizedMap(WeakHashMap())
    private val SESSIONS_BY_PEER = ConcurrentHashMap<Long, Session>()
    private val CONFIG_PARTNERS = ConcurrentHashMap<Long, String>()
    private val PEER_PARTNERS = ConcurrentHashMap<Long, String>()
    private val HOOKED_CALLBACKS: MutableSet<String> =
        Collections.synchronizedSet(HashSet())

    @Volatile private var recordMethod: Method? = null
    @Volatile private var isInCallMethod: Method? = null
    @Volatile private var appContext: Context? = null

    fun install(executor: PatchExecutor) {
        val classLoader = executor.classLoader
        appContext = executor.appContext

        val pkg = appContext?.let { ctx ->
            try {
                val info = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
                val versionCode = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                    info.longVersionCode
                } else {
                    @Suppress("DEPRECATION") info.versionCode.toLong()
                }
                "${ctx.packageName} ${info.versionName} ($versionCode)"
            } catch (t: Throwable) {
                "unknown (getPackageInfo failed: $t)"
            }
        } ?: "unknown (no appContext)"
        Logger.printInfo { "[Zalo][CallRecording] install() starting for $pkg" }
        Logger.printInfo {
            "[Zalo][CallRecording] pinned symbol table: PEER_MANAGER_CLASS=" +
                "${CallRecordingSymbols.PEER_MANAGER_CLASS} CURRENT_CALLBACK_CLASS=" +
                "${CallRecordingSymbols.CURRENT_CALLBACK_CLASS} ACTIVITY_READY_METHOD=" +
                "${CallRecordingSymbols.ACTIVITY_READY_METHOD} (verified for Zalo 26.08.02 / " +
                "260802903 only - expect these to miss on any other build)"
        }

        val peerClass = loadOrNull(CallRecordingSymbols.PEER_JNI, classLoader)
        if (peerClass == null) {
            Logger.printInfo {
                "[Zalo][CallRecording] FATAL: class ${CallRecordingSymbols.PEER_JNI} not found " +
                    "in this build's classloader. The primary (non-obfuscated) capture path " +
                    "cannot work at all on this Zalo version."
            }
            throw IllegalStateException("PeerJNI missing; ZRTC recorder unavailable")
        }
        Logger.printInfo { "[Zalo][CallRecording] resolved PeerJNI class: ${peerClass.name}" }

        try {
            recordMethod = peerClass.getDeclaredMethod(
                START_RECORD, Long::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType, String::class.java
            ).apply { isAccessible = true }
            isInCallMethod = peerClass.getDeclaredMethod(
                IS_IN_CALL, Long::class.javaPrimitiveType
            ).apply { isAccessible = true }
        } catch (t: Throwable) {
            Logger.printException({
                "[Zalo][CallRecording] FATAL: could not resolve $START_RECORD/$IS_IN_CALL on " +
                    "${peerClass.name} - native recorder entry points changed signature on this build"
            }, t)
            throw t
        }
        Logger.printInfo {
            "[Zalo][CallRecording] resolved native methods: $START_RECORD, $IS_IN_CALL"
        }

        val peerMetadataHooks = hookPeerMetadata(peerClass)
        val audioStreamHooks = hookAudioStreamRegistration(peerClass)
        val terminationHooks = hookPeerTermination(peerClass)
        val callbackRegistrationHooks = hookCallbackRegistration(peerClass)
        val callbackBaseHooks = hookCallbackBase(classLoader)
        val currentCallbackHooks = hookCurrentCallback(classLoader)
        val activityHooks = hookActivities(classLoader)
        val notificationHooks = hookNotifications()

        val hooks = peerMetadataHooks + audioStreamHooks + terminationHooks +
            callbackRegistrationHooks + callbackBaseHooks + currentCallbackHooks +
            activityHooks + notificationHooks

        Logger.printInfo {
            "[Zalo][CallRecording] hook counts - peerMetadata=$peerMetadataHooks " +
                "audioStreamRegistration=$audioStreamHooks peerTermination=$terminationHooks " +
                "callbackRegistration=$callbackRegistrationHooks callbackBase=$callbackBaseHooks " +
                "currentCallback(obf)=$currentCallbackHooks activities(obf)=$activityHooks " +
                "notifications=$notificationHooks"
        }
        if (callbackBaseHooks == 0) {
            Logger.printInfo {
                "[Zalo][CallRecording] WARNING: callbackBase=0 means no method on " +
                    "${CallRecordingSymbols.CALL_CALLBACK} matched " +
                    "CallRecordingLifecycle.observes() - if VNG renamed the ZRTC callback " +
                    "methods (onIncomingCall/onCallAudioState/onCallEnd/...) on this build, the " +
                    "primary start/stop trigger will never fire even though hooks 'installed' ok."
            }
        }
        if (currentCallbackHooks == 0) {
            Logger.printInfo {
                "[Zalo][CallRecording] note: currentCallback(obf)=0 - " +
                    "${CallRecordingSymbols.CURRENT_CALLBACK_CLASS} not found or hooked; expected " +
                    "on any build other than 26.08.02 (260802903), harmless if callbackBase>0"
            }
        }
        if (activityHooks == 0) {
            Logger.printInfo {
                "[Zalo][CallRecording] note: activities(obf)=0 - secondary in-call-activity " +
                    "trigger unavailable on this build, harmless if callbackBase>0"
            }
        }

        check(hooks > 0) { "No call lifecycle hooks installed" }
        Logger.printInfo { "[Zalo][CallRecording] Auto-record installed $hooks hooks total" }

        recoverPending()
    }

    // --- Peer metadata: partner UID + call direction ---------------------------

    private fun hookPeerMetadata(peerClass: Class<*>): Int {
        var count = 0
        count += hookAllByName(peerClass, PARTNER_ID_METHOD) {
            before { param ->
                val args = param.args
                if (args != null && args.size >= 2 &&
                    args[0] is Long && args[1] is Int
                ) {
                    val uid = (args[1] as Int).toLong() and 0xffffffffL
                    if (uid > 0L) CONFIG_PARTNERS[args[0] as Long] = uid.toString()
                }
            }
        }
        val bindPeer: HookScope.() -> Unit = {
            before { param ->
                val args = param.args
                if (args != null && args.size >= 2 && args[0] is Long && args[1] is Long) {
                    val configHandle = args[1] as Long
                    val peerHandle = args[0] as Long
                    CONFIG_PARTNERS[configHandle]?.takeIf { it.isNotEmpty() }?.let {
                        PEER_PARTNERS[peerHandle] = it
                    }
                    val session = SESSIONS_BY_PEER.getOrPut(peerHandle) {
                        Session(peerHandle, PEER_PARTNERS[peerHandle])
                    }
                    session.direction =
                        if (param.method.name == MAKE_CALL) "outgoing" else "incoming"
                }
            }
        }
        count += hookAllByName(peerClass, MAKE_CALL, bindPeer)
        count += hookAllByName(peerClass, INCOMING_CALL, bindPeer)
        return count
    }

    // --- Audio stream registration -> confirmed + connected -> start -----------

    private fun hookAudioStreamRegistration(peerClass: Class<*>): Int {
        var count = 0
        for (methodName in arrayOf(
            "zrtc_peer_register_in_audio_stream",
            "zrtc_peer_register_out_audio_stream"
        )) {
            count += hookAllByName(peerClass, methodName) {
                after { param ->
                    val args = param.args ?: return@after
                    if (args.isEmpty() || args[0] !is Long) return@after
                    val peerHandle = args[0] as Long
                    val session = SESSIONS_BY_PEER.getOrPut(peerHandle) {
                        Session(peerHandle, PEER_PARTNERS[peerHandle])
                    }
                    synchronized(session) {
                        session.confirmed = true
                        session.audioConnected = true
                    }
                    start(session, methodName)
                }
            }
        }
        return count
    }

    // --- Peer termination -> stop ----------------------------------------------

    private fun hookPeerTermination(peerClass: Class<*>): Int {
        var count = 0
        for (methodName in arrayOf(
            "zrtc_peer_end_call", "zrtc_peer_force_stop", "zrtc_peer_delete"
        )) {
            count += hookAllByName(peerClass, methodName) {
                before { param ->
                    val args = param.args ?: return@before
                    if (args.isEmpty() || args[0] !is Long) return@before
                    stopForPeer(args[0] as Long, "PeerJNI#$methodName")
                }
            }
        }
        return count
    }

    // --- Callback registration binds a callback object to a peer handle --------

    private fun hookCallbackRegistration(peerClass: Class<*>): Int =
        hookAllByName(peerClass, REGISTER_CALLBACK) {
            before { param ->
                val args = param.args ?: return@before
                if (args.size < 2 || args[0] !is Long || args[1] == null) return@before
                val callback = args[1]!!
                if (!isCallCallback(callback.javaClass)) return@before
                hookCallbackClass(callback.javaClass)
                val peerHandle = args[0] as Long
                val session = SESSIONS_BY_PEER.getOrPut(peerHandle) {
                    Session(peerHandle, PEER_PARTNERS[peerHandle])
                }
                SESSIONS[callback] = session
            }
        }

    private fun hookCallbackBase(classLoader: ClassLoader): Int {
        val callbackClass = loadOrNull(CallRecordingSymbols.CALL_CALLBACK, classLoader)
        if (callbackClass == null) {
            Logger.printInfo {
                "[Zalo][CallRecording] hookCallbackBase: class " +
                    "${CallRecordingSymbols.CALL_CALLBACK} not found on this build"
            }
            return 0
        }
        return hookCallbackClass(callbackClass)
    }

    private fun hookCurrentCallback(classLoader: ClassLoader): Int {
        val callbackClass = loadOrNull(CallRecordingSymbols.CURRENT_CALLBACK_CLASS, classLoader)
        if (callbackClass == null) {
            Logger.printInfo {
                "[Zalo][CallRecording] hookCurrentCallback: obfuscated class " +
                    "${CallRecordingSymbols.CURRENT_CALLBACK_CLASS} not found (expected off the " +
                    "pinned build) - primary path still relies on hookCallbackBase"
            }
            return 0
        }
        return hookCallbackClass(callbackClass)
    }

    private fun hookCallbackClass(callbackClass: Class<*>): Int {
        var count = 0
        val matchedNames = mutableSetOf<String>()
        val seenNames = mutableSetOf<String>()
        var current: Class<*>? = callbackClass
        while (current != null && current != Any::class.java) {
            for (method in current.declaredMethods) {
                val name = method.name
                seenNames.add(name)
                if (Modifier.isAbstract(method.modifiers) ||
                    !CallRecordingLifecycle.observes(name)
                ) continue
                val signature = current.name + "#" + method.toGenericString()
                if (!HOOKED_CALLBACKS.add(signature)) continue
                try {
                    method.isAccessible = true
                    hookMember(method, callbackHook(method))
                    count++
                    matchedNames.add(name)
                } catch (t: Throwable) {
                    HOOKED_CALLBACKS.remove(signature)
                    Logger.printException({ "[Zalo] callback hook failed: $signature" }, t)
                }
            }
            current = current.superclass
        }
        Logger.printInfo {
            "[Zalo][CallRecording] hookCallbackClass(${callbackClass.name}): hooked=$count " +
                "matched=$matchedNames"
        }
        if (count == 0) {
            Logger.printInfo {
                "[Zalo][CallRecording] hookCallbackClass(${callbackClass.name}): none of " +
                    "CallRecordingLifecycle.observes()'s expected names " +
                    "(onIncomingCall/onMakeCall/onCallConfirmed/onPreConnectSuccessful/" +
                    "onCallAudioState/onCallVideoState/onCallState/onCallEnd/onCallErr/" +
                    "onCallAutoHangup) matched any declared method. All declared method names " +
                    "seen on this class hierarchy: $seenNames"
            }
        }
        return count
    }

    private fun callbackHook(method: Method): HookScope.() -> Unit = {
        before { param ->
            val methodName = method.name
            var session = SESSIONS[param.thisObject]
            val hadSession = session != null
            // Zalo can reuse its callback after replacing the native peer. A new call
            // must resolve the current handle instead of reviving the retired session.
            if (session == null || CallRecordingLifecycle.beginsCall(methodName)) {
                resolveCurrentSession(param.thisObject)?.let { session = it }
            }
            val s = session
            Logger.printInfo {
                "[Zalo][CallRecording] callback fired: $methodName hadSession=$hadSession " +
                    "resolvedSession=${s != null} peerHandle=${s?.peerHandle} deleted=${s?.deleted}"
            }
            if (s == null || s.deleted) {
                Logger.printInfo {
                    "[Zalo][CallRecording] $methodName: no usable session, ignoring " +
                        "(session==null=${s == null}, deleted=${s?.deleted})"
                }
                return@before
            }
            when (methodName) {
                "onIncomingCall" -> { s.direction = "incoming"; return@before }
                "onMakeCall" -> { s.direction = "outgoing"; return@before }
            }
            val state = firstInt(param.args)
            if (CallRecordingLifecycle.isVideoState(methodName)) return@before
            if (CallRecordingLifecycle.shouldStopAudio(methodName, state)) {
                // ZRTC ignores recordAudio(false, ...) after its controller leaves the
                // confirmed state. Stop inside this before-hook.
                Logger.printInfo {
                    "[Zalo][CallRecording] $methodName state=$state -> stopping " +
                        "(peerHandle=${s.peerHandle})"
                }
                stop(s, methodName)
                return@before
            }
            var shouldStart: Boolean
            synchronized(s) {
                if (CallRecordingLifecycle.confirmsCall(methodName)) s.confirmed = true
                if (CallRecordingLifecycle.connectsAudio(methodName, state)) {
                    s.audioConnected = true
                }
                shouldStart = CallRecordingLifecycle.shouldStartAudio(
                    s.confirmed, s.audioConnected
                )
            }
            Logger.printInfo {
                "[Zalo][CallRecording] $methodName state=$state confirmed=${s.confirmed} " +
                    "audioConnected=${s.audioConnected} started=${s.started} shouldStart=$shouldStart"
            }
            if (shouldStart) start(s, methodName)
        }
    }

    // --- In-call activity "controls ready" secondary trigger (obfuscated) ------

    private fun hookActivities(classLoader: ClassLoader): Int {
        var count = 0
        val readyMethod = CallRecordingSymbols.ACTIVITY_READY_METHOD
        val stateField = CallRecordingSymbols.ACTIVITY_CALL_STATE_FIELD
        val connectedMethod = CallRecordingSymbols.ACTIVITY_CONNECTED_METHOD
        for (className in CallRecordingSymbols.CALL_ACTIVITIES) {
            val activityClass = loadOrNull(className, classLoader)
            if (activityClass == null) {
                Logger.printInfo {
                    "[Zalo][CallRecording] hookActivities: class $className not found on this build"
                }
                continue
            }
            if (!Activity::class.java.isAssignableFrom(activityClass)) {
                Logger.printInfo {
                    "[Zalo][CallRecording] hookActivities: $className resolved but is not an " +
                        "Activity subclass on this build, skipping"
                }
                continue
            }
            if (readyMethod.isNotEmpty()) {
                val readyHooked = hookAllByName(activityClass, readyMethod) {
                    after { param ->
                        if (stateField.isEmpty() || connectedMethod.isEmpty()) return@after
                        val callState = param.thisObject.getObjectFieldOrNull(stateField)
                        if (callState == null) {
                            Logger.printInfo {
                                "[Zalo][CallRecording] hookActivities: field $stateField not " +
                                    "found on ${param.thisObject.javaClass.name} - obfuscated " +
                                    "symbol pinned to 26.08.02 likely doesn't apply here"
                            }
                            return@after
                        }
                        val connected = callState.callMethodOrNull(connectedMethod)
                        if (connected != true) return@after
                        val session = resolveCurrentSession(param.thisObject) ?: return@after
                        synchronized(session) {
                            session.confirmed = true
                            session.audioConnected = true
                        }
                        start(session, "activity_ready")
                    }
                }
                count += readyHooked
                if (readyHooked == 0) {
                    Logger.printInfo {
                        "[Zalo][CallRecording] hookActivities: method $readyMethod not found on " +
                            "$className on this build"
                    }
                }
            }
            count += hookAllByName(activityClass, "onDestroy") {
                before { param ->
                    resolveCurrentSession(param.thisObject)?.let { stop(it, "activity_destroy") }
                }
            }
        }
        return count
    }

    // --- Notification observer feeds caller identity ---------------------------

    private fun hookNotifications(): Int {
        val nmClass = loadOrNull("android.app.NotificationManager", CallRecorder::class.java.classLoader)
        if (nmClass == null) {
            Logger.printInfo {
                "[Zalo][CallRecording] hookNotifications: android.app.NotificationManager not " +
                    "resolvable - caller name/number metadata will fall back to 'Zalo contact'"
            }
            return 0
        }
        var count = 0
        count += hookAllByName(nmClass, "notify") {
            before { param ->
                param.args?.forEach { arg ->
                    if (arg is Notification) CallRecordingMetadataStore.observe(arg)
                }
            }
        }
        return count
    }

    // --- Native start / stop ---------------------------------------------------

    private fun start(session: Session, trigger: String) {
        synchronized(session) {
            if (session.deleted || session.started ||
                !CallRecordingLifecycle.shouldStartAudio(session.confirmed, session.audioConnected)
            ) {
                Logger.printInfo {
                    "[Zalo][CallRecording] start() skipped trigger=$trigger deleted=" +
                        "${session.deleted} started=${session.started} confirmed=" +
                        "${session.confirmed} audioConnected=${session.audioConnected}"
                }
                return
            }
            val app = appContext
            if (app == null) {
                Logger.printInfo { "[Zalo][CallRecording] start() skipped trigger=$trigger: no appContext" }
                return
            }
            val native = recordMethod
            if (native == null) {
                Logger.printInfo {
                    "[Zalo][CallRecording] start() skipped trigger=$trigger: recordMethod is " +
                        "null (native $START_RECORD never resolved during install)"
                }
                return
            }
            if (session.tempFile == null) {
                session.startedAt = System.currentTimeMillis()
                session.pendingName = CallRecordingOutput.newPendingName(
                    session.startedAt, session.direction
                )
                session.tempFile = File(CallRecordingOutput.tempDirectory(app), session.pendingName)
            }
            try {
                native.invoke(null, session.peerHandle, true, session.tempFile!!.absolutePath)
                session.started = true
                Logger.printInfo {
                    "[Zalo] start record dir=${session.direction} trigger=$trigger"
                }
            } catch (t: Throwable) {
                session.tempFile?.delete()
                Logger.printException({ "[Zalo] native start failed" }, t)
            }
        }
    }

    private fun stop(session: Session, trigger: String) {
        val tempFile: File?
        val startedAt: Long
        val direction: String
        val peerUid: String
        val observed = CallRecordingMetadataStore.current()
        synchronized(session) {
            if (!session.started) {
                Logger.printInfo {
                    "[Zalo][CallRecording] stop() trigger=$trigger: session was never started, " +
                        "nothing to finalize (peerHandle=${session.peerHandle})"
                }
                session.confirmed = false
                session.audioConnected = false
                return
            }
            try {
                recordMethod?.invoke(
                    null, session.peerHandle, false,
                    session.tempFile?.absolutePath ?: ""
                )
            } catch (t: Throwable) {
                Logger.printException({ "[Zalo] native stop failed" }, t)
            } finally {
                session.started = false
            }
            val mapped = PEER_PARTNERS[session.peerHandle]
            peerUid = if (!mapped.isNullOrEmpty()) mapped
            else session.peerUid ?: observed.peerUid
            tempFile = session.tempFile
            startedAt = session.startedAt
            direction = session.direction
            CallRecordingMetadataStore.clear()
            // A ZRTC peer handle can survive across calls. Clear per-call state.
            session.confirmed = false
            session.audioConnected = false
            session.tempFile = null
            session.pendingName = null
            session.startedAt = 0L
            session.direction = "unknown"
        }
        val app = appContext
        if (app == null) {
            Logger.printInfo { "[Zalo][CallRecording] stop() trigger=$trigger: no appContext, cannot finalize" }
            return
        }
        if (tempFile == null) {
            Logger.printInfo { "[Zalo][CallRecording] stop() trigger=$trigger: no tempFile, cannot finalize" }
            return
        }
        Logger.printInfo {
            "[Zalo][CallRecording] stop() trigger=$trigger: handing off to finalizeRecording " +
                "file=${tempFile.absolutePath} exists=${tempFile.exists()} " +
                "size=${if (tempFile.exists()) tempFile.length() else -1} direction=$direction"
        }
        CallRecordingOutput.finalizeRecording(
            app, tempFile, startedAt, direction, peerUid,
            observed.displayName, observed.phoneNumber, null
        )
    }

    private fun stopForPeer(peerHandle: Long, trigger: String) {
        val session = SESSIONS_BY_PEER[peerHandle] ?: return
        synchronized(session) {
            if (trigger.endsWith("zrtc_peer_delete")) session.deleted = true
            stop(session, trigger)
            if (session.deleted) SESSIONS_BY_PEER.remove(peerHandle, session)
        }
    }

    private fun recoverPending() {
        appContext?.let { CallRecordingOutput.recoverPending(it, null) }
    }

    // --- Obfuscated peer-manager re-bind (optional) ----------------------------

    private fun resolveCurrentSession(callback: Any?): Session? {
        callback ?: return null
        val managerClassName = CallRecordingSymbols.PEER_MANAGER_CLASS
        val instanceMethod = CallRecordingSymbols.PEER_MANAGER_INSTANCE_METHOD
        val containerField = CallRecordingSymbols.PEER_CONTAINER_FIELD
        val handleField = CallRecordingSymbols.PEER_HANDLE_FIELD
        if (managerClassName.isEmpty() || instanceMethod.isEmpty() ||
            containerField.isEmpty() || handleField.isEmpty()
        ) return null
        return try {
            val managerClass = loadOrNull(managerClassName, callback.javaClass.classLoader)
            if (managerClass == null) {
                Logger.printInfo {
                    "[Zalo][CallRecording] resolveCurrentSession: class $managerClassName not " +
                        "found on this build (pinned to 26.08.02) - fallback re-bind unavailable"
                }
                return null
            }
            val manager = managerClass.callStaticMethodOrNull(instanceMethod)
            if (manager == null) {
                Logger.printInfo {
                    "[Zalo][CallRecording] resolveCurrentSession: static method " +
                        "$managerClassName#$instanceMethod not found/failed on this build"
                }
                return null
            }
            val container = manager.getObjectFieldOrNull(containerField)
            if (container == null) {
                Logger.printInfo {
                    "[Zalo][CallRecording] resolveCurrentSession: field $containerField not " +
                        "found on ${manager.javaClass.name} on this build"
                }
                return null
            }
            val peerHandle = container.getLongFieldOrNull(handleField)
            if (peerHandle == null) {
                Logger.printInfo {
                    "[Zalo][CallRecording] resolveCurrentSession: field $handleField not found " +
                        "on ${container.javaClass.name} on this build"
                }
                return null
            }
            if (peerHandle == 0L) return null
            val session = SESSIONS_BY_PEER.getOrPut(peerHandle) {
                Session(peerHandle, PEER_PARTNERS[peerHandle])
            }
            SESSIONS[callback] = session
            session
        } catch (t: Throwable) {
            Logger.printException({ "[Zalo][CallRecording] resolveCurrentSession failed" }, t)
            null
        }
    }

    // --- helpers ---------------------------------------------------------------

    private fun isPeerActive(session: Session): Boolean {
        if (session.deleted) return false
        val method = isInCallMethod ?: return true
        return try {
            val value = method.invoke(null, session.peerHandle)
            value !is Boolean || value
        } catch (t: Throwable) {
            true
        }
    }

    private fun firstInt(args: Array<Any?>?): Int {
        args?.forEach { if (it is Int) return it }
        return Int.MIN_VALUE
    }

    private fun isCallCallback(type: Class<*>): Boolean {
        var current: Class<*>? = type
        while (current != null) {
            if (CallRecordingSymbols.CALL_CALLBACK == current.name) return true
            current = current.superclass
        }
        return false
    }

    private fun loadOrNull(name: String, classLoader: ClassLoader?): Class<*>? =
        try {
            classLoader?.loadClass(name)
        } catch (t: Throwable) {
            null
        }

    private fun Class<*>.callStaticMethodOrNull(methodName: String): Any? = try {
        de.robv.android.xposed.XposedHelpers.callStaticMethod(this, methodName)
    } catch (t: Throwable) {
        null
    }

    private class Session(val peerHandle: Long, val peerUid: String?) {
        var direction: String = "unknown"
        var startedAt: Long = 0L
        var pendingName: String? = null
        var tempFile: File? = null
        var confirmed: Boolean = false
        var audioConnected: Boolean = false
        var started: Boolean = false
        @Volatile var deleted: Boolean = false
    }
}

/**
 * Tiny before/after DSL wrapper so the ported code reads like the original
 * `XpHooks.Before` / `XpHooks.After` and hooks every overload of a method name.
 */
private class HookScope {
    var before: ((de.robv.android.xposed.XC_MethodHook.MethodHookParam) -> Unit)? = null
    var after: ((de.robv.android.xposed.XC_MethodHook.MethodHookParam) -> Unit)? = null
    fun before(f: (de.robv.android.xposed.XC_MethodHook.MethodHookParam) -> Unit) { before = f }
    fun after(f: (de.robv.android.xposed.XC_MethodHook.MethodHookParam) -> Unit) { after = f }
}

private fun hookMember(member: java.lang.reflect.Member, block: HookScope.() -> Unit) {
    val scope = HookScope().apply(block)
    member.hookMethod {
        scope.before?.let { b -> before { b(it) } }
        scope.after?.let { a -> after { a(it) } }
    }
}

/** Hooks every method overload named [name] on [clazz]; returns how many were hooked. */
private fun hookAllByName(clazz: Class<*>, name: String, block: HookScope.() -> Unit): Int {
    var count = 0
    var current: Class<*>? = clazz
    val seen = HashSet<String>()
    while (current != null && current != Any::class.java) {
        for (method in current.declaredMethods) {
            if (method.name != name) continue
            val sig = method.toGenericString()
            if (!seen.add(sig)) continue
            try {
                method.isAccessible = true
                hookMember(method, block)
                count++
            } catch (t: Throwable) {
                Logger.printException({ "[Zalo] hook failed: ${current!!.name}#$name" }, t)
            }
        }
        current = current.superclass
    }
    if (count == 0) {
        Logger.printInfo {
            "[Zalo][CallRecording] hookAllByName: no method named '$name' found anywhere in " +
                "${clazz.name}'s hierarchy on this build"
        }
    }
    return count
}
