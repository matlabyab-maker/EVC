package com.evc.app

import android.app.Activity
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
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import androidx.media3.common.MediaItem
import androidx.media3.common.Effects
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.Composition
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ExportException
import androidx.media3.effect.RgbFilter
import java.util.concurrent.Executors
import java.io.File
import java.io.FileInputStream
import kotlin.math.max

class MainActivity : Activity() {
    private lateinit var videoView: VideoView
    private lateinit var statusText: TextView
    private lateinit var emptyHint: TextView
    private lateinit var storyboardRows: LinearLayout
    private lateinit var selectionText: TextView
    private lateinit var timeModeButton: Button
    private lateinit var humanModeButton: Button
    private lateinit var peopleModeButton: Button
    private lateinit var selectForDeleteModeButton: Button
    private lateinit var selectForExportModeButton: Button
    private var selectingForExport = false
    private lateinit var timelineView: TimelineView
    private val timelineSplitPoints = sortedSetOf<Long>()
    private val deletedTimelineRanges = mutableListOf<Pair<Long, Long>>()
    private var selectedTimelineRange: Pair<Long, Long>? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val selectedFrames = linkedSetOf<Int>()
    private val frameViews = mutableMapOf<Int, FrameLayout>()
    private val frameBitmaps = mutableMapOf<Int, Bitmap>()
    private val humanFrames = linkedSetOf<Int>()
    private val distinctPeopleFrames = linkedSetOf<Int>()
    private var showingDistinctPeople = false
    private var peopleDetectionRunning = false
    private val deletedStoryboardFrames = linkedSetOf<Int>()
    private var selectedUri: Uri? = null
    private var activeTransformer: Transformer? = null
    private var videoDurationMs = 0L
    private var storyboardGeneration = 0
    private var humanDetectionRunning = false
    private var showingHumanOnly = false

    private val faceDetector by lazy {
        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
            .build()
        FaceDetection.getClient(options)
    }

