package com.salman.wavelauncher

import android.app.Dialog
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import java.util.Date
import java.util.Locale
import kotlin.math.abs

class MainActivity : BaseLauncherActivity() {

    private lateinit var list: ListView
    private lateinit var rail: WaveRailView
    private lateinit var clock: TextView
    private lateinit var dateView: TextView
    private lateinit var widgetsHost: LinearLayout
    private lateinit var widgetsBottom: LinearLayout
    private lateinit var adapter: HomeAdapter
    private var apps: List<AppEntry> = emptyList()
    private var workApps: List<AppEntry> = emptyList()
    private var workProfilePaused: Boolean = false
    private var folders: MutableMap<String, MutableList<String>> = mutableMapOf()
    private val ui = Handler(Looper.getMainLooper())

    private lateinit var appWidgetHost: AppWidgetHost
    private lateinit var appWidgetManager: AppWidgetManager
    private val hostedWidgets = HashMap<Int, AppWidgetHostView>()
    private val widgetMisses = HashMap<Int, Int>()

    private val countsReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) = refreshDots()
    }

    private val tick = object : Runnable {
        override fun run() { updateClock(); ui.postDelayed(this, 10_000) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        list = findViewById(R.id.list)
        rail = findViewById(R.id.rail)
        clock = findViewById(R.id.clock)
        dateView = findViewById(R.id.date)
        widgetsHost = findViewById(R.id.widgets)
        widgetsBottom = findViewById(R.id.widgetsBottom)
        widgetsBottom.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateListBottomInset() }

        appWidgetHost = AppWidgetHost(this, HOST_ID)
        appWidgetManager = AppWidgetManager.getInstance(this)

        adapter = HomeAdapter()
        list.adapter = adapter

        list.setOnItemClickListener { _, _, pos, _ -> adapter.launch(pos) }
        SwipeDetect.install(list) { openSearch() }

        findViewById<TextView>(R.id.corner).setOnClickListener { openDrawer() }

        if (intent?.getBooleanExtra("pick_widget", false) == true) pickWidget()
        if (intent?.getBooleanExtra("manage_widgets", false) == true) manageWidgets()

        val filter = IntentFilter(NotifListener.ACTION_COUNTS_CHANGED)
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            registerReceiver(countsReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(countsReceiver, filter)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra("pick_widget", false)) pickWidget()
        if (intent.getBooleanExtra("manage_widgets", false)) manageWidgets()
    }

    private val systemBound = HashSet<Int>()

    override fun onStart() {
        super.onStart()
        android.util.Log.d(TAG, "onStart: attaching widgets before listen")
        restoreWidgets()
        // Host views register with the host's update map on attach-to-window
        // (first layout pass) — so listen only AFTER traversal, or the service's
        // initial view push finds zero registered views and drops it.
        widgetsHost.post {
            if (isFinishing != true) {
                android.util.Log.d(TAG, "startListening after layout, views=${hostedWidgets.size}")
                appWidgetHost.startListening()
            }
        }
        ui.postDelayed({ if (isFinishing != true) restoreWidgets() }, 3000)
        ui.postDelayed({ if (isFinishing != true) restoreWidgets() }, 10000)
    }

    override fun onStop() {
        super.onStop()
        appWidgetHost.stopListening()
    }

    override fun onResume() {
        super.onResume()
        applyTheme()
        updateClock()
        tick.run()
        reloadApps()
        refreshDots()
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(countsReceiver) } catch (_: Exception) { }
        ui.removeCallbacks(tick)
    }

    override fun onThemeChanged() {
        applyTheme()
        adapter.buildRows()
        adapter.notifyDataSetChanged()
        reloadApps()
    }

    private fun reloadApps() {
        Thread {
            val fresh = AppLoader.loadApps(this)
            val wh = WorkApps.workProfile(this)
            val work = if (wh != null) WorkApps.loadApps(this, wh) else emptyList()
            val workPaused = wh != null && WorkApps.isPaused(this, wh)
            ui.post {
                apps = fresh
                workApps = work
                workProfilePaused = workPaused
                folders = LauncherPrefs.folders(this)
                adapter.buildRows()
                adapter.notifyDataSetChanged()
                setupRail()
            }
        }.start()
    }

    private fun openSearch() {
        startActivity(Intent(this, SearchActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION))
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    private fun openDrawer() {
        startActivity(Intent(this, DrawerActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION))
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    private fun updateClock() {
        val s = settings
        clock.text = formatClock(this, s.h24)
        clock.textSize = s.clockSizeSp.toFloat()
        clock.typeface = Theme.fontFamily(s.fontIndex, Typeface.NORMAL)
        dateView.text = longDate()
        dateView.typeface = Theme.fontFamily(s.fontIndex, Typeface.NORMAL)
    }

    private fun longDate(): String =
        java.text.SimpleDateFormat("EEEE, MMMM d", Locale.getDefault()).format(Date())

    private fun applyTheme() {
        val s = settings
        findViewById<View>(R.id.root).setBackgroundColor(Theme.bg(s))
        clock.setTextColor(Theme.text(s))
        dateView.setTextColor(Theme.text2(s))
        findViewById<TextView>(R.id.corner).setTextColor(Theme.text(s))
        val accent = LauncherPrefs.accent(s)
        rail.accentColor = accent
        rail.textColor = Theme.text2(s)
        rail.dark = Theme.isDark(s)
        adapter.notifyDataSetChanged()
    }

    private fun refreshDots() {
        ui.post { adapter.notifyDataSetChanged() }
    }

    // ================= widgets =================
    private fun widgetHeightPx(info: AppWidgetProviderInfo): Int {
        val d = resources.displayMetrics.density
        // minHeight is dp per AppWidgetProviderInfo docs
        val h = (info.minHeight * d).toInt()
        return h.coerceAtLeast((100 * d).toInt())
    }

    private fun restoreWidgets() {
        // system-bound ids are the source of truth: getAppWidgetIds() returns every
        // id the system still holds for this host — they survive updates even when
        // our prefs are empty, so adopt any we do not know about yet.
        val systemIds = appWidgetHost.getAppWidgetIds()
        val persisted = LauncherPrefs.widgetIds(this)
        if (systemIds.size != persisted.size) {
            val merged = LinkedHashSet<Int>()
            for (i in persisted) merged.add(i)
            for (sid in systemIds) merged.add(sid)
            LauncherPrefs.saveWidgetIds(this, merged.toIntArray())
            android.util.Log.d(TAG, "adopted system ids: system=${systemIds.contentToString()} persisted=${persisted.contentToString()}")
        }
        android.util.Log.d(TAG, "restoreWidgets: persisted=${persisted.contentToString()} hosted=${hostedWidgets.keys}")
        val ids = LinkedHashSet<Int>()
        for (i in LauncherPrefs.widgetIds(this)) ids.add(i)
        for (id in ids) {
            val info = appWidgetManager.getAppWidgetInfo(id)
            if (info == null) {
                // null right after updates/rebinds — keep the id forever so the
                // widget resurrects when the provider republishes; only detach
                // the blank view after repeated misses
                val misses = (widgetMisses[id] ?: 0) + 1
                widgetMisses[id] = misses
                if (misses == 5) {
                    hostedWidgets.remove(id)?.let { hv ->
                        (hv.parent as? View)?.let { card -> (card.parent as? ViewGroup)?.removeView(card) }
                    }
                    updateListBottomInset()
                } else if (misses < 5) {
                    ui.postDelayed({ if (isFinishing != true) restoreWidgets() }, 2500)
                }
                continue
            }
            widgetMisses.remove(id)
            systemBound.add(id)
            if (hostedWidgets.containsKey(id)) continue   // healthy card, leave it alone
            android.util.Log.d(TAG, "attach id=$id zone=${LauncherPrefs.widgetZone(this, id)}")
            attachWidget(id, info, LauncherPrefs.widgetZone(this, id))
        }
        updateListBottomInset()
    }

    private fun attachWidget(id: Int, info: AppWidgetProviderInfo, zone: String? = null) {
        val z = zone ?: "top"
        if (zone != null) LauncherPrefs.saveWidgetZone(this, id, z)
        // persist the id itself if it is brand new (the v0.5.0 add-flow regression)
        val current = LauncherPrefs.widgetIds(this)
        if (id !in current) {
            LauncherPrefs.saveWidgetIds(this, current + id)
            android.util.Log.d(TAG, "persisted new widget id=$id zone=$z")
        }
        val hostView = appWidgetHost.createView(this, id, info)
        hostView.setAppWidget(id, info)
        hostedWidgets[id] = hostView
        val card = wrapWidget(hostView, info)
        card.tag = id
        card.setOnLongClickListener {
            confirmRemoveWidget(id, info)
            true
        }
        val target = if (z == "bottom") widgetsBottom else widgetsHost
        target.addView(card, widgetParams())
        if (z == "bottom") updateListBottomInset()
    }

    private fun updateListBottomInset() {
        widgetsBottom.post {
            val extra = if (widgetsBottom.childCount > 0) widgetsBottom.height + dp(8) else 0
            list.setPadding(list.paddingLeft, list.paddingTop, list.paddingRight, dp(90) + extra)
            list.clipToPadding = false
        }
    }

    private fun wrapWidget(hv: AppWidgetHostView, info: AppWidgetProviderInfo): View {
        return FrameLayout(this).apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(0x22000000)
            }
            val pad = dp(8)
            setPadding(pad, pad, pad, pad)
            // clip children so provider content never paints outside the card
            clipChildren = true
            addView(hv, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, widgetHeightPx(info) - 2 * pad))
        }
    }

    private fun widgetParams(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { bottomMargin = dp(8) }

    private fun manageWidgets() {
        val d = Dialog(this)
        d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(12), dp(12))
        }
        col.addView(TextView(this).apply {
            text = "Widgets on home"
            textSize = 18f; setTypeface(typeface, Typeface.BOLD)
            setTextColor(Theme.text(settings))
            setPadding(0, 0, 0, dp(4))
        })
        val ids = LauncherPrefs.widgetIds(this)
        col.addView(TextView(this).apply {
            text = if (ids.isEmpty()) "No widgets" else "${ids.size} widget(s)"
            textSize = 11.5f; setTextColor(Theme.text2(settings))
            setPadding(0, 0, 0, dp(10))
        })
        if (ids.isEmpty()) {
            col.addView(TextView(this).apply {
                text = "Add some from Settings > Widgets"
                textSize = 13f; setTextColor(Theme.text2(settings)); setPadding(0, dp(8), 0, dp(8))
            })
        }
        for (id in ids) {
            val info = appWidgetManager.getAppWidgetInfo(id)
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(4), dp(8), dp(4), dp(8))
            }
            val name = if (info != null) widgetLabel(info) else "unavailable (id $id)"
            val zone = LauncherPrefs.widgetZone(this, id)
            row.addView(TextView(this).apply {
                text = "$name\n[$zone]"
                textSize = 13f; setTextColor(Theme.text(settings))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            if (info != null) {
                val zoneBtn = TextView(this).apply {
                    text = if (zone == "bottom") "move top" else "move bottom"
                    textSize = 12f; setTextColor(LauncherPrefs.accent(settings))
                    setPadding(dp(12), dp(8), dp(12), dp(8))
                    setOnClickListener {
                        val nz = if (zone == "bottom") "top" else "bottom"
                        LauncherPrefs.saveWidgetZone(this@MainActivity, id, nz)
                        hostedWidgets.remove(id)?.let { hv ->
                            (hv.parent as? View)?.let { card -> (card.parent as? ViewGroup)?.removeView(card) }
                        }
                        attachWidget(id, info, nz)
                        d.dismiss()
                        manageWidgets()
                    }
                }
                row.addView(zoneBtn)
            }
            val rm = TextView(this).apply {
                text = "Remove"; textSize = 12.5f; setTypeface(typeface, Typeface.BOLD)
                setTextColor(0xFFF28B82.toInt())
                setPadding(dp(12), dp(8), dp(12), dp(8))
                setOnClickListener {
                    if (info != null) removeWidget(id)
                    else {
                        LauncherPrefs.clearWidget(this@MainActivity, id)
                        LauncherPrefs.saveWidgetIds(this@MainActivity,
                            LauncherPrefs.widgetIds(this@MainActivity).filter { it != id }.toIntArray())
                    }
                    d.dismiss()
                    manageWidgets()
                }
            }
            row.addView(rm)
            col.addView(row)
        }
        val close = TextView(this).apply {
            text = "Done"; textSize = 14f; setTypeface(typeface, Typeface.BOLD)
            setTextColor(LauncherPrefs.accent(settings)); gravity = Gravity.END
            setPadding(dp(12), dp(10), dp(12), dp(4))
            setOnClickListener { d.dismiss() }
        }
        col.addView(close)
        d.setContentView(makeCard(col))
        d.window?.setBackgroundDrawableResource(android.R.color.transparent)
        d.show()
    }

    private fun confirmRemoveWidget(id: Int, info: AppWidgetProviderInfo) {
        val label = widgetLabel(info)
        Dialog(this).apply {
            requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
            setContentView(makeCard(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(22), dp(18), dp(22), dp(10))
                addView(TextView(this@MainActivity).apply {
                    text = "Remove $label?"
                    textSize = 16f
                    setTextColor(Theme.text(settings))
                    setTypeface(typeface, Typeface.BOLD)
                })
                addView(TextView(this@MainActivity).apply {
                    text = "The widget is detached from home. The app keeps its data."
                    textSize = 12f; setTextColor(Theme.text2(settings))
                    setPadding(0, dp(6), 0, dp(4))
                })
                val row = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END
                    setPadding(0, dp(10), 0, dp(6))
                }
                val cancel = TextView(this@MainActivity).apply {
                    text = "Cancel"; textSize = 14f; setTextColor(Theme.text2(settings))
                    setPadding(dp(18), dp(10), dp(18), dp(10))
                }
                val remove = TextView(this@MainActivity).apply {
                    text = "Remove"; textSize = 14f; setTypeface(typeface, Typeface.BOLD)
                    setTextColor(0xFFF28B82.toInt())
                    setPadding(dp(18), dp(10), dp(18), dp(10))
                }
                cancel.setOnClickListener { dismiss() }
                remove.setOnClickListener {
                    dismiss(); removeWidget(id)
                }
                row.addView(cancel); row.addView(remove)
                addView(row)
            }))
            window?.setBackgroundDrawableResource(android.R.color.transparent)
            show()
        }
    }

    private fun widgetLabel(info: AppWidgetProviderInfo): String = try {
        packageManager.getActivityInfo(info.provider, 0).loadLabel(packageManager).toString()
    } catch (_: Exception) { "widget" }

    private fun removeWidget(id: Int) {
        hostedWidgets.remove(id)?.let { hv ->
            (hv.parent as? View)?.let { card -> (card.parent as? ViewGroup)?.removeView(card) }
        }
        widgetMisses.remove(id)
        systemBound.remove(id)
        LauncherPrefs.clearWidget(this, id)
        appWidgetHost.deleteAppWidgetId(id)
        LauncherPrefs.saveWidgetIds(this, LauncherPrefs.widgetIds(this).filter { it != id }.toIntArray())
        Toast.makeText(this, "Widget removed", Toast.LENGTH_SHORT).show()
    }

    // ---------- widget picker with live previews ----------
    private fun allWidgetProviders(): List<AppWidgetProviderInfo> {
        val out = ArrayList<AppWidgetProviderInfo>()
        out.addAll(appWidgetManager.installedProviders)
        return out.filter { it.provider.packageName != packageName }
            .sortedBy { widgetLabel(it).lowercase() }
    }

    private fun previewDrawable(info: AppWidgetProviderInfo): android.graphics.drawable.Drawable? {
        return try {
            val pm = packageManager
            val resId = if (info.previewImage != 0) info.previewImage else info.icon
            if (resId == 0) return info.loadIcon(this, resources.displayMetrics.densityDpi)
            pm.getResourcesForApplication(info.provider.packageName).getDrawableForDensity(resId, resources.displayMetrics.densityDpi)
                ?: info.loadIcon(this, resources.displayMetrics.densityDpi)
        } catch (_: Exception) {
            try { info.loadIcon(this, resources.displayMetrics.densityDpi) } catch (_: Exception) { null }
        }
    }

    private fun pickWidget() {
        val providers = allWidgetProviders()
        if (providers.isEmpty()) {
            Toast.makeText(this, "No widgets installed", Toast.LENGTH_SHORT).show(); return
        }
        val dialog = Dialog(this)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val card = makeCard(root.apply {
            setPadding(dp(18), dp(16), dp(18), dp(10))
            addView(TextView(this@MainActivity).apply {
                text = "Add a widget"
                textSize = 17f; setTextColor(Theme.text(settings)); setTypeface(typeface, Typeface.BOLD)
                setPadding(dp(4), 0, 0, dp(10))
            })
        })
        val grid = GridView(this).apply {
            numColumns = 2
            horizontalSpacing = dp(10)
            verticalSpacing = dp(10)
        }
        grid.adapter = object : BaseAdapter() {
            override fun getCount() = providers.size
            override fun getItem(p: Int) = providers[p]
            override fun getItemId(p: Int) = p.toLong()
            override fun getView(p: Int, conv: View?, parent: ViewGroup): View {
                val info = providers[p]
                val v = conv ?: LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    setPadding(dp(6), dp(8), dp(6), dp(8))
                    background = GradientDrawable().apply {
                        cornerRadius = dp(12).toFloat()
                        setColor(0x14FFFFFF)
                    }
                    val iv = ImageView(this@MainActivity)
                    val tv = TextView(this@MainActivity).apply {
                        textSize = 11f; setTextColor(Theme.text2(settings)); maxLines = 1
                        gravity = Gravity.CENTER
                    }
                    addView(iv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(110)))
                    addView(tv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                    tag = Pair(iv, tv)
                }
                val (iv, tv) = v.tag as Pair<ImageView, TextView>
                val d = previewDrawable(info)
                if (d != null) iv.setImageDrawable(d) else iv.setImageResource(android.R.drawable.ic_menu_view)
                iv.scaleType = ImageView.ScaleType.FIT_CENTER
                tv.text = widgetLabel(info)
                return v
            }
        }
        grid.setOnItemClickListener { _, _, pos, _ ->
            dialog.dismiss()
            bindFlow(providers[pos])
        }
        root.addView(grid, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(380)))
        dialog.setContentView(card)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT)
        dialog.show()
    }

    private fun bindFlow(info: AppWidgetProviderInfo) {
        val id = appWidgetHost.allocateAppWidgetId()
        // ask the system to bind (user consent dialog if needed)
        @Suppress("DEPRECATION")
        val bind = Intent(AppWidgetManager.ACTION_APPWIDGET_BIND).apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider)
            if (info.profile != null) putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER_PROFILE, info.profile)
        }
        try {
            startActivityForResult(bind, REQ_BIND_WIDGET)
        } catch (e: Exception) {
            appWidgetHost.deleteAppWidgetId(id)
            Toast.makeText(this, "Cannot bind widget: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_BIND_WIDGET) {
            val id = data?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1)
                ?: appWidgetHost.appWidgetIds.lastOrNull() ?: -1
            if (resultCode != RESULT_OK || id == -1) {
                appWidgetHost.deleteAppWidgetId(id); return
            }
            val info = appWidgetManager.getAppWidgetInfo(id) ?: return
            if (info.configure != null) {
                try {
                    val cfg = Intent(AppWidgetManager.ACTION_APPWIDGET_CONFIGURE).apply {
                        setComponent(info.configure)
                        putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                    }
                    startActivityForResult(cfg, REQ_CONFIG_WIDGET)
                    return
                } catch (_: Exception) { }
            }
            finishBind(id, info)
        } else if (requestCode == REQ_CONFIG_WIDGET) {
            val id = data?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1) ?: -1
            val info = appWidgetManager.getAppWidgetInfo(id)
            if (resultCode == RESULT_OK && id != -1 && info != null) finishBind(id, info)
            else if (id != -1 && hostedWidgets[id] == null) appWidgetHost.deleteAppWidgetId(id)
        }
    }

    private fun finishBind(id: Int, info: AppWidgetProviderInfo) {
        askPlacement(id, info)
    }

    private fun askPlacement(id: Int, info: AppWidgetProviderInfo) {
        val d = Dialog(this)
        d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(18), dp(22), dp(12))
        }
        col.addView(TextView(this).apply {
            text = "Add ${widgetLabel(info)} to…"
            textSize = 16f; setTypeface(typeface, Typeface.BOLD)
            setTextColor(Theme.text(settings))
            setPadding(0, 0, 0, dp(10))
        })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
        fun choice(label: String, zone: String, accent: Boolean): TextView =
            TextView(this@MainActivity).apply {
                text = label; textSize = 14f
                setTypeface(typeface, if (accent) Typeface.BOLD else Typeface.NORMAL)
                setTextColor(if (accent) LauncherPrefs.accent(settings) else Theme.text2(settings))
                setPadding(dp(18), dp(10), dp(18), dp(10))
                setOnClickListener { d.dismiss(); attachWidget(id, info, zone) }
            }
        row.addView(choice("Top of list", "top", false))
        row.addView(choice("Bottom bar", "bottom", true))
        col.addView(row)
        d.setContentView(makeCard(col))
        d.window?.setBackgroundDrawableResource(android.R.color.transparent)
        d.show()
    }

    // ================= rail =================
    private fun setupRail() {
        val letters = linkedSetOf<String>()
        for (row in adapter.rowsIterator()) {
            val L = when (row) {
                is HomeRow.App -> row.app.letter
                is HomeRow.Section -> row.letter
                is HomeRow.Folder -> row.name.take(1).uppercase()
                else -> null
            }
            if (L != null) letters.add(L)
        }
        rail.letters = letters.toList()
        letterRowIndex = IntArray(rail.letters.size) { li ->
            val L = rail.letters[li]
            var found = -1
            for (i in 0 until adapter.count) {
                val row = adapter.getItem(i)
                val ltr = when (row) {
                    is HomeRow.App -> row.app.letter
                    is HomeRow.Section -> row.letter
                    is HomeRow.Folder -> row.name.take(1).uppercase()
                    else -> null
                }
                if (ltr == L) { found = i; break }
            }
            found
        }
        rail.onLetterDrag = { idx, _ ->
            val pos = letterRowIndex.getOrElse(idx) { -1 }
            if (pos >= 0) list.setSelectionFromTop(pos, 0)
        }
        rail.onDragEnd = { rail.waveAmp = 0f }

        list.setOnScrollListener(object : AbsScrollListener() {
            override fun onScroll(view: AbsListView, first: Int, visibleItemCount: Int, totalItemCount: Int) {
                updateWave()
            }
        })
        updateWave()
    }

    private fun updateWave() {
        val total = adapter.count
        if (total == 0 || rail.letters.isEmpty()) return
        val first = list.firstVisiblePosition
        val delta = (first - lastFirst).toFloat()
        lastFirst = first
        val v = abs(delta) * 0.9f
        rail.waveAmp = (rail.waveAmp + (v - rail.waveAmp) * 0.3f).coerceIn(0f, 6f)

        val relY = IntArray(rail.letters.size)
        val childH = if (list.childCount > 0) list.getChildAt(0).height else 120
        for (li in rail.letters.indices) {
            val rowIdx = letterRowIndex.getOrElse(li) { -1 }
            relY[li] = when {
                rowIdx < 0 -> 10_000
                rowIdx < first -> -1000 - li * 4        // above viewport: docked, order-preserving
                rowIdx < first + list.childCount -> {
                    val child = list.getChildAt(rowIdx - first)
                    child?.top ?: ((rowIdx - first) * childH)
                }
                else -> 10_000                          // below viewport: upright
            }
        }
        rail.letterRelY = relY
    }

    private var lastFirst = 0
    private var letterRowIndex: IntArray = IntArray(0)
    private val expandedCategories = HashSet<String>()

    private fun catMembers(name: String): Set<String> =
        LauncherPrefs.categoryMembers(this, name)

    // ================= folder pop-up cards =================
    private fun makeCard(content: LinearLayout): View {
        val wrap = FrameLayout(this)
        val card = FrameLayout(this)
        card.background = GradientDrawable().apply {
            cornerRadius = dp(20).toFloat()
            setColor(if (Theme.isDark(settings)) 0xFF14171B.toInt() else 0xFFFFFFFF.toInt())
            setStroke(dp(1), 0x22FFFFFF)
        }
        card.addView(content, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        wrap.setPadding(dp(28), 0, dp(28), 0)
        wrap.addView(card, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        return wrap
    }

    private fun openFolderDialog(name: String) {
        val members = folders[name] ?: return
        val entries = apps.filter { it.packageName in members } +
                workApps.filter { it.packageName in members }
        val d = Dialog(this)
        d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(12), dp(12))
        }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(TextView(this).apply {
            text = name; textSize = 18f; setTypeface(typeface, Typeface.BOLD)
            setTextColor(Theme.text(settings))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        val edit = TextView(this).apply {
            text = "Edit"; textSize = 13f; setTextColor(LauncherPrefs.accent(settings))
            setPadding(dp(12), dp(6), dp(12), dp(6))
        }
        edit.setOnClickListener { d.dismiss(); editFolderDialog(name) }
        head.addView(edit)
        col.addView(head)
        col.addView(TextView(this).apply {
            text = "${entries.size} app(s)"
            textSize = 11.5f; setTextColor(Theme.text2(settings))
            setPadding(0, dp(2), 0, dp(10))
        })
        if (entries.isEmpty()) {
            col.addView(TextView(this).apply {
                text = "Empty — long-press apps to add them"
                textSize = 13f; setTextColor(Theme.text2(settings)); setPadding(0, dp(8), 0, dp(8))
            })
        } else {
            val lv = ListView(this).apply {
                divider = null; dividerHeight = 0
                adapter = object : BaseAdapter() {
                    override fun getCount() = entries.size
                    override fun getItem(p: Int) = entries[p]
                    override fun getItemId(p: Int) = p.toLong()
                    override fun getView(p: Int, conv: View?, parent: ViewGroup): View {
                        val e = entries[p]
                        val row = conv ?: LinearLayout(this@MainActivity).apply {
                            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                            setPadding(dp(4), dp(8), dp(8), dp(8))
                            val iv = ImageView(this@MainActivity)
                            val tv = TextView(this@MainActivity).apply {
                                textSize = 15f; setTextColor(Theme.text(settings)); maxLines = 1
                            }
                            addView(iv, LinearLayout.LayoutParams(dp(34), dp(34)).apply { rightMargin = dp(14) })
                            addView(tv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                            tag = Pair(iv, tv)
                        }
                        val (iv, tv) = row.tag as Pair<ImageView, TextView>
                        e.icon?.let { iv.setImageDrawable(it) }
                        tv.text = e.label
                        row.setOnClickListener { d.dismiss(); e.launch(this@MainActivity) }
                        return row
                    }
                }
            }
            col.addView(lv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (entries.size * dp(52)).coerceAtMost(dp(360))))
        }
        d.setContentView(makeCard(col))
        d.window?.setBackgroundDrawableResource(android.R.color.transparent)
        d.show()
    }

    private fun editFolderDialog(name: String) {
        val members = folders[name] ?: mutableListOf()
        val all = apps + workApps
        val checked = BooleanArray(all.size) { all[it].packageName in members }
        val labels = all.map { it.label + if (it.user != null) " (work)" else "" }.toTypedArray()
        android.app.AlertDialog.Builder(this)
            .setTitle("Apps in “$name”")
            .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                val pkg = all[which].packageName
                if (isChecked) members.add(pkg)
                else members.removeAll { it == pkg }
            }
            .setPositiveButton("Save") { dlg, _ ->
                folders[name] = members
                LauncherPrefs.saveFolders(this, folders)
                adapter.buildRows(); adapter.notifyDataSetChanged(); setupRail()
                dlg.dismiss()
            }
            .setNeutralButton("Delete folder") { dlg, _ ->
                folders.remove(name)
                LauncherPrefs.saveFolders(this, folders)
                adapter.buildRows(); adapter.notifyDataSetChanged(); setupRail()
                dlg.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun editAppFoldersDialog(app: AppEntry) {
        val options = arrayOf("Folders…", "Categories…", "New folder with this app", "New category with this app")
        android.app.AlertDialog.Builder(this)
            .setTitle(app.label)
            .setItems(options) { dlg, which ->
                dlg.dismiss()
                when (which) {
                    0 -> folderAssignDialog(app)
                    1 -> categoryAssignDialog(app)
                    2 -> newFolderWithApp(app)
                    else -> newCategoryPrompt(app)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun folderAssignDialog(app: AppEntry) {
        val names = folders.keys.toTypedArray()
        val checked = BooleanArray(names.size) { folders[names[it]]?.contains(app.packageName) == true }
        val builder = android.app.AlertDialog.Builder(this)
            .setTitle("Folders for “${app.label}”")
        if (names.isNotEmpty()) {
            builder.setMultiChoiceItems(names, checked) { _, which, isChecked ->
                val pkg = app.packageName
                if (isChecked) folders[names[which]]?.add(pkg)
                else folders[names[which]]?.removeAll { it == pkg }
            }
        }
        builder
            .setPositiveButton("Save") { dlg, _ ->
                LauncherPrefs.saveFolders(this, folders)
                adapter.buildRows(); adapter.notifyDataSetChanged(); setupRail();
                dlg.dismiss()
            }
            .setNeutralButton("New folder…") { dlg, _ ->
                dlg.dismiss(); newFolderWithApp(app)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun categoryAssignDialog(app: AppEntry) {
        val names = LauncherPrefs.categories(this).toTypedArray()
        val checked = BooleanArray(names.size) { app.packageName in LauncherPrefs.categoryMembers(this, names[it]) }
        val builder = android.app.AlertDialog.Builder(this)
            .setTitle("Categories for “${app.label}”")
        if (names.isNotEmpty()) {
            builder.setMultiChoiceItems(names, checked) { _, which, isChecked ->
                val members = LauncherPrefs.categoryMembers(this, names[which]).toMutableSet()
                if (isChecked) members.add(app.packageName) else members.remove(app.packageName)
                LauncherPrefs.saveCategoryMembers(this, names[which], members)
            }
        }
        builder
            .setPositiveButton("Done") { dlg, _ ->
                adapter.buildRows(); adapter.notifyDataSetChanged(); setupRail()
                dlg.dismiss()
            }
            .setNeutralButton("New category…") { dlg, _ ->
                dlg.dismiss(); newCategoryPrompt(app)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun newFolderWithApp(app: AppEntry) {
        val input = android.widget.EditText(this).apply { hint = "Folder name"; setSingleLine(true) }
        android.app.AlertDialog.Builder(this)
            .setTitle("New folder")
            .setView(input)
            .setPositiveButton("Create") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    folders[name] = mutableListOf(app.packageName)
                    LauncherPrefs.saveFolders(this, folders)
                    adapter.buildRows(); adapter.notifyDataSetChanged(); setupRail();
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun editCategoryDialog(name: String) {
        val members = LauncherPrefs.categoryMembers(this, name).toMutableSet()
        val all = apps + workApps
        val checked = BooleanArray(all.size) { all[it].packageName in members }
        val labels = all.map { it.label + if (it.user != null) " (work)" else "" }.toTypedArray()
        android.app.AlertDialog.Builder(this)
            .setTitle("Apps in \u201C$name\u201D")
            .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                val pkg = all[which].packageName
                if (isChecked) members.add(pkg) else members.remove(pkg)
            }
            .setPositiveButton("Save") { dlg, _ ->
                LauncherPrefs.saveCategoryMembers(this, name, members)
                adapter.buildRows(); adapter.notifyDataSetChanged(); setupRail()
                dlg.dismiss()
            }
            .setNeutralButton("Delete category") { dlg, _ ->
                LauncherPrefs.deleteCategory(this, name)
                adapter.buildRows(); adapter.notifyDataSetChanged(); setupRail()
                dlg.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun newCategoryPrompt(app: AppEntry?) {
        val input = android.widget.EditText(this).apply { hint = "Category name (e.g. Favorites, Media)"; setSingleLine(true) }
        android.app.AlertDialog.Builder(this)
            .setTitle("New category")
            .setView(input)
            .setPositiveButton("Create") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    val names = LauncherPrefs.categories(this) + name
                    LauncherPrefs.saveCategories(this, names)
                    if (app != null) {
                        LauncherPrefs.saveCategoryMembers(this, name, setOf(app.packageName))
                    }
                    adapter.buildRows(); adapter.notifyDataSetChanged(); setupRail()
                    editCategoryDialog(name)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    inner class HomeAdapter : BaseAdapter() {
        private var rows: List<HomeRow> = emptyList()
        fun rowsIterator(): Iterator<HomeRow> = rows.iterator()

        fun buildRows() {
            val s = settings
            val out = ArrayList<HomeRow>()
            val used = HashSet<String>()
            var idx = 0
            var lastLetter = ""

            class Entry(val name: String, val app: AppEntry?)
            val entries = ArrayList<Entry>()
            for ((name, pkgs) in folders) used.addAll(pkgs)
            for (a in apps) {
                if (a.packageName in used) continue
                entries.add(Entry(a.label, a))
            }
            val coll = java.text.Collator.getInstance()
            val cmp = Comparator<Entry> { x: Entry, y: Entry -> coll.compare(x.name, y.name) }
            entries.sortWith(cmp)

            for (e in entries) {
                val letter = e.name.take(1).uppercase()
                if (letter != lastLetter) { out.add(HomeRow.Section(letter)); lastLetter = letter }
                out.add(HomeRow.App(e.app as AppEntry, idx < s.iconCount))
                idx++
            }

            if (s.showWork && workApps.isNotEmpty()) {
                out.add(HomeRow.WorkHeader)
                for (a in workApps) {
                    if (a.letter != lastLetter) { out.add(HomeRow.Section(a.letter)); lastLetter = a.letter }
                    out.add(HomeRow.App(a, idx < s.iconCount))
                    idx++
                }
            } else if (s.showWork && workApps.isEmpty() && workProfilePaused) {
                out.add(HomeRow.WorkHeader)
            }

            // top block: Categories section, then Folders section (only if non-empty)
            val top = ArrayList<HomeRow>()

            val sortedCats = LauncherPrefs.categories(this@MainActivity).sortedWith(
                Comparator { a: String, b: String -> a.compareTo(b, ignoreCase = true) })
            if (sortedCats.isNotEmpty()) {
                top.add(HomeRow.SectionBanner("CATEGORIES"))
                for (cname in sortedCats) {
                    val collapsed = cname !in expandedCategories
                    top.add(HomeRow.CategoryHeader(cname, collapsed))
                    if (!collapsed) {
                        for (a in apps) {
                            if (a.packageName in catMembers(cname)) top.add(HomeRow.App(a, false))
                        }
                    }
                }
            }

            val sortedFolders = folders.keys.sortedWith(
                Comparator { a: String, b: String -> a.compareTo(b, ignoreCase = true) })
            if (sortedFolders.isNotEmpty()) {
                top.add(HomeRow.SectionBanner("FOLDERS"))
                for (fname in sortedFolders) {
                    val members = folders[fname] ?: emptyList()
                    top.add(HomeRow.Folder(fname, members))
                }
            }

            top.addAll(out)
            out.clear()
            out.addAll(top)

            out.add(HomeRow.SettingsRow)
            rows = out
        }

        override fun getCount(): Int = rows.size
        override fun getItem(position: Int): HomeRow = rows[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = rows[position]
            val v: View
            val vh: RowVH
            if (convertView == null) {
                v = layoutInflater.inflate(R.layout.item_app, parent, false)
                vh = RowVH(v)
                v.tag = vh
            } else {
                v = convertView
                vh = v.tag as RowVH
            }
            val s = settings
            val accent = LauncherPrefs.accent(s)
            val font = Theme.fontFamily(s.fontIndex, Typeface.NORMAL)
            vh.name.typeface = font

            when (row) {
                is HomeRow.Section -> {
                    vh.name.visibility = View.INVISIBLE
                    vh.glyph.removeAllViews()
                    vh.glyph.addView(letterView(row.letter, accent, big = true),
                        FrameLayout.LayoutParams(dp(32), dp(32)))
                    vh.dot.visibility = View.GONE
                    v.isEnabled = false
                    v.setOnClickListener(null)
                    v.setOnLongClickListener(null)
                }
                is HomeRow.SectionBanner -> {
                    vh.name.visibility = View.VISIBLE
                    vh.name.textSize = 12f
                    vh.name.text = row.label
                    vh.name.setTypeface(vh.name.typeface, Typeface.BOLD)
                    vh.name.letterSpacing = 0.12f
                    vh.name.setTextColor(Theme.text2(s))
                    vh.glyph.removeAllViews()
                    vh.dot.visibility = View.GONE
                    v.isEnabled = false
                    v.setOnClickListener(null)
                    v.setOnLongClickListener(null)
                }
                is HomeRow.CategoryHeader -> {
                    vh.name.visibility = View.VISIBLE
                    vh.name.textSize = 21f
                    vh.name.setTypeface(vh.name.typeface, Typeface.BOLD)
                    val chevron = if (row.collapsed) "\u25B8" else "\u25BE"
                    vh.name.text = "$chevron \u2605 ${row.name}"
                    vh.name.setTextColor(LauncherPrefs.accent(s))
                    vh.glyph.removeAllViews()
                    vh.dot.visibility = View.GONE
                    v.isEnabled = true
                    v.setOnClickListener {
                        if (row.collapsed) expandedCategories.add(row.name)
                        else expandedCategories.remove(row.name)
                        adapter.buildRows()
                        adapter.notifyDataSetChanged()
                        list.setSelectionFromTop(0, 0)
                        setupRail()
                    }
                    v.setOnLongClickListener { editCategoryDialog(row.name); true }
                }
                is HomeRow.SettingsRow -> {
                    vh.name.visibility = View.VISIBLE
                    vh.name.textSize = 15f
                    vh.name.text = "\u2699 Settings"
                    vh.name.setTextColor(Theme.text(s))
                    vh.glyph.removeAllViews()
                    vh.glyph.addView(letterView("\u2699", accent),
                        FrameLayout.LayoutParams(dp(30), dp(30)))
                    vh.dot.visibility = View.GONE
                    v.isEnabled = true
                    v.setOnClickListener {
                        startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
                    }
                    v.setOnLongClickListener(null)
                }
                is HomeRow.WorkHeader -> {
                    vh.name.visibility = View.VISIBLE
                    vh.name.text = if (workProfilePaused) "WORK PROFILE \u2014 PAUSED (unpause in Settings)" else "WORK PROFILE"
                    vh.name.textSize = 11f
                    vh.name.setTextColor(Theme.text2(s))
                    vh.glyph.removeAllViews()
                    vh.dot.visibility = View.GONE
                    v.isEnabled = false
                    v.setOnClickListener(null)
                    v.setOnLongClickListener(null)
                }
                is HomeRow.App -> {
                    vh.name.visibility = View.VISIBLE
                    vh.name.textSize = 16f
                    vh.name.text = row.app.label
                    vh.name.setTextColor(Theme.text(s))
                    vh.glyph.removeAllViews()
                    if (row.app.icon != null) {
                        vh.glyph.addView(iconView(row.app.icon, s.iconShape),
                            FrameLayout.LayoutParams(dp(30), dp(30)))
                    } else {
                        vh.glyph.addView(letterView(row.app.letter, accent),
                            FrameLayout.LayoutParams(dp(32), dp(32)))
                    }
                    val n = NotifCounts.counts[row.app.packageName] ?: 0
                    vh.dot.visibility = if (n > 0) View.VISIBLE else View.GONE
                    v.isEnabled = true
                    v.setOnClickListener { row.app.launch(this@MainActivity) }
                    v.setOnLongClickListener { editAppFoldersDialog(row.app); true }
                }
                is HomeRow.Folder -> {
                    vh.name.visibility = View.VISIBLE
                    vh.name.textSize = 16f
                    vh.name.text = "${row.name} \u25B8"
                    vh.name.setTextColor(Theme.text(s))
                    vh.glyph.removeAllViews()
                    vh.glyph.addView(letterView(row.name.take(1).uppercase(), accent, folder = true),
                        FrameLayout.LayoutParams(dp(32), dp(32)))
                    vh.dot.visibility = View.GONE
                    v.isEnabled = true
                    v.setOnClickListener { openFolderDialog(row.name) }
                    v.setOnLongClickListener { editFolderDialog(row.name); true }
                }
            }
            return v
        }

        fun launch(pos: Int) {
            val row = rows.getOrNull(pos) ?: return
            if (row is HomeRow.App) row.app.launch(this@MainActivity)
        }

        private fun letterView(letter: String, accent: Int, big: Boolean = false, folder: Boolean = false) =
            TextView(this@MainActivity).apply {
                text = if (folder) "\u25A3" else letter
                setTextColor(accent)
                textSize = if (big) 17f else 17f
                gravity = Gravity.CENTER
                setTypeface(typeface, Typeface.BOLD)
            }

        private fun iconView(icon: android.graphics.drawable.Drawable, shape: Int) =
            ImageView(this@MainActivity).apply {
                setImageDrawable(icon)
                if (shape == 1) {
                    clipToOutline = true
                    outlineProvider = object : android.view.ViewOutlineProvider() {
                        override fun getOutline(view: View, outline: android.graphics.Outline) {
                            outline.setOval(0, 0, view.width.coerceAtLeast(1), view.height.coerceAtLeast(1))
                        }
                    }
                }
            }
    }

    class RowVH(v: View) {
        val glyph: FrameLayout = v.findViewById(R.id.glyph)
        val name: TextView = v.findViewById(R.id.name)
        val dot: View = v.findViewById(R.id.dot)
    }

    companion object {
        const val TAG = "WaveWidget"
        const val HOST_ID = 4242
        const val REQ_BIND_WIDGET = 101
        const val REQ_CONFIG_WIDGET = 102
    }
}

sealed class HomeRow {
    data class Section(val letter: String) : HomeRow()
    data class App(val app: AppEntry, val iconed: Boolean) : HomeRow()
    data class Folder(val name: String, val pkgNames: List<String>) : HomeRow()
    object WorkHeader : HomeRow()
    object SettingsRow : HomeRow()
    data class CategoryHeader(val name: String, val collapsed: Boolean) : HomeRow()
    data class SectionBanner(val label: String) : HomeRow()
}

/** minimal swipe-down detection without VelocityTracker plumbing */
object SwipeDetect {
    fun install(list: ListView, onSwipeDown: () -> Unit) {
        var startY = 0f
        list.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> startY = e.y
                MotionEvent.ACTION_UP -> {
                    val dy = e.y - startY
                    val lv = v as ListView
                    if (dy > 140 && lv.firstVisiblePosition == 0 &&
                        (lv.getChildAt(0)?.top ?: 0) >= 0) {
                        onSwipeDown()
                    }
                }
            }
            false
        }
    }
}
