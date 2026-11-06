package org.codeberg.anonymous950.questenhancer

import android.content.Context
import android.content.pm.ApplicationInfo
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

/**
 * Dock and Library tweaks, after LucentW's systemux-accountless (Quest 1, v50),
 * done as hooks instead of a patched SystemUX.apk:
 *
 * 1. Up to [MAX_PINNED] pinned apps on the dock instead of Meta's hard-coded 5.
 * 2. No default apps (Messenger, Share) put on an empty dock.
 * 3. Library without a Meta account: when Meta's app service (OCMS) knows no apps,
 *    the locally installed apps are listed instead, with their own icons. With an
 *    account nothing changes (sideloads stay in Unknown Sources, where MainHook
 *    adds Pin/Unpin).
 */
object DockLibraryHooks {
    private const val TAG = "QuestEnhancer/Dock"
    private const val MAX_PINNED = 10
    private const val PINNED_PREF_KEY = "aui_bar_apps_pinned"

    private const val DYNAMIC_APPS_VM = "com.oculus.panelapp.anytimeui.bar.apps.DynamicAppsViewModel"
    private const val OVR_LIBRARY = "com.oculus.libraryapi.OVRLibrary"
    private const val UNKNOWN_SOURCES_UTIL = $$"com.oculus.vrshell.util.UnknownSourcesUtil$Companion"
    private const val DYNAMIC_APPS_UTIL = "com.oculus.panelapp.anytimeui.utils.DynamicAppsUtil"
    private const val FAKE_APP_UTIL = "com.oculus.vrshell.util.FakeAppUtil"
    private const val APP = "com.oculus.library.model.App"

    // The dock's view model, for the pinned count AppStateUtil reports.
    @Volatile
    private var dockViewModel: Any? = null

    // Set while our own code (or Meta's unknown-sources scan) needs OCMS's real list.
    private val rawOcms = ThreadLocal<Boolean>()

    // What the last raw OCMS read on this thread returned: was it empty?
    private val lastOcmsEmpty = ThreadLocal<Boolean>()

    // Did OCMS know no apps at its last read? (No Meta account.)
    @Volatile
    private var accountless = false

    @Volatile
    private var loggedState = false

    /** Is a Meta account on the headset? (`dumpsys account` shows it as type com.oculus.) */
    private fun hasMetaAccount(context: Context): String = try {
        android.accounts.AccountManager.get(context).getAccountsByType("com.oculus").isNotEmpty().toString()
    } catch (t: Throwable) {
        "unknown ($t)"
    }

    fun install(cl: ClassLoader) {
        hook("pin cap") { hookPinCap(cl) }
        hook("default pins") { hookDefaultPins(cl) }
        hook("accountless library") { hookAccountlessLibrary(cl) }
    }

