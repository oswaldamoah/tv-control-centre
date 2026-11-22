package com.oswald.tvcontrol

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.media.tv.TvContract
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.*
import okhttp3.*
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Same green theme as before, with a little more depth. */
private object Pal {
    val BG_TOP = 0xFF0B2A1C.toInt()
    val BG_BOTTOM = 0xFF04110B.toInt()
    val SURFACE = 0xFF0F2F21.toInt()
    val SURFACE_STROKE = 0xFF1B4733.toInt()
    val FOCUS_FILL = 0xFF174A33.toInt()
    val ACCENT = 0xFF7DE3A5.toInt()
    val ACCENT_DEEP = 0xFF3FA66B.toInt()
    val ON_ACCENT = 0xFF05241A.toInt()
    val TEXT = 0xFFE8FFF1.toInt()
    val MUTED = 0xFF9AC8AA.toInt()
    val WARN = 0xFFFFD27D.toInt()
    val ERROR = 0xFFFF8A80.toInt()
}

private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

class MainActivity : Activity() {
    private val prefs by lazy { getSharedPreferences("tvcc", MODE_PRIVATE) }
    private var socket: WebSocket? = null

    // Slideshow
    private var slides = mutableListOf<String>()
    private var slideIndex = 0
    private var currentBitmap: Bitmap? = null
    private var imageRequestId = 0
    private var inAmbient = false
    private var wallpaperFront: ImageView? = null
    private var wallpaperBack: ImageView? = null
    private var ambientOverlay: View? = null
    private val imageExecutor = Executors.newSingleThreadExecutor()

    // Shared views that get refreshed in place
    private var clockView: TextView? = null
    private var dateView: TextView? = null
    private var statusDot: View? = null
    private var statusText: TextView? = null
    private var shortcutBadge: TextView? = null
    private var connLabel = "Checking laptop companion…"
    private var connColor = Pal.WARN

    // Timers and connection state
    private val mainHandler = Handler(Looper.getMainLooper())
    private var activityResumed = false
    private var reconnectDelayMs = 3_000L
    private var reconnectRunnable: Runnable? = null
    private var heartbeatRunnable: Runnable? = null
    private val picker = 4101

    private val timeFmt by lazy { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    private val dateFmt by lazy { SimpleDateFormat("EEEE, d MMMM yyyy", Locale.getDefault()) }

    private val client by lazy {
        OkHttpClient.Builder().pingInterval(30, TimeUnit.SECONDS).retryOnConnectionFailure(true).build()
    }

    /** Keeps the clocks current and nudges the ambient overlay a few pixels each minute (burn-in protection). */
    private val tick = object : Runnable {
        override fun run() {
            updateClock()
            ambientOverlay?.translationX = ((SystemClock.elapsedRealtime() / 60_000L % 4L) * dp(10)).toFloat()
            mainHandler.postDelayed(this, 15_000L)
        }
    }

    private val slideTimer = object : Runnable {
        override fun run() {
            nextSlide()
            mainHandler.postDelayed(this, SLIDE_INTERVAL_MS)
        }
    }

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.systemUiVisibility = 5894
        slides = prefs.getString("slides", "").orEmpty().split("\n").filter { it.isNotBlank() }.toMutableList()
        dashboard()
        handleShortcutIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        activityResumed = true
        connectCompanion()
        startHeartbeat()
        mainHandler.removeCallbacks(tick)
        mainHandler.post(tick)
        refreshShortcutBadge()
        if (inAmbient) startSlideTimer()
    }

    override fun onPause() {
        stopHeartbeat()
        mainHandler.removeCallbacks(tick)
        stopSlideTimer()
        super.onPause()
    }

    override fun onStop() {
        activityResumed = false
        reconnectRunnable?.let(mainHandler::removeCallbacks)
        reconnectRunnable = null
        socket?.close(1000, "TV app not in foreground")
        socket = null
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShortcutIntent(intent)
    }

