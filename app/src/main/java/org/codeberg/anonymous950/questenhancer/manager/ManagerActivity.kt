package org.codeberg.anonymous950.questenhancer.manager

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.edit
import androidx.core.graphics.toColorInt
import androidx.core.view.isGone

/**
 * Quest 1 Manager, laid out like Quest Settings: areas on the left, rows on the right.
 * Tweaks with controls (catalog "controls") can be changed; the rest only show their state.
 */
class ManagerActivity : Activity() {
    private val bg = Color.rgb(0x1C, 0x1E, 0x21)
    private val panel = Color.rgb(0x26, 0x29, 0x2D)
    private val selected = Color.rgb(0x3A, 0x3E, 0x44)
    private val dim = Color.rgb(0xA8, 0xAD, 0xB4)
    private val accent = Color.rgb(0x1A, 0x73, 0xE8)

    private var tweaks: List<Tweak> = emptyList()
    private val states = mutableMapOf<String, RootShell.Result>()
    private var bundled: Map<String, Bundled> = emptyMap()
    /** Module tweaks: the module's state on the headset; absent = not installed. */
    private val installed = mutableMapOf<String, Installed>()
    private lateinit var nav: LinearLayout
    private lateinit var content: LinearLayout
    private var area = ""
    private var loading = false
    /** A change was made that only shows after a restart (catalog "after": "reboot"). */
    private var rebootNeeded = false
    private var systemUxRestartNeeded = false