    private fun hook(name: String, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            // One missing method must not take the other tweaks (or SystemUX) down.
            XposedBridge.log("$TAG: $name not installed: $t")
        }
    }

    private fun pinnedCount(): Int {
        val vm = dockViewModel ?: return 0
        val pinned = XposedHelpers.callMethod(vm, "getPinnedApps") as Map<*, *>
        return pinned.size
    }

    // --- 1. pinned cap --------------------------------------------------------------
    private fun hookPinCap(cl: ClassLoader) {
        val vmClass = XposedHelpers.findClass(DYNAMIC_APPS_VM, cl)
        XposedBridge.hookAllConstructors(vmClass, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                dockViewModel = param.thisObject
            }
        })

        // The saved list is cut to the cap passed here (5).
        XposedHelpers.findAndHookMethod(
            vmClass, "writeAppListPrefValue",
            java.util.List::class.java, String::class.java, Int::class.javaPrimitiveType,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (param.args[1] == PINNED_PREF_KEY) param.args[2] = MAX_PINNED
                }
            })

        // Pin requests from the Library menu: "size < 5" decides between add and remove.
        XposedHelpers.findAndHookMethod(
            $$"$$DYNAMIC_APPS_VM$initializeAppStateObserver$1", cl,
            "onPinnedAppStateShouldChange",
            String::class.java, Boolean::class.javaPrimitiveType,
            object : XC_MethodReplacement() {
                override fun replaceHookedMethod(param: MethodHookParam): Any? {
                    val vm = XposedHelpers.getObjectField(param.thisObject, "this$0")
                    val pinned = XposedHelpers.callMethod(vm, "getPinnedApps") as Map<*, *>
                    val pkg = param.args[0] as String
                    if (param.args[1] as Boolean && pinned.size < MAX_PINNED) {
                        XposedHelpers.callMethod(vm, "addPinnedApp", pkg)
                    } else {
                        XposedHelpers.callMethod(vm, "removePinnedApp", pkg)
                    }
                    return null
                }
            })

        // "Dock is full" (greys out Pin in the Library) is reported as "size == 5".
        XposedHelpers.findAndHookMethod(
            "com.oculus.vrshell.util.AppStateUtil", cl, "onPinnedAppStateChanged",
            String::class.java, Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (param.args[1] as Boolean && dockViewModel != null) {
                        param.args[2] = pinnedCount() >= MAX_PINNED
                    }
                }
            })
    }

    // --- 2. no default pins ---------------------------------------------------------
    private fun hookDefaultPins(cl: ClassLoader) {
        XposedHelpers.findAndHookMethod(
            DYNAMIC_APPS_VM, cl, "getDefaultPinnedApps",
            object : XC_MethodReplacement() {
                override fun replaceHookedMethod(param: MethodHookParam): Any = LinkedHashMap<Any?, Any?>()
            })
    }

    // --- 3. Library without a Meta account -----------------------------------------
    private fun hookAccountlessLibrary(cl: ClassLoader) {
        val ovrLibrary = XposedHelpers.findClass(OVR_LIBRARY, cl)
        val appClass = XposedHelpers.findClass(APP, cl)

        XposedHelpers.findAndHookMethod(ovrLibrary, "getAllApps", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val ocms = param.result as Array<*>?
                val empty = ocms == null || ocms.isEmpty()
                if (empty != accountless || !loggedState) {
                    loggedState = true
                    val context = XposedHelpers.getObjectField(param.thisObject, "mContext") as Context
                    XposedBridge.log("$TAG: Meta account (com.oculus): ${hasMetaAccount(context)}, Meta app list: ${ocms?.size ?: 0} apps, local-apps fallback: ${if (empty) "on" else "off"}")
                }
                accountless = empty
                if (rawOcms.get() == true) {
                    lastOcmsEmpty.set(empty)
                    return
                }
                if (!empty) return
                val context = XposedHelpers.getObjectField(param.thisObject, "mContext") as Context
                val local = localApps(cl, context)
                val result = java.lang.reflect.Array.newInstance(appClass, local.size)
                local.forEachIndexed { i, app -> java.lang.reflect.Array.set(result, i, app) }
                param.result = result
            }
        })

        XposedHelpers.findAndHookMethod(ovrLibrary, "getApp", String::class.java, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                // Only without an account: with one, OCMS answers for its own apps.
                if (param.result != null || rawOcms.get() == true || !accountless) return
                val pkg = param.args[0] as String? ?: return
                val context = XposedHelpers.getObjectField(param.thisObject, "mContext") as Context
                val info = try {
                    context.packageManager.getApplicationInfo(pkg, 0)
                } catch (e: Exception) {
                    return
                }
                if (info.flags and ApplicationInfo.FLAG_SYSTEM != 0) return
                param.result = appForPackage(cl, context, pkg)
            }
        })

        // Meta's unknown-sources scan reads OCMS to leave out the apps it knows: give it
        // the real list, and note whether it was empty.
        XposedHelpers.findAndHookMethod(UNKNOWN_SOURCES_UTIL, cl, "getUnknownSources", Context::class.java,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    param.setObjectExtra("prevRaw", rawOcms.get())
                    rawOcms.set(true)
                    lastOcmsEmpty.set(false)
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    rawOcms.set(param.getObjectExtra("prevRaw") as Boolean?)
                }
            })

        // Without an account the local apps are already in the main grid: keep the
        // Library's Unknown Sources section empty so nothing is listed twice.
        XposedHelpers.findAndHookMethod(
            "com.oculus.panelapp.library.fetchers.LibraryUnknownSourcesFetcher", cl, "fetchAppsSync",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (lastOcmsEmpty.get() == true && param.result != null) {
                        param.result = ArrayList<Any?>()
                    }
                }
            })

        // Local apps have no store art: draw their own icon on the Library tile.
        val fakeAppCompanion = XposedHelpers.getStaticObjectField(
            XposedHelpers.findClass(FAKE_APP_UTIL, cl), "Companion"
        )
        XposedHelpers.findAndHookMethod(
            "com.oculus.panelapp.library.adapters.LibraryContentAdapter", cl, "loadImage",
            APP, "com.oculus.panelapp.library.databinding.LibraryAppTileBinding",
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val app = param.args[0] ?: return
                    val binding = param.args[1] ?: return
                    if (!(XposedHelpers.callMethod(fakeAppCompanion, "isFromUnknownSource", app) as Boolean)) return
                    val context = XposedHelpers.getObjectField(param.thisObject, "mContext") as Context
                    val icon = XposedHelpers.callMethod(fakeAppCompanion, "getAppIconForUnknownPackage", context, app)
                        ?: return
                    val image = XposedHelpers.getObjectField(binding, "imageView") as android.widget.ImageView
                    image.scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                    image.setImageDrawable(icon as android.graphics.drawable.Drawable)
                    XposedHelpers.callMethod(binding, "setImageAvailable", true)
                    param.result = null
                }
            })
    }

    /** The locally installed (non-system) apps, as Library apps. */
    private fun localApps(cl: ClassLoader, context: Context): List<Any> {
        val companion = XposedHelpers.getStaticObjectField(
            XposedHelpers.findClass("com.oculus.vrshell.util.UnknownSourcesUtil", cl), "Companion"
        )
        val sources = XposedHelpers.callMethod(companion, "getUnknownSources", context) as List<*>
        return sources.mapNotNull { source ->
            val pkg = XposedHelpers.callMethod(source, "getPackageName") as String? ?: return@mapNotNull null
            appForPackage(cl, context, pkg)
        }
    }

    private fun appForPackage(cl: ClassLoader, context: Context, pkg: String): Any? {
        val util = XposedHelpers.newInstance(XposedHelpers.findClass(DYNAMIC_APPS_UTIL, cl))
        return XposedHelpers.callMethod(util, "getAppForUnknownPackageName", context, pkg)
    }
}
