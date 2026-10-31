package com.evc.app

import android.app.Activity
import android.app.TimePickerDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.content.ContentValues
import android.os.Environment
import android.provider.MediaStore
import android.media.MediaMetadataRetriever
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaCodec
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.MotionEvent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.MediaController
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import androidx.media3.common.MediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.VideoEncoderSettings
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.Composition
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ExportException
import androidx.media3.effect.RgbAdjustment
import java.util.concurrent.Executors
import java.io.File
import java.io.FileInputStream
import kotlin.math.max

class MainActivity : Activity() {
    private lateinit var videoView: VideoView
    private lateinit var playerColorPreview: ImageView
    private val previewBitmaps = mutableMapOf<Int, Bitmap>()
    private var playerPreviewRequest = 0
    private lateinit var statusText: TextView
    private lateinit var emptyHint: TextView
    private lateinit var storyboardRows: LinearLayout
    private lateinit var selectionText: TextView
    private lateinit var timeModeButton: Button
    private lateinit var selectForDeleteModeButton: Button
    private lateinit var selectForExportModeButton: Button
    private var selectingForExport = false
    private lateinit var timelineView: TimelineView
    private val timelineSplitPoints = sortedSetOf<Long>()
    private val deletedTimelineRanges = mutableListOf<Pair<Long, Long>>()
    private var selectedTimelineRange: Pair<Long, Long>? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    // Keep timeline sampling independent so 120 storyboard frames cannot block the timeline strip.
    private val timelineThumbnailWorker = Executors.newSingleThreadExecutor()
    private val selectedFrames = linkedSetOf<Int>()
    private val frameViews = mutableMapOf<Int, FrameLayout>()
    private val frameBitmaps = mutableMapOf<Int, Bitmap>()
    private val deletedStoryboardFrames = linkedSetOf<Int>()
    private var selectedUri: Uri? = null
    private var activeTransformer: Transformer? = null
    private var videoDurationMs = 0L
    private var storyboardStartMs = 0L
    private var storyboardEndMs = 0L
    private var storyboardGeneration = 0
    private data class RgbProfile(val red: Float, val green: Float, val blue: Float)
    private val frameColorProfiles = mutableMapOf<Int, RgbProfile>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        videoView = findViewById(R.id.videoView)
        playerColorPreview = findViewById(R.id.playerColorPreview)
        statusText = findViewById(R.id.statusText)
        emptyHint = findViewById(R.id.emptyHint)
        storyboardRows = findViewById(R.id.storyboardRows)
        selectionText = findViewById(R.id.selectionText)
        timeModeButton = findViewById(R.id.timeModeButton)
        selectForDeleteModeButton = findViewById(R.id.selectForDeleteModeButton)
        selectForExportModeButton = findViewById(R.id.selectForExportModeButton)
        timelineView = findViewById(R.id.timelineView)
        timelineView.onSeek = { timeMs ->
            try { videoView.seekTo(timeMs.coerceIn(0L, videoDurationMs).toInt()) } catch (_: Exception) { }
        }
        timelineView.onSelectRange = { range ->
            selectedTimelineRange = range
            statusText.text = "قطعهٔ انتخابی: ${formatTime(range.first)} تا ${formatTime(range.second)}"
        }
        findViewById<Button>(R.id.splitTimelineButton).setOnClickListener {
            if (videoDurationMs <= 0L) { Toast.makeText(this, "ابتدا ویدیو انتخاب کنید", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            if (videoDurationMs < 2L) { Toast.makeText(this, "مدت ویدیو برای برش کافی نیست", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            // Cut at the actual player position at the instant the button is tapped,
            // not the last position manually selected on the timeline.
            val currentPosition = try { videoView.currentPosition.toLong() } catch (_: Exception) { timelineView.playheadMs }
            if (currentPosition <= 0L || currentPosition >= videoDurationMs) {
                Toast.makeText(this, "ویدیو را به زمان موردنظر پخش یا جابه‌جا کنید و دوباره Cut بزنید", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val at = currentPosition.coerceIn(1L, videoDurationMs - 1L)
            if (timelineSplitPoints.add(at)) {
                timelineView.setPlayhead(at)
                timelineView.setTimeline(videoDurationMs, timelineSplitPoints.toList(), deletedTimelineRanges)
                selectedTimelineRange = null
                statusText.text = "Cut ثبت شد: ${formatTime(at)}؛ با هر بار زدن، برش دیگری در زمان فعلی پخش ثبت می‌شود"
            } else Toast.makeText(this, "در این زمان برشی ثبت شده است", Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.deleteTimelineSegmentButton).setOnClickListener {
            val range = selectedTimelineRange
            if (range == null) { Toast.makeText(this, "ابتدا یک قطعه از نوار زمان را انتخاب کنید", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            if (deletedTimelineRanges.none { it.first == range.first && it.second == range.second }) deletedTimelineRanges.add(range)
            selectedTimelineRange = null
            timelineView.setTimeline(videoDurationMs, timelineSplitPoints.toList(), deletedTimelineRanges)
            statusText.text = "قطعهٔ ${formatTime(range.first)} تا ${formatTime(range.second)} برای حذف از خروجی علامت خورد"
        }
        findViewById<Button>(R.id.restoreTimelineButton).setOnClickListener {
            deletedTimelineRanges.clear(); timelineSplitPoints.clear(); selectedTimelineRange = null
            timelineView.setTimeline(videoDurationMs, emptyList(), emptyList())
            statusText.text = "برش‌ها و حذف‌های نوار زمان بازگردانی شدند"
        }
        findViewById<Button>(R.id.exportSegmentsSeparatelyButton).setOnClickListener { exportTimelineSegmentsSeparately() }

        findViewById<Button>(R.id.openButton).setOnClickListener { chooseVideo() }
        findViewById<Button>(R.id.selectAllButton).setOnClickListener {
            if (selectedUri == null || videoDurationMs <= 0L) {
                Toast.makeText(this, "ابتدا یک ویدیو انتخاب کنید", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            selectedFrames.clear()
            selectedFrames.addAll((0 until ROWS * FRAMES_PER_ROW).filter { it !in deletedStoryboardFrames })
            refreshSelectionStyles()
            updateSelectionText()
        }
        findViewById<Button>(R.id.clearSelectionButton).setOnClickListener {
            selectedFrames.clear()
            refreshSelectionStyles()
            updateSelectionText()
        }
        findViewById<Button>(R.id.deleteSelectedButton).setOnClickListener { deleteSelectedStoryboardFrames() }
        selectForDeleteModeButton.setOnClickListener { setStoryboardSelectionMode(false) }
        selectForExportModeButton.setOnClickListener { setStoryboardSelectionMode(true) }
        findViewById<Button>(R.id.exportButton).setOnClickListener { exportRemainingVideo(selectingForExport) }
        findViewById<Button>(R.id.applyColorToSelectedButton).setOnClickListener { applyColorToSelectedFrames() }
        findViewById<Button>(R.id.colorizePreviewButton).setOnClickListener { exportColorizedVideo() }
        setupManualColorControls()
        timeModeButton.setOnClickListener { showTimeMode() }
        findViewById<Button>(R.id.applyStoryboardTimeRangeButton).setOnClickListener { applyStoryboardTimeRange() }
        findViewById<Button>(R.id.pickStoryboardStartButton).setOnClickListener { showStoryboardTimePicker(true) }
        findViewById<Button>(R.id.pickStoryboardEndButton).setOnClickListener { showStoryboardTimePicker(false) }

        val controller = MediaController(this)
        controller.setAnchorView(videoView)
        videoView.setMediaController(controller)
        videoView.setOnPreparedListener { mediaPlayer ->
            emptyHint.visibility = View.GONE
            playerColorPreview.visibility = View.GONE
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
        updateModeButtons()
        updateStoryboardSelectionModeButtons()
        handleIncomingVideoIntent(intent)
    }

    /** Accepts a video shared from another app through Android's Sharesheet. */
    private fun handleIncomingVideoIntent(incoming: Intent?) {
        if (incoming == null) return
        val action = incoming.action
        if (action != Intent.ACTION_SEND && action != Intent.ACTION_SEND_MULTIPLE) return
        val sharedUri: Uri? = if (android.os.Build.VERSION.SDK_INT >= 33) {
            incoming.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            (incoming.getParcelableExtra<android.os.Parcelable>(Intent.EXTRA_STREAM) as? Uri)
        }
        val uri = sharedUri ?: incoming.clipData?.getItemAt(0)?.uri
        if (uri == null) {
            Toast.makeText(this, "ویدیوی اشتراک‌گذاری‌شده قابل دریافت نیست", Toast.LENGTH_LONG).show()
            return
        }
        loadSelectedVideo(uri, false)
    }

    private fun loadSelectedVideo(uri: Uri, persistPermission: Boolean) {
        if (persistPermission) {
            try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) { }
        }
        selectedUri = uri
        storyboardStartMs = 0L
        storyboardEndMs = 0L
        selectingForExport = false
        selectedForExportButtonReset()
        timelineSplitPoints.clear()
        deletedTimelineRanges.clear()
        selectedTimelineRange = null
        timelineView.setTimeline(0L, emptyList(), emptyList())
        timelineView.setThumbnails(emptyList())
        selectedFrames.clear()
        frameBitmaps.values.forEach { if (!it.isRecycled) it.recycle() }
        frameBitmaps.clear()
        previewBitmaps.values.forEach { if (!it.isRecycled) it.recycle() }
        previewBitmaps.clear()
        playerColorPreview.setImageDrawable(null)
        playerColorPreview.visibility = View.GONE
        deletedStoryboardFrames.clear()
        frameColorProfiles.clear()
        buildEmptyStoryboard()
        emptyHint.visibility = View.VISIBLE
        statusText.text = "در حال آماده‌سازی ویدیو و استخراج فریم‌ها…"
        videoView.setVideoURI(uri)
        videoView.requestFocus()
        loadStoryboard(uri)
    }

    /** Sets up manual RGB channel controls for the real video export. */
    private fun setupManualColorControls() {
        val red = findViewById<SeekBar>(R.id.redColorSeek)
        val green = findViewById<SeekBar>(R.id.greenColorSeek)
        val blue = findViewById<SeekBar>(R.id.blueColorSeek)
        val redValue = findViewById<TextView>(R.id.redColorValue)
        val greenValue = findViewById<TextView>(R.id.greenColorValue)
        val blueValue = findViewById<TextView>(R.id.blueColorValue)
        fun updateLabels() {
            redValue.text = "قرمز ${"%.2f".format(java.util.Locale.US, red.progress / 100f)}"
            greenValue.text = "سبز ${"%.2f".format(java.util.Locale.US, green.progress / 100f)}"
            blueValue.text = "آبی ${"%.2f".format(java.util.Locale.US, blue.progress / 100f)}"
        }
        val listener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                updateLabels()
                if (fromUser) refreshLiveColorPreview()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        }
        red.setOnSeekBarChangeListener(listener)
        green.setOnSeekBarChangeListener(listener)
        blue.setOnSeekBarChangeListener(listener)
        updateLabels()
    }

    /** Save the current RGB combination for every selected storyboard interval. */
    private fun applyColorToSelectedFrames() {
        if (selectedUri == null || videoDurationMs <= 0L) {
            Toast.makeText(this, "ابتدا ویدیو را انتخاب کنید", Toast.LENGTH_SHORT).show(); return
        }
        if (selectedFrames.isEmpty()) {
            Toast.makeText(this, "ابتدا یک یا چند اسلاید را انتخاب کنید", Toast.LENGTH_LONG).show(); return
        }
        val profile = currentRgbProfile()
        selectedFrames.forEach { frameColorProfiles[it] = profile }
        refreshLiveColorPreview()
        statusText.text = "ترکیب رنگ فعلی برای ${selectedFrames.size} اسلاید ذخیره شد؛ برای هر اسلاید می‌توان ترکیب دیگری ثبت کرد"
        Toast.makeText(this, "تنظیم رنگ برای ${selectedFrames.size} اسلاید ذخیره شد", Toast.LENGTH_SHORT).show()
    }

    /** Updates storyboard thumbnails immediately and shows a tinted still-frame overlay in the player. */
    private fun refreshLiveColorPreview() {
        val current = currentRgbProfile()
        for ((index, original) in frameBitmaps) {
            if (original.isRecycled) continue
            val profile = if (selectedFrames.isEmpty() || index in selectedFrames) current else frameColorProfiles[index] ?: RgbProfile(1f, 1f, 1f)
            val tinted = tintBitmap(original, profile)
            previewBitmaps.put(index, tinted)?.let { old -> if (!old.isRecycled) old.recycle() }
            val cell = frameViews[index]
            val image = cell?.let { container ->
                (0 until container.childCount).mapNotNull { container.getChildAt(it) as? ImageView }.firstOrNull()
            }
            image?.setImageBitmap(tinted)
        }
        val source = selectedUri ?: run { playerColorPreview.visibility = View.GONE; return }
        val request = ++playerPreviewRequest
        val position = try { videoView.currentPosition.toLong().coerceAtLeast(0L) } catch (_: Exception) { 0L }
        playerColorPreview.visibility = View.VISIBLE
        statusText.text = "پیش‌نمایش زندهٔ رنگ در حال به‌روزرسانی است…"
        worker.execute {
            var retriever: MediaMetadataRetriever? = null
            try {
                retriever = MediaMetadataRetriever()
                retriever.setDataSource(this, source)
                val frame = retriever.getFrameAtTime(position * 1000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                if (frame != null) {
                    val tinted = tintBitmap(frame, current)
                    if (!frame.isRecycled) frame.recycle()
                    mainHandler.post {
                        if (request == playerPreviewRequest && !isFinishing) {
                            playerColorPreview.setImageBitmap(tinted)
                            playerColorPreview.visibility = View.VISIBLE
                            statusText.text = "پیش‌نمایش رنگ روی اسلایدها و فریم فعلی پلیر به‌روز شد"
                        } else if (!tinted.isRecycled) tinted.recycle()
                    }
                }
            } catch (_: Exception) {
                mainHandler.post {
                    if (request == playerPreviewRequest) {
                        playerColorPreview.visibility = View.GONE
                        statusText.text = "پیش‌نمایش اسلایدها به‌روز شد؛ گرفتن فریم پلیر ممکن نشد"
                    }
                }
            } finally { try { retriever?.release() } catch (_: Exception) {} }
        }
    }

    private fun tintBitmap(source: Bitmap, profile: RgbProfile): Bitmap {
        val w = source.width
        val h = source.height
        val pixels = IntArray(w * h)
        source.getPixels(pixels, 0, w, 0, 0, w, h)
        for (i in pixels.indices) {
            val c = pixels[i]
            pixels[i] = Color.argb(Color.alpha(c),
                (Color.red(c) * profile.red).toInt().coerceIn(0, 255),
                (Color.green(c) * profile.green).toInt().coerceIn(0, 255),
                (Color.blue(c) * profile.blue).toInt().coerceIn(0, 255))
        }
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { it.setPixels(pixels, 0, w, 0, 0, w, h) }
    }

    private fun currentRgbProfile() = RgbProfile(
        findViewById<SeekBar>(R.id.redColorSeek).progress / 100f,
        findViewById<SeekBar>(R.id.greenColorSeek).progress / 100f,
        findViewById<SeekBar>(R.id.blueColorSeek).progress / 100f
    )

    /** Exports the full video with an independently saved RGB effect for each storyboard interval. */
    private fun exportColorizedVideo() {
        val source = selectedUri
        if (source == null || videoDurationMs <= 0L) {
            Toast.makeText(this, "ابتدا یک ویدیوی معتبر انتخاب کنید", Toast.LENGTH_LONG).show(); return
        }
        val sizeField = findViewById<android.widget.EditText>(R.id.targetSizeMb)
        val targetMb = sizeField.text.toString().trim().replace(',', '.').toFloatOrNull()
        if (targetMb == null || targetMb < 1f || targetMb > 4096f) {
            sizeField.error = "حجم هدف را بین 1 تا 4096 مگابایت وارد کنید"; return
        }
        val button = findViewById<Button>(R.id.colorizePreviewButton)
        button.isEnabled = false
        statusText.text = "در حال ساخت خروجی با اعمال تغییرات رنگ و حجم هدف؛ رمزگذاری مجدد انجام می‌شود…"
        val output = File(cacheDir, "evc_colorized_${System.currentTimeMillis()}.mp4")
        try {
            val total = ROWS * FRAMES_PER_ROW
            val neutral = RgbProfile(1f, 1f, 1f)
            val items = (0 until total).mapNotNull { index ->
                val startMs = videoDurationMs * index / total
                val endMs = videoDurationMs * (index + 1) / total
                if (endMs <= startMs) return@mapNotNull null
                val profile = frameColorProfiles[index] ?: neutral
                val effect = RgbAdjustment.Builder()
                    .setRedScale(profile.red).setGreenScale(profile.green).setBlueScale(profile.blue).build()
                val clippedMedia = MediaItem.Builder().setUri(source)
                    .setClippingConfiguration(MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(startMs).setEndPositionMs(endMs).build()).build()
                EditedMediaItem.Builder(clippedMedia).setEffects(Effects(emptyList(), listOf(effect))).build()
            }
            if (items.isEmpty()) throw IllegalStateException("بازهٔ قابل خروجی پیدا نشد")
            // Estimate video bitrate from target size and duration. Final size can vary with content and audio.
            val totalBitsPerSecond = (targetMb.toDouble() * 1024.0 * 1024.0 * 8.0 / (videoDurationMs / 1000.0)).toInt()
            val videoBitrate = (totalBitsPerSecond - 128_000).coerceIn(200_000, 20_000_000)
            val encoderFactory = DefaultEncoderFactory.Builder(this)
                .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(videoBitrate).build())
                .build()
            val composition = Composition.Builder(EditedMediaItemSequence.Builder(items).build()).build()
            val transformer = Transformer.Builder(this)
                .setEncoderFactory(encoderFactory)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        activeTransformer = null
                        button.isEnabled = true
                        try {
                            val values = ContentValues().apply {
                                put(MediaStore.Video.Media.DISPLAY_NAME, "EVC_colorized_${System.currentTimeMillis()}.mp4")
                                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                                if (android.os.Build.VERSION.SDK_INT >= 29) {
                                    put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/EVC")
                                    put(MediaStore.Video.Media.IS_PENDING, 1)
                                }
                            }
                            val outUri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                                ?: throw IllegalStateException("ساخت فایل در Movies/EVC ممکن نشد")
                            contentResolver.openOutputStream(outUri)?.use { out -> FileInputStream(output).use { it.copyTo(out) } }
                                ?: throw IllegalStateException("نوشتن فایل خروجی ناموفق بود")
                            if (android.os.Build.VERSION.SDK_INT >= 29) contentResolver.update(outUri, ContentValues().apply {
                                put(MediaStore.Video.Media.IS_PENDING, 0)
                            }, null, null)
                            output.delete()
                            statusText.text = "خروجی رنگی در Movies/EVC ذخیره شد. حجم هدف حدود ${targetMb} MB بود؛ رمزگذاری مجدد انجام شد."
                            Toast.makeText(this@MainActivity, "خروجی رنگی ذخیره شد؛ حجم نهایی ممکن است با هدف تفاوت داشته باشد", Toast.LENGTH_LONG).show()
                        } catch (e: Exception) {
                            output.delete(); statusText.text = "ذخیرهٔ خروجی ناموفق بود: ${e.message ?: "خطا"}"
                            Toast.makeText(this@MainActivity, statusText.text, Toast.LENGTH_LONG).show()
                        }
                    }
                    override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                        activeTransformer = null; output.delete(); button.isEnabled = true
                        statusText.text = "خروجی رنگی ناموفق بود: ${exportException.message ?: "خطای رمزگذاری"}"
                        Toast.makeText(this@MainActivity, statusText.text, Toast.LENGTH_LONG).show()
                    }
                }).build()
            activeTransformer = transformer
            transformer.start(composition, output.absolutePath)
        } catch (e: Exception) {
            activeTransformer = null; output.delete(); button.isEnabled = true
            statusText.text = "شروع خروجی رنگی ناموفق بود: ${e.message ?: "خطا"}"
            Toast.makeText(this, statusText.text, Toast.LENGTH_LONG).show()
        }
    }

    // Experimental thumbnail-only colorization preview. It does not alter the source video
    // or the exported video; this first prototype uses luminance heuristics, not AI segmentation.
    private fun colorizeStoryboardPreview() {
        if (frameBitmaps.isEmpty()) {
            Toast.makeText(this, "ابتدا ویدیو را انتخاب کنید و منتظر استخراج اسلایدها بمانید", Toast.LENGTH_LONG).show()
            return
        }
        var processed = 0
        for ((index, original) in frameBitmaps) {
            if (original.isRecycled) continue
            val w = original.width
            val h = original.height
            val pixels = IntArray(w * h)
            original.getPixels(pixels, 0, w, 0, 0, w, h)
            for (i in pixels.indices) {
                val c = pixels[i]
                val r = Color.red(c)
                val g = Color.green(c)
                val b = Color.blue(c)
                val y = (0.299 * r + 0.587 * g + 0.114 * b).toInt()
                val chroma = maxOf(r, g, b) - minOf(r, g, b)
                if (chroma < 28 && y in 35..220) {
                    // Heuristic only: mid/darker tones receive a restrained green tint;
                    // brighter midtones receive a restrained warm tint.
                    pixels[i] = if (y < 118) {
                        Color.rgb((y * 0.72).toInt().coerceIn(0,255),
                                  (y * 1.08 + 8).toInt().coerceIn(0,255),
                                  (y * 0.70).toInt().coerceIn(0,255))
                    } else {
                        Color.rgb((y * 1.10 + 12).toInt().coerceIn(0,255),
                                  (y * 0.88 + 5).toInt().coerceIn(0,255),
                                  (y * 0.72).toInt().coerceIn(0,255))
                    }
                }
            }
            val preview = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            preview.setPixels(pixels, 0, w, 0, 0, w, h)
            val cell = frameViews[index]
            val image = cell?.let { container ->
                (0 until container.childCount).mapNotNull { container.getChildAt(it) as? ImageView }.firstOrNull()
            }
            image?.setImageBitmap(preview)
            processed++
        }
        statusText.text = "پیش‌نمایش رنگی‌سازی آزمایشی روی $processed اسلاید اعمال شد؛ ویدیوی اصلی و خروجی تغییر نکرده‌اند"
        Toast.makeText(this, "این نسخه فقط پیش‌نمایش اسلایدها را رنگی می‌کند؛ خروجی ویدیویی رنگی هنوز فعال نیست", Toast.LENGTH_LONG).show()
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
        loadSelectedVideo(uri, true)
    }

    private fun buildEmptyStoryboard() {
        storyboardRows.removeAllViews()
        frameViews.clear()
        for (row in 0 until ROWS) {
            val rowLabel = TextView(this).apply {
                text = "ردیف ${row + 1} • فریم‌های ${row * FRAMES_PER_ROW + 1} تا ${(row + 1) * FRAMES_PER_ROW}"
                setTextColor(Color.rgb(185, 215, 255)); textSize = 11f
                setPadding(dp(2), 0, dp(2), 0)
            }
            storyboardRows.addView(rowLabel)
            val horizontal = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
            val strip = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (col in 0 until FRAMES_PER_ROW) {
                val index = row * FRAMES_PER_ROW + col
                if (index in deletedStoryboardFrames) continue
                val cell = makeFrameCell(index, null, "${index + 1}")
                strip.addView(cell, LinearLayout.LayoutParams(dp(62), dp(48)).apply { setMargins(0, 0, dp(1), 0) })
                frameViews[index] = cell
            }
            horizontal.addView(strip)
            storyboardRows.addView(horizontal)
        }
        updateSelectionText()
    }

    private fun makeFrameCell(index: Int, bitmap: Bitmap?, label: String): FrameLayout {
        val cell = FrameLayout(this)
        cell.background = GradientDrawable().apply {
            setColor(Color.rgb(18, 35, 65)); cornerRadius = dp(2).toFloat()
        }
        val image = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            if (bitmap != null) setImageBitmap(bitmap) else setImageResource(android.R.drawable.ic_media_play)
            if (bitmap == null) setColorFilter(Color.rgb(116, 150, 198))
            contentDescription = "فریم ${index + 1}"
        }
        cell.addView(image, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val timeLabel = TextView(this).apply {
            text = label; textSize = 9f; setTextColor(Color.WHITE)
            setPadding(dp(3), 0, dp(3), 0); gravity = Gravity.CENTER
            setBackgroundColor(0xAA07142F.toInt())
        }
        cell.addView(timeLabel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(16), Gravity.BOTTOM))
        val badge = TextView(this).apply {
            text = "✓"; textSize = 15f; setTextColor(Color.BLACK); gravity = Gravity.CENTER
            setBackgroundColor(Color.rgb(255, 210, 31)); visibility = View.GONE
        }
        val badgeParams = FrameLayout.LayoutParams(dp(22), dp(22), Gravity.TOP or Gravity.END)
        cell.addView(badge, badgeParams)
        cell.tag = badge
        cell.foreground = makeBorder(index in selectedFrames)
        cell.setOnClickListener {
            if (selectedUri == null || videoDurationMs <= 0L) {
                Toast.makeText(this, "ابتدا یک ویدیو انتخاب کنید", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (!selectedFrames.add(index)) selectedFrames.remove(index)
            refreshCellStyle(index)
            updateSelectionText()
            val rangeStart = storyboardStartMs.coerceIn(0L, videoDurationMs)
            val rangeEnd = (if (storyboardEndMs > rangeStart) storyboardEndMs else videoDurationMs).coerceIn(rangeStart, videoDurationMs)
            val timeMs = rangeStart + (rangeEnd - rangeStart) * index / (ROWS * FRAMES_PER_ROW)
            timelineView.setPlayhead(timeMs)
            try { videoView.seekTo(timeMs.toInt()) } catch (_: Exception) { }
        }
        return cell
    }

    private fun makeBorder(selected: Boolean): GradientDrawable = GradientDrawable().apply {
        setColor(Color.TRANSPARENT)
        cornerRadius = dp(2).toFloat()
        setStroke(dp(if (selected) 4 else 1), if (selected) Color.rgb(255, 210, 31) else Color.rgb(61, 91, 135))
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
                    if (storyboardEndMs <= storyboardStartMs || storyboardEndMs > duration) {
                        storyboardStartMs = 0L
                        storyboardEndMs = duration
                        findViewById<android.widget.EditText>(R.id.storyboardStartTime).setText("00:00")
                        findViewById<android.widget.EditText>(R.id.storyboardEndTime).setText(formatTime(duration))
                    }
                    // Initialize the timeline with the selected video's duration.
                    // Without this, TimelineView remains in its empty state and cannot draw thumbnails.
                    timelineView.setTimeline(duration, timelineSplitPoints.toList(), deletedTimelineRanges)
                    statusText.text = if (duration > 0) "مدت ویدیو ${formatTime(duration)} • در حال استخراج فریم‌ها…" else "در حال استخراج فریم‌ها…"
                }
                // Generate a small, dedicated timeline strip first; it no longer waits for all 120
                // storyboard images and is not rebuilt/sorted after every individual frame.
                loadTimelineThumbnails(uri, duration, generation)

                val count = ROWS * FRAMES_PER_ROW
                for (index in 0 until count) {
                    if (generation != storyboardGeneration) break
                    if (index in deletedStoryboardFrames) continue
                    val rangeStart = storyboardStartMs.coerceIn(0L, duration)
                    val rangeEnd = (if (storyboardEndMs > rangeStart) storyboardEndMs else duration).coerceIn(rangeStart, duration)
                    val timestampMs = if (count <= 1) rangeStart else rangeStart + (rangeEnd - rangeStart) * index / count
                    // Decode at thumbnail resolution where Android supports it; fall back to a
                    // normal frame decode on older Android versions. Only one cheap sync fallback.
                    var bitmap: Bitmap? = try {
                        if (android.os.Build.VERSION.SDK_INT >= 27) {
                            retriever.getScaledFrameAtTime(timestampMs * 1000L, MediaMetadataRetriever.OPTION_CLOSEST, 160, 100)
                        } else {
                            retriever.getFrameAtTime(timestampMs * 1000L, MediaMetadataRetriever.OPTION_CLOSEST)
                        }
                    } catch (_: Exception) { null }
                    if (bitmap == null) {
                        bitmap = try {
                            if (android.os.Build.VERSION.SDK_INT >= 27) {
                                retriever.getScaledFrameAtTime(timestampMs * 1000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 160, 100)
                            } else {
                                retriever.getFrameAtTime(timestampMs * 1000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                            }
                        } catch (_: Exception) { null }
                    }
                    if (bitmap != null) {
                        val scaled = scaleBitmapPreservingAspect(bitmap, 160, 100)
                        mainHandler.post {
                            if (generation == storyboardGeneration) {
                                frameBitmaps[index] = scaled
                                val cell = frameViews[index]
                                val image = cell?.getChildAt(0) as? ImageView
                                image?.setImageBitmap(scaled)
                                image?.clearColorFilter()
                                (cell?.getChildAt(1) as? TextView)?.text = formatTime(timestampMs)
                                cell?.visibility = if (index in deletedStoryboardFrames) View.GONE else View.VISIBLE
                            } else scaled.recycle()
                        }
                    }
                }
                mainHandler.post {
                    if (generation == storyboardGeneration) {
                        statusText.text = "استوری‌بورد زمانی آماده است: ۸ ردیف × ۱۵ فریم"
                    }
                }
            } catch (_: Exception) {
                mainHandler.post { if (generation == storyboardGeneration) statusText.text = "استخراج فریم‌ها ناموفق بود؛ پخش ویدیو همچنان در دسترس است" }
            } finally { try { retriever?.release() } catch (_: Exception) { } }
        }
    }


    /** Samples a compact strip independently from the slower 120-frame storyboard. */
    private fun loadTimelineThumbnails(uri: Uri, durationMs: Long, generation: Int) {
        timelineThumbnailWorker.execute {
            var retriever: MediaMetadataRetriever? = null
            val frames = mutableListOf<Pair<Long, Bitmap>>()
            try {
                retriever = MediaMetadataRetriever()
                retriever.setDataSource(this, uri)
                val sampleCount = 16
                for (i in 0 until sampleCount) {
                    if (generation != storyboardGeneration) break
                    val time = if (sampleCount <= 1) 0L else durationMs * i / sampleCount
                    val bitmap = try {
                        if (android.os.Build.VERSION.SDK_INT >= 27) {
                            retriever.getScaledFrameAtTime(time * 1000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 240, 135)
                        } else {
                            retriever.getFrameAtTime(time * 1000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                        }
                    } catch (_: Exception) { null }
                    if (bitmap != null) frames.add(time to scaleBitmapPreservingAspect(bitmap, 240, 135))
                }
                mainHandler.post {
                    if (generation == storyboardGeneration) {
                        timelineView.setThumbnails(frames)
                    } else frames.forEach { if (!it.second.isRecycled) it.second.recycle() }
                }
            } catch (_: Exception) {
                frames.forEach { if (!it.second.isRecycled) it.second.recycle() }
            } finally { try { retriever?.release() } catch (_: Exception) { } }
        }
    }

    private fun showTimeMode() {
        storyboardStartMs = 0L
        storyboardEndMs = videoDurationMs
        findViewById<android.widget.EditText>(R.id.storyboardStartTime).setText("00:00")
        findViewById<android.widget.EditText>(R.id.storyboardEndTime).setText(formatTime(videoDurationMs))
        frameViews.forEach { (index, view) -> view.visibility = if (index in deletedStoryboardFrames) View.GONE else View.VISIBLE }
        updateModeButtons()
        updateStoryboardSelectionModeButtons()
        val uri = selectedUri
        if (uri != null && videoDurationMs > 0L) reloadStoryboardForRange(uri)
        statusText.text = "نمایش زمانی کامل: از 00:00 تا ${formatTime(videoDurationMs)}"
    }

    private fun parseTimeInput(value: String): Long? {
        val text = value.trim()
        if (text.isEmpty()) return null
        return try {
            val parts = text.split(":")
            val seconds = when (parts.size) {
                1 -> parts[0].toLong()
                2 -> parts[0].toLong() * 60L + parts[1].toLong()
                3 -> parts[0].toLong() * 3600L + parts[1].toLong() * 60L + parts[2].toLong()
                else -> return null
            }
            if (parts.any { it.toLongOrNull() == null } || seconds < 0L) null else seconds * 1000L
        } catch (_: Exception) { null }
    }

    /** Opens a native time picker for the storyboard start/end, while keeping manual text entry available. */
    private fun showStoryboardTimePicker(isStart: Boolean) {
        if (videoDurationMs <= 0L) {
            Toast.makeText(this, "ابتدا ویدیو را انتخاب کنید", Toast.LENGTH_SHORT).show()
            return
        }
        val fieldId = if (isStart) R.id.storyboardStartTime else R.id.storyboardEndTime
        val field = findViewById<android.widget.EditText>(fieldId)
        val currentMs = parseTimeInput(field.text.toString()) ?: if (isStart) storyboardStartMs else storyboardEndMs
        val totalMinutes = (currentMs / 60000L).toInt().coerceAtLeast(0)
        val hour = (totalMinutes / 60) % 24
        val minute = totalMinutes % 60
        TimePickerDialog(this, { _, selectedHour, selectedMinute ->
            val pickedMs = (selectedHour * 3600L + selectedMinute * 60L) * 1000L
            field.setText(formatTime(pickedMs.coerceAtMost(videoDurationMs)))
            field.error = null
        }, hour, minute, true).apply {
            setTitle(if (isStart) "زمان شروع نمایش اسلایدها" else "زمان پایان نمایش اسلایدها")
            show()
        }
    }

    private fun applyStoryboardTimeRange() {
        val uri = selectedUri
        if (uri == null || videoDurationMs <= 0L) {
            Toast.makeText(this, "ابتدا ویدیو را انتخاب کنید", Toast.LENGTH_SHORT).show(); return
        }
        val start = parseTimeInput(findViewById<android.widget.EditText>(R.id.storyboardStartTime).text.toString())
        val end = parseTimeInput(findViewById<android.widget.EditText>(R.id.storyboardEndTime).text.toString())
        if (start == null || end == null || start >= end || end > videoDurationMs) {
            findViewById<android.widget.EditText>(R.id.storyboardEndTime).error = "زمان پایان باید بعد از شروع و در محدودهٔ ویدیو باشد"
            Toast.makeText(this, "زمان را به‌شکل دقیقه:ثانیه وارد کنید؛ مثلاً 01:25", Toast.LENGTH_LONG).show(); return
        }
        storyboardStartMs = start
        storyboardEndMs = end
        reloadStoryboardForRange(uri)
        statusText.text = "استوری‌بورد بازهٔ ${formatTime(start)} تا ${formatTime(end)} در حال آماده‌سازی است"
    }

    private fun reloadStoryboardForRange(uri: Uri) {
        frameBitmaps.values.forEach { if (!it.isRecycled) it.recycle() }
        frameBitmaps.clear()
        timelineView.setThumbnails(emptyList())
        previewBitmaps.values.forEach { if (!it.isRecycled) it.recycle() }
        previewBitmaps.clear()
        loadStoryboard(uri)
    }

    private fun scaleBitmapPreservingAspect(source: Bitmap, maxWidth: Int = 160, maxHeight: Int = 100): Bitmap {
        val scale = minOf(maxWidth.toFloat() / source.width, maxHeight.toFloat() / source.height, 1f)
        val width = (source.width * scale).toInt().coerceAtLeast(1)
        val height = (source.height * scale).toInt().coerceAtLeast(1)
        if (width == source.width && height == source.height) return source
        return Bitmap.createScaledBitmap(source, width, height, true).also { if (it !== source && !source.isRecycled) source.recycle() }
    }

    private fun selectedForExportButtonReset() {
        if (::selectForDeleteModeButton.isInitialized && ::selectForExportModeButton.isInitialized) updateStoryboardSelectionModeButtons()
        if (findViewById<Button>(R.id.exportButton) != null) findViewById<Button>(R.id.exportButton).text = "خروجی بدون تغییر کدک"
    }

    private fun setStoryboardSelectionMode(exportMode: Boolean) {
        selectingForExport = exportMode
        selectedFrames.clear()
        refreshSelectionStyles()
        updateSelectionText()
        updateStoryboardSelectionModeButtons()
        findViewById<Button>(R.id.exportButton).text = if (exportMode) "خروجی بدون تغییر کدک: اسلایدهای انتخاب‌شده" else "خروجی بدون تغییر کدک"
        statusText.text = if (exportMode) "حالت خروجی: اسلایدهای موردنظر را انتخاب کنید؛ فقط همان بازه‌ها خروجی می‌شوند" else "حالت حذف: اسلایدهای ناخواسته را انتخاب و سپس «حذف بازه‌ها» را بزنید"
    }

    private fun updateStoryboardSelectionModeButtons() {
        selectForDeleteModeButton.backgroundTintList = android.content.res.ColorStateList.valueOf(if (!selectingForExport) Color.rgb(255, 210, 31) else Color.rgb(37, 69, 111))
        selectForDeleteModeButton.setTextColor(if (!selectingForExport) Color.rgb(7, 20, 47) else Color.WHITE)
        selectForExportModeButton.backgroundTintList = android.content.res.ColorStateList.valueOf(if (selectingForExport) Color.rgb(67, 217, 163) else Color.rgb(37, 69, 111))
        selectForExportModeButton.setTextColor(if (selectingForExport) Color.rgb(7, 20, 47) else Color.WHITE)
    }

    private fun deleteSelectedStoryboardFrames() {
        if (selectingForExport) {
            Toast.makeText(this, "اکنون حالت خروجی از انتخاب‌شده‌ها فعال است؛ برای حذف، دکمهٔ «انتخاب برای حذف» را بزنید", Toast.LENGTH_LONG).show()
            return
        }
        if (selectedFrames.isEmpty()) {
            Toast.makeText(this, "ابتدا فریم‌هایی را انتخاب کنید", Toast.LENGTH_SHORT).show()
            return
        }
        val toDelete = selectedFrames.toList()
        toDelete.forEach { index ->
            deletedStoryboardFrames.add(index)
            frameViews[index]?.let { cell -> (cell.parent as? ViewGroup)?.removeView(cell) }
            frameViews.remove(index)
            frameBitmaps.remove(index)?.let { if (!it.isRecycled) it.recycle() }
        }
        selectedFrames.clear()
        updateSelectionText()
        statusText.text = "${toDelete.size} بازهٔ زمانی برای حذف از خروجی علامت‌گذاری شد"
        Toast.makeText(this, "بازه‌های زمانی این اسلایدها از ویدیوی خروجی حذف می‌شوند؛ فایل اصلی دست‌نخورده می‌ماند", Toast.LENGTH_LONG).show()
    }


    /** Saves every Cut-delimited timeline interval as its own file, stream-copying tracks without re-encoding. */
    private fun exportTimelineSegmentsSeparately() {
        val source = selectedUri
        if (source == null || videoDurationMs <= 0L) {
            Toast.makeText(this, "ابتدا یک ویدیوی معتبر انتخاب کنید", Toast.LENGTH_SHORT).show()
            return
        }
        val boundaries = (listOf(0L) + timelineSplitPoints.filter { it > 0L && it < videoDurationMs } + listOf(videoDurationMs)).distinct().sorted()
        if (boundaries.size < 2) {
            Toast.makeText(this, "ابتدا با دکمهٔ Cut نقاط تقسیم را روی نوار زمان ثبت کنید", Toast.LENGTH_LONG).show()
            return
        }
        val deleted = synchronized(deletedTimelineRanges) { deletedTimelineRanges.toList() }
        val segments = (0 until boundaries.size - 1).map { boundaries[it] to boundaries[it + 1] }
            .filter { (a, b) -> b > a && deleted.none { d -> d.first <= a && d.second >= b } }
        if (segments.isEmpty()) {
            Toast.makeText(this, "قطعهٔ قابل ذخیره‌ای باقی نمانده است", Toast.LENGTH_LONG).show()
            return
        }
        val button = findViewById<Button>(R.id.exportSegmentsSeparatelyButton)
        button.isEnabled = false
        statusText.text = "در حال ذخیرهٔ ${segments.size} قطعه به‌صورت فایل‌های جداگانه و بدون رمزگذاری مجدد…"
        worker.execute {
            var saved = 0
            val errors = mutableListOf<String>()
            segments.forEachIndexed { index, range ->
                var temp: File? = null
                var outUri: Uri? = null
                try {
                    temp = muxRemainingRanges(source, listOf(range))
                    val values = ContentValues().apply {
                        put(MediaStore.Video.Media.DISPLAY_NAME, "EVC_segment_${(index + 1).toString().padStart(3, '0')}_${System.currentTimeMillis()}.mp4")
                        put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                        if (android.os.Build.VERSION.SDK_INT >= 29) {
                            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/EVC")
                            put(MediaStore.Video.Media.IS_PENDING, 1)
                        }
                    }
                    outUri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                        ?: throw IllegalStateException("ساخت فایل قطعهٔ ${index + 1} ناموفق بود")
                    contentResolver.openOutputStream(outUri)?.use { output -> FileInputStream(temp).use { input -> input.copyTo(output) } }
                        ?: throw IllegalStateException("نوشتن قطعهٔ ${index + 1} ناموفق بود")
                    if (android.os.Build.VERSION.SDK_INT >= 29) {
                        val done = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
                        contentResolver.update(outUri, done, null, null)
                    }
                    saved++
                } catch (e: Exception) {
                    outUri?.let { try { contentResolver.delete(it, null, null) } catch (_: Exception) { } }
                    errors.add("قطعهٔ ${index + 1}: ${e.message ?: e.javaClass.simpleName}")
                } finally {
                    temp?.delete()
                }
            }
            mainHandler.post {
                button.isEnabled = true
                statusText.text = if (errors.isEmpty()) "$saved قطعه به‌صورت فایل‌های جداگانه در Movies/EVC ذخیره شد؛ بدون رمزگذاری مجدد" else "$saved قطعه ذخیره شد؛ ${errors.size} قطعه ناموفق بود: ${errors.first()}"
                Toast.makeText(this, statusText.text, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun exportRemainingVideo(onlySelected: Boolean = false) {
        val source = selectedUri
        if (source == null || videoDurationMs <= 0L) {
            Toast.makeText(this, "ابتدا یک ویدیوی معتبر انتخاب کنید", Toast.LENGTH_SHORT).show()
            return
        }
        val total = ROWS * FRAMES_PER_ROW
        val requestedFrames = selectedFrames.toSet()
        if (onlySelected && requestedFrames.isEmpty()) {
            Toast.makeText(this, "برای این حالت، ابتدا اسلایدهایی را که باید در خروجی باشند انتخاب کنید", Toast.LENGTH_LONG).show()
            return
        }
        val kept = if (onlySelected) requestedFrames.filter { it !in deletedStoryboardFrames } else (0 until total).filter { it !in deletedStoryboardFrames }
        if (kept.isEmpty()) {
            Toast.makeText(this, if (onlySelected) "هیچ اسلاید انتخاب‌شدهٔ قابل‌خروجی وجود ندارد" else "همهٔ بازه‌ها حذف شده‌اند؛ چیزی برای خروجی باقی نمانده است", Toast.LENGTH_LONG).show()
            return
        }
        // Current version exports the remaining intervals without re-encoding.
        // The target-size/re-encoding mode will be added separately to avoid silently
        // changing codec or quality in the original-quality mode.
        statusText.text = if (onlySelected) "در حال ساخت خروجی بدون تغییر کدک از اسلایدهای انتخاب‌شده…" else "در حال ساخت خروجی بدون تغییر کدک از بازه‌های باقی‌مانده…"
        findViewById<Button>(R.id.exportButton).isEnabled = false
        worker.execute {
            var result: File? = null
            var error: String? = null
            try {
                val excluded = mutableListOf<Pair<Long, Long>>()
                var runStart: Int? = null
                for (i in 0 until total) {
                    val shouldExclude = if (onlySelected) i !in requestedFrames || i in deletedStoryboardFrames else i in deletedStoryboardFrames
                    if (shouldExclude && runStart == null) runStart = i
                    if ((!shouldExclude || i == total - 1) && runStart != null) {
                        val endIndex = if (shouldExclude && i == total - 1) i + 1 else i
                        val startMs = videoDurationMs * runStart!! / total
                        val endMs = videoDurationMs * endIndex / total
                        if (endMs > startMs) excluded.add(startMs to endMs)
                        runStart = null
                    }
                }
                synchronized(deletedTimelineRanges) { excluded.addAll(deletedTimelineRanges) }
                val mergedExcluded = excluded.sortedBy { it.first }.fold(mutableListOf<Pair<Long, Long>>()) { acc, r ->
                    if (acc.isNotEmpty() && r.first <= acc.last().second) {
                        val last = acc.removeAt(acc.lastIndex); acc.add(last.first to max(last.second, r.second))
                    } else acc.add(r)
                    acc
                }
                val keptRanges = mutableListOf<Pair<Long, Long>>()
                var cursor = 0L
                for (r in mergedExcluded) {
                    val a = r.first.coerceIn(0L, videoDurationMs); val b = r.second.coerceIn(0L, videoDurationMs)
                    if (a > cursor) keptRanges.add(cursor to a)
                    cursor = max(cursor, b)
                }
                if (cursor < videoDurationMs) keptRanges.add(cursor to videoDurationMs)
                result = muxRemainingRanges(source, keptRanges)
            } catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName
            }
            mainHandler.post {
                findViewById<Button>(R.id.exportButton).isEnabled = true
                val file = result
                if (file != null && file.exists() && file.length() > 0L) {
                    try {
                        val values = ContentValues().apply {
                            put(MediaStore.Video.Media.DISPLAY_NAME, "EVC_edited_${System.currentTimeMillis()}.mp4")
                            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                            if (android.os.Build.VERSION.SDK_INT >= 29) {
                                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/EVC")
                                put(MediaStore.Video.Media.IS_PENDING, 1)
                            }
                        }
                        val outUri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                        if (outUri == null) throw IllegalStateException("ساخت فایل در پوشهٔ Movies/EVC ممکن نشد")
                        contentResolver.openOutputStream(outUri)?.use { out -> FileInputStream(file).use { input -> input.copyTo(out) } }
                            ?: throw IllegalStateException("نوشتن فایل خروجی ناموفق بود")
                        if (android.os.Build.VERSION.SDK_INT >= 29) {
                            val done = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
                            contentResolver.update(outUri, done, null, null)
                        }
                        file.delete()
                        statusText.text = "ویدیوی خروجی در Movies/EVC ذخیره شد (بدون رمزگذاری مجدد)"
                        Toast.makeText(this, "ویدیوی خروجی در پوشهٔ Movies/EVC ذخیره شد", Toast.LENGTH_LONG).show()
                    } catch (e: Exception) {
                        statusText.text = "انتقال خروجی به پوشهٔ ویدیوها ناموفق بود: ${e.message ?: "خطا"}"
                        Toast.makeText(this, statusText.text, Toast.LENGTH_LONG).show()
                    }
                } else {
                    statusText.text = "ساخت ویدیوی خروجی ناموفق بود: ${error ?: "فرمت یا برش پشتیبانی نشد"}"
                    Toast.makeText(this, statusText.text, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /** Muxes kept time ranges from the source without decoding/re-encoding. */
    private fun muxRemainingRanges(source: Uri, ranges: List<Pair<Long, Long>>): File {
        val probe = MediaExtractor()
        probe.setDataSource(this, source, null)
        val videoTrack = (0 until probe.trackCount).firstOrNull {
            probe.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
        } ?: throw IllegalStateException("ترک ویدیویی پیدا نشد")
        val output = File(cacheDir, "evc_export_${System.currentTimeMillis()}.mp4")
        val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val trackMap = mutableMapOf<Int, Int>()
        for (i in 0 until probe.trackCount) {
            val format = probe.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("video/") || mime.startsWith("audio/")) {
                trackMap[i] = muxer.addTrack(format)
                probe.selectTrack(i)
            }
        }
        if (trackMap.isEmpty()) {
            probe.release(); muxer.release()
            throw IllegalStateException("ترک قابل خروجی پیدا نشد")
        }
        muxer.start()
        val buffer = java.nio.ByteBuffer.allocate(8 * 1024 * 1024)
        val info = MediaCodec.BufferInfo()
        var outputBaseUs = 0L
        try {
            for ((rangeIndex, range) in ranges.withIndex()) {
                val (requestedStartMs, requestedEndMs) = range
                if (requestedEndMs <= requestedStartMs) continue
                // Use the next video sync sample so every exported segment starts on a
                // decodable key frame. This can trim a small amount at a cut boundary.
                val syncProbe = MediaExtractor()
                syncProbe.setDataSource(this, source, null)
                syncProbe.selectTrack(videoTrack)
                syncProbe.seekTo(requestedStartMs * 1000L, MediaExtractor.SEEK_TO_NEXT_SYNC)
                val actualStartUs = if (syncProbe.sampleTrackIndex >= 0) syncProbe.sampleTime else -1L
                syncProbe.release()
                if (actualStartUs < 0L || actualStartUs >= requestedEndMs * 1000L) continue
                probe.seekTo(actualStartUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                // Align the muxer's segment start to the video sync timestamp selected above.
                var segmentFirstVideoUs: Long? = null
                val pending = mutableListOf<Triple<Int, Long, Pair<ByteArray, Int>>>()
                while (probe.sampleTrackIndex >= 0) {
                    val track = probe.sampleTrackIndex
                    val pts = probe.sampleTime
                    if (pts >= requestedEndMs * 1000L) break
                    if (track in trackMap && pts >= actualStartUs) {
                        if (track == videoTrack && segmentFirstVideoUs == null) segmentFirstVideoUs = pts
                        buffer.clear()
                        val size = probe.readSampleData(buffer, 0)
                        if (size > 0) {
                            val bytes = ByteArray(size)
                            buffer.position(0); buffer.get(bytes, 0, size)
                            pending.add(Triple(track, pts, bytes to probe.sampleFlags))
                        }
                    }
                    if (!probe.advance()) break
                }
                val segmentStart = segmentFirstVideoUs ?: actualStartUs
                for ((track, pts, sample) in pending) {
                    val bytes = sample.first
                    val flags = sample.second
                    buffer.clear(); buffer.put(bytes); buffer.flip()
                    info.set(0, bytes.size, outputBaseUs + (pts - segmentStart).coerceAtLeast(0L), flags)
                    muxer.writeSampleData(trackMap.getValue(track), buffer, info)
                }
                val segmentDuration = (requestedEndMs * 1000L - segmentStart).coerceAtLeast(1L)
                outputBaseUs += segmentDuration
                // Concatenate kept ranges continuously. Do not insert artificial timestamp
                // gaps between selected segments; this keeps selected-output playback compact.
            }
        } finally {
            try { muxer.stop() } catch (_: Exception) { }
            muxer.release()
            probe.release()
        }
        if (!output.exists() || output.length() == 0L) {
            output.delete()
            throw IllegalStateException("هیچ بازهٔ قابل‌خروجی باقی نماند؛ شاید برش‌های این کدک با حفظ کدک پشتیبانی نشوند")
        }
        return output
    }

    private fun updateModeButtons() {
        timeModeButton.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.rgb(255, 210, 31))
        timeModeButton.setTextColor(Color.rgb(7, 20, 47))
    }

    private fun refreshSelectionStyles() { frameViews.keys.forEach { refreshCellStyle(it) } }
    private fun refreshCellStyle(index: Int) {
        val cell = frameViews[index] ?: return
        cell.foreground = makeBorder(index in selectedFrames)
        val badge = cell.tag as? TextView
        badge?.visibility = if (index in selectedFrames) View.VISIBLE else View.GONE
    }
    private fun updateSelectionText() {
        selectionText.text = "${if (selectingForExport) "برای خروجی" else "برای حذف"}: ${selectedFrames.size} • باقی‌مانده: ${frameViews.size} از ${ROWS * FRAMES_PER_ROW}"
        refreshSelectionStyles()
    }
    private fun formatTime(ms: Long): String {
        val seconds = max(0L, ms / 1000L)
        val hours = seconds / 3600L
        val minutes = (seconds % 3600L) / 60L
        val secs = seconds % 60L
        return if (hours > 0L) String.format("%02d:%02d:%02d", hours, minutes, secs)
        else String.format("%02d:%02d", minutes, secs)
    }
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    override fun onPause() {
        super.onPause()
        if (::videoView.isInitialized && videoView.isPlaying) videoView.pause()
    }
    override fun onDestroy() {
        storyboardGeneration++
        worker.shutdownNow()
        frameBitmaps.values.forEach { if (!it.isRecycled) it.recycle() }
        super.onDestroy()
    }
    companion object {
        private const val REQUEST_VIDEO = 2101
        private const val ROWS = 8
        private const val FRAMES_PER_ROW = 15
    }
}


/** Interactive timeline: one-finger drag scrubs; pinch changes the visible time scale. */
class TimelineView(context: android.content.Context, attrs: android.util.AttributeSet? = null) : View(context, attrs) {
    var onSeek: ((Long) -> Unit)? = null
    var onSelectRange: ((Pair<Long, Long>) -> Unit)? = null
    var playheadMs: Long = 0L
        private set
    private var durationMs = 0L
    private var splitPoints = listOf<Long>()
    private var deletedRanges = listOf<Pair<Long, Long>>()
    private var thumbnails: List<Pair<Long, Bitmap>> = emptyList()
    private var zoom = 1f
    private var windowStartMs = 0L
    private var lastX = 0f
    private var downX = 0f
    private var downY = 0f
    private var initialSpan = 0f
    private var initialZoom = 1f
    private var moved = false
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(8, 17, 32) }
    private val density = resources.displayMetrics.density

    fun setTimeline(duration: Long, cuts: List<Long>, deleted: List<Pair<Long, Long>>) {
        durationMs = duration.coerceAtLeast(0L)
        splitPoints = cuts.sorted()
        deletedRanges = deleted.toList()
        windowStartMs = windowStartMs.coerceIn(0L, max(0L, durationMs - visibleDuration()))
        invalidate()
    }
    fun setThumbnails(frames: List<Pair<Long, Bitmap>>) {
        val next = frames.filter { !it.second.isRecycled }.sortedBy { it.first }
        val retained = next.map { it.second }.toSet()
        thumbnails.map { it.second }.distinct().forEach { old ->
            if (old !in retained && !old.isRecycled) old.recycle()
        }
        thumbnails = next
        invalidate()
    }
    fun setPlayhead(timeMs: Long) { playheadMs = timeMs.coerceIn(0L, durationMs); invalidate() }
    private fun visibleDuration(): Long = if (durationMs <= 0L) 1L else (durationMs / zoom).toLong().coerceAtLeast(1L)
    private fun timeAt(x: Float): Long {
        val usable = (width - 16f * density).coerceAtLeast(1f)
        return (windowStartMs + (((x - 8f * density).coerceIn(0f, usable) / usable) * visibleDuration().toFloat()).toLong()).coerceIn(0L, durationMs)
    }
    private fun xAt(time: Long): Float {
        val usable = (width - 16f * density).coerceAtLeast(1f)
        return 8f * density + ((time - windowStartMs).toFloat() / visibleDuration()).coerceIn(0f, 1f) * usable
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val left = 8f * density; val right = width - left
        val top = height * .30f; val bottom = height * .72f
        paint.color = Color.rgb(37, 69, 111); canvas.drawRoundRect(left, top, right, bottom, 5f * density, 5f * density, paint)
        if (durationMs <= 0L) {
            paint.color = Color.LTGRAY; paint.textSize = 11f * density
            canvas.drawText("نوار زمان پس از انتخاب ویدیو آماده می‌شود", left, height * .58f, paint); return
        }
        val start = windowStartMs; val end = minOf(durationMs, start + visibleDuration())
        // Render extracted video frames as one continuous thumbnail strip across the timeline.
        if (thumbnails.isNotEmpty()) {
            val save = canvas.save()
            canvas.clipRect(left, top, right, bottom)
            // Preserve the complete source frame inside each time cell; no stretch or crop.
            thumbnails.forEachIndexed { index, frame ->
                val centerX = xAt(frame.first)
                val prevX = if (index == 0) left else (xAt(thumbnails[index - 1].first) + centerX) / 2f
                val nextX = if (index == thumbnails.lastIndex) right else (centerX + xAt(thumbnails[index + 1].first)) / 2f
                if (nextX < left || prevX > right) return@forEachIndexed
                val dst = RectF(max(left, prevX), top, minOf(right, nextX), bottom)
                if (dst.width() > 0f && dst.height() > 0f) {
                    val bitmap = frame.second
                    val bitmapRatio = bitmap.width.toFloat() / bitmap.height.toFloat()
                    val targetRatio = dst.width() / dst.height()
                    // Fit the whole frame inside its time cell; never stretch or crop the source image.
                    val fitted = if (bitmapRatio > targetRatio) {
                        val fittedHeight = dst.width() / bitmapRatio
                        RectF(dst.left, dst.centerY() - fittedHeight / 2f, dst.right, dst.centerY() + fittedHeight / 2f)
                    } else {
                        val fittedWidth = dst.height() * bitmapRatio
                        RectF(dst.centerX() - fittedWidth / 2f, dst.top, dst.centerX() + fittedWidth / 2f, dst.bottom)
                    }
                    paint.color = Color.WHITE
                    canvas.drawRect(dst, backgroundPaint)
                    canvas.drawBitmap(bitmap, null, fitted, paint)
                }
            }
            canvas.restoreToCount(save)
            paint.color = Color.argb(70, 8, 17, 32)
            canvas.drawRoundRect(left, top, right, bottom, 5f * density, 5f * density, paint)
        }
        deletedRanges.forEach { r ->
            if (r.second >= start && r.first <= end) {
                paint.color = Color.rgb(181, 44, 69)
                canvas.drawRect(xAt(r.first), top, xAt(r.second), bottom, paint)
            }
        }
        splitPoints.filter { it in start..end }.forEach { cut ->
            paint.color = Color.rgb(255, 210, 31); paint.strokeWidth = 2f * density
            canvas.drawLine(xAt(cut), top - 5f * density, xAt(cut), bottom + 5f * density, paint)
        }
        paint.color = Color.WHITE; paint.textSize = 9f * density
        canvas.drawText(format(playheadMs), left, 10f * density + 2f, paint)
        paint.color = Color.rgb(67, 217, 163); paint.strokeWidth = 3f * density
        val px = xAt(playheadMs); canvas.drawLine(px, 3f * density, px, height.toFloat(), paint)
        paint.color = Color.LTGRAY; paint.textSize = 8f * density
        canvas.drawText(format(start), left, height - 3f * density, paint)
        val endText = format(end); canvas.drawText(endText, right - paint.measureText(endText), height - 3f * density, paint)
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = event.x; lastX = event.x; downY = event.y; moved = false; return true }
            MotionEvent.ACTION_POINTER_DOWN -> if (event.pointerCount >= 2) { initialSpan = span(event); initialZoom = zoom; moved = true; return true }
            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount >= 2) {
                    val current = span(event)
                    if (initialSpan > 0f && current > 0f && durationMs > 0L) {
                        val focusTime = timeAt((event.getX(0) + event.getX(1)) / 2f)
                        zoom = (initialZoom * current / initialSpan).coerceIn(1f, 80f)
                        val frac = ((event.getX(0) + event.getX(1)) / 2f / width.coerceAtLeast(1)).coerceIn(0f, 1f)
                        windowStartMs = (focusTime - (visibleDuration() * frac).toLong()).coerceIn(0L, max(0L, durationMs - visibleDuration()))
                        invalidate()
                    }
                    return true
                }
                val dx = event.x - lastX
                if (kotlin.math.abs(event.x - downX) > 5f * density) moved = true
                if (durationMs > 0L && moved) {
                    val usable = (width - 16f * density).coerceAtLeast(1f)
                    val shift = (-dx / usable * visibleDuration()).toLong()
                    windowStartMs = (windowStartMs + shift).coerceIn(0L, max(0L, durationMs - visibleDuration()))
                    playheadMs = timeAt(event.x)
                    onSeek?.invoke(playheadMs)
                    invalidate()
                }
                lastX = event.x; return true
            }
            MotionEvent.ACTION_UP -> {
                if (!moved && durationMs > 0L) {
                    val t = timeAt(event.x); playheadMs = t; onSeek?.invoke(t)
                    val boundaries = (listOf(0L) + splitPoints.filter { it in 0L..durationMs } + listOf(durationMs)).distinct().sorted()
                    val idx = (0 until boundaries.size - 1).firstOrNull { t >= boundaries[it] && t <= boundaries[it + 1] } ?: 0
                    if (boundaries.size > 1) onSelectRange?.invoke(boundaries[idx] to boundaries[idx + 1])
                    invalidate()
                }
                performClick(); return true
            }
            MotionEvent.ACTION_CANCEL -> return true
        }
        return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }
    private fun span(e: MotionEvent): Float { if (e.pointerCount < 2) return 0f; val dx=e.getX(0)-e.getX(1); val dy=e.getY(0)-e.getY(1); return kotlin.math.sqrt(dx*dx+dy*dy) }
    private fun format(ms: Long): String { val s=(ms.coerceAtLeast(0L)/1000L); return String.format(java.util.Locale.US, "%02d:%02d", s/60, s%60) }
}
