package com.salman.wavelauncher

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

data class LauncherSettings(
    var themeMode: Int = 0,          // 0 dark, 1 light, 2 wallpaper
    var accentIndex: Int = 0,
    var clockSizeSp: Int = 58,
    var iconCount: Int = 5,
    var iconShape: Int = 0,          // 0 rounded square, 1 circle
    var fontIndex: Int = 0,          // 0 system, 1 light, 2 serif, 3 mono
    var h24: Boolean = false,
    var wallpaperUri: String = "",
    var showWork: Boolean = false,
    var wallpaperDim: Int = 45,
    var scrollHintOffsetDp: Int = 300,
    var showSearchBar: Boolean = true,
    var dockIconSizeDp: Int = 26,
    var dockOpacity: Int = 0
)

object LauncherPrefs {
    private const val NAME = "wave_launcher"
    val ACCENTS = intArrayOf(
        0xFF4DB6AC.toInt(),  // teal
        0xFFD9A441.toInt(),  // amber
        0xFFA78BFA.toInt(),  // violet
        0xFF81C995.toInt()   // green
    )
    val FONTS = arrayOf("System", "Light", "Serif", "Mono")
    val THEME_MODES = arrayOf("Dark", "Light", "Wallpaper")
    val ICON_SHAPES = arrayOf("Rounded", "Circle")

    fun prefs(c: Context): SharedPreferences =
        c.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun load(c: Context): LauncherSettings {
        val p = prefs(c)
        return LauncherSettings(
            themeMode = p.getInt("themeMode", 0),
            accentIndex = p.getInt("accent", 0),
            clockSizeSp = p.getInt("clockSize", 58),
            iconCount = p.getInt("iconCount", 5),
            iconShape = p.getInt("iconShape", 0),
            fontIndex = p.getInt("fontIndex", 0),
            h24 = p.getBoolean("h24", false),
            wallpaperUri = p.getString("wallpaperUri", "") ?: "",
            showWork = p.getBoolean("showWork", false),
            wallpaperDim = p.getInt("wallpaperDim", 45),
            scrollHintOffsetDp = p.getInt("scrollHintOffsetDp", 300),
            showSearchBar = p.getBoolean("showSearchBar", true),
            dockIconSizeDp = p.getInt("dockIconSizeDp", 26),
            dockOpacity = p.getInt("dockOpacity", 0)
        )
    }

    fun save(c: Context, s: LauncherSettings) {
        prefs(c).edit()
            .putInt("themeMode", s.themeMode)
            .putInt("accent", s.accentIndex)
            .putInt("clockSize", s.clockSizeSp)
            .putInt("iconCount", s.iconCount)
            .putInt("iconShape", s.iconShape)
            .putInt("fontIndex", s.fontIndex)
            .putBoolean("h24", s.h24)
            .putString("wallpaperUri", s.wallpaperUri)
            .putBoolean("showWork", s.showWork)
            .putInt("wallpaperDim", s.wallpaperDim)
            .putInt("scrollHintOffsetDp", s.scrollHintOffsetDp)
            .putBoolean("showSearchBar", s.showSearchBar)
            .putInt("dockIconSizeDp", s.dockIconSizeDp)
            .putInt("dockOpacity", s.dockOpacity)
            .apply()
    }

    fun accent(s: LauncherSettings): Int =
        ACCENTS[s.accentIndex.coerceIn(0, ACCENTS.size - 1)]

    fun accentCount(): Int = ACCENTS.size

    // ---- folders: JSON map name -> [packageName] ----
    fun folders(c: Context): MutableMap<String, MutableList<String>> {
        val out = HashMap<String, MutableList<String>>()
        val raw = prefs(c).getString("folders", null) ?: return out
        try {
            val obj = JSONObject(raw)
            for (name in obj.keys()) {
                val arr = obj.getJSONArray(name)
                val list = ArrayList<String>()
                for (i in 0 until arr.length()) list.add(arr.getString(i))
                out[name] = list
            }
        } catch (_: Exception) { }
        return out
    }

    fun saveFolders(c: Context, folders: Map<String, List<String>>) {
        val obj = JSONObject()
        for ((name, pkgs) in folders) obj.put(name, JSONArray(pkgs))
        prefs(c).edit().putString("folders", obj.toString()).apply()
    }

    // ---- widget ids persisted ----
    fun widgetIds(c: Context): IntArray {
        val raw = prefs(c).getString("widgetIds", null) ?: return IntArray(0)
        return try {
            val arr = JSONArray(raw)
            IntArray(arr.length()) { arr.getInt(it) }
        } catch (_: Exception) { IntArray(0) }
    }

