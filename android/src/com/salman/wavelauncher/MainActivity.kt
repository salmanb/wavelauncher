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
import android.graphics.Canvas
import android.graphics.Point
import android.view.Gravity
import android.view.DragEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewConfiguration
import android.view.HapticFeedbackConstants
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageView
import android.widget.HorizontalScrollView
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
    private lateinit var widgetsHost: LinearLayout
    private lateinit var bottomStack: LinearLayout
    private lateinit var folderRowScroll: HorizontalScrollView
    private lateinit var folderRow: LinearLayout
    private lateinit var adapter: HomeAdapter
    private var apps: List<AppEntry> = emptyList()
    private var workApps: List<AppEntry> = emptyList()
    private var workProfilePaused: Boolean = false
    private var folders: MutableMap<String, MutableList<String>> = mutableMapOf()
    private var dockItems: MutableList<String> = mutableListOf()
    private val ui = Handler(Looper.getMainLooper())

    private lateinit var appWidgetHost: AppWidgetHost
    private lateinit var appWidgetManager: AppWidgetManager
    private val hostedWidgets = HashMap<Int, AppWidgetHostView>()
    private val widgetMisses = HashMap<Int, Int>()

    private val countsReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) = refreshDots()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        list = findViewById(R.id.list)
        rail = findViewById(R.id.rail)
        widgetsHost = findViewById(R.id.widgets)
        bottomStack = findViewById(R.id.bottomStack)
        folderRowScroll = findViewById(R.id.folderRowScroll)
        folderRow = findViewById(R.id.folderRow)
        bottomStack.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateListBottomInset() }
        // ONE permanent drag listener on the dock row: handles list-drag drops
        // (add/append), dock-internal reorder drops (bubbled from cells), and
        // drag-out removal (dock-internal drags that end outside the dock).
        val dockRowListener = View.OnDragListener { _, ev ->
            when (ev.action) {
                DragEvent.ACTION_DRAG_STARTED -> {
                    // accept every drag so this listener keeps receiving events
                    listDragKey != null || draggingDockKey != null
                }
                DragEvent.ACTION_DROP -> {
                    val key = ev.clipData?.getItemAt(0)?.text?.toString()
                        ?: return@OnDragListener false
                    // list-drag landing on the row (not a cell): append
                    if (listDragKey != null && !dockDropConsumed) {
                        dockDropConsumed = true
                        if (!dockItems.contains(key)) dockItems.add(key)
                        LauncherPrefs.saveDockItems(this, dockItems)
                        listDragKey = null
                        rebuildDock()
                        return@OnDragListener true
                    }
                    // dock-internal drag dropped on the row (outside any cell): remove
                    if (draggingDockKey != null) {
                        draggingDockKey = null
                        dockItems.remove(key)
                        LauncherPrefs.saveDockItems(this, dockItems)
                        rebuildDock()
                    }
                    true
                }
                DragEvent.ACTION_DRAG_ENDED -> {
                    folderRowScroll.alpha = 1f
                    // list-drag that ended without a dock drop: cancel + menu
                    if (listDragKey != null && !dockDropConsumed) {
                        val pkg = listDragKey!!.substringAfter(':')
                        listDragKey = null
                        (apps + workApps).firstOrNull { it.packageName == pkg }?.let {
                            editAppFoldersDialog(it)
                        }
                    }
                    draggingDockKey = null
                    true
                }
                else -> true
            }
        }
        bottomStack.setOnDragListener(dockRowListener)
        // long-press the dock area (empty space): add-folder menu. The HSV's
        // onTouchEvent does not call super, so View long-press never runs there;
        // use the same hold-timer pattern as the dock cells, returning false so
        // scrolling still works.
        folderRowScroll.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dockAddDown = ev.rawX to ev.rawY
                    dockHandler.removeCallbacks(dockAddRunnable)
                    dockHandler.postDelayed(dockAddRunnable, 450)
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    val slop = ViewConfiguration.get(this).scaledTouchSlop
                    if (abs(ev.rawX - dockAddDown.first) > slop || abs(ev.rawY - dockAddDown.second) > slop) {
                        dockHandler.removeCallbacks(dockAddRunnable)   // scrolling, not a hold
                    }
                    false
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    dockHandler.removeCallbacks(dockAddRunnable)
                    false
                }
                else -> false
            }
        }
        // root fallback: the dock row is scrollable/gone sometimes; root catches
        // drops in the whole bottom area and treats "released over the dock" as add
        findViewById<View>(R.id.root).setOnDragListener { _, ev ->
            when (ev.action) {
                DragEvent.ACTION_DRAG_LOCATION -> {
                    // remember the last drag position
                    dragX = ev.x; dragY = ev.y
                    true
                }
                DragEvent.ACTION_DROP -> {
                    android.util.Log.d("WaveWidget", "ROOT DROP: dragY=$dragY")
                    val key = ev.clipData?.getItemAt(0)?.text?.toString()
                        ?: return@setOnDragListener false
                    // was the finger over the dock area?
                    val loc = IntArray(2)
                    folderRowScroll.getLocationOnScreen(loc)
                    val over = dragY >= loc[1] - folderRowScroll.height
                    if (over && listDragKey != null && !dockDropConsumed) {
                        dockDropConsumed = true
                        if (!dockItems.contains(key)) dockItems.add(key)
                        LauncherPrefs.saveDockItems(this, dockItems)
                        listDragKey = null
                        rebuildDock()
                    } else if (!over && draggingDockKey != null) {
                        // dock-internal drag released outside the dock: remove
                        dockItems.remove(draggingDockKey)
                        LauncherPrefs.saveDockItems(this, dockItems)
                        rebuildDock()
                        draggingDockKey = null
                    }
                    true
                }
                DragEvent.ACTION_DRAG_ENDED -> {
                    folderRowScroll.alpha = 1f
                    if (listDragKey != null && !dockDropConsumed) {
                        val pkg = listDragKey!!.substringAfter(':')
                        listDragKey = null
                        (apps + workApps).firstOrNull { it.packageName == pkg }?.let {
                            editAppFoldersDialog(it)
                        }
                    }
                    draggingDockKey = null
                    true
                }
                else -> true
            }
        }

        appWidgetHost = AppWidgetHost(this, HOST_ID)
        appWidgetManager = AppWidgetManager.getInstance(this)

        adapter = HomeAdapter()
        list.adapter = adapter

        list.setOnItemClickListener { _, _, pos, _ -> adapter.launch(pos) }
        // catch drops over the list area: cancel the dock drag + open menu
        list.setOnDragListener { _, ev ->
            when (ev.action) {
                DragEvent.ACTION_DROP -> {
                    android.util.Log.d("WaveWidget", "LIST DROP (cancel)")
                    if (draggingDockKey != null) {
                        // dock-internal drag released over the list: remove
                        dockItems.remove(draggingDockKey)
                        LauncherPrefs.saveDockItems(this, dockItems)
                        rebuildDock()
                        draggingDockKey = null
                    } else if (listDragKey != null && !dockDropConsumed) {
                        val pkg = listDragKey!!.substringAfter(':')
                        listDragKey = null
                        rebuildDock()
                        (apps + workApps).firstOrNull { it.packageName == pkg }?.let {
                            editAppFoldersDialog(it)
                        }
                    }
                    true
                }
                else -> true
            }
        }


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
        // late providers are covered by the self-scheduling 2.5s retry in
        // restoreWidgets; fixed extra passes made miss counters climb too fast
    }

    override fun onStop() {
        super.onStop()
        appWidgetHost.stopListening()
    }

    override fun onResume() {
        super.onResume()
        applyTheme()
        rebuildDock()
        reloadApps()
        refreshDots()
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(countsReceiver) } catch (_: Exception) { }
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
                dockItems = LauncherPrefs.dockItems(this).toMutableList()
                adapter.buildRows()
                adapter.notifyDataSetChanged()
                rebuildDock()
                setupRail()
            }
        }.start()
    }

    private fun openSearch() {
        startActivity(Intent(this, SearchActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION))
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    private fun applyTheme() {
        val s = settings
        findViewById<View>(R.id.root).setBackgroundColor(Theme.bg(s))
        val barAlpha = (100 - settings.dockOpacity.coerceIn(0, 90)) * 255 / 100
        val barColor = if (Theme.isDark(s)) 0xFF1B1F24.toInt() else 0xFFE8EAED.toInt()
        folderRowScroll.setBackgroundColor((barColor and 0x00FFFFFF) or (barAlpha shl 24))
        val accent = LauncherPrefs.accent(s)
        rail.accentColor = accent
        rail.textColor = Theme.text2(s)
        rail.dark = Theme.isDark(s)
        rail.hintOffsetPx = dp(settings.scrollHintOffsetDp.coerceIn(24, 400)).toFloat()
        adapter.notifyDataSetChanged()
    }

    private fun refreshDots() {
        ui.post { adapter.notifyDataSetChanged() }
    }

    // ================= widgets =================
    private fun widgetHeightPx(info: AppWidgetProviderInfo): Int {
        // minHeight is already in px (framework converts the provider's dp at
        // parse time); multiplying by density again made cards 2.6-3.5x too tall
        return info.minHeight.coerceAtLeast(dp(100))
    }

    private var dockAddDown = 0f to 0f
    private val dockAddRunnable = Runnable { showDockAddMenu() }

    private var pendingWidgetId = -1   // id allocated by bindFlow, consumed in onActivityResult
    private var restorePending = false // B6: one self-scheduling retry at a time

    private fun restoreWidgets() {
        // system-bound ids are the source of truth: getAppWidgetIds() returns every
        // id the system still holds for this host — they survive updates even when
        // our prefs are empty, so adopt any we do not know about yet.
        restorePending = false
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
                } else if (misses < 5 && !restorePending) {
                    restorePending = true
                    ui.postDelayed({ if (isFinishing != true) restoreWidgets() }, 2500)
                }
                continue
            }
            widgetMisses.remove(id)
            systemBound.add(id)
            if (hostedWidgets.containsKey(id)) continue   // healthy card, leave it alone
            android.util.Log.d(TAG, "attach id=$id")
            attachWidget(id, info)
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
        hostedWidgets[id] = hostView
        // providers that size themselves need real dimensions
        val d = resources.displayMetrics.density
        val wDp = (widgetsHost.width / d).toInt().takeIf { it > 0 }
            ?: (resources.displayMetrics.widthPixels / d).toInt()
        hostView.updateAppWidgetSize(null, wDp, 100, wDp, 100)
        widgetsHost.post { hostView.updateAppWidgetSize(null, wDp, (hostView.height / d).toInt().coerceAtLeast(100), wDp, (hostView.height / d).toInt().coerceAtLeast(100)) }
        val card = wrapWidget(hostView, info)
        card.tag = id
        card.setOnLongClickListener {
            confirmRemoveWidget(id, info)
            true
        }
        widgetsHost.addView(card, widgetParams())
    }

    private fun updateListBottomInset() {
        bottomStack.post {
            list.setPadding(list.paddingLeft, list.paddingTop, list.paddingRight, bottomStack.height + dp(16))
            list.clipToPadding = false
        }
    }

    /** rounded-rect background for dock items */
    private fun pillBg(): android.graphics.drawable.GradientDrawable =
        android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = 24f * resources.displayMetrics.density
            setColor(if (Theme.isDark(settings)) 0xFF2A2F36.toInt() else 0xFFE8EAED.toInt())
        }

    /** long-press on the dock bar itself: add a new empty folder */
    private fun showDockAddMenu() {
        android.app.AlertDialog.Builder(this)
            .setTitle("Add to dock")
            .setItems(arrayOf("New folder")) { dlg, _ ->
                dlg.dismiss()
                var n = 1
                while (folders.keys.contains("Folder $n")) n++
                val name = "Folder $n"
                folders[name] = mutableListOf()
                LauncherPrefs.saveFolders(this, folders)
                if (!dockItems.contains("folder:$name")) dockItems.add("folder:$name")
                LauncherPrefs.saveDockItems(this, dockItems)
                adapter.buildRows(); adapter.notifyDataSetChanged(); rebuildDock(); setupRail()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** menu for a dock item: Open / Remove from dock */
    private fun showDockMenu(key: String) {
        val e = dockEntry(key) ?: return
        android.app.AlertDialog.Builder(this)
            .setTitle(e.label)
            .setItems(arrayOf("Open", "Remove from dock")) { dlg, which ->
                dlg.dismiss()
                when (which) {
                    0 -> {
                        if (e.isFolder) openFolderDialog(e.label)
                        else (apps + workApps).firstOrNull { it.packageName == key.substringAfter(':') }?.launch(this)
                    }
                    else -> {
                        dockItems.remove(key)
                        LauncherPrefs.saveDockItems(this, dockItems)
                        rebuildDock()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** dock entry: "app:<pkg>" or "folder:<name>"; resolve label + icon */
    private data class DockEntry(val key: String, val label: String, val icon: android.graphics.drawable.Drawable?, val isFolder: Boolean)

    private fun dockEntry(key: String): DockEntry? {
        val isFolder = key.startsWith("folder:")
        val id = key.substringAfter(':')
        if (isFolder) {
            if (folders[id] == null) return null          // folder deleted
            return DockEntry(key, id, null, true)
        }
        val a = (apps + workApps).firstOrNull { it.packageName == id } ?: return null
        return DockEntry(key, a.label, a.icon, false)
    }

    /** visible item count = the number of rows the desktop list shows (viewport / 56dp row) */
    private fun dockVisibleCount(): Int {
        val rowH = 56f * resources.displayMetrics.density
        val listH = list.height.takeIf { it > 0 } ?: (480f * resources.displayMetrics.density).toInt()
        return (listH / rowH).toInt().coerceIn(4, 10)
    }

    /** rebuild the dock: one cell per live item; dock resizes to content */
    private fun rebuildDock() {
        folderRow.removeAllViews()
        val density = resources.displayMetrics.density
        // prune keys that no longer resolve (uninstalled app / deleted folder)
        if (apps.isNotEmpty()) {
            val dead = dockItems.filter { dockEntry(it) == null }
            if (dead.isNotEmpty()) {
                dockItems.removeAll { it in dead }
                LauncherPrefs.saveDockItems(this, dockItems)
            }
        }
        val items = dockItems.toList()
        for ((i, key) in items.withIndex()) {
            val e = dockEntry(key) ?: continue
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                if (key.startsWith("folder:")) {
                    // folders stand out: accent outline + tinted bg
                    background = pillBg().apply {
                        setStroke((2 * density).toInt(), LauncherPrefs.accent(settings))
                        setColor((LauncherPrefs.accent(settings) and 0xFFFFFF) or 0x2E000000)
                    }
                } else {
                    background = pillBg()
                }
                setPadding((10 * density).toInt(), (6 * density).toInt(), (10 * density).toInt(), (6 * density).toInt())
                tag = key
                setOnDragListener { v, ev ->
                    when (ev.action) {
                        DragEvent.ACTION_DRAG_ENTERED -> { v.alpha = 0.6f; true }
                        DragEvent.ACTION_DRAG_EXITED, DragEvent.ACTION_DRAG_ENDED -> { v.alpha = 1f; true }
                        DragEvent.ACTION_DROP -> {
                            v.alpha = 1f
                            val dropKey = ev.clipData.getItemAt(0).text.toString()
                            val targetTag = v.tag as? String
                            android.util.Log.d("WaveWidget", "cell DROP: dropKey=$dropKey target=$targetTag")
                            if (dropKey != targetTag) {
                                if (dockItems.contains(dropKey)) dockItems.remove(dropKey)
                                // insert BEFORE the cell it was dropped on (existing shifts right)
                                val at = dockItems.indexOf(targetTag).let { t -> if (t >= 0) t else dockItems.size }
                                dockItems.add(at.coerceIn(0, dockItems.size), dropKey)
                                LauncherPrefs.saveDockItems(this@MainActivity, dockItems)
                            }
                            if (listDragKey != null) dockDropConsumed = true
                            draggingDockKey = null
                            listDragKey = null
                            rebuildDock()
                            true
                        }
                        else -> true
                    }
                }
            }
            val iconSize = dp(settings.dockIconSizeDp.coerceIn(14, 48))
            if (e.isFolder) {
                // mini preview: up to 3 member icons, in the same order the
                // folder view shows them (case-insensitive label sort)
                val memberEntries = (apps + workApps)
                    .filter { it.packageName in (folders[e.label] ?: emptyList()) }
                    .sortedWith(Comparator { a, b -> a.label.compareTo(b.label, ignoreCase = true) })
                val rowIcons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                for (me in memberEntries.take(3)) {
                    me.icon?.let {
                        rowIcons.addView(ImageView(this@MainActivity).apply {
                            setImageDrawable(it)
                            layoutParams = LinearLayout.LayoutParams(iconSize, iconSize)
                        })
                    }
                }
                if (rowIcons.childCount == 0) {
                    rowIcons.addView(TextView(this@MainActivity).apply {
                        text = "📁"; textSize = 15f
                    })
                }
                cell.addView(rowIcons)
            } else {
                cell.addView(ImageView(this@MainActivity).apply {
                    setImageDrawable(e.icon)
                    layoutParams = LinearLayout.LayoutParams(iconSize, iconSize)
                })
            }
            cell.addView(TextView(this@MainActivity).apply {
                text = e.label
                textSize = 10.5f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                if (e.isFolder) {
                    // folder label carries the accent too
                    setTextColor(LauncherPrefs.accent(settings))
                    setTypeface(typeface, Typeface.BOLD)
                } else {
                    setTextColor(Theme.text(settings))
                }
            })

            // hold-still: menu pops up while finger is down.
            // hold-then-move: drag starts (reorder or drag-out to remove).
            cell.setOnTouchListener { v2, ev ->
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        dockHandler.removeCallbacks(holdRunnable)
                        dockHandler.postDelayed(holdRunnable, 450)
                        holdKey = key
                        holdView = v2
                        holdFired = false
                        downX = ev.rawX; downY = ev.rawY
                        false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val slop = ViewConfiguration.get(this@MainActivity).scaledTouchSlop
                        val moved = abs(ev.rawX - downX) > slop || abs(ev.rawY - downY) > slop
                        if (!holdFired && moved) {
                            // let the HorizontalScrollView scroll
                            dockHandler.removeCallbacks(holdRunnable)
                            return@setOnTouchListener false
                        }
                        if (holdFired && moved) {
                            dockHandler.removeCallbacks(holdRunnable)
                            startDockDrag(key, v2)
                            holdFired = false
                            return@setOnTouchListener true
                        }
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        dockHandler.removeCallbacks(holdRunnable)
                        val swallow = holdFired   // menu shown: don't launch the app
                        holdFired = false
                        swallow
                    }
                    else -> false
                }
            }
            cell.setOnClickListener {
                if (e.isFolder) openFolderDialog(e.label, cell)
                else (apps + workApps).firstOrNull { it.packageName == e.key.substringAfter(':') }?.launch(this@MainActivity)
            }
            cell.layoutParams = android.widget.LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = (8 * density).toInt()
            }
            folderRow.addView(cell)
        }
        // empty dock: just the visible bar (add via long-press / app menu)
        folderRowScroll.visibility = View.VISIBLE
        folderRowScroll.minimumHeight = dp(64)
        updateListBottomInset()
    }

    private var draggingDockKey: String? = null
    private var listDragKey: String? = null      // item being dragged from the list
    private var dockDropConsumed = false         // drag was dropped on the dock
    private val dockHandler = Handler(Looper.getMainLooper())
    private var holdKey: String? = null
    private var holdView: View? = null
    private var holdFired = false
    private var downX = 0f
    private var downY = 0f
    private var touchDragOverlay: View? = null
    private var touchDragPill: View? = null

    /** long-press fired on a list row; finger still down, no movement yet */
    private inner class ArmedDrag(val app: AppEntry, val row: View, val armX: Float, val armY: Float)
    private var armedDrag: ArmedDrag? = null
    private var ghostRow: View? = null     // list row dimmed while dragging
    private var lastRawX = 0f
    private var lastRawY = 0f
    private var dragX = 0f
    private var dragY = 0f

    private val holdRunnable = object : Runnable {
        override fun run() {
            val k = holdKey ?: return
            holdFired = true
            holdView?.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            showDockMenu(k)   // menu appears while finger is still down
        }
    }

    private fun startDockDrag(key: String, v: View) {
        draggingDockKey = key
        val clip = android.content.ClipData.newPlainText("dock", key)
        v.startDragAndDrop(clip, android.view.View.DragShadowBuilder(v), key, 0)
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
                text = name
                textSize = 13f; setTextColor(Theme.text(settings))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
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
        pendingWidgetId = id
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
            // use the id we allocated; the intent's extra is often missing on
            // cancel and guessing from appWidgetIds can free a live widget
            val id = data?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1)?.takeIf { it != -1 }
                ?: pendingWidgetId
            pendingWidgetId = -1
            if (resultCode != RESULT_OK || id == -1) {
                if (id != -1) appWidgetHost.deleteAppWidgetId(id)
                return
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
            val id = data?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1)?.takeIf { it != -1 }
                ?: pendingWidgetId
            pendingWidgetId = -1
            val info = appWidgetManager.getAppWidgetInfo(id)
            if (resultCode == RESULT_OK && id != -1 && info != null) finishBind(id, info)
            else if (id != -1 && hostedWidgets[id] == null) appWidgetHost.deleteAppWidgetId(id)
        }
    }

    private fun finishBind(id: Int, info: AppWidgetProviderInfo) {
        attachWidget(id, info)
    }

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
        rail.onDragEnd = { rail.hideScrollHint() }

        list.setOnScrollListener(object : AbsScrollListener() {
            override fun onScroll(view: AbsListView, first: Int, visibleItemCount: Int, totalItemCount: Int) {
                updateWave()
                if (scrollTouchY >= 0f) showHintAt(scrollTouchY)
            }
        })
        updateWave()
        list.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastRawX = ev.rawX; lastRawY = ev.rawY
                    scrollTouchY = ev.rawY
                    swipeStartY = ev.y
                    showHintAt(ev.rawY)
                }
                MotionEvent.ACTION_MOVE -> {
                    lastRawX = ev.rawX; lastRawY = ev.rawY
                    scrollTouchY = ev.rawY
                    showHintAt(ev.rawY)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    scrollTouchY = -1f
                    rail.hideScrollHint()
                    val dy = ev.y - swipeStartY
                    val threshold = 56 * resources.displayMetrics.density
                    if (dy > threshold && list.firstVisiblePosition == 0 &&
                        (list.getChildAt(0)?.top ?: 0) >= 0) {
                        openSearch()
                    }
                }
            }
            v.onTouchEvent(ev)
        }
    }

    private fun updateWave() {
        val total = adapter.count
        if (total == 0 || rail.letters.isEmpty()) return
        val first = list.firstVisiblePosition

        // current letter = the letter of the topmost visible row
        rail.currentLetterIndex = run {
            var idx = -1
            for (i in 0 until list.childCount) {
                val row = adapter.getItem(first + i) ?: continue
                val L = when (row) {
                    is HomeRow.App -> row.app.letter
                    is HomeRow.Section -> row.letter
                    is HomeRow.Folder -> row.name.take(1).uppercase()
                    else -> null
                } ?: continue
                idx = rail.letters.indexOf(L)
                if (idx >= 0) break
            }
            idx
        }
    }

    private var letterRowIndex: IntArray = IntArray(0)

    /** y (list coords) of the finger while scrolling; -1 = not touching */
    private var scrollTouchY: Float = -1f
    private var swipeStartY: Float = 0f

    /** show the current-letter bubble at a screen-space y, converted to rail coords */
    private fun showHintAt(rawY: Float) {
        val loc = IntArray(2)
        rail.getLocationOnScreen(loc)
        topLetter()?.let { rail.showScrollHint(it, rawY - loc[1]) }
    }

    private fun topLetter(): String? {
        val first = list.firstVisiblePosition
        for (i in 0 until list.childCount) {
            val row = adapter.getItem(first + i) ?: continue
            val L = when (row) {
                is HomeRow.App -> row.app.letter
                is HomeRow.Section -> row.letter
                is HomeRow.Folder -> row.name.take(1).uppercase()
                else -> null
            }
            if (L != null) return L
        }
        return null
    }
    private val expandedCategories = HashSet<String>()

    private fun catMembers(name: String): Set<String> =
        LauncherPrefs.categoryMembers(this, name)

    // ================= folder pop-up cards =================
    private fun makeCard(content: LinearLayout, above: Int? = null): View {
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
        if (above != null) {
            // anchored mode: fixed-width card pinned above the dock icon's x,
            // bottom near the dock, clamped to stay fully on screen
            wrap.setPadding(0, 0, 0, 0)   // vertical placement comes from the window y offset
            wrap.addView(card, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
            wrap.post {
                // narrow screens: shrink to fit with an 8dp margin
                card.layoutParams = card.layoutParams.apply {
                    (this as? FrameLayout.LayoutParams)?.width =
                        minOf(dp(320), wrap.width - dp(8)).coerceAtLeast(dp(200))
                    card.layoutParams = this
                }
                val target = above - wrap.paddingLeft - card.measuredWidth / 2f
                val min = dp(4).toFloat()
                val max = (wrap.width - wrap.paddingLeft - wrap.paddingRight - card.measuredWidth - dp(4)).toFloat().coerceAtLeast(min)
                card.translationX = target.coerceIn(min, max)
            }
        } else {
            wrap.addView(card, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        }
        return wrap
    }

    private fun openFolderDialog(name: String, anchor: View? = null) {
        val members = folders[name] ?: return
        val entries = (apps.filter { it.packageName in members } +
                workApps.filter { it.packageName in members })
            .sortedWith(Comparator { a, b -> a.label.compareTo(b.label, ignoreCase = true) })
        // anchor geometry from the cell's real on-screen bounds
        var anchorX: Int? = null
        var anchorTop: Int? = null
        if (anchor != null) {
            val content = findViewById<View>(android.R.id.content)
            val cl = IntArray(2); content.getLocationOnScreen(cl)
            val al = IntArray(2); anchor.getLocationOnScreen(al)
            anchorX = al[0] + anchor.width / 2 - cl[0]
            anchorTop = al[1] - cl[1]
        }
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
            // 4x5 grid of icons (4 columns, scrollable past 5 rows)
            val gv = GridView(this).apply {
                numColumns = 4
                horizontalSpacing = dp(8); verticalSpacing = dp(10)
                gravity = Gravity.CENTER
                adapter = object : BaseAdapter() {
                    override fun getCount() = entries.size
                    override fun getItem(p: Int) = entries[p]
                    override fun getItemId(p: Int) = p.toLong()
                    override fun getView(p: Int, conv: View?, parent: ViewGroup): View {
                        val e = entries[p]
                        val cell = conv ?: LinearLayout(this@MainActivity).apply {
                            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                            setPadding(dp(4), dp(6), dp(4), dp(6))
                            val iv = ImageView(this@MainActivity)
                            val tv = TextView(this@MainActivity).apply {
                                textSize = 10.5f; setTextColor(Theme.text(settings)); maxLines = 1
                                ellipsize = android.text.TextUtils.TruncateAt.END
                            }
                            addView(iv, LinearLayout.LayoutParams(dp(34), dp(34)))
                            addView(tv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
                            tag = Pair(iv, tv)
                        }
                        val (iv, tv) = cell.tag as Pair<ImageView, TextView>
                        e.icon?.let { iv.setImageDrawable(it) }
                        tv.text = e.label
                        cell.setOnClickListener { d.dismiss(); e.launch(this@MainActivity) }
                        cell.setOnLongClickListener { d.dismiss(); editAppFoldersDialog(e); true }
                        return cell
                    }
                }
            }
            // cap by the space actually above the dock cell; grid height derives
            // from ROWS (4 columns), not entries — 8 apps = 2 rows, not 8
            val rows = (entries.size + 3) / 4
            val cap = anchorTop?.let { minOf(dp(300), it - dp(8) - dp(96)).coerceAtLeast(dp(58)) } ?: dp(300)
            col.addView(gv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (rows * dp(58)).coerceAtMost(cap)))
        }
        d.setContentView(makeCard(col, above = anchorX))
        d.window?.setBackgroundDrawableResource(android.R.color.transparent)
        d.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        if (anchorTop != null) {
            // bottom-pinned window, y derived from the cell's real bounds:
            // card bottom sits ~8dp above the tapped cell at any dock height
            val content = findViewById<View>(android.R.id.content)
            val lp = d.window!!.attributes
            lp.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            lp.y = content.height - anchorTop + dp(8)
            d.window!!.attributes = lp
        }
        d.show()
    }

    private fun editFolderDialog(name: String) {
        val members = folders[name] ?: mutableListOf()
        // "Add to dock" shortcut lives in the app-row menu; folders get it here too
        
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
                adapter.buildRows(); adapter.notifyDataSetChanged(); rebuildDock(); setupRail()
                dlg.dismiss()
            }
            .setNeutralButton("Delete folder") { dlg, _ ->
                folders.remove(name)
                LauncherPrefs.saveFolders(this, folders)
                adapter.buildRows(); adapter.notifyDataSetChanged(); rebuildDock(); setupRail()
                dlg.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }


    /** touch-driven list->dock drag: overlay dims the screen, a floating pill
     *  follows the finger, release over the dock adds the item (AOSP-style). */
    private fun startListTouchDrag(a: ArmedDrag) {
        val density = resources.displayMetrics.density
        listDragKey = "app:" + a.app.packageName
        ghostRow = a.row
        a.row.alpha = 0.35f         // row left behind reads as a ghost
        a.row.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)

        // full-screen overlay that intercepts the whole gesture
        val overlay = FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundColor(0x33000000)
        }
        // the floating pill: icon + label, follows the finger
        val pill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = pillBg()
            alpha = 0.9f            // a lifted copy of the row
            setPadding((12 * density).toInt(), (8 * density).toInt(), (12 * density).toInt(), (8 * density).toInt())
            addView(ImageView(this@MainActivity).apply {
                setImageDrawable(a.app.icon)
                layoutParams = LinearLayout.LayoutParams(dp(26), dp(26)).apply { marginEnd = dp(6) }
            })
            addView(TextView(this@MainActivity).apply {
                text = a.app.label
                textSize = 14f
                setTextColor(Theme.text(settings))
                maxLines = 1
            })
        }
        overlay.addView(pill, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        touchDragOverlay = overlay   // decorView child: remove THIS to tear down
        touchDragPill = pill
        // window content FrameLayout: full-size overlay that actually renders
        (window.decorView as android.view.ViewGroup).addView(overlay)

        // synchronous measure + layout so the pill never flashes at 0,0
        pill.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        pill.layout(0, 0, pill.measuredWidth, pill.measuredHeight)
        positionPill(lastRawX, lastRawY)

        // touch stream is routed via MainActivity.dispatchTouchEvent (the overlay
        // was added mid-gesture, so it never receives events itself)
    }

    /** place the pill above the finger given screen (raw) coordinates */
    private fun positionPill(rawX: Float, rawY: Float) {
        val pill = touchDragPill ?: return
        val decorLoc = IntArray(2); window.decorView.getLocationOnScreen(decorLoc)
        val pillW = pill.measuredWidth.takeIf { it > 0 } ?: 1
        val pillH = pill.measuredHeight.takeIf { it > 0 } ?: 1
        if (pill.width == 0) pill.layout(0, 0, pillW, pillH)   // un-laid child draws nothing
        pill.x = rawX - decorLoc[0] - pillW / 2f
        pill.y = rawY - decorLoc[1] - pillH - dp(28)          // pill floats above the finger
    }

    /** tear down the list->dock drag UI */
    private fun endListTouchDrag() {
        touchDragOverlay?.let { (window.decorView as android.view.ViewGroup).removeView(it) }
        touchDragOverlay = null
        touchDragPill = null
        ghostRow?.alpha = 1f
        ghostRow = null
        armedDrag = null
        listDragKey = null
        folderRowScroll.alpha = 1f
        scrollTouchY = -1f
        rail.hideScrollHint()   // ListView never sees UP after interception
    }

    /** intercept the whole touch stream: armed long-press + active list->dock drag */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        // row touches are dispatched to the row, never to the list's touch
        // listener — track the finger here so the arm position is always current
        if (ev.actionMasked == MotionEvent.ACTION_DOWN || ev.actionMasked == MotionEvent.ACTION_MOVE) {
            lastRawX = ev.rawX; lastRawY = ev.rawY
        }
        val armed = armedDrag
        if (armed != null && listDragKey == null) {
            when (ev.actionMasked) {
                MotionEvent.ACTION_MOVE -> {
                    // thumbs drift during a 450ms hold; scaledTouchSlop (~8px)
                    // fires on tremor — require a deliberate drag distance before
                    // converting the open menu into a drag
                    val armSlop = dp(14).toFloat()
                    if (abs(ev.rawX - armed.armX) > armSlop || abs(ev.rawY - armed.armY) > armSlop) {
                        lastRawX = ev.rawX; lastRawY = ev.rawY
                        appMenuDialog?.dismiss()       // menu converts into a drag
                        appMenuDialog = null
                        startListTouchDrag(armed)      // now dragging; later events hit the block below
                    }
                    return true                        // list must not scroll while armed
                }
                MotionEvent.ACTION_UP -> {
                    armedDrag = null
                    scrollTouchY = -1f; rail.hideScrollHint()
                    return true                        // menu is already open under the finger
                }
                MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_DOWN -> {
                    armedDrag = null
                    scrollTouchY = -1f; rail.hideScrollHint()
                    return super.dispatchTouchEvent(ev)
                }
                else -> return true
            }
        }
        if (listDragKey == null || touchDragOverlay == null || touchDragPill == null) {
            return super.dispatchTouchEvent(ev)
        }
        // stale-drag guard: a missed UP/CANCEL leaves drag state stuck; a fresh
        // DOWN clears it so the UI never goes half-dead
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            endListTouchDrag()
            return super.dispatchTouchEvent(ev)
        }
        // window-content coordinates: decorView origin = window origin
        val decorLoc = IntArray(2)
        window.decorView.getLocationOnScreen(decorLoc)
        val sx = ev.rawX - decorLoc[0]
        val sy = ev.rawY - decorLoc[1]
        val pill = touchDragPill!!
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                positionPill(ev.rawX, ev.rawY)
                val loc = IntArray(2)
                folderRowScroll.getLocationOnScreen(loc)
                val dockTop = loc[1] - decorLoc[1]
                val over = sy >= dockTop || pill.y + pill.height >= dockTop
                folderRowScroll.alpha = if (over) 1f else 0.75f
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val loc = IntArray(2)
                folderRowScroll.getLocationOnScreen(loc)
                val dockTop = loc[1] - decorLoc[1]
                val overDock = sy >= dockTop || pill.y + pill.height >= dockTop
                // capture before teardown: the pill's last position drives the hit-test
                val pillCx = pill.x + pill.width / 2f
                val pillBottom = pill.y + pill.height
                val key = listDragKey
                endListTouchDrag()
                android.util.Log.d("WaveWidget", "touchDrag end: overDock=$overDock fingerY=$sy dockTop=$dockTop key=$key")
                if (overDock && key != null) {
                    // releasing on a folder cell adds the app to that folder.
                    // the pill floats ~28dp above the finger: hit-test with the
                    // pill's bottom-center first, the finger as fallback
                    val dropped = hitTestDockCell(pillCx, pillBottom) ?: hitTestDockCell(sx, sy)
                    if (dropped != null && dropped.startsWith("folder:")) {
                        val fname = dropped.substringAfter(':')
                        val members = folders[fname] ?: mutableListOf()
                        val pkg = key.substringAfter(':')
                        if (!members.contains(pkg)) members.add(pkg)
                        folders[fname] = members
                        LauncherPrefs.saveFolders(this, folders)
                        adapter.buildRows(); adapter.notifyDataSetChanged(); rebuildDock(); setupRail()
                        android.util.Log.d("WaveWidget", "folder add ok: $pkg -> $fname")
                    } else {
                        if (!dockItems.contains(key)) dockItems.add(key)
                        LauncherPrefs.saveDockItems(this, dockItems)
                        rebuildDock()
                        android.util.Log.d("WaveWidget", "dock add ok: $key size=${dockItems.size}")
                    }
                }
                // released off the dock: cancel silently (the user chose to drag)
            }
        }
        return true   // consume: the ListView must not scroll while dragging
    }

    /** the dock cell at window coords (x, y), or null */
    private fun hitTestDockCell(x: Float, y: Float): String? {
        val dl = IntArray(2)
        window.decorView.getLocationOnScreen(dl)
        for (i in 0 until folderRow.childCount) {
            val c = folderRow.getChildAt(i)
            val loc = IntArray(2)
            c.getLocationOnScreen(loc)
            val left = loc[0] - dl[0]; val top = loc[1] - dl[1]
            if (x >= left && x < left + c.width && y >= top && y < top + c.height) {
                return c.tag as? String
            }
        }
        return null
    }

    private fun addToDock(key: String) {
        if (dockItems.contains(key)) dockItems.remove(key)
        dockItems.add(key)
        LauncherPrefs.saveDockItems(this, dockItems)
        rebuildDock()
    }

    private var appMenuDialog: android.app.AlertDialog? = null

    private fun editAppFoldersDialog(app: AppEntry) {
        val options = arrayOf("Add to dock", "Folders…", "New folder with this app", "App info", "Uninstall")
        val menu = android.app.AlertDialog.Builder(this)
            .setTitle(app.label)
            .setItems(options) { dlg, which ->
                dlg.dismiss()
                appMenuDialog = null
                when (which) {
                    0 -> addToDock("app:" + app.packageName)
                    1 -> folderAssignDialog(app)
                    2 -> newFolderWithApp(app)
                    3 -> {
                        val i = android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        i.data = android.net.Uri.fromParts("package", app.packageName, null)
                        i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        startActivity(i)
                    }
                    else -> {
                        val i = Intent(Intent.ACTION_DELETE)
                        i.data = android.net.Uri.fromParts("package", app.packageName, null)
                        i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        startActivity(i)
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
        appMenuDialog = menu
    }

    private fun folderAssignDialog(app: AppEntry) {
        val names = folders.keys.toTypedArray()
        val checked = BooleanArray(names.size) { folders[names[it]]?.contains(app.packageName) == true }
        // work on copies; commit to live state only on Save (Cancel = no change)
        val draft = folders.mapValues { it.value.toMutableList() }.toMutableMap()
        val builder = android.app.AlertDialog.Builder(this)
            .setTitle("Folders for “${app.label}”")
        if (names.isNotEmpty()) {
            builder.setMultiChoiceItems(names, checked) { _, which, isChecked ->
                val pkg = app.packageName
                if (isChecked) draft[names[which]]?.add(pkg)
                else draft[names[which]]?.removeAll { it == pkg }
            }
        }
        builder
            .setPositiveButton("Save") { dlg, _ ->
                folders.clear(); folders.putAll(draft)
                LauncherPrefs.saveFolders(this, folders)
                adapter.buildRows(); adapter.notifyDataSetChanged(); rebuildDock(); setupRail()
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
                val name = input.text.toString().trim().replace("|", "")
                if (name.isNotEmpty()) {
                    // existing folder of the same name gains the app; never wiped
                    folders.getOrPut(name) { mutableListOf() }.let {
                        if (app.packageName !in it) it.add(app.packageName)
                    }
                    LauncherPrefs.saveFolders(this, folders)
                    adapter.buildRows(); adapter.notifyDataSetChanged(); rebuildDock(); setupRail()
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
            var idx = 0
            var lastLetter = ""

            class Entry(val name: String, val app: AppEntry?)
            val entries = ArrayList<Entry>()
            for (a in apps) {
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

            // folders live only in the dock now: plain A-Z list + work + settings
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
                    v.setOnLongClickListener {
                        // long-press fired: show the menu NOW, while the finger is
                        // still down. dispatchTouchEvent watches for movement — if
                        // the finger drags past the slop, dismiss the menu and
                        // start the drag instead (decided per-gesture).
                        armedDrag = ArmedDrag(row.app, v, lastRawX, lastRawY)
                        editAppFoldersDialog(row.app)
                        true
                    }
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
                    v.setOnLongClickListener {
                        val opts = arrayOf("Add to dock", "Edit apps in folder")
                        android.app.AlertDialog.Builder(this@MainActivity)
                            .setTitle(row.name)
                            .setItems(opts) { dlg, which ->
                                dlg.dismiss()
                                when (which) {
                                    0 -> addToDock("folder:" + row.name)
                                    else -> editFolderDialog(row.name)
                                }
                            }
                            .setNegativeButton("Cancel", null)
                            .show()
                        true
                    }
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

