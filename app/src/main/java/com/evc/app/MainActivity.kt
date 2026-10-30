package com.evc.app

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.MediaController
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import java.util.concurrent.Executors
import kotlin.math.max

class MainActivity : Activity() {
    private lateinit var videoView: VideoView
    private lateinit var statusText: TextView
    private lateinit var emptyHint: TextView
    private lateinit var storyboardRows: LinearLayout
    private lateinit var selectionText: TextView
    private val mainHandler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val selectedFrames = linkedSetOf<Int>()
    private val frameViews = mutableMapOf<Int, FrameLayout>()
    private var selectedUri: Uri? = null
    private var videoDurationMs = 0L
    private var storyboardGeneration = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        videoView = findViewById(R.id.videoView)
        statusText = findViewById(R.id.statusText)
        emptyHint = findViewById(R.id.emptyHint)
        storyboardRows = findViewById(R.id.storyboardRows)
        selectionText = findViewById(R.id.selectionText)
        findViewById<Button>(R.id.openButton).setOnClickListener { chooseVideo() }
        findViewById<Button>(R.id.clearSelectionButton).setOnClickListener {
            selectedFrames.clear()
            refreshSelectionStyles()
            updateSelectionText()
        }

        val controller = MediaController(this)
        controller.setAnchorView(videoView)
        videoView.setMediaController(controller)
        videoView.setOnPreparedListener { mediaPlayer ->
            emptyHint.visibility = View.GONE
            statusText.text = "ویدیو آمادهٔ پخش است"
            mediaPlayer.setOnVideoSizeChangedListener { _, _, _ -> controller.setAnchorView(videoView) }
        }
        videoView.setOnCompletionListener { statusText.text = "پخش تمام شد" }
        videoView.setOnErrorListener { _, what, extra ->
            statusText.text = "پخش این فایل ممکن نیست (خطا $what/$extra)"
            emptyHint.visibility = View.VISIBLE
            true
        }
        buildEmptyStoryboard()
    }

    private fun chooseVideo() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "video/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        try { startActivityForResult(intent, REQUEST_VIDEO) }
        catch (_: Exception) { Toast.makeText(this, "انتخاب‌گر فایل در دسترس نیست", Toast.LENGTH_LONG).show() }
    }

    @Deprecated("Uses the compatible document picker result API for broad Android support")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_VIDEO || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        try {
            val flags = data.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION
            if (flags != 0) contentResolver.takePersistableUriPermission(uri, flags)
        } catch (_: Exception) { /* Temporary read permission may still be enough for playback. */ }
        selectedUri = uri
        selectedFrames.clear()
        frameViews.clear()
        emptyHint.visibility = View.VISIBLE
        statusText.text = "در حال آماده‌سازی ویدیو و فریم‌ها…"
        videoView.setVideoURI(uri)
        videoView.requestFocus()
        loadStoryboard(uri)
    }

    private fun buildEmptyStoryboard() {
        storyboardRows.removeAllViews()
        frameViews.clear()
        for (row in 0 until ROWS) {
            val rowLabel = TextView(this).apply {
                text = "ردیف ${row + 1}  •  فریم‌های ${row * FRAMES_PER_ROW + 1} تا ${(row + 1) * FRAMES_PER_ROW}"
                setTextColor(Color.rgb(185, 215, 255)); textSize = 11f
                setPadding(dp(4), dp(3), dp(4), dp(3))
            }
            storyboardRows.addView(rowLabel)
            val horizontal = HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = false
            }
            val strip = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (col in 0 until FRAMES_PER_ROW) {
                val index = row * FRAMES_PER_ROW + col
                val cell = makeFrameCell(index, null, "${index + 1}")
                strip.addView(cell, LinearLayout.LayoutParams(dp(74), dp(56)).apply { setMargins(dp(2), dp(1), dp(2), dp(4)) })
                frameViews[index] = cell
            }
            horizontal.addView(strip)
            storyboardRows.addView(horizontal)
        }
        updateSelectionText()
    }

    private fun makeFrameCell(index: Int, bitmap: Bitmap?, label: String): FrameLayout {
        val cell = FrameLayout(this)
        val border = GradientDrawable().apply {
            setColor(Color.rgb(18, 35, 65)); cornerRadius = dp(5).toFloat()
            setStroke(dp(1), Color.rgb(61, 91, 135))
        }
        cell.background = border
        val image = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            if (bitmap != null) setImageBitmap(bitmap)
            else setImageResource(android.R.drawable.ic_media_play)
            setColorFilter(if (bitmap == null) Color.rgb(116, 150, 198) else Color.TRANSPARENT)
            contentDescription = "فریم ${index + 1}"
        }
        cell.addView(image, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val timeLabel = TextView(this).apply {
            text = label; textSize = 9f; setTextColor(Color.WHITE)
            setPadding(dp(3), 0, dp(3), 0); gravity = Gravity.CENTER
            setBackgroundColor(0xAA07142F.toInt())
        }
        cell.addView(timeLabel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(16), Gravity.BOTTOM))
        cell.setOnClickListener {
            if (selectedUri == null || videoDurationMs <= 0L) {
                Toast.makeText(this, "ابتدا یک ویدیو انتخاب کنید", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (!selectedFrames.add(index)) selectedFrames.remove(index)
            refreshCellStyle(index)
            updateSelectionText()
            val timeUs = (videoDurationMs * index / (ROWS * FRAMES_PER_ROW - 1)).coerceAtLeast(0L) * 1000L
            try { videoView.seekTo((timeUs / 1000L).toInt()) } catch (_: Exception) { }
        }
        return cell
    }

    private fun loadStoryboard(uri: Uri) {
        val generation = ++storyboardGeneration
        worker.execute {
            var retriever: MediaMetadataRetriever? = null
            try {
                retriever = MediaMetadataRetriever()
                retriever.setDataSource(this, uri)
                val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                mainHandler.post {
                    if (generation != storyboardGeneration) return@post
                    videoDurationMs = duration
                    statusText.text = if (duration > 0) "ویدیو: ${formatTime(duration)}  •  در حال ساخت ۹۰ فریم…" else "در حال ساخت استوری‌بورد…"
                }
                val count = ROWS * FRAMES_PER_ROW
                for (index in 0 until count) {
                    if (generation != storyboardGeneration) break
                    val timestampMs = if (count <= 1) 0L else duration * index / (count - 1)
                    val bitmap = try { retriever.getFrameAtTime(timestampMs * 1000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) } catch (_: Exception) { null }
                    if (bitmap != null) {
                        val scaled = Bitmap.createScaledBitmap(bitmap, 220, 132, true)
                        if (scaled !== bitmap) bitmap.recycle()
                        mainHandler.post {
                            if (generation == storyboardGeneration) {
                                val cell = frameViews[index]
                                val image = cell?.getChildAt(0) as? ImageView
                                image?.setImageBitmap(scaled)
                                image?.clearColorFilter()
                                (cell?.getChildAt(1) as? TextView)?.text = formatTime(timestampMs)
                            } else scaled.recycle()
                        }
                    }
                }
                mainHandler.post {
                    if (generation == storyboardGeneration) statusText.text = "استوری‌بورد آماده است: ۶ ردیف × ۱۵ فریم"
                }
            } catch (e: Exception) {
                mainHandler.post { if (generation == storyboardGeneration) statusText.text = "ساخت فریم‌ها ناموفق بود؛ پخش ویدیو همچنان در دسترس است" }
            } finally { try { retriever?.release() } catch (_: Exception) { } }
        }
    }

    private fun refreshSelectionStyles() { frameViews.keys.forEach { refreshCellStyle(it) } }
    private fun refreshCellStyle(index: Int) {
        val cell = frameViews[index] ?: return
        cell.background = GradientDrawable().apply {
            setColor(if (index in selectedFrames) Color.rgb(74, 61, 12) else Color.rgb(18, 35, 65))
            cornerRadius = dp(5).toFloat()
            setStroke(dp(if (index in selectedFrames) 3 else 1), if (index in selectedFrames) Color.rgb(255, 210, 31) else Color.rgb(61, 91, 135))
        }
    }
    private fun updateSelectionText() {
        selectionText.text = "انتخاب‌شده: ${selectedFrames.size} فریم از ۹۰  •  لمس فریم برای انتخاب/لغو"
    }
    private fun formatTime(ms: Long): String {
        val seconds = max(0L, ms / 1000L)
        return String.format("%02d:%02d", seconds / 60, seconds % 60)
    }
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    override fun onPause() {
        super.onPause()
        if (::videoView.isInitialized && videoView.isPlaying) videoView.pause()
    }
    override fun onDestroy() {
        storyboardGeneration++
        worker.shutdownNow()
        super.onDestroy()
    }
    companion object {
        private const val REQUEST_VIDEO = 2101
        private const val ROWS = 6
        private const val FRAMES_PER_ROW = 15
    }
}