    fun saveWidgetIds(c: Context, ids: IntArray) {
        val arr = JSONArray()
        for (i in ids) arr.put(i)
        prefs(c).edit().putString("widgetIds", arr.toString()).apply()
    }

    // ---- categories: ordered names + per-category package sets ----
    fun categories(c: Context): List<String> {
        val raw = prefs(c).getString("categories", null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            val out = ArrayList<String>()
            for (i in 0 until arr.length()) out.add(arr.getString(i))
            out
        } catch (e: Exception) { emptyList() }
    }

    fun saveCategories(c: Context, names: List<String>) {
        val arr = JSONArray()
        for (n in names) arr.put(n)
        prefs(c).edit().putString("categories", arr.toString()).apply()
    }

    fun categoryMembers(c: Context, name: String): Set<String> {
        val raw = prefs(c).getString("cat_" + name, null) ?: return emptySet()
        return try {
            val arr = JSONArray(raw)
            val out = HashSet<String>()
            for (i in 0 until arr.length()) out.add(arr.getString(i))
            out
        } catch (e: Exception) { emptySet() }
    }

    fun saveCategoryMembers(c: Context, name: String, pkgs: Collection<String>) {
        val arr = JSONArray()
        for (pkg in pkgs) arr.put(pkg)
        prefs(c).edit().putString("cat_" + name, arr.toString()).apply()
    }

    fun deleteCategory(c: Context, name: String) {
        val names = categories(c).filter { it != name }
        saveCategories(c, names)
        prefs(c).edit().remove("cat_" + name).apply()
    }

    fun widgetZone(c: Context, id: Int): String = prefs(c).getString("wzone_$id", "top") ?: "top"
    fun saveWidgetZone(c: Context, id: Int, zone: String) {
        prefs(c).edit().putString("wzone_$id", zone).apply()
    }
    fun widgetLabel(c: Context, id: Int): String? = prefs(c).getString("wlabel_$id", null)
    fun saveWidgetLabel(c: Context, id: Int, label: String) {
        prefs(c).edit().putString("wlabel_$id", label).apply()
    }
    fun clearWidget(c: Context, id: Int) {
        prefs(c).edit().remove("wlabel_$id").remove("wzone_$id").apply()
    }

    // ---- dock: ordered entries "app:<packageName>" or "folder:<name>" ----
    fun dockItems(c: Context): List<String> {
        val raw = prefs(c).getString("dock", null) ?: return emptyList()
        if (raw.isEmpty()) return emptyList()
        if (!raw.startsWith("[")) {
            // legacy | format (folder names containing | were impossible then)
            return raw.split("|").filter { it.isNotEmpty() }
        }
        return try {
            val out = ArrayList<String>()
            val arr = org.json.JSONArray(raw)
            for (i in 0 until arr.length()) out.add(arr.getString(i))
            out
        } catch (e: Exception) { emptyList() }
    }

    fun saveDockItems(c: Context, items: List<String>) {
        val arr = org.json.JSONArray()
        for (it in items) arr.put(it)
        prefs(c).edit().putString("dock", arr.toString()).apply()
    }
}

object Theme {
    fun isDark(s: LauncherSettings) = s.themeMode != 1
    fun text(s: LauncherSettings): Int = when (s.themeMode) {
        2 -> 0xFFF4F6F8.toInt()
        1 -> 0xFF1A1C1E.toInt()
        else -> 0xFFE8EAED.toInt()
    }
    fun text2(s: LauncherSettings): Int = when (s.themeMode) {
        2 -> 0xFFB9C0C6.toInt()
        1 -> 0xFF5F6368.toInt()
        else -> 0xFF9AA0A6.toInt()
    }
    fun bg(s: LauncherSettings): Int = when (s.themeMode) {
        2 -> android.graphics.Color.argb(
            (s.wallpaperDim.coerceIn(0, 90) * 255 / 100), 0, 0, 0)
        1 -> 0xF2EEF0F3.toInt()
        else -> 0xF20B0D10.toInt()
    }
    fun fontFamily(index: Int, style: Int): android.graphics.Typeface {
        val family = when (index.coerceIn(0, 3)) {
            1 -> "sans-serif-light"
            2 -> "serif"
            3 -> "monospace"
            else -> "sans-serif"
        }
        return android.graphics.Typeface.create(family, style)
    }
}
