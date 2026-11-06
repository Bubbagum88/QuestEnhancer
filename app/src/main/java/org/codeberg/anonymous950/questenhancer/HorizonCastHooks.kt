package org.codeberg.anonymous950.questenhancer

import android.content.Context
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.io.File

/**
 * Casting diagnostics for Horizon (com.oculus.horizon, v50.0.0.198.257).
 *
 * Casting to a Chromecast TV sends 2-7 frames a second: the screen capture feeds
 * WebRTC 24 frames a second, but only about one in five reaches the video encoder.
 * WebRTC (liboculus_p2p.so) drops them in one of two places, and logs neither:
 * the encoder pause (send queue too long, or network down) or the frame dropper.
 *
 * This turns on WebRTC's own diagnostics, changing nothing else:
 * 1. its detailed log, to logcat (tags libjingle / WebRTC);
 * 2. its trace events during each cast, written when the cast stops to
 *    /data/data/com.oculus.horizon/files/webrtc-cast-trace.json. An "EncoderPaused"
 *    event there means the encoder pause is what drops the frames.
 */
object HorizonCastHooks {
    private const val TAG = "QuestEnhancer/Cast"
    private const val PLATFORM_PLUGIN = "com.oculus.horizon.platformplugin.PlatformPluginManager"
    private const val LOGGING = "org.webrtc.Logging"
    private const val FACTORY = "org.webrtc.PeerConnectionFactory"
    private const val TRACE_FILE = "webrtc-cast-trace.json"

    // org.webrtc.PeerConnectionFactory's static initializer loads this library, which
    // Horizon does not have: its WebRTC is in liboculus_p2p.so.
    private const val STOCK_WEBRTC_LIB = "jingle_peerconnection_so"

    // webrtc::TraceLevel: state info, warning, error, critical, debug, info.
    private const val TRACE_LEVELS = 0x0001 or 0x0002 or 0x0004 or 0x0008 or 0x0800 or 0x1000

    // rtc::LoggingSeverity LS_VERBOSE (org.webrtc.Logging.Severity ordinal).
    private const val LS_VERBOSE = 1

    @Volatile
    private var filesDir: File? = null

    @Volatile
    private var loggingOn = false

    @Volatile
    private var tracing = false

    fun install(cl: ClassLoader) {
        hook("webrtc logging") { hookLibraryLoad(cl) }
        hook("cast trace") { hookCastTrace(cl) }
    }

    private fun hook(name: String, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            XposedBridge.log("$TAG: $name not installed: $t")
        }
    }

    // PeerConnectionFactory's static initializer loads jingle_peerconnection_so, which
    // Horizon does not have; its native methods are already there, in liboculus_p2p.so.
    // So that one load is skipped, only while the tracer starts. Runtime.loadLibrary0
    // (Class, String) is hooked, not System.loadLibrary: System.loadLibrary finds the
    // library path from its caller's class loader, and a hooked call's caller is the
    // hook framework, so every library load in Horizon failed.
    private fun <T> withoutStockLibrary(block: () -> T): T {
        val unhooks = XposedBridge.hookAllMethods(
            Runtime::class.java, "loadLibrary0",
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val args = param.args
                    if (args.size == 2 && args[0] is Class<*> && args[1] == STOCK_WEBRTC_LIB) {
                        param.result = null
                    }
                }
            })
        try {
            return block()
        } finally {
            unhooks.forEach { it.unhook() }
        }
    }

    // PlatformPluginManager's constructor loads libovrplatformplugin.so, which needs
    // liboculus_p2p.so; WebRTC's JNI methods work from then on.
    private fun hookLibraryLoad(cl: ClassLoader) {
        XposedBridge.hookAllConstructors(
            XposedHelpers.findClass(PLATFORM_PLUGIN, cl),
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (loggingOn) return
                    filesDir = (param.args[0] as Context).filesDir
                    try {
                        val logging = XposedHelpers.findClass(LOGGING, cl)
                        XposedHelpers.callStaticMethod(
                            logging, "nativeEnableTracing", "logcat:", TRACE_LEVELS, LS_VERBOSE
                        )
                        XposedBridge.log("$TAG: WebRTC detailed logging on")
                    } catch (t: Throwable) {
                        XposedBridge.log("$TAG: WebRTC logging failed: $t")
                    }
                    try {
                        withoutStockLibrary {
                            XposedHelpers.callStaticMethod(
                                XposedHelpers.findClass(FACTORY, cl), "initializeInternalTracer"
                            )
                        }
                        loggingOn = true
                        XposedBridge.log("$TAG: WebRTC tracer ready")
                    } catch (t: Throwable) {
                        XposedBridge.log("$TAG: WebRTC tracer failed: $t")
                    }
                }
            })
    }

    // Trace events from the start of a cast to its end.
    private fun hookCastTrace(cl: ClassLoader) {
        val int = Int::class.javaPrimitiveType
        val str = String::class.java
        val factory = XposedHelpers.findClass(FACTORY, cl)

        XposedHelpers.findAndHookMethod(
            PLATFORM_PLUGIN, cl, "nativeInitializeForCastingWithTurnServers",
            str, str, str, int, int, int, int,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val dir = filesDir ?: return
                    if (!loggingOn || tracing) return
                    val file = File(dir, TRACE_FILE).path
                    tracing = XposedHelpers.callStaticMethod(
                        factory, "startInternalTracingCapture", file
                    ) as Boolean
                    XposedBridge.log(
                        "$TAG: cast ${param.args[4]}x${param.args[3]} ${param.args[5]} fps " +
                            "${param.args[6]} kbps; trace to $file: $tracing"
                    )
                }
            })

        XposedHelpers.findAndHookMethod(
            PLATFORM_PLUGIN, cl, "nativeStopLiveStreaming",
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!tracing) return
                    XposedHelpers.callStaticMethod(factory, "stopInternalTracingCapture")
                    tracing = false
                    XposedBridge.log("$TAG: cast trace written")
                }
            })
    }
}
