package com.salman.wavelauncher

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class SettingsActivity : BaseLauncherActivity() {

    private lateinit var col: LinearLayout
    private lateinit var scroll: ScrollView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(60), dp(24), dp(48))
        }
        scroll = ScrollView(this).apply {
            setBackgroundColor(Theme.bg(settings))
            addView(col)
        }
        setContentView(scroll)
        build()
    }

    override fun onThemeChanged() {
        scroll.setBackgroundColor(Theme.bg(settings))
        build()
    }

    private fun build() {
        val s = settings
        col.removeAllViews()

        title("Settings")
        caption("Wave Launcher v0.2 — open-source Niagara-style")

        section("Theme")
        stepper("Theme mode", LauncherPrefs.THEME_MODES[s.themeMode]) {
            saveSettings(s.copy(themeMode = (s.themeMode + 1) % LauncherPrefs.THEME_MODES.size))
        }
        if (s.themeMode == 2) {
            action(if (s.wallpaperUri.isEmpty()) "Pick wallpaper image" else "Change wallpaper image") {
                pickWallpaper()
            }
            dimSlider("Text readability (background dim)", s.wallpaperDim) { v ->
                saveSettings(s.copy(wallpaperDim = v))
            }
            caption("Lower = wallpaper more visible, text harder to read. Higher = darker backdrop, easier text.")
        }
        stepper("Accent color", accentName(s.accentIndex)) {
            saveSettings(s.copy(accentIndex = (s.accentIndex + 1) % LauncherPrefs.accentCount()))
        }
        stepper("Font", LauncherPrefs.FONTS[s.fontIndex]) {
            saveSettings(s.copy(fontIndex = (s.fontIndex + 1) % LauncherPrefs.FONTS.size))
        }

        section("Home screen")
        stepper("Clock size", "${s.clockSizeSp}sp") {
            val next = if (s.clockSizeSp >= 76) 36 else s.clockSizeSp + 6
            saveSettings(s.copy(clockSizeSp = next))
        }
        stepper("Icon shape", LauncherPrefs.ICON_SHAPES[s.iconShape]) {
            saveSettings(s.copy(iconShape = (s.iconShape + 1) % LauncherPrefs.ICON_SHAPES.size))
        }
        toggle("24-hour clock", s.h24) { v -> saveSettings(s.copy(h24 = v)) }
        action("Manage categories") { manageCategories() }

        section("Widgets")
        action("Add widget to home") { pickWidget() }
        action("Manage / remove widgets") {
            val i = Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            i.putExtra("manage_widgets", true)
            startActivity(i)
        }
        val ids = LauncherPrefs.widgetIds(this)
        caption(if (ids.isEmpty()) "No widgets on home yet" else "${ids.size} widget(s) on home — long-press a widget row on home soon to remove")

        section("Work profile")
        val wh = WorkApps.workProfile(this)
        if (wh == null) {
            caption("No work profile on this device")
        } else {
            toggle("Show work apps in list", s.showWork) { v -> saveSettings(s.copy(showWork = v)) }
            val paused = WorkApps.isPaused(this, wh)
            action(if (paused) "Unpause work apps" else "Pause work apps") {
                val ok = WorkApps.requestQuiet(this, wh, !paused)
                if (!ok) Toast.makeText(this, "System declined quiet-mode change; use system UI", Toast.LENGTH_LONG).show()
                else {
                    Toast.makeText(this, if (paused) "Work apps on" else "Work apps paused", Toast.LENGTH_SHORT).show()
                    build()
                }
            }
            caption(if (paused) "Work apps are currently paused (hidden apps stay in drawer? no — paused profile apps do not launch)" else "Work apps are active")
        }

        section("System")
        action("Notification access (for dots)") {
            safeStart(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        action("Set as default launcher") {
            safeStart(Intent(Settings.ACTION_HOME_SETTINGS))
        }

        caption("Swipe down on home = search · drag right rail = wave jump · long-press app = folders · long-press folder = edit")
    }

    private fun manageCategories() {
        val cats = LauncherPrefs.categories(this).toTypedArray()
        val items = if (cats.isEmpty()) arrayOf("＋ New category…") else cats + arrayOf("＋ New category…")
        android.app.AlertDialog.Builder(this)
            .setTitle("Categories")
            .setItems(items) { dlg, which ->
                dlg.dismiss()
                if (which == items.size - 1) {
                    val input = android.widget.EditText(this).apply { hint = "Category name"; setSingleLine(true) }
                    android.app.AlertDialog.Builder(this)
                        .setTitle("New category")
                        .setView(input)
                        .setPositiveButton("Create") { _, _ ->
                            val name = input.text.toString().trim()
                            if (name.isNotEmpty()) {
                                LauncherPrefs.saveCategories(this, LauncherPrefs.categories(this) + name)
                                manageCategories()
                            }
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                } else {
                    val name = cats[which]
                    val members = LauncherPrefs.categoryMembers(this, name).toMutableSet()
                    val all = AppLoader.loadApps(this)
                    val labels = all.map { it.label }.toTypedArray()
                    val checked = BooleanArray(all.size) { all[it].packageName in members }
                    android.app.AlertDialog.Builder(this)
                        .setTitle("Apps in “$name”")
                        .setMultiChoiceItems(labels, checked) { _, which2, isChecked ->
                            if (isChecked) members.add(all[which2].packageName)
                            else members.remove(all[which2].packageName)
                        }
                        .setPositiveButton("Save") { d2, _ ->
                            LauncherPrefs.saveCategoryMembers(this, name, members)
                            d2.dismiss()
                        }
                        .setNeutralButton("Delete category") { d2, _ ->
                            LauncherPrefs.deleteCategory(this, name)
                            d2.dismiss()
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun pickWidget() {
        try {
            setResult(Activity.RESULT_OK)
            // delegate to MainActivity which owns the AppWidgetHost
            val i = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            i.putExtra("pick_widget", true)
            startActivity(i)
            Toast.makeText(this, "Pick a widget on the home screen prompt", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Widget host unavailable: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun dimSlider(name: String, value: Int, onChange: (Int) -> Unit) {
        val col2 = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col2.setPadding(0, dp(14), 0, dp(6))
        val label = TextView(this).apply {
            text = "$name — $value%"
            textSize = 15.5f; setTextColor(Theme.text(settings))
        }
        val seek = android.widget.SeekBar(this).apply {
            max = 90
            progress = value
            keyProgressIncrement = 5
        }
        seek.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: android.widget.SeekBar?, v: Int, fromUser: Boolean) {
                label.text = "$name — $v%"
            }
            override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {
                onChange(sb?.progress ?: value)
            }
        })
        col2.addView(label)
        col2.addView(seek, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        col.addView(col2)
    }

    private fun pickWallpaper() {
        try {
            val i = Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "image/*"
                addCategory(Intent.CATEGORY_OPENABLE)
            }
            startActivityForResult(Intent.createChooser(i, "Pick wallpaper"), REQ_WALLPAPER)
        } catch (e: Exception) {
            Toast.makeText(this, "No image picker: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_WALLPAPER && resultCode == Activity.RESULT_OK) {
            val uri = data?.data
            if (uri != null) {
                try {
                    contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (_: Exception) { }
                saveSettings(settings.copy(wallpaperUri = uri.toString()))
                Toast.makeText(this, "Wallpaper set — switch theme mode to Wallpaper to see it", Toast.LENGTH_LONG).show()
                build()
            }
        }
    }

    private fun safeStart(i: Intent) {
        try { startActivity(i) } catch (e: Exception) {
            Toast.makeText(this, "Not found: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun accentName(i: Int) = when (i % LauncherPrefs.accentCount()) {
        0 -> "Teal"; 1 -> "Amber"; 2 -> "Violet"; else -> "Green"
    }

    private fun title(t: String) = col.addView(TextView(this).apply {
        text = t; textSize = 22f; setTypeface(typeface, Typeface.BOLD)
        setTextColor(Theme.text(settings))
        setPadding(0, 0, 0, dp(4))
    })

    private fun caption(t: String) = col.addView(TextView(this).apply {
        text = t; textSize = 12f
        setTextColor(Theme.text2(settings))
        setPadding(0, dp(6), 0, dp(12))
    })

    private fun section(t: String) = col.addView(TextView(this).apply {
        text = t; textSize = 12f; setTypeface(typeface, Typeface.BOLD)
        setTextColor(LauncherPrefs.accent(settings))
        setPadding(0, dp(16), 0, dp(8))
    })

    private fun row(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(14), 0, dp(14))
    }

    private fun label(text: String): View = TextView(this).apply {
        this.text = text; textSize = 15.5f
        setTextColor(Theme.text(settings))
    }

    private fun toggle(name: String, on: Boolean, onChange: (Boolean) -> Unit) {
        val r = row()
        r.addView(label(name), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val sw = Switch(this).apply { isChecked = on }
        sw.setOnCheckedChangeListener { _: CompoundButton, v: Boolean -> onChange(v) }
        r.addView(sw)
        col.addView(r)
    }

    private fun stepper(name: String, value: String, onStep: () -> Unit) {
        val r = row()
        val col2 = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col2.addView(TextView(this).apply {
            text = name; textSize = 15.5f; setTextColor(Theme.text(settings))
        })
        col2.addView(TextView(this).apply {
            text = value; textSize = 12f; setTextColor(Theme.text2(settings))
        })
        r.addView(col2, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val btn = TextView(this).apply {
            text = "›"; textSize = 22f; setPadding(dp(24), 0, dp(24), 0)
            setTextColor(LauncherPrefs.accent(settings))
        }
        btn.setOnClickListener { onStep() }
        r.addView(btn)
        col.addView(r)
    }

    private fun action(name: String, onClick: () -> Unit) {
        val r = row()
        r.addView(label(name), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val btn = TextView(this).apply {
            text = "›"; textSize = 22f; setPadding(dp(24), 0, dp(24), 0)
            setTextColor(LauncherPrefs.accent(settings))
        }
        btn.setOnClickListener { onClick() }
        r.addView(btn)
        col.addView(r)
    }

    companion object {
        const val REQ_WALLPAPER = 201
    }
}
