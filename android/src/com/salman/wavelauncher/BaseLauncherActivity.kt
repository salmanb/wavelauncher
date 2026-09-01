package com.salman.wavelauncher

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.text.format.DateFormat
import android.widget.AbsListView
import android.widget.AdapterView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Shared base for launcher activities: loads settings once per start,
 * propagates changes to subclasses via onThemeChanged().
 */
abstract class BaseLauncherActivity : Activity() {

    lateinit var settings: LauncherSettings
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = LauncherPrefs.load(this)
    }

    override fun onStart() {
        super.onStart()
        settings = LauncherPrefs.load(this)
    }

    open fun onThemeChanged() {}

    fun saveSettings(s: LauncherSettings) {
        settings = s
        LauncherPrefs.save(this, s)
        onThemeChanged()
    }
}

fun formatClock(c: Context, h24Pref: Boolean): String {
    val use24 = h24Pref || DateFormat.is24HourFormat(c)
    val pattern = if (use24) "HH:mm" else "h:mm a"
    return SimpleDateFormat(pattern, Locale.getDefault()).format(Date())
}

/** Adapter-friendly no-op scroll listener so callers override only onScroll. */
abstract class AbsScrollListener : AbsListView.OnScrollListener {
    override fun onScrollStateChanged(view: AbsListView, scrollState: Int) {}
    override fun onScroll(view: AbsListView, firstVisibleItem: Int, visibleItemCount: Int, totalItemCount: Int) {}
}
