package com.oswald.tvcontrol

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.*
import okhttp3.*
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class MainActivity : Activity() {
    private val prefs by lazy { getSharedPreferences("tvcc", MODE_PRIVATE) }
    private var socket: WebSocket? = null
    private lateinit var status: TextView
    private lateinit var wallpaper: ImageView
    private var slides = mutableListOf<String>()
    private var slideIndex = 0
    private val picker = 4101
    private val client by lazy { OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).build() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.decorView.systemUiVisibility = 5894
        slides = prefs.getString("slides", "").orEmpty().split("\n").filter { it.isNotBlank() }.toMutableList()
        dashboard()
        connectCompanion()
    }

    private fun makeScreen(title: String): LinearLayout {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 28, 48, 28)
            setBackgroundColor(0xFF071B13.toInt())
        }
        root.addView(TextView(this).apply {
            text = title; textSize = 28f; setTextColor(0xFFE8FFF1.toInt())
        })
        setContentView(root)
        return root
    }

    private fun button(parent: LinearLayout, label: String, action: () -> Unit) {
        parent.addView(Button(this).apply {
            text = label; textSize = 18f; isFocusable = true
            setOnClickListener { action() }
        }, LinearLayout.LayoutParams(-1, 68).apply { topMargin = 8 })
    }

    private fun dashboard() {
        val col = makeScreen("TV CONTROL CENTRE")
        col.addView(TextView(this).apply {
            text = "HISENSE A4  •  HDMI BACKUP: HDMI 3  •  AMBIENT: SLIDESHOW"
            textSize = 14f; setTextColor(0xFF9AC8AA.toInt())
        })
        status = TextView(this).apply {
            text = "Laptop companion: checking…"; textSize = 16f
            setTextColor(0xFF7DE3A5.toInt()); setPadding(0, 16, 0, 8)
        }
        col.addView(status)
        button(col, "AMBIENT MODE") { ambient() }
        button(col, "WIRELESS SCREEN SHARE") { launchShare() }
        button(col, "HDMI 3 — BACKUP") { hdmi3() }
        button(col, "RECOVERY OPTIONS") { recovery() }
        button(col, "CHANGE SLIDESHOW IMAGES") { chooseImages() }
        button(col, "NORMAL HISENSE TV") { finish() }
    }

    private fun ambient() {
        val frame = FrameLayout(this).apply { setBackgroundColor(0xFF071B13.toInt()) }
        wallpaper = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        frame.addView(wallpaper, FrameLayout.LayoutParams(-1, -1))
        val overlay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.BOTTOM
            setPadding(48, 28, 48, 40); setBackgroundColor(0x66071912)
        }
        overlay.addView(TextView(this).apply {
            text = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
            textSize = 56f; setTextColor(-1)
        })
        overlay.addView(TextView(this).apply {
            text = java.text.SimpleDateFormat("EEEE, d MMMM yyyy", java.util.Locale.getDefault()).format(java.util.Date())
            textSize = 20f; setTextColor(0xFFE0F4E7.toInt())
        })
        frame.addView(overlay, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        frame.isFocusableInTouchMode = true
        frame.setOnKeyListener { _, key, event ->
            if (event.action != android.view.KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (key) {
                android.view.KeyEvent.KEYCODE_DPAD_CENTER, android.view.KeyEvent.KEYCODE_ENTER,
                android.view.KeyEvent.KEYCODE_MEDIA_NEXT -> { nextSlide(); true }
                android.view.KeyEvent.KEYCODE_BACK, android.view.KeyEvent.KEYCODE_MENU -> { dashboard(); true }
                else -> false
            }
        }
        setContentView(frame); frame.requestFocus(); renderSlide()
    }

    private fun renderSlide() {
        if (slides.isEmpty()) return
        if (slideIndex >= slides.size) slideIndex = 0
        try {
            contentResolver.openInputStream(Uri.parse(slides[slideIndex])).use {
                wallpaper.setImageBitmap(BitmapFactory.decodeStream(it))
            }
        } catch (_: Exception) {}
    }

    private fun nextSlide() {
        if (slides.isNotEmpty()) { slideIndex = (slideIndex + 1) % slides.size; renderSlide() }
    }

    private fun chooseImages() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "image/*"; putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
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

    private fun launchShare() {
        val i = packageManager.getLaunchIntentForPackage("com.mediatek.androidbox")
        if (i != null) startActivity(i) else toast("Screen Share has no exposed launch activity. Use the original TV app menu.")
    }

    private fun hdmi3() {
        val inputId = "com.hisense.tv.hitvinput/.hdmi.HdmiTvInputService/HW6"
        try {
            val uri = android.media.tv.TvContract.buildChannelUriForPassthroughInput(inputId)
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (_: Exception) { toast("HDMI 3 selection is not exposed. Use the remote INPUT button.") }
    }

    private fun recovery() {
        AlertDialog.Builder(this).setTitle("Recovery options")
            .setItems(arrayOf("Reopen Screen Share", "Switch to HDMI 3", "Return to dashboard")) { _, which ->
                when (which) { 0 -> launchShare(); 1 -> hdmi3(); else -> dashboard() }
            }.show()
    }

    private fun connectCompanion() {
        // Replace with the Windows PC's IPv4 address before installing.
        val host = prefs.getString("pc_ip", "192.168.8.159")!!
        val request = Request.Builder().url("ws://$host:8765").build()
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                runOnUiThread { if (::status.isInitialized) status.text = "Laptop companion: connected" }
                ws.send("{\"type\":\"hello\",\"device\":\"Hisense A4\",\"hdmiBackup\":\"HDMI 3\"}")
            }
            override fun onMessage(ws: WebSocket, text: String) {
                try {
                    when (JSONObject(text).optString("command")) {
                        "ambient" -> runOnUiThread { ambient() }
                        "dashboard" -> runOnUiThread { dashboard() }
                        "screen_share" -> runOnUiThread { launchShare() }
                        "hdmi3" -> runOnUiThread { hdmi3() }
                        "next_wallpaper" -> runOnUiThread { nextSlide() }
                    }
                } catch (_: Exception) {}
            }
            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                runOnUiThread { if (::status.isInitialized) status.text = "Laptop offline — remote controls remain available" }
            }
            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                runOnUiThread { if (::status.isInitialized) status.text = "Laptop companion: disconnected" }
            }
        })
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
    override fun onDestroy() { socket?.close(1000, "closing"); super.onDestroy() }
}