    override fun onDestroy() {
        stopHeartbeat()
        stopSlideTimer()
        mainHandler.removeCallbacks(tick)
        reconnectRunnable?.let(mainHandler::removeCallbacks)
        socket?.cancel()
        clearWallpaperImage()
        imageExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun handleShortcutIntent(intent: Intent?) {
        if (intent?.action != ACTION_REMOTE_SHORTCUT) return
        val digit = intent.getStringExtra(EXTRA_SHORTCUT_DIGIT) ?: return
        intent.action = null
        when (digit) {
            "1" -> selectHdmi(1)
            "2" -> selectHdmi(2)
            "3" -> selectHdmi(3)
            "4" -> selectHdmi(4)
            "5" -> launchShare()
            "6" -> openWifiRecovery()
            "7" -> ambient()
            "8" -> dashboard()
            "9" -> nextSlide()
            "0" -> selectHdmi(3)
        }
    }

    // ---------------------------------------------------------------- ui helpers

    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun rounded(fill: Int, radiusDp: Int, strokeColor: Int = 0, strokeDp: Int = 0) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(radiusDp).toFloat()
        if (strokeDp > 0) setStroke(dp(strokeDp), strokeColor)
    }

    private fun backdrop() = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Pal.BG_TOP, Pal.BG_BOTTOM))

    private fun text(value: String, sizeSp: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = value
        textSize = sizeSp
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun sectionLabel(label: String) = text(label, 15f, Pal.MUTED, bold = true).apply {
        setPadding(dp(4), dp(24), 0, dp(10))
    }

    /** A focusable card. D-pad focus lifts and outlines it; no external drawables needed. */
    private fun tile(
        title: String,
        subtitle: String? = null,
        primary: Boolean = false,
        subtitleColor: Int? = null,
        minHeightDp: Int = 84,
        action: () -> Unit
    ): LinearLayout {
        val fg = if (primary) Pal.ON_ACCENT else Pal.TEXT
        val sub = subtitleColor ?: if (primary) Pal.ON_ACCENT else Pal.MUTED
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(minHeightDp)
            setPadding(dp(22), dp(14), dp(22), dp(14))
            isFocusable = true
            isClickable = true
            background = StateListDrawable().apply {
                addState(
                    intArrayOf(android.R.attr.state_focused),
                    rounded(if (primary) Pal.ACCENT else Pal.FOCUS_FILL, 18, if (primary) Pal.TEXT else Pal.ACCENT, 3)
                )
                addState(
                    intArrayOf(),
                    rounded(if (primary) Pal.ACCENT_DEEP else Pal.SURFACE, 18, Pal.SURFACE_STROKE, 1)
                )
            }
            setOnFocusChangeListener { v, hasFocus ->
                val s = if (hasFocus) 1.04f else 1f
                v.animate().scaleX(s).scaleY(s).setDuration(140).start()
            }
            setOnClickListener { action() }
            addView(text(title, 20f, fg, bold = true).apply { maxLines = 1 })
            if (subtitle != null) addView(text(subtitle, 14f, sub).apply { maxLines = 1; setPadding(0, dp(2), 0, 0) })
        }
    }

    private fun row(vararg items: View): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        clipChildren = false
        clipToPadding = false
        items.forEachIndexed { i, v ->
            addView(v, LinearLayout.LayoutParams(0, MATCH, 1f).apply { if (i > 0) marginStart = dp(12) })
        }
    }

    private fun dialog() = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)

    // ---------------------------------------------------------------- dashboard

    private fun dashboard() {
        inAmbient = false
        stopSlideTimer()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        clearWallpaperImage()
        ambientOverlay = null

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = backdrop()
            setPadding(dp(56), dp(40), dp(56), dp(32))
            clipChildren = false
            clipToPadding = false
        }

        // Left: the clock is the focal point, status sits quietly underneath.
        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        left.addView(text("TV Control Centre", 20f, Pal.ACCENT, bold = true))

        val clock = text("--:--", 84f, Pal.TEXT).apply {
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            includeFontPadding = false
            setPadding(0, dp(20), 0, 0)
        }
        clockView = clock
        left.addView(clock)

        val date = text("", 18f, Pal.MUTED)
        dateView = date
        left.addView(date)

        left.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))

        val dot = View(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(connColor) }
        }
        statusDot = dot
        val statusLabel = text(connLabel, 16f, Pal.TEXT)
        statusText = statusLabel
        val pill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(20), dp(10))
            background = rounded(Pal.SURFACE, 999, Pal.SURFACE_STROKE, 1)
        }
        pill.addView(dot, LinearLayout.LayoutParams(dp(10), dp(10)).apply { marginEnd = dp(12) })
        pill.addView(statusLabel)
        left.addView(pill, LinearLayout.LayoutParams(WRAP, WRAP))

        val badge = text("", 14f, Pal.MUTED).apply { setPadding(dp(4), dp(14), 0, 0) }
        shortcutBadge = badge
        left.addView(badge)

        left.addView(text("Hisense A4, with HDMI 3 as the backup input", 13f, Pal.MUTED).apply {
            setPadding(dp(4), dp(8), 0, 0)
            alpha = 0.75f
        })

        // Right: scrollable tiles. Padding gives scaled tiles room so they never clip.
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
        }
        fun addRow(vararg tiles: View) {
            col.addView(row(*tiles), LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(12) })
        }

        col.addView(sectionLabel("Quick actions").apply { setPadding(dp(4), 0, 0, dp(10)) })
        val ambientTile = tile("Ambient mode", "Clock and slideshow", primary = true, minHeightDp = 96) { ambient() }
        addRow(ambientTile, tile("Screen share", "Cast wirelessly", minHeightDp = 96) { launchShare() })
        // first row has no top margin
        (col.getChildAt(1).layoutParams as LinearLayout.LayoutParams).topMargin = 0

        col.addView(sectionLabel("HDMI inputs"))
        val hdmi = (1..4).map { n ->
            tile("HDMI $n", if (n == 3) "Backup" else null, subtitleColor = if (n == 3) Pal.ACCENT else null, minHeightDp = 72) {
                selectHdmi(n)
            }
        }
        col.addView(row(*hdmi.toTypedArray()), LinearLayout.LayoutParams(MATCH, WRAP))

        col.addView(sectionLabel("Settings and recovery"))
        val host = prefs.getString("pc_ip", DEFAULT_IP).orEmpty()
        addRow(
            tile("Recovery", "Share, HDMI 3, home") { recovery() },
            tile("Slideshow images", "${slides.size} selected") { chooseImages() }
        )
        addRow(
            tile("Remote shortcuts", "Hold a number key for 3 seconds") { shortcutSetup() },
            tile("Windows companion", host) { companionSettings() }
        )
        addRow(tile("Back to Hisense TV", "Close this app", minHeightDp = 72) { finish() })

        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            clipChildren = false
            clipToPadding = false
            setPadding(dp(10), dp(10), dp(10), dp(10))
            addView(col, FrameLayout.LayoutParams(MATCH, WRAP))
        }

        root.addView(left, LinearLayout.LayoutParams(0, MATCH, 0.36f))
        root.addView(scroll, LinearLayout.LayoutParams(0, MATCH, 0.64f).apply { marginStart = dp(40) })
        setContentView(root)

        applyStatus()
        updateClock()
        refreshShortcutBadge()
        ambientTile.requestFocus()
    }

    private fun setConnection(label: String, color: Int) {
        connLabel = label
        connColor = color
        applyStatus()
    }

    private fun applyStatus() {
        statusText?.text = connLabel
        (statusDot?.background as? GradientDrawable)?.setColor(connColor)
    }

    private fun refreshShortcutBadge() {
        val on = shortcutServiceEnabled()
        shortcutBadge?.apply {
            text = if (on) "Remote shortcuts are on" else "Remote shortcuts need setup"
            setTextColor(if (on) Pal.ACCENT else Pal.WARN)
        }
    }

    private fun updateClock() {
        val now = Date()
        clockView?.text = timeFmt.format(now)
        dateView?.text = dateFmt.format(now)
    }

    // ---------------------------------------------------------------- ambient mode

    private fun ambient() {
        inAmbient = true
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val frame = FrameLayout(this).apply {
            background = backdrop()
            isFocusableInTouchMode = true
        }
        // Two layers so slides crossfade instead of snapping.
        val front = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        val back = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; alpha = 0f }
        wallpaperFront = front
        wallpaperBack = back
        frame.addView(front, FrameLayout.LayoutParams(MATCH, MATCH))
        frame.addView(back, FrameLayout.LayoutParams(MATCH, MATCH))

        val scrim = View(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0x00030D08, 0xDD030D08.toInt())
            )
        }
        frame.addView(scrim, FrameLayout.LayoutParams(MATCH, dp(320), Gravity.BOTTOM))

        val overlay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(56), 0, dp(56), dp(44))
        }
        val clock = text("--:--", 76f, -1).apply {
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            includeFontPadding = false
        }
        val date = text("", 20f, Pal.TEXT)
        clockView = clock
        dateView = date
        overlay.addView(clock)
        overlay.addView(date)
        if (slides.isEmpty()) {
            overlay.addView(text("No slideshow images yet. Press Back, then choose Slideshow images.", 14f, Pal.MUTED).apply {
                setPadding(0, dp(12), 0, 0)
            })
        }
        ambientOverlay = overlay
        frame.addView(overlay, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM))

        frame.setOnKeyListener { _, key, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (key) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_NEXT -> { nextSlide(); true }
                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_MENU -> { dashboard(); true }
                else -> false
            }
        }
        setContentView(frame)
        frame.requestFocus()
        updateClock()
        renderSlide()
        startSlideTimer()
    }

    private fun startSlideTimer() {
        stopSlideTimer()
        if (inAmbient && slides.size > 1 && SLIDE_INTERVAL_MS > 0) mainHandler.postDelayed(slideTimer, SLIDE_INTERVAL_MS)
    }

    private fun stopSlideTimer() = mainHandler.removeCallbacks(slideTimer)

    /** Decode wallpapers off the UI thread and downsample them to TV-sized images. */
    private fun renderSlide() {
        if (!inAmbient || slides.isEmpty()) return
        if (slideIndex >= slides.size) slideIndex = 0
        val uri = Uri.parse(slides[slideIndex])
        val requestId = ++imageRequestId
        try {
            imageExecutor.execute {
                val decoded = decodeWallpaper(uri, TARGET_W, TARGET_H)
                mainHandler.post {
                    if (decoded == null) return@post
                    if (requestId != imageRequestId || isFinishing || isDestroyed) {
                        if (!decoded.isRecycled) decoded.recycle()
                        return@post
                    }
                    showBitmap(decoded)
                }
            }
        } catch (_: Exception) { /* executor already shut down */ }
    }

    private fun showBitmap(bmp: Bitmap) {
        val front = wallpaperFront ?: return
        val back = wallpaperBack ?: return
        currentBitmap = bmp
        // Never recycle a bitmap that an ImageView may still be drawing.
        if (front.drawable == null) {
            front.setImageBitmap(bmp)
            front.alpha = 0f
            front.animate().alpha(1f).setDuration(600).start()
            return
        }
        back.animate().cancel()
        back.setImageBitmap(bmp)
        back.alpha = 0f
        back.animate().alpha(1f).setDuration(900).withEndAction {
            front.setImageBitmap(bmp)
            back.alpha = 0f
            back.setImageDrawable(null)
        }.start()
    }

    private fun decodeWallpaper(uri: Uri, reqWidth: Int, reqHeight: Int): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            val largestRatio = maxOf(
                bounds.outWidth.toDouble() / reqWidth,
                bounds.outHeight.toDouble() / reqHeight
            )
            var sample = 1
            while (sample < largestRatio) sample *= 2
            val options = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
        } catch (_: Exception) { null }
    }

    private fun clearWallpaperImage() {
        imageRequestId++
        wallpaperFront?.animate()?.cancel()
        wallpaperBack?.animate()?.cancel()
        wallpaperFront?.setImageDrawable(null)
        wallpaperBack?.setImageDrawable(null)
        wallpaperFront = null
        wallpaperBack = null
        // Drop our reference and let Android reclaim the bitmap when no drawable uses it.
        currentBitmap = null
    }

    private fun nextSlide() {
        if (slides.isEmpty()) return
        slideIndex = (slideIndex + 1) % slides.size
        renderSlide()
        // restart the countdown so a manual skip isn't followed by an instant auto-skip
        startSlideTimer()
    }

    // ---------------------------------------------------------------- image picker

    private fun chooseImages() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "image/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            addCategory(Intent.CATEGORY_OPENABLE)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(i, picker)
    }

    @Deprecated("Legacy picker callback for Android 11")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != picker || resultCode != RESULT_OK || data == null) return
        val chosen = mutableListOf<Uri>()
        data.clipData?.let { clip -> for (n in 0 until clip.itemCount) chosen.add(clip.getItemAt(n).uri) }
            ?: data.data?.let { chosen.add(it) }
        chosen.forEach { uri ->
            try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
            if (!slides.contains(uri.toString())) slides.add(uri.toString())
        }
        prefs.edit().putString("slides", slides.joinToString("\n")).apply()
        slideIndex = 0
        ambient()
    }

    // ---------------------------------------------------------------- TV actions

    private fun launchShare() {
        val i = packageManager.getLaunchIntentForPackage("com.mediatek.androidbox")
        if (i != null) startActivity(i) else toast("Screen Share has no exposed launch activity. Use the original TV app menu.")
    }

    private fun hdmi3() = selectHdmi(3)

    private fun shortcutServiceEnabled(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        return enabled.split(':').any {
            it.equals("$packageName/.RemoteShortcutService", true) ||
                it.equals("$packageName/${RemoteShortcutService::class.java.name}", true)
        }
    }

    private fun selectHdmi(number: Int) {
        try {
            val manager = getSystemService(TV_INPUT_SERVICE) as TvInputManager
            val candidates = manager.tvInputList.filter { it.type == TvInputInfo.TYPE_HDMI }
            val match = candidates.firstOrNull { info ->
                val label = info.loadLabel(this).toString().lowercase()
                val id = info.id.lowercase()
                label.contains("hdmi $number") || label.contains("hdmi$number") ||
                    id.contains("hdmi$number") || id.contains("hdmi_$number") ||
                    (number == 3 && id == "com.hisense.tv.hitvinput/.hdmi.hdmitvinputservice/hw6")
            }
            val inputId = match?.id ?: if (number == 3) {
                "com.hisense.tv.hitvinput/.hdmi.HdmiTvInputService/HW6"
            } else null
            if (inputId == null) {
                toast("HDMI $number was not identified by this TV firmware. Use INPUT on the remote.")
                return
            }
            startActivity(Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(inputId)))
        } catch (_: Exception) {
            toast("HDMI $number selection is not exposed by this firmware. Use the remote INPUT button.")
        }
    }

    private fun openWifiRecovery() {
        try { startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) }
        catch (_: Exception) { toast("Open Network Settings from the Hisense TV settings menu.") }
    }

    private fun shortcutSetup() {
        dialog().setTitle("3-second remote shortcuts")
            .setMessage(
                "Hold a number key continuously for 3 seconds to run its action. Shorter presses are not used by this app and continue to the foreground TV app.\n\n" +
                    "1 to 4: HDMI 1 to 4\n5: Screen Share\n6: Wi-Fi settings\n7: Ambient mode\n8: Dashboard\n9: Next background\n0: HDMI 3\n\n" +
                    "Enable TV Control Centre under Settings → Accessibility. Some Hisense firmware versions may not deliver global IR key events to third-party accessibility services."
            )
            .setPositiveButton("Open accessibility settings") { _, _ ->
                try { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                catch (_: Exception) { toast("Open Accessibility in TV Settings.") }
            }.setNegativeButton("Close", null).show()
    }

    private fun companionSettings() {
        val field = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine()
            setText(prefs.getString("pc_ip", DEFAULT_IP))
            hint = "Windows PC IPv4 address"
        }
        val holder = FrameLayout(this).apply {
            setPadding(dp(24), dp(8), dp(24), 0)
            addView(field, FrameLayout.LayoutParams(MATCH, WRAP))
        }
        dialog().setTitle("Windows companion IP")
            .setMessage("Enter the Windows PC IPv4 address shown in the companion app.")
            .setView(holder)
            .setPositiveButton("Save and reconnect") { _, _ ->
                val host = field.text.toString().trim()
                if (host.isNotBlank()) {
                    prefs.edit().putString("pc_ip", host).apply()
                    socket?.close(1000, "IP changed")
                    connectCompanion()
                    if (!inAmbient) dashboard() // refresh the address shown on the tile
                }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun recovery() {
        dialog().setTitle("Recovery options")
            .setItems(arrayOf("Reopen Screen Share", "Switch to HDMI 3", "Return to dashboard")) { _, which ->
                when (which) { 0 -> launchShare(); 1 -> hdmi3(); else -> dashboard() }
            }.show()
    }

    // ---------------------------------------------------------------- companion socket

    private fun connectCompanion() {
        if (!activityResumed) return
        reconnectRunnable?.let(mainHandler::removeCallbacks)
        reconnectRunnable = null
        socket?.cancel()
        socket = null
        setConnection("Connecting to laptop…", Pal.WARN)
        val host = prefs.getString("pc_ip", DEFAULT_IP)!!
        val request = try { Request.Builder().url("ws://$host:8765").build() }
            catch (_: Exception) {
                setConnection("Invalid laptop address", Pal.ERROR)
                scheduleReconnect()
                return
            }
        socket = client.newWebSocket(request, object : WebSocketListener() {
            // Callbacks from a socket we already replaced or closed are ignored (ws !== socket).
            override fun onOpen(ws: WebSocket, response: Response) {
                reconnectDelayMs = 3_000L
                runOnUiThread { if (ws === socket) setConnection("Laptop connected", Pal.ACCENT) }
                ws.send("{\"type\":\"hello\",\"device\":\"Hisense A4\",\"hdmiBackup\":\"HDMI 3\"}")
            }

            override fun onMessage(ws: WebSocket, text: String) {
                try {
                    val command = JSONObject(text).optString("command")
                    runOnUiThread {
                        if (ws !== socket) return@runOnUiThread
                        when (command) {
                            "ambient" -> ambient()
                            "dashboard" -> dashboard()
                            "screen_share" -> launchShare()
                            "hdmi1" -> selectHdmi(1)
                            "hdmi2" -> selectHdmi(2)
                            "hdmi3" -> hdmi3()
                            "hdmi4" -> selectHdmi(4)
                            "wifi_recovery" -> openWifiRecovery()
                            "next_wallpaper" -> nextSlide()
                        }
                    }
                } catch (_: Exception) {}
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                runOnUiThread {
                    if (ws !== socket) return@runOnUiThread
                    setConnection("Laptop offline, retrying…", Pal.ERROR)
                    scheduleReconnect()
                }
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                runOnUiThread {
                    if (ws !== socket) return@runOnUiThread
                    if (activityResumed) setConnection("Laptop disconnected", Pal.WARN)
                    if (activityResumed && code != 1000) scheduleReconnect()
                }
            }
        })
    }

    private fun scheduleReconnect() {
        if (!activityResumed || reconnectRunnable != null) return
        val delay = reconnectDelayMs
        reconnectDelayMs = (reconnectDelayMs * 2).coerceAtMost(30_000L)
        reconnectRunnable = Runnable {
            reconnectRunnable = null
            connectCompanion()
        }.also { mainHandler.postDelayed(it, delay) }
    }

    /** Main-thread heartbeat lets the Windows companion distinguish a live UI from a dead socket. */
    private fun startHeartbeat() {
        stopHeartbeat()
        heartbeatRunnable = object : Runnable {
            override fun run() {
                socket?.send("{\"type\":\"heartbeat\",\"uptimeMs\":${SystemClock.elapsedRealtime()}}")
                if (activityResumed) mainHandler.postDelayed(this, 5_000L)
            }
        }.also { mainHandler.postDelayed(it, 5_000L) }
    }

    private fun stopHeartbeat() {
        heartbeatRunnable?.let(mainHandler::removeCallbacks)
        heartbeatRunnable = null
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    companion object {
        const val ACTION_REMOTE_SHORTCUT = "com.oswald.tvcontrol.REMOTE_SHORTCUT"
        const val EXTRA_SHORTCUT_DIGIT = "shortcut_digit"
        private const val DEFAULT_IP = "192.168.8.159"
        /** Auto-advance interval for the ambient slideshow. Set to 0 to go back to manual-only. */
        private const val SLIDE_INTERVAL_MS = 45_000L
        /** Decode target. Raise to 1920x1080 if the TV has RAM to spare and 720p looks soft. */
        private const val TARGET_W = 1280
        private const val TARGET_H = 720
    }
}