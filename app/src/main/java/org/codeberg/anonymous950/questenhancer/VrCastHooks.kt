package org.codeberg.anonymous950.questenhancer

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

/**
 * Chromecast casting (com.oculus.vrcast, VrCastService v10).
 *
 * vrcast answers WebRTC's offer for the TV itself (GoogleCastMWSBridge.jsonAnswerToSdp),
 * with no bandwidth feedback lines and only "a=fmtp:96 x-google-max-bitrate=N". WebRTC
 * then has no estimate of the link and keeps its start value (about 300 kbps), and its
 * pacer sends about 2.5 times that: roughly 750 kbps, with up to 2 s of video queued.
 *
 * This adds x-google-min-bitrate and x-google-start-bitrate to that line, so WebRTC
 * starts at, and never estimates below, the given rate. Off unless the system
 * properties are above 0 (read at each cast, no reboot):
 *   su -c setprop debug.qe.gcast_min_kbps 2000
 *   su -c setprop debug.qe.gcast_start_kbps 2500
 * vrcast's own debug.oculus.gcast.bitrate (default 3000) stays the maximum.
 */
object VrCastHooks {
    private const val TAG = "QuestEnhancer/Cast"
    private const val BRIDGE = "com.oculus.vrcast.googlecast.GoogleCastMWSBridge"
    private const val FMTP = "a=fmtp:96 "

    fun install(cl: ClassLoader) {
        val hooks = XposedBridge.hookAllMethods(
            XposedHelpers.findClass(BRIDGE, cl), "jsonAnswerToSdp",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val sdp = param.result as? String ?: return
                    val min = prop("debug.qe.gcast_min_kbps")
                    val start = prop("debug.qe.gcast_start_kbps")
                    if (min <= 0 && start <= 0) return
                    if (!sdp.contains(FMTP)) {
                        XposedBridge.log("$TAG: answer has no $FMTP line; left as is")
                        return
                    }
                    var add = ""
                    if (min > 0) add += "x-google-min-bitrate=$min;"
                    if (start > 0) add += "x-google-start-bitrate=$start;"
                    param.result = sdp.replace(FMTP, FMTP + add)
                    XposedBridge.log("$TAG: answer fmtp now ${add}x-google-max-bitrate (min $min, start $start)")
                }
            })
        XposedBridge.log("$TAG: vrcast answer hook: ${hooks.size} method(s)")
    }

    private fun prop(name: String): Int = try {
        val sp = Class.forName("android.os.SystemProperties")
        sp.getMethod("getInt", String::class.java, Int::class.javaPrimitiveType)
            .invoke(null, name, 0) as Int
    } catch (t: Throwable) {
        0
    }
}
