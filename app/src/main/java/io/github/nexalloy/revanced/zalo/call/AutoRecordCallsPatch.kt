package io.github.nexalloy.revanced.zalo.call

import android.app.Activity
import android.app.Notification
import android.content.Context
import android.os.Handler
import android.os.Looper
import app.morphe.extension.shared.Logger
import io.github.nexalloy.PatchExecutor
import io.github.nexalloy.hookMethod
import io.github.nexalloy.patch
import java.io.File
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

val AutoRecordCalls = patch(
    name = "Auto-record call audio",
    description = "Records Zalo one-to-one voice/video call audio to " +
        "Recordings/Zalo Call Recordings as M4A. Runs the native ZRTC recorder. " +
        "Default off. Recording calls may require the consent of all parties " +
        "where you live — check local law before enabling.",
) {
    val symbols = ResolvedCallSymbols(
        callbackImpl = ::callCallbackImplFingerprint.clazz,
        peerAccessor = ::callPeerManagerAccessorFingerprint.method.apply { isAccessible = true },
        peerContainer = ::callPeerContainerField.field.apply { isAccessible = true },
        peerHandle = ::callPeerHandleField.field.apply { isAccessible = true },
        notificationBuilder = ::callNotificationBuildFingerprint.method,
    )
    CallRecorder.install(this, symbols)
}