    private val poseDetector by lazy {
        val options = PoseDetectorOptions.Builder()
            .setDetectorMode(PoseDetectorOptions.SINGLE_IMAGE_MODE)
            .build()
        PoseDetection.getClient(options)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        videoView = findViewById(R.id.videoView)
        statusText = findViewById(R.id.statusText)
        emptyHint = findViewById(R.id.emptyHint)
        storyboardRows = findViewById(R.id.storyboardRows)
        selectionText = findViewById(R.id.selectionText)
        timeModeButton = findViewById(R.id.timeModeButton)
        humanModeButton = findViewById(R.id.humanModeButton)
        peopleModeButton = findViewById(R.id.peopleModeButton)
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
            val at = timelineView.playheadMs.coerceIn(1L, videoDurationMs - 1L)
            if (timelineSplitPoints.add(at)) {
                timelineView.setTimeline(videoDurationMs, timelineSplitPoints.toList(), deletedTimelineRanges)
                selectedTimelineRange = null
                statusText.text = "برش در ${formatTime(at)} ثبت شد؛ برای انتخاب قطعه روی نوار بزنید"
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
        findViewById<Button>(R.id.colorizePreviewButton).setOnClickListener { exportColorizedVideo() }
        timeModeButton.setOnClickListener { showTimeMode() }
        humanModeButton.setOnClickListener { showHumanMode() }
        peopleModeButton.setOnClickListener { showDistinctPeopleMode() }

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
        updateModeButtons()
        updateStoryboardSelectionModeButtons()
    }

    /** Exports a real MP4 with a warm sepia color effect using Media3 Transformer. */
    private fun exportColorizedVideo() {
        val source = selectedUri
        if (source == null) {
            Toast.makeText(this, "ابتدا یک ویدیو انتخاب کنید", Toast.LENGTH_LONG).show()
            return
        }
        val button = findViewById<Button>(R.id.colorizePreviewButton)
        button.isEnabled = false
        statusText.text = "در حال رنگی‌سازی و رمزگذاری ویدیوی خروجی؛ لطفاً برنامه را نبندید…"
        val output = File(cacheDir, "evc_colorized_${System.currentTimeMillis()}.mp4")
        try {
            // A restrained warm sepia treatment is a real video-frame effect, not an AI
            // restoration model. It avoids the previous green-tinted thumbnail heuristic.
            val effects = Effects(emptyList(), listOf(RgbFilter.createSepiaFilter()))
            val item = EditedMediaItem.Builder(MediaItem.fromUri(source))
                .setEffects(effects)
                .build()
            val transformer = Transformer.Builder(this)
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
                            if (android.os.Build.VERSION.SDK_INT >= 29) {
                                contentResolver.update(outUri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
                            }
                            output.delete()
                            statusText.text = "ویدیوی رنگی‌شده در Movies/EVC ذخیره شد؛ صدا نیز در خروجی حفظ می‌شود"
                            Toast.makeText(this@MainActivity, "ویدیوی رنگی‌شده ذخیره شد", Toast.LENGTH_LONG).show()
                        } catch (e: Exception) {
                            output.delete()
                            statusText.text = "ذخیرهٔ ویدیوی رنگی‌شده ناموفق بود: ${e.message ?: "خطا"}"
                            Toast.makeText(this@MainActivity, statusText.text, Toast.LENGTH_LONG).show()
                        }
                    }

                    override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                        activeTransformer = null
                        output.delete()
                        button.isEnabled = true
                        statusText.text = "رنگی‌سازی ویدیو ناموفق بود: ${exportException.message ?: "خطای رمزگذاری"}"
                        Toast.makeText(this@MainActivity, statusText.text, Toast.LENGTH_LONG).show()
                    }
                })
                .build()
            activeTransformer = transformer
            transformer.start(item, output.absolutePath)
        } catch (e: Exception) {
            activeTransformer = null
            output.delete()
            button.isEnabled = true
            statusText.text = "شروع رنگی‌سازی ناموفق بود: ${e.message ?: "خطا"}"
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
        try {
            val flags = data.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION
            if (flags != 0) contentResolver.takePersistableUriPermission(uri, flags)
        } catch (_: Exception) { }
        selectedUri = uri
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
        humanFrames.clear()
        deletedStoryboardFrames.clear()
        showingHumanOnly = false
        buildEmptyStoryboard()
        emptyHint.visibility = View.VISIBLE
        statusText.text = "در حال آماده‌سازی ویدیو و استخراج فریم‌ها…"
        videoView.setVideoURI(uri)
        videoView.requestFocus()
        loadStoryboard(uri)
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
                strip.addView(cell, LinearLayout.LayoutParams(dp(74), dp(56)).apply { setMargins(0, 0, 0, 0) })
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
            setColor(Color.rgb(18, 35, 65)); cornerRadius = dp(5).toFloat()
        }
        val image = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
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
            val timeMs = videoDurationMs * index / (ROWS * FRAMES_PER_ROW)
            timelineView.setPlayhead(timeMs)
            try { videoView.seekTo(timeMs.toInt()) } catch (_: Exception) { }
        }
        return cell
    }

    private fun makeBorder(selected: Boolean): GradientDrawable = GradientDrawable().apply {
        setColor(Color.TRANSPARENT)
        cornerRadius = dp(5).toFloat()
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
                    // Initialize the timeline with the selected video's duration.
                    // Without this, TimelineView remains in its empty state and cannot draw thumbnails.
                    timelineView.setTimeline(duration, timelineSplitPoints.toList(), deletedTimelineRanges)
                    statusText.text = if (duration > 0) "مدت ویدیو ${formatTime(duration)} • در حال استخراج فریم‌ها…" else "در حال استخراج فریم‌ها…"
                }
                val count = ROWS * FRAMES_PER_ROW
                for (index in 0 until count) {
                    if (generation != storyboardGeneration) break
                    if (index in deletedStoryboardFrames) continue
                    val timestampMs = if (count <= 1) 0L else duration * index / count
                    // Some containers/codecs return null for an exact sampled timestamp.
                    // Retry nearby timestamps and a key-frame lookup before leaving a blank cell.
                    var bitmap: Bitmap? = null
                    val candidatesUs = listOf(
                        timestampMs * 1000L,
                        max(0L, timestampMs - 500L) * 1000L,
                        (timestampMs + 500L).coerceAtMost(duration.coerceAtLeast(timestampMs)) * 1000L,
                        max(0L, timestampMs - 1500L) * 1000L,
                        (timestampMs + 1500L).coerceAtMost(duration.coerceAtLeast(timestampMs)) * 1000L
                    ).distinct()
                    for (candidateUs in candidatesUs) {
                        bitmap = try { retriever.getFrameAtTime(candidateUs, MediaMetadataRetriever.OPTION_CLOSEST) } catch (_: Exception) { null }
                        if (bitmap != null) break
                    }
                    if (bitmap == null) {
                        bitmap = try { retriever.getFrameAtTime(timestampMs * 1000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) } catch (_: Exception) { null }
                    }
                    if (bitmap != null) {
                        val sourceBitmap = bitmap
                        val scaled = Bitmap.createScaledBitmap(sourceBitmap, 220, 132, true)
                        if (scaled !== sourceBitmap) sourceBitmap.recycle()
                        mainHandler.post {
                            if (generation == storyboardGeneration) {
                                frameBitmaps[index] = scaled
                                timelineView.setThumbnails(frameBitmaps.entries.sortedBy { it.key }.map { entry ->
                                    val sampleTime = if (count <= 1) 0L else duration * entry.key / count
                                    sampleTime to entry.value
                                })
                                val cell = frameViews[index]
                                val image = cell?.getChildAt(0) as? ImageView
                                image?.setImageBitmap(scaled)
                                image?.clearColorFilter()
                                (cell?.getChildAt(1) as? TextView)?.text = formatTime(timestampMs)
                                cell?.visibility = if (showingHumanOnly && index !in humanFrames) View.GONE else View.VISIBLE
                            } else scaled.recycle()
                        }
                    }
                }
                mainHandler.post {
                    if (generation == storyboardGeneration) {
                        statusText.text = "استوری‌بورد آماده است: ۶ ردیف × ۱۵ فریم؛ برای تشخیص انسان حالت صحنه‌های انسانی را بزنید"
                    }
                }
            } catch (_: Exception) {
                mainHandler.post { if (generation == storyboardGeneration) statusText.text = "استخراج فریم‌ها ناموفق بود؛ پخش ویدیو همچنان در دسترس است" }
            } finally { try { retriever?.release() } catch (_: Exception) { } }
        }
    }

    private fun showTimeMode() {
        showingHumanOnly = false
        showingDistinctPeople = false
        frameViews.forEach { (index, view) -> view.visibility = if (index in deletedStoryboardFrames) View.GONE else View.VISIBLE }
        updateModeButtons()
        updateStoryboardSelectionModeButtons()
        statusText.text = "نمایش زمانی: فریم‌ها در طول ویدیو با فاصلهٔ زمانی نمونه‌برداری شده‌اند"
    }

    private fun showHumanMode() {
        if (selectedUri == null) {
            Toast.makeText(this, "ابتدا یک ویدیو انتخاب کنید", Toast.LENGTH_SHORT).show()
            return
        }
        showingHumanOnly = true
        showingDistinctPeople = false
        updateModeButtons()
        updateStoryboardSelectionModeButtons()
        val indices = frameBitmaps.keys.filter { it !in deletedStoryboardFrames }.sorted()
        if (indices.isEmpty()) {
            statusText.text = "ابتدا صبر کنید تا فریم‌ها استخراج شوند"
            showingHumanOnly = false
            updateModeButtons()
        updateStoryboardSelectionModeButtons()
            return
        }
        if (humanDetectionRunning) return
        humanDetectionRunning = true
        humanFrames.clear()
        statusText.text = "در حال تشخیص انسان در فریم‌ها… 0/${indices.size}"
        detectHumanAt(indices, 0, storyboardGeneration)
    }

    private fun detectHumanAt(indices: List<Int>, position: Int, generation: Int) {
        if (generation != storyboardGeneration || !showingHumanOnly) {
            humanDetectionRunning = false
            return
        }
        if (position >= indices.size) {
            humanDetectionRunning = false
            frameViews.forEach { (index, view) -> view.visibility = if (index in humanFrames) View.VISIBLE else View.GONE }
            statusText.text = "پایان تشخیص: ${humanFrames.size} فریم دارای انسان از ${indices.size} فریم بررسی‌شده"
            return
        }
        val index = indices[position]
        val bitmap = frameBitmaps[index]
        if (bitmap == null || bitmap.isRecycled) {
            mainHandler.post { detectHumanAt(indices, position + 1, generation) }
            return
        }
        val input = InputImage.fromBitmap(bitmap, 0)
        poseDetector.process(input)
            .addOnSuccessListener { pose ->
                // Pose detection identifies a person when enough body landmarks are present.
                if (pose.allPoseLandmarks.size >= 5) humanFrames.add(index)
            }
            .addOnFailureListener { /* Skip uncertain frame and continue. */ }
            .addOnCompleteListener {
                statusText.text = "در حال تشخیص انسان… ${position + 1}/${indices.size}"
                detectHumanAt(indices, position + 1, generation)
            }
    }


    private fun showDistinctPeopleMode() {
        if (selectedUri == null) {
            Toast.makeText(this, "ابتدا یک ویدیو انتخاب کنید", Toast.LENGTH_SHORT).show()
            return
        }
        showingDistinctPeople = true
        showingHumanOnly = false
        updateModeButtons()
        updateStoryboardSelectionModeButtons()
        val indices = frameBitmaps.keys.filter { it !in deletedStoryboardFrames }.sorted()
        if (indices.isEmpty()) {
            statusText.text = "ابتدا صبر کنید تا فریم‌ها استخراج شوند"
            showingDistinctPeople = false
            updateModeButtons()
        updateStoryboardSelectionModeButtons()
            return
        }
        if (peopleDetectionRunning) return
        peopleDetectionRunning = true
        distinctPeopleFrames.clear()
        statusText.text = "در حال یافتن چهره‌های متفاوت… 0/${indices.size}"
        detectDistinctPeopleAt(indices, 0, storyboardGeneration, mutableListOf())
    }

    // Selects representative frames for visually different detected faces. This is a
    // lightweight appearance heuristic, not biometric identity recognition.
    private fun detectDistinctPeopleAt(indices: List<Int>, position: Int, generation: Int, signatures: MutableList<FloatArray>) {
        if (generation != storyboardGeneration || !showingDistinctPeople) {
            peopleDetectionRunning = false
            return
        }
        if (position >= indices.size) {
            peopleDetectionRunning = false
            frameViews.forEach { (index, view) ->
                view.visibility = if (index in deletedStoryboardFrames) View.GONE else if (index in distinctPeopleFrames) View.VISIBLE else View.GONE
            }
            statusText.text = "پایان بررسی: ${distinctPeopleFrames.size} اسلاید نماینده از چهره‌های متفاوت؛ تشخیص تقریبی است"
            return
        }
        val index = indices[position]
        val bitmap = frameBitmaps[index]
        if (bitmap == null || bitmap.isRecycled) {
            mainHandler.post { detectDistinctPeopleAt(indices, position + 1, generation, signatures) }
            return
        }
        faceDetector.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { faces ->
                for (face in faces.sortedByDescending { it.boundingBox.width() * it.boundingBox.height() }) {
                    val b = face.boundingBox
                    val left = b.left.coerceIn(0, bitmap.width - 1)
                    val top = b.top.coerceIn(0, bitmap.height - 1)
                    val right = b.right.coerceIn(left + 1, bitmap.width)
                    val bottom = b.bottom.coerceIn(top + 1, bitmap.height)
                    if ((right - left) < 12 || (bottom - top) < 12) continue
                    val crop = Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
                    val thumb = Bitmap.createScaledBitmap(crop, 12, 12, true)
                    if (thumb !== crop) crop.recycle()
                    val sig = FloatArray(144)
                    var k = 0
                    for (y in 0 until 12) for (x in 0 until 12) {
                        val c = thumb.getPixel(x, y)
                        sig[k++] = (Color.red(c) * 0.299f + Color.green(c) * 0.587f + Color.blue(c) * 0.114f) / 255f
                    }
                    thumb.recycle()
                    val nearest = signatures.minOfOrNull { old -> sig.indices.map { kotlin.math.abs(sig[it] - old[it]) }.average() } ?: 1.0
                    if (nearest > 0.16) {
                        signatures.add(sig)
                        distinctPeopleFrames.add(index)
                        break
                    }
                }
            }
            .addOnCompleteListener {
                statusText.text = "در حال یافتن چهره‌های متفاوت… ${position + 1}/${indices.size}"
                detectDistinctPeopleAt(indices, position + 1, generation, signatures)
            }
    }

    private fun selectedForExportButtonReset() {
        if (::selectForDeleteModeButton.isInitialized && ::selectForExportModeButton.isInitialized) updateStoryboardSelectionModeButtons()
        if (findViewById<Button>(R.id.exportButton) != null) findViewById<Button>(R.id.exportButton).text = "ساخت ویدیوی خروجی از باقی‌مانده‌ها"
    }

    private fun setStoryboardSelectionMode(exportMode: Boolean) {
        selectingForExport = exportMode
        selectedFrames.clear()
        refreshSelectionStyles()
        updateSelectionText()
        updateStoryboardSelectionModeButtons()
        findViewById<Button>(R.id.exportButton).text = if (exportMode) "ساخت خروجی از اسلایدهای انتخاب‌شده" else "ساخت ویدیوی خروجی از باقی‌مانده‌ها"
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
            humanFrames.remove(index)
        }
        selectedFrames.clear()
        updateSelectionText()
        statusText.text = "${toDelete.size} بازهٔ زمانی برای حذف از خروجی علامت‌گذاری شد"
        Toast.makeText(this, "بازه‌های زمانی این اسلایدها از ویدیوی خروجی حذف می‌شوند؛ فایل اصلی دست‌نخورده می‌ماند", Toast.LENGTH_LONG).show()
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
        statusText.text = if (onlySelected) "در حال ساخت خروجی فقط از اسلایدهای انتخاب‌شده…" else "در حال ساخت ویدیوی خروجی از بازه‌های باقی‌مانده…"
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
        timeModeButton.backgroundTintList = android.content.res.ColorStateList.valueOf(if (!showingHumanOnly) Color.rgb(255, 210, 31) else Color.rgb(37, 69, 111))
        timeModeButton.setTextColor(if (!showingHumanOnly) Color.rgb(7, 20, 47) else Color.WHITE)
        humanModeButton.backgroundTintList = android.content.res.ColorStateList.valueOf(if (showingHumanOnly) Color.rgb(255, 210, 31) else Color.rgb(37, 69, 111))
        humanModeButton.setTextColor(if (showingHumanOnly) Color.rgb(7, 20, 47) else Color.WHITE)
        peopleModeButton.backgroundTintList = android.content.res.ColorStateList.valueOf(if (showingDistinctPeople) Color.rgb(255, 210, 31) else Color.rgb(37, 69, 111))
        peopleModeButton.setTextColor(if (showingDistinctPeople) Color.rgb(7, 20, 47) else Color.WHITE)
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
        frameBitmaps.values.forEach { if (!it.isRecycled) it.recycle() }
        try { poseDetector.close() } catch (_: Exception) { }
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
    private val density = resources.displayMetrics.density

    fun setTimeline(duration: Long, cuts: List<Long>, deleted: List<Pair<Long, Long>>) {
        durationMs = duration.coerceAtLeast(0L)
        splitPoints = cuts.sorted()
        deletedRanges = deleted.toList()
        windowStartMs = windowStartMs.coerceIn(0L, max(0L, durationMs - visibleDuration()))
        invalidate()
    }
    fun setThumbnails(frames: List<Pair<Long, Bitmap>>) {
        thumbnails = frames.filter { !it.second.isRecycled }.sortedBy { it.first }
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
            // Keep each thumbnail's aspect ratio. Crop the source bitmap to the
            // destination cell's aspect ratio instead of stretching it to fit.
            thumbnails.forEachIndexed { index, frame ->
                val centerX = xAt(frame.first)
                val prevX = if (index == 0) left else (xAt(thumbnails[index - 1].first) + centerX) / 2f
                val nextX = if (index == thumbnails.lastIndex) right else (centerX + xAt(thumbnails[index + 1].first)) / 2f
                if (nextX < left || prevX > right) return@forEachIndexed
                val dst = RectF(max(left, prevX), top, minOf(right, nextX), bottom)
                if (dst.width() > 0f && dst.height() > 0f) {
                    val bitmap = frame.second
                    val targetRatio = dst.width() / dst.height()
                    val bitmapRatio = bitmap.width.toFloat() / bitmap.height.toFloat()
                    val srcRect = if (bitmapRatio > targetRatio) {
                        val cropWidth = (bitmap.height * targetRatio).toInt().coerceIn(1, bitmap.width)
                        val cropLeft = (bitmap.width - cropWidth) / 2
                        Rect(cropLeft, 0, cropLeft + cropWidth, bitmap.height)
                    } else {
                        val cropHeight = (bitmap.width / targetRatio).toInt().coerceIn(1, bitmap.height)
                        val cropTop = (bitmap.height - cropHeight) / 2
                        Rect(0, cropTop, bitmap.width, cropTop + cropHeight)
                    }
                    paint.color = Color.WHITE
                    canvas.drawBitmap(bitmap, srcRect, dst, paint)
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