    private val preferences by lazy { getSharedPreferences("xposed_preferences", @Suppress("DEPRECATION") @SuppressLint("WorldReadableFiles") MODE_WORLD_READABLE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.setBackgroundColor(bg)
        tweaks = Catalog.load(this)
        bundled = Catalog.bundled(this)
        area = savedInstanceState?.getString("area") ?: tweaks.firstOrNull()?.area ?: ""

        nav = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(20), dp(12), dp(12))
        }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(28), dp(20), dp(28), dp(20))
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(ScrollView(this@ManagerActivity).apply {
                setBackgroundColor(panel)
                addView(nav)
            }, LinearLayout.LayoutParams(dp(260), LinearLayout.LayoutParams.MATCH_PARENT))
            addView(ScrollView(this@ManagerActivity).apply { addView(content) },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        }
        setContentView(root)
        drawNav()
        drawContent()
        refresh(retryRoot = false)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("area", area)
    }

    private fun drawNav() {
        nav.removeAllViews()
        nav.addView(label("Quest Enhancer", 20f, Color.WHITE, bold = true).apply {
            setPadding(dp(12), 0, dp(12), dp(2))
        })
        nav.addView(label("by Anonymous950 · Quest 1 fork by Bubbagum88", 12f, dim).apply {
            setPadding(dp(12), 0, dp(12), dp(16))
        })
        for (a in tweaks.map { it.area }.distinct()) {
            nav.addView(label(a, 16f, Color.WHITE).apply {
                setPadding(dp(16), dp(12), dp(16), dp(12))
                background = rounded(if (a == area) selected else Color.TRANSPARENT)
                setOnClickListener { area = a; drawNav(); drawContent() }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(4)
            })
        }
        nav.addView(label("Refresh", 16f, dim).apply {
            setPadding(dp(16), dp(24), dp(16), dp(12))
            setOnClickListener { refresh(retryRoot = true) }
        })
    }

    private fun drawContent() {
        content.removeAllViews()
        content.addView(label(area, 24f, Color.WHITE, bold = true).apply { setPadding(0, 0, 0, dp(4)) })
        content.addView(label(
            if (loading) "Reading from the headset..." else "What is set on this headset now.",
            13f, dim).apply { setPadding(0, 0, 0, dp(16)) })
        if (!loading && RootShell.isRefused) content.addView(noRootRow())
        if (rebootNeeded) content.addView(rebootRow())
        if (systemUxRestartNeeded) content.addView(restartSystemUxRow())
        for (t in tweaks.filter { it.area == area }) content.addView(row(t))
        if (area == "QuestEnhancer" && !loading && !RootShell.isRefused) content.addView(removeAllRow())
    }

    private fun row(t: Tweak): View {
        val r = states[t.id]
        // No switch for a check that failed (root refused, timed out): its state is unknown.
        val on = r?.takeIf { it.code == 0 }?.let { t.onIf?.containsMatchIn(it.output) }
        val b = bundled[t.bundle]
        val have = installed[t.id]
        val status = when {
            r == null -> "Checking..."
            have?.removing == true -> "Uninstalled: it goes at the next restart."
            have?.pending == true -> "Installed${b?.let { " " + it.version } ?: ""}: restart the headset to finish."
            have?.off == true -> "Off: Magisk skips it from the next restart."
            b != null && have == null && r.code != 0 -> "Not installed. Install adds it with Magisk."
            r.code != 0 && t.install.isNotEmpty() -> t.install
            r.code != 0 -> r.output.ifEmpty { "Couldn't read (exit ${r.code})" }
            t.controls.isNotEmpty() && on == null -> ""
            on == null -> r.output.lines().joinToString(" · ")
            on -> "On"
            else -> "Off"
        }
        val details = label(buildString {
            append(t.summary).append("\n\n")
            append("By ").append(t.author).append(" · ").append(t.licence).append(" · risk: ").append(t.risk)
            if (t.url.isNotEmpty()) append('\n').append(t.url)
            if (r != null && r.output.isNotEmpty()) append("\n\n").append(r.output)
        }, 13f, dim).apply { visibility = View.GONE; setPadding(0, dp(8), 0, 0) }

        val text = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(label(t.name, 17f, Color.WHITE))
            if (status.isNotEmpty()) addView(label(status, 13f, dim))
            addView(details)
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(text, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            if (on != null) addView(Switch(this@ManagerActivity).apply {
                isChecked = on
                isEnabled = false
            })
            setOnClickListener {
                details.visibility = if (details.isGone) View.VISIBLE else View.GONE
            }
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(14), dp(20), dp(14))
            background = rounded(panel)
            addView(header)
            if (r != null) moduleBar(t, b, have)?.let { addView(it) }
            // Controls only once the state is read, so they never show a guess.
            if (r != null && r.code == 0) for (c in t.controls) addView(control(t, c, r.output))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(8)
            }
        }
    }

    /** One setting: a sub-heading, a switch on the right, a row of choices, or a button. */
    private fun control(t: Tweak, c: Control, output: String): View {
        if (c.type == "header") return label(c.label, 13f, dim, bold = true).apply {
            setPadding(0, dp(22), 0, 0)
            isAllCaps = true
        }
        val inline = c.type == "switch" || c.type == "button"
        val box = LinearLayout(this).apply {
            orientation = if (inline) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            gravity = if (inline) Gravity.CENTER_VERTICAL else Gravity.NO_GRAVITY
            setPadding(0, dp(14), 0, 0)
        }
        val text = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(label(c.label, 15f, Color.WHITE))
            if (c.hint.isNotEmpty()) addView(label(c.hint, 12f, dim))
        }
        box.addView(text,
            if (inline) LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            else LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        when (c.type) {
            "switch" -> {
                val isOn = if (c.preference.isNotEmpty()) {
                    preferences.getBoolean(c.preference, c.preferenceDefault)
                } else {
                    c.state?.containsMatchIn(output) == true
                }
                box.addView(Switch(this).apply {
                    isChecked = isOn
                    setOnCheckedChangeListener { _, checked ->
                        // Cancelled: redraw, which puts the switch back.
                        confirmThen(if (checked) c.confirmOn else c.confirmOff, { drawContent() }) {
                            if (c.preference.isNotEmpty()) {
                                preferences.edit(commit = true) {
                                    putBoolean(c.preference, checked)
                                }

                                if (checked && c.on.isNotEmpty()) act(t, c.on)
                                else if (!checked && c.off.isNotEmpty()) act(t, c.off)
                                else act(t, "")
                                drawContent()
                            } else {
                                if (checked && c.on.isNotEmpty()) act(t, c.on)
                                else if (!checked && c.off.isNotEmpty()) act(t, c.off)
                                else act(t, "")
                            }
                        }
                    }
                })
            }
            "button" -> box.addView(label(c.on.ifEmpty { "Run" }, 14f, Color.WHITE, bold = true).apply {
                setPadding(dp(16), dp(8), dp(16), dp(8))
                background = rounded(accent)
                setOnClickListener { confirmThen(c.confirm) { act(t, c.run, showOutput = true) } }
            })
            "slider" -> {
                // Saved for the hooks when let go; SystemUX reads it when it starts.
                val now = preferences.getInt(c.preference, c.valueDefault).coerceIn(c.min, c.max)
                val value = label(now.toString(), 15f, Color.WHITE, bold = true)
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, dp(8), 0, 0)
                }
                row.addView(SeekBar(this).apply {
                    max = c.max - c.min
                    progress = now - c.min
                    setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                        override fun onProgressChanged(bar: SeekBar, p: Int, fromUser: Boolean) {
                            value.text = (c.min + p).toString()
                        }
                        override fun onStartTrackingTouch(bar: SeekBar) {}
                        override fun onStopTrackingTouch(bar: SeekBar) {
                            preferences.edit(commit = true) { putInt(c.preference, c.min + bar.progress) }
                            act(t, "")
                            drawContent()
                        }
                    })
                }, LinearLayout.LayoutParams(dp(360), LinearLayout.LayoutParams.WRAP_CONTENT))
                row.addView(value, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(12) })
                box.addView(row)
            }
            "choice" -> {
                val current = c.state?.find(output)?.groupValues?.getOrNull(1)
                val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                val lp = { LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(8) } }
                var matched = false
                for (o in c.options) {
                    val chosen = o.value.equals(current, ignoreCase = true)
                    matched = matched || chosen
                    chips.addView(chip(o, chosen) {
                        confirmThen(c.confirm) { act(t, c.run.replace("{value}", o.value)) }
                    }, lp())
                }
                if (c.wheel) {
                    // A colour not among the presets shows on the Custom chip.
                    val now = current?.takeIf { !matched }?.let { runCatching { it.toColorInt() }.getOrNull() }
                    chips.addView(chip(Option("Custom", "", now ?: Color.GRAY), now != null) {
                        pickColor(now ?: Color.WHITE) { hex -> act(t, c.run.replace("{value}", hex)) }
                    }, lp())
                }
                box.addView(HorizontalScrollView(this).apply {
                    isHorizontalScrollBarEnabled = false
                    setPadding(0, dp(8), 0, 0)
                    addView(chips)
                })
            }
        }
        return box
    }

    /** The tweak's Magisk module id: the bundled module's, or the catalog's "module". */
    private fun moduleId(t: Tweak) = bundled[t.bundle]?.id ?: t.module

    /**
     * The module row: Install (bundled, not on the headset), or once it is there an on/off
     * switch, Update (the APK carries a newer versionCode) and Uninstall. Null for a tweak
     * that is no module.
     */
    private fun moduleBar(t: Tweak, b: Bundled?, have: Installed?): View? {
        val id = moduleId(t)
        if (id.isEmpty() || (have == null && b == null)) return null
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(14), 0, 0)
        }
        fun text(s: String) = box.addView(label(s, 15f, Color.WHITE),
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        fun button(s: String, onTap: () -> Unit) = box.addView(label(s, 14f, Color.WHITE, bold = true).apply {
            setPadding(dp(16), dp(8), dp(16), dp(8))
            background = rounded(accent)
            setOnClickListener { onTap() }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            leftMargin = dp(8)
        })
        val m = "/data/adb/modules/$id"
        when {
            have == null -> {
                text("Version ${b!!.version} is in this app.")
                button("Install") {
                    confirmThen("Install ${t.name} (${b.version}) with Magisk? Risk: ${t.risk}. It works after a restart.") {
                        install(t, b)
                    }
                }
            }
            have.removing -> {
                text("Changed your mind?")
                button("Keep it") { moduleCommand(t, "rm -f $m/remove") }
            }
            else -> {
                text("Module")
                if (!have.pending) box.addView(Switch(this).apply {
                    isChecked = !have.off
                    setOnCheckedChangeListener { _, on ->
                        moduleCommand(t, if (on) "rm -f $m/disable" else "touch $m/disable")
                    }
                })
                if (b != null && !have.pending && b.versionCode > have.versionCode) button("Update to ${b.version}") {
                    confirmThen("Update ${t.name} to ${b.version} with Magisk? Risk: ${t.risk}. It works after a restart.") {
                        install(t, b)
                    }
                }
                button("Uninstall") {
                    confirmThen("Uninstall ${t.name}? Magisk removes it at the next restart.") {
                        // A pending install would replace the old folder (and its remove flag) at boot: drop it too.
                        moduleCommand(t, "rm -rf /data/adb/modules_update/$id; [ -d $m ] && touch $m/remove; true")
                    }
                }
            }
        }
        return box
    }

    /** Runs a command on the module's folder, then reads its state and the tweak's check again. */
    private fun moduleCommand(t: Tweak, command: String) {
        Thread {
            val r = RootShell.run(command)
            val v = installedVersion(moduleId(t))
            val s = RootShell.run(t.check)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                states[t.id] = s
                if (v != null) installed[t.id] = v else installed.remove(t.id)
                if (r.code != 0) Toast.makeText(this, r.output.ifEmpty { "failed (exit ${r.code})" }, Toast.LENGTH_LONG).show()
                else rebootNeeded = true
                drawContent()
            }
        }.start()
    }

    /** Copies the bundled zip out of the APK and hands it to Magisk, then reads the state again. */
    private fun install(t: Tweak, b: Bundled) {
        Thread {
            // Magisk's installer clears "disable": a module that was off stays off.
            val wasOff = installed[t.id]?.off == true
            val r = installZip(b)
            if (r.code == 0) {
                if (wasOff) RootShell.run("touch /data/adb/modules/${b.id}/disable")
                track(b.id)
            }
            val v = installedVersion(b.id)
            val s = RootShell.run(t.check)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                states[t.id] = s
                if (v != null) installed[t.id] = v else installed.remove(t.id)
                if (r.code != 0) {
                    // Magisk's installer prints why (wrong device, missing kernel feature...).
                    Toast.makeText(this, r.output.lines().takeLast(4).joinToString("\n").ifEmpty { "install failed (exit ${r.code})" }, Toast.LENGTH_LONG).show()
                } else rebootNeeded = true
                drawContent()
            }
        }.start()
    }

    /** Copies a bundled zip out of the APK and hands it to Magisk. */
    private fun installZip(b: Bundled): RootShell.Result {
        val zip = java.io.File(cacheDir, "${b.fix}.zip")
        return try {
            assets.open("modules/${b.fix}.zip").use { i -> zip.outputStream().use { i.copyTo(it) } }
            zip.setReadable(true, false)
            RootShell.run("magisk --install-module '${zip.absolutePath}'", timeoutSeconds = 180)
        } catch (e: Exception) {
            RootShell.Result(-1, "couldn't copy the module: ${e.message}")
        } finally {
            zip.delete()
        }
    }

    /**
     * Notes a module the Manager installed in [TRACKED], and makes sure the cleanup
     * module is on the headset: it removes the noted modules if this app is uninstalled
     * (fixes/15-qe-cleanup). Runs on the background thread.
     */
    private fun track(id: String) {
        if (id == CLEANUP_ID) return
        RootShell.run("mkdir -p ${TRACKED.substringBeforeLast('/')}; " +
            "grep -qx '$id' $TRACKED 2>/dev/null || echo '$id' >> $TRACKED")
        val cleanup = bundled.values.firstOrNull { it.id == CLEANUP_ID } ?: return
        val have = installedVersion(CLEANUP_ID)
        if (have == null || have.removing || have.versionCode < cleanup.versionCode) {
            RootShell.run("rm -f /data/adb/modules/$CLEANUP_ID/remove")
            installZip(cleanup)
        }
    }

    /**
     * "Remove everything": the modules this Manager installed and its persist.qe.* props,
     * so nothing it added keeps running once the app is uninstalled. OS update blocking,
     * Chats and the adb port are only undone if ticked (they protect the headset).
     */
    private fun removeAllRow() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(20), dp(14), dp(20), dp(14))
        background = rounded(panel)
        addView(LinearLayout(this@ManagerActivity).apply {
            orientation = LinearLayout.VERTICAL
            addView(label("Remove everything", 15f, Color.WHITE))
            addView(label("Before uninstalling QuestEnhancer: removes the modules it installed and its " +
                "settings. If you just uninstall, the cleanup module does this at the next boot.", 12f, dim))
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        addView(label("Remove", 14f, Color.WHITE, bold = true).apply {
            setPadding(dp(16), dp(8), dp(16), dp(8))
            background = rounded(accent)
            setOnClickListener { removeAll() }
        })
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(12)
        }
    }

    private fun removeAll() {
        Thread {
            val mods = RootShell.run("[ -f $TRACKED ] && while read -r m; do " +
                "[ -d /data/adb/modules/\$m ] || [ -d /data/adb/modules_update/\$m ] && echo \$m; done < $TRACKED; true")
                .output.lines().map { it.trim() }.filter { it.isNotEmpty() && it != CLEANUP_ID }
            val props = RootShell.run("getprop | sed -n 's/^\\[\\(persist\\.qe\\.[^]]*\\)\\].*/\\1/p'")
                .output.lines().map { it.trim() }.filter { it.isNotEmpty() }
            // The headset-protecting switches that are on now, from their own checks.
            val keep = listOf("os-updates", "chats", "adb-port").mapNotNull { id ->
                val t = tweaks.firstOrNull { it.id == id } ?: return@mapNotNull null
                val c = t.controls.firstOrNull { it.type == "switch" && it.off.isNotEmpty() } ?: return@mapNotNull null
                val out = RootShell.run(t.check)
                if (out.code == 0 && c.state?.containsMatchIn(out.output) == true) t to c else null
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                val names = mods.map { id -> tweaks.firstOrNull { moduleId(it) == id }?.name ?: id }
                val msg = buildString {
                    append(if (names.isEmpty()) "No modules installed by QuestEnhancer." else "Modules removed at the next restart: ${names.joinToString(", ")}.")
                    append(if (props.isEmpty()) "" else "\n\nQuestEnhancer settings cleared: ${props.joinToString(", ")}.")
                    append("\n\nThe dock goes back to 5 pins (SystemUX restarts; the first 5 stay pinned).")
                }
                val labels = keep.map { (t, c) -> "Undo: ${t.name} (${c.label})" }.toTypedArray()
                val ticked = BooleanArray(labels.size)
                AlertDialog.Builder(this)
                    .setTitle("Remove everything?")
                    .setMessage(msg + if (labels.isEmpty()) "" else "\n\nThese stay as they are unless ticked (on the next screen).")
                    .setPositiveButton("Next") { _, _ ->
                        if (labels.isEmpty()) runRemoveAll(mods, props, emptyList())
                        else AlertDialog.Builder(this)
                            .setTitle("Also undo? (usually not)")
                            .setMultiChoiceItems(labels, ticked) { _, i, on -> ticked[i] = on }
                            .setPositiveButton("Remove") { _, _ ->
                                runRemoveAll(mods, props, keep.filterIndexed { i, _ -> ticked[i] }.map { it.second.off })
                            }
                            .setNegativeButton("Cancel", null)
                            .show()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }.start()
    }

    private fun runRemoveAll(mods: List<String>, props: List<String>, undo: List<String>) {
        Thread {
            val cmd = (mods.map { "rm -rf /data/adb/modules_update/$it; [ -d /data/adb/modules/$it ] && touch /data/adb/modules/$it/remove" } +
                props.map { "resetprop -p --delete $it" } +
                undo +
                listOf("rm -f $TRACKED", "true")).joinToString("; ")
            // Dock back to stock's 5: the hooks trim the saved pins as SystemUX starts again.
            preferences.edit(commit = true) { putInt("dock_max_pins", 5) }
            val r = RootShell.run("$cmd; am force-stop com.oculus.systemux", timeoutSeconds = 60)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                if (r.code != 0) Toast.makeText(this, r.output.ifEmpty { "failed (exit ${r.code})" }, Toast.LENGTH_LONG).show()
                else AlertDialog.Builder(this)
                    .setMessage("Done. Restart the headset, then uninstall QuestEnhancer (and turn it off in Vector first).")
                    .setPositiveButton("OK", null)
                    .show()
                rebootNeeded = true
                refresh(retryRoot = false)
            }
        }.start()
    }

    /**
     * The module on the headset: pending in modules_update (installed, not yet restarted),
     * marked for removal, or active (on or off). Null when it isn't there.
     */
    private fun installedVersion(id: String): Installed? {
        val r = RootShell.run(
            "u=/data/adb/modules_update/$id/module.prop; m=/data/adb/modules/$id; " +
                "vc() { sed -n 's/^versionCode=//p' \$1; }; " +
                "if [ -f \$m/remove ]; then echo \"removing \$(vc \$m/module.prop)\"; " +
                "elif [ -f \$u ]; then echo \"pending \$(vc \$u)\"; " +
                "elif [ -f \$m/module.prop ] && [ -f \$m/disable ]; then echo \"off \$(vc \$m/module.prop)\"; " +
                "elif [ -f \$m/module.prop ]; then echo \"on \$(vc \$m/module.prop)\"; " +
                "else echo none; fi")
        val parts = r.output.trim().split(' ')
        if (r.code != 0 || parts[0] == "none") return null
        val vc = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: 0
        return Installed(vc, pending = parts[0] == "pending", off = parts[0] == "off", removing = parts[0] == "removing")
    }

    /** Asks first when [message] is set; [onCancel] runs if the answer is no. */
    private fun confirmThen(message: String, onCancel: () -> Unit = {}, run: () -> Unit) {
        if (message.isEmpty()) return run()
        AlertDialog.Builder(this)
            .setMessage(message)
            .setPositiveButton("OK") { _, _ -> run() }
            .setNegativeButton("Cancel") { _, _ -> onCancel() }
            .setOnCancelListener { onCancel() }
            .show()
    }

    /** The colour wheel, with a swatch of the pick; answers #RRGGBB. */
    private fun pickColor(start: Int, done: (String) -> Unit) {
        val swatch = View(this).apply { background = rounded(start) }
        val wheel = ColorWheel(this).apply {
            color = start
            onColor = { swatch.background = rounded(it) }
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(16), dp(24), dp(8))
            addView(wheel)
            addView(swatch, LinearLayout.LayoutParams(dp(200), dp(36)).apply { topMargin = dp(16) })
        }
        AlertDialog.Builder(this)
            .setTitle("Pick a colour")
            .setView(box)
            .setPositiveButton("Use it") { _, _ ->
                done(String.format("#%06X", wheel.color and 0xFFFFFF))
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun chip(o: Option, chosen: Boolean, onTap: () -> Unit) = label(o.label, 14f, Color.WHITE).apply {
        setPadding(dp(14), dp(8), dp(14), dp(8))
        background = rounded(if (chosen) accent else selected)
        if (o.swatch != null) {
            setCompoundDrawablesRelativeWithIntrinsicBounds(GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(o.swatch)
                setSize(dp(14), dp(14))
            }, null, null, null)
            compoundDrawablePadding = dp(8)
        }
        setOnClickListener { onTap() }
    }

    /** Runs a control's command, then reads that tweak's state again. */
    private fun act(t: Tweak, command: String, showOutput: Boolean = false) {
        if (command.isEmpty()) {
            if (t.after == "reboot") rebootNeeded = true
            if (t.after == "systemux") systemUxRestartNeeded = true
            return
        }
        Thread {
            // Changes get longer than checks: ptcolor rewrites part of an APK copy.
            val r = RootShell.run(command, timeoutSeconds = 180)
            val s = RootShell.run(t.check)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                states[t.id] = s
                if (r.code != 0) Toast.makeText(this, r.output.ifEmpty { "failed (exit ${r.code})" }, Toast.LENGTH_LONG).show()
                else {
                    if (showOutput && r.output.isNotEmpty()) Toast.makeText(this, r.output, Toast.LENGTH_LONG).show()
                    if (t.after == "reboot") rebootNeeded = true
                    if (t.after == "systemux") systemUxRestartNeeded = true
                }
                drawContent()
            }
        }.start()
    }

    private fun rebootRow() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(20), dp(14), dp(20), dp(14))
        background = rounded(selected)
        addView(label("Restart the headset to see the change.", 15f, Color.WHITE),
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        addView(label("Restart now", 15f, Color.WHITE, bold = true).apply {
            setPadding(dp(16), dp(8), dp(16), dp(8))
            background = rounded(accent)
            setOnClickListener {
                AlertDialog.Builder(this@ManagerActivity)
                    .setTitle("Restart the headset?")
                    .setMessage("The headset restarts now. Anything open closes.")
                    .setPositiveButton("Restart") { _, _ -> Thread { RootShell.run("svc power reboot || reboot") }.start() }
                    .setNegativeButton("Not now", null)
                    .show()
            }
        })
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(12)
        }
    }

    private fun restartSystemUxRow() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(20), dp(14), dp(20), dp(14))
        background = rounded(selected)
        addView(label("Restart SystemUX to see the change. This will not close your running apps.", 15f, Color.WHITE),
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        addView(label("Restart SystemUX", 15f, Color.WHITE, bold = true).apply {
            setPadding(dp(16), dp(8), dp(16), dp(8))
            background = rounded(accent)
            setOnClickListener {
                systemUxRestartNeeded = false
                drawContent()
                Thread { RootShell.run("am force-stop com.oculus.systemux") }.start()
            }
        })
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(12)
        }
    }

    /**
     * Root was refused. Magisk's grant popup is easy to miss in the headset (it times out
     * and denies), and asking again gets the same answer, so say how to grant it by hand.
     */
    private fun noRootRow() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(20), dp(14), dp(20), dp(14))
        background = rounded(selected)
        addView(label("Quest Enhancer has no root access, so it can't read or change anything. " +
            "Open Magisk, go to Superuser and turn Quest Enhancer on (if it isn't listed, tap Add " +
            "and pick it). Then come back and tap Refresh.", 15f, Color.WHITE),
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        addView(label("Open Magisk", 15f, Color.WHITE, bold = true).apply {
            setPadding(dp(16), dp(8), dp(16), dp(8))
            background = rounded(accent)
            setOnClickListener {
                val open = packageManager.getLaunchIntentForPackage("com.topjohnwu.magisk")
                if (open != null) startActivity(open)
                else Toast.makeText(this@ManagerActivity,
                    "Magisk not found by its usual name. Open it from Unknown Sources.", Toast.LENGTH_LONG).show()
            }
        })
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(12)
        }
    }

    /** All checks on one background thread through the one root shell. */
    private fun refresh(retryRoot: Boolean) {
        if (loading) return
        loading = true
        states.clear()
        drawContent()
        Thread {
            if (retryRoot) RootShell.retry()
            for (t in tweaks) {
                val v = moduleId(t).takeIf { it.isNotEmpty() }?.let { installedVersion(it) }
                val r = if (t.check.isBlank()) {
                    RootShell.Result(0, "")
                } else {
                    RootShell.run(t.check)
                }
                runOnUiThread {
                    if (v != null) installed[t.id] = v else installed.remove(t.id)
                    states[t.id] = r
                }
            }
            runOnUiThread {
                loading = false
                if (!isDestroyed) drawContent()
            }
        }.start()
    }

    private fun label(s: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun rounded(color: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(10).toFloat()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}

/** Modules this Manager installed, one id a line; read by the cleanup module. */
private const val TRACKED = "/data/adb/questenhancer/installed"
private const val CLEANUP_ID = "qe_cleanup"

/**
 * A module on the headset. [pending]: installed, waiting for a restart. [off]: its disable
 * flag is set. [removing]: its remove flag is set (Magisk deletes it at the next restart).
 */
data class Installed(val versionCode: Int, val pending: Boolean, val off: Boolean = false, val removing: Boolean = false)