private class ResolvedCallSymbols(
    val callbackImpl: Class<*>?,
    val peerAccessor: Method?,
    val peerContainer: java.lang.reflect.Field?,
    val peerHandle: java.lang.reflect.Field?,
    val notificationBuilder: Method,
) {
    val canResolvePeer get() = peerAccessor != null && peerContainer != null && peerHandle != null
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
    @Volatile private var symbols: ResolvedCallSymbols? = null

    private const val NATIVE_DISPATCH_TIMEOUT_MS = 2_000L
    private const val CAPTURE_PROBE_DELAY_MS = 2_000L
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private val NATIVE_LOCK = Any()
    private var activeNativeSession: Session? = null

    fun install(executor: PatchExecutor, resolved: ResolvedCallSymbols) {
        symbols = resolved
        val classLoader = executor.classLoader
        appContext = executor.appContext

        val peerClass = loadOrNull(CallRecordingSymbols.PEER_JNI, classLoader)
            ?: throw IllegalStateException("PeerJNI missing; ZRTC recorder unavailable")

        recordMethod = peerClass.getDeclaredMethod(
            START_RECORD, Long::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType, String::class.java
        ).apply { isAccessible = true }
        isInCallMethod = peerClass.getDeclaredMethod(
            IS_IN_CALL, Long::class.javaPrimitiveType
        ).apply { isAccessible = true }

        var hooks = 0
        hooks += hookPeerMetadata(peerClass)
        hooks += hookAudioStreamRegistration(peerClass)
        hooks += hookPeerTermination(peerClass)
        hooks += hookCallbackRegistration(peerClass)
        hooks += hookCallbackBase(classLoader)
        hooks += hookCallbackImpl(resolved.callbackImpl)
        hooks += hookActivities(classLoader)
        hooks += hookNotifications(resolved.notificationBuilder)

        check(hooks > 0) { "No call lifecycle hooks installed" }
        Logger.printInfo { "[Zalo] Auto-record installed $hooks hooks" }

        recoverPending()
    }


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

    private fun hookCallbackBase(classLoader: ClassLoader): Int =
        loadOrNull(CallRecordingSymbols.CALL_CALLBACK, classLoader)
            ?.let { hookCallbackClass(it) } ?: 0

    private fun hookCallbackImpl(callbackImpl: Class<*>?): Int =
        callbackImpl?.let { hookCallbackClass(it) } ?: 0

    private fun hookCallbackClass(callbackClass: Class<*>): Int {
        var count = 0
        var current: Class<*>? = callbackClass
        while (current != null && current != Any::class.java) {
            for (method in current.declaredMethods) {
                val name = method.name
                if (Modifier.isAbstract(method.modifiers) ||
                    !CallRecordingLifecycle.observes(name)
                ) continue
                val signature = current.name + "#" + method.toGenericString()
                if (!HOOKED_CALLBACKS.add(signature)) continue
                try {
                    method.isAccessible = true
                    hookMember(method, callbackHook(method))
                    count++
                } catch (t: Throwable) {
                    HOOKED_CALLBACKS.remove(signature)
                    Logger.printException({ "[Zalo] callback hook failed: $signature" }, t)
                }
            }
            current = current.superclass
        }
        return count
    }

    private fun callbackHook(method: Method): HookScope.() -> Unit = {
        before { param ->
            val methodName = method.name
            var session = SESSIONS[param.thisObject]
            if (session == null || CallRecordingLifecycle.beginsCall(methodName)) {
                resolveCurrentSession(param.thisObject)?.let { session = it }
            }
            val s = session
            if (s == null || s.deleted) return@before
            when (methodName) {
                "onIncomingCall" -> { s.direction = "incoming"; return@before }
                "onMakeCall" -> { s.direction = "outgoing"; return@before }
            }
            val state = firstInt(param.args)
            if (CallRecordingLifecycle.isVideoState(methodName)) return@before
            if (CallRecordingLifecycle.shouldStopAudio(methodName, state)) {
                stop(s, methodName)
                return@before
            }
            var shouldStart: Boolean
            synchronized(s) {
                if (CallRecordingLifecycle.confirmsCall(methodName)) s.confirmed = true
                if (CallRecordingLifecycle.connectsAudio(methodName, state)) {
                    s.audioConnected = true
                }
                if (CallRecordingLifecycle.connectsCall(methodName, state)) {
                    s.confirmed = true
                    s.audioConnected = true
                }
                shouldStart = CallRecordingLifecycle.shouldStartAudio(
                    s.confirmed, s.audioConnected
                )
            }
            if (shouldStart) start(s, methodName)
        }
    }


    private fun hookActivities(classLoader: ClassLoader): Int {
        var count = 0
        for (className in CallRecordingSymbols.CALL_ACTIVITIES) {
            val activityClass = loadOrNull(className, classLoader) ?: continue
            if (!Activity::class.java.isAssignableFrom(activityClass)) continue
            count += hookAllByName(activityClass, "onDestroy") {
                before {
                    SESSIONS_BY_PEER.values.filter { it.started }
                        .forEach { stop(it, "activity_destroy") }
                }
            }
        }
        return count
    }


    private fun hookNotifications(builder: Method): Int {
        hookMember(builder) {
            after { param -> (param.result as? Notification)?.let(CallRecordingMetadataStore::observe) }
        }
        return 1
    }


    private fun start(session: Session, trigger: String) {
        synchronized(session) {
            if (session.deleted || session.started ||
                !CallRecordingLifecycle.shouldStartAudio(session.confirmed, session.audioConnected)
            ) return
        }
        runOnMainAndWait { startOnMain(session, trigger) }
    }

    private fun startOnMain(session: Session, trigger: String) {
        synchronized(NATIVE_LOCK) {
            val active = activeNativeSession
            if (active != null && active !== session) {
                if (synchronized(active) { active.started }) return
                activeNativeSession = null
            }
            synchronized(session) {
                if (session.deleted || session.started ||
                    !CallRecordingLifecycle.shouldStartAudio(session.confirmed, session.audioConnected)
                ) return
                val app = appContext ?: return
                val native = recordMethod ?: return
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
                    activeNativeSession = session
                    scheduleCaptureProbe(session, ++session.startAttempts)
                    Logger.printInfo {
                        "[Zalo] start record dir=${session.direction} trigger=$trigger"
                    }
                } catch (t: Throwable) {
                    session.tempFile?.delete()
                    Logger.printException({ "[Zalo] native start failed" }, t)
                }
            }
        }
    }

    private fun scheduleCaptureProbe(session: Session, attempt: Int) {
        mainHandler.postDelayed({ probeCapture(session, attempt) }, CAPTURE_PROBE_DELAY_MS)
    }

    private fun probeCapture(session: Session, attempt: Int) {
        synchronized(NATIVE_LOCK) {
            synchronized(session) {
                if (!session.started || session.startAttempts != attempt ||
                    activeNativeSession !== session
                ) return
                val stale = session.tempFile ?: return
                val size = if (stale.isFile) stale.length() else 0L
                if (!CallRecordingLifecycle.shouldReattachEmptyCapture(attempt, size)) return
                val native = recordMethod ?: return
                try {
                    native.invoke(null, session.peerHandle, false, stale.absolutePath)
                } catch (t: Throwable) {
                    Logger.printException({ "[Zalo] native re-attach stop failed" }, t)
                    return
                }
                session.startedAt = System.currentTimeMillis()
                session.pendingName = CallRecordingOutput.newPendingName(
                    session.startedAt, session.direction
                )
                val next = File(stale.parentFile, session.pendingName!!)
                try {
                    native.invoke(null, session.peerHandle, true, next.absolutePath)
                    session.tempFile = next
                    stale.delete()
                    scheduleCaptureProbe(session, ++session.startAttempts)
                } catch (t: Throwable) {
                    session.tempFile = stale
                    Logger.printException({ "[Zalo] native re-attach failed" }, t)
                }
            }
        }
    }

    private fun stop(session: Session, trigger: String) {
        synchronized(session) {
            if (!session.started) {
                session.confirmed = false
                session.audioConnected = false
                return
            }
        }
        runOnMainAndWait { stopOnMain(session) }
    }

    private fun stopOnMain(session: Session) {
        val tempFile: File?
        val startedAt: Long
        val direction: String
        val peerUid: String
        val observed = CallRecordingMetadataStore.current()
        synchronized(NATIVE_LOCK) {
            synchronized(session) {
                if (!session.started) {
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
                    if (activeNativeSession === session) activeNativeSession = null
                }
                val mapped = PEER_PARTNERS[session.peerHandle]
                peerUid = if (!mapped.isNullOrEmpty()) mapped
                else session.peerUid ?: observed.peerUid
                tempFile = session.tempFile
                startedAt = session.startedAt
                direction = session.direction
                CallRecordingMetadataStore.clear()
                session.confirmed = false
                session.audioConnected = false
                session.tempFile = null
                session.pendingName = null
                session.startedAt = 0L
                session.startAttempts = 0
                session.direction = "unknown"
            }
        }
        val app = appContext ?: return
        if (tempFile == null) return
        CallRecordingOutput.finalizeRecording(
            app, tempFile, startedAt, direction, peerUid,
            observed.displayName, observed.phoneNumber, null
        )
    }

    private fun runOnMainAndWait(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action()
            return
        }
        val done = CountDownLatch(1)
        val cancelled = AtomicBoolean()
        val dispatch = Runnable {
            try {
                if (!cancelled.get()) action()
            } finally {
                done.countDown()
            }
        }
        if (!mainHandler.post(dispatch)) return
        try {
            if (!done.await(NATIVE_DISPATCH_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                cancelled.set(true)
                mainHandler.removeCallbacks(dispatch)
                Logger.printInfo { "[Zalo] native recorder dispatch timed out" }
            }
        } catch (e: InterruptedException) {
            cancelled.set(true)
            mainHandler.removeCallbacks(dispatch)
            Thread.currentThread().interrupt()
        }
    }

    private fun stopForPeer(peerHandle: Long, trigger: String) {
        val session = SESSIONS_BY_PEER[peerHandle] ?: return
        val deleting = trigger.endsWith("zrtc_peer_delete")
        if (deleting) session.deleted = true
        stop(session, trigger)
        if (deleting) SESSIONS_BY_PEER.remove(peerHandle, session)
    }

    private fun recoverPending() {
        appContext?.let { CallRecordingOutput.recoverPending(it, null) }
    }


    private fun resolveCurrentSession(callback: Any?): Session? {
        callback ?: return null
        val resolved = symbols ?: return null
        if (!resolved.canResolvePeer) return null
        return try {
            val manager = resolved.peerAccessor!!.invoke(null) ?: return null
            val container = resolved.peerContainer!!.get(manager) ?: return null
            val peerHandle = resolved.peerHandle!!.getLong(container)
            if (peerHandle == 0L) return null
            val session = SESSIONS_BY_PEER.getOrPut(peerHandle) {
                Session(peerHandle, PEER_PARTNERS[peerHandle])
            }
            SESSIONS[callback] = session
            session
        } catch (t: Throwable) {
            null
        }
    }


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


    private class Session(val peerHandle: Long, val peerUid: String?) {
        var direction: String = "unknown"
        var startedAt: Long = 0L
        var pendingName: String? = null
        var tempFile: File? = null
        var confirmed: Boolean = false
        var audioConnected: Boolean = false
        var started: Boolean = false
        var startAttempts: Int = 0
        @Volatile var deleted: Boolean = false
    }
}

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
    return count
}
