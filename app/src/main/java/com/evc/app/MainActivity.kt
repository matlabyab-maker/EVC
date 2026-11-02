package com.evc.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import androidx.media3.effect.HslAdjustment
import androidx.media3.effect.Crop
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
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
    private var storyboardIntervalMs: Long? = null
    private var storyboardGeneration = 0
    private data class RgbProfile(val red: Float, val green: Float, val blue: Float, val saturation: Float)
    private val frameColorProfiles = mutableMapOf<Int, RgbProfile>()
    private val mergeUris = mutableListOf<Uri>()
    private val PICK_MERGE_VIDEOS = 7304
    private val PICK_REFERENCE_IMAGE = 7305
    private val PICK_VECTOR_VIDEOS = 7306
    private val vectorUris = mutableListOf<Uri>()
    private var referenceImageUri: Uri? = null
    // Crop margins are stored as percentages of the source frame and reused for export.
    private var cropLeftPct = 0
    private var cropTopPct = 0
    private var cropRightPct = 0
    private var cropBottomPct = 0
    private var cropSettingsSaved = false
    // Applies to original-quality stream-copy exports (main export, timeline, storyboard segments).
    private var outputContainerMatroska = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        videoView = findViewById(R.id.videoView)
        findViewById<Button>(R.id.cropButton).setOnClickListener { showCropOptions() }
        findViewById<Button>(R.id.outputFormatButton).setOnClickListener {
            outputContainerMatroska = !outputContainerMatroska
            findViewById<Button>(R.id.outputFormatButton).text = if (outputContainerMatroska) "فرمت: MKV" else "فرمت: MP4"
            statusText.text = if (outputContainerMatroska)
                "فرمت خروجی بدون رمزگذاری مجدد: MKV؛ ترک‌ها با FFmpeg کپی می‌شوند"
            else "فرمت خروجی بدون رمزگذاری مجدد: MP4"
        }

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
        findViewById<Button>(R.id.mergeButton).setOnClickListener { if (mergeUris.isEmpty()) chooseVideosForMerge() else mergeSelectedVideos() }
        findViewById<Button>(R.id.vectorButton).setOnClickListener { chooseVideosForVector() }
        findViewById<Button>(R.id.advancedEditButton).setOnClickListener { showAdvancedEditDialog() }
        findViewById<Button>(R.id.imageSceneSearchButton).setOnClickListener { chooseReferenceImage() }
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
        findViewById<Button>(R.id.exportButton).setOnClickListener {
            // Fail closed: never interpret an empty storyboard selection as permission to
            // export the whole video. Full/remaining-video export is allowed only when the
            // user has actually marked storyboard intervals for deletion; timeline-range
            // export remains available through its explicit selected range.
            when {
                selectingForExport -> exportSelectedStoryboardFramesSeparately()
                selectedFrames.isNotEmpty() -> exportSelectedStoryboardFramesSeparately()
                selectedTimelineRange != null -> exportRemainingVideo(false)
                deletedStoryboardFrames.isNotEmpty() -> exportRemainingVideo(false)
                else -> {
                    statusText.text = "خروجی انجام نشد؛ ابتدا «انتخاب برای خروجی» را بزنید و اسلایدهای دلخواه را انتخاب کنید، یا یک بازه از نوار زمان انتخاب کنید"
                    Toast.makeText(this, statusText.text, Toast.LENGTH_LONG).show()
                }
            }
        }
        findViewById<Button>(R.id.applyColorToSelectedButton).setOnClickListener { applyColorToSelectedFrames() }
        findViewById<Button>(R.id.colorizePreviewButton).setOnClickListener { exportColorizedVideo() }
        setupManualColorControls()
        timeModeButton.setOnClickListener { showTimeMode() }
        findViewById<Button>(R.id.applyStoryboardTimeRangeButton).setOnClickListener { applyStoryboardTimeRange() }
        findViewById<Button>(R.id.pickStoryboardStartButton).setOnClickListener { setStoryboardTimeFromPlayback(true) }
        findViewById<Button>(R.id.pickStoryboardEndButton).setOnClickListener { setStoryboardTimeFromPlayback(false) }

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

    /** Accepts one shared video for editing or multiple shared videos for Merge. */
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingVideoIntent(intent)
    }

    private fun handleIncomingVideoIntent(incoming: Intent?) {
        if (incoming == null) return
        val action = incoming.action
        if (action != Intent.ACTION_SEND && action != Intent.ACTION_SEND_MULTIPLE) return
        val uris = mutableListOf<Uri>()
        if (action == Intent.ACTION_SEND_MULTIPLE) {
            @Suppress("DEPRECATION")
            val streams = incoming.getParcelableArrayListExtra<android.os.Parcelable>(Intent.EXTRA_STREAM)
            streams?.forEach { (it as? Uri)?.let(uris::add) }
        } else {
            val uri = if (android.os.Build.VERSION.SDK_INT >= 33) {
                incoming.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                incoming.getParcelableExtra<android.os.Parcelable>(Intent.EXTRA_STREAM) as? Uri
            }
            uri?.let(uris::add)
        }
        incoming.clipData?.let { clip ->
            for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let { if (it !in uris) uris.add(it) }
        }
        if (uris.isEmpty()) {
            Toast.makeText(this, "ویدیوی اشتراک‌گذاری‌شده قابل دریافت نیست", Toast.LENGTH_LONG).show()
            return
        }
        if (uris.size > 1) {
            mergeUris.clear(); mergeUris.addAll(uris.distinct())
            findViewById<Button>(R.id.mergeButton).text = "Merge (${mergeUris.size})"
            statusText.text = "${mergeUris.size} ویدیو دریافت شد؛ برای ادغام، دکمهٔ Merge را بزنید"
            Toast.makeText(this, "ویدیوها به بخش Merge منتقل شدند", Toast.LENGTH_SHORT).show()
        } else {
            mergeUris.clear()
            loadSelectedVideo(uris.first(), false)
        }
    }

    private fun chooseVideosForVector() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = "video/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(intent, PICK_VECTOR_VIDEOS)
    }

    private fun showVectorPreview() {
        val count = vectorUris.size
        val cols = when (count) { 1 -> 1; 2, 3, 4 -> 2; else -> 3 }
        val rows = (count + cols - 1) / cols
        val grid = android.widget.GridLayout(this).apply {
            columnCount = cols; rowCount = rows
            setBackgroundColor(Color.BLACK)
            useDefaultMargins = false
            alignmentMode = android.widget.GridLayout.ALIGN_BOUNDS
        }
        val players = mutableListOf<VideoView>()
        vectorUris.forEachIndexed { index, uri ->
            val player = VideoView(this)
            val lp = android.widget.GridLayout.LayoutParams().apply {
                width = 0; height = 0
                columnSpec = android.widget.GridLayout.spec(index % cols, 1, 1f)
                rowSpec = android.widget.GridLayout.spec(index / cols, 1, 1f)
                setMargins(0, 0, 0, 0)
            }
            player.layoutParams = lp
            player.setVideoURI(uri)
            player.setOnPreparedListener { mp -> mp.isLooping = true }
            grid.addView(player); players.add(player)
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, 0)
            addView(grid, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(TextView(this@MainActivity).apply {
                text = "پیش‌نمایش چیدمان فشرده: $count ویدیو • فاصلهٔ قاب‌ها صفر"
                setTextColor(Color.WHITE); textSize = 12f; gravity = Gravity.CENTER
                setPadding(4, 6, 4, 6)
            })
        }
        val dialog = AlertDialog.Builder(this).setTitle("Vector • پیش‌نمایش چیدمان")
            .setView(container)
            .setPositiveButton("پخش هم‌زمان", null)
            .setNeutralButton("انتخاب دوباره", null)
            .setNegativeButton("بستن", null).create()
        dialog.setOnShowListener {
            dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, (resources.displayMetrics.heightPixels * 0.78f).toInt())
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                players.forEach { it.seekTo(0); it.start() }
                statusText.text = "Vector: پیش‌نمایش $count ویدیو؛ قاب‌ها بدون فاصله هستند. خروجی ترکیبی هنوز در این نسخه پیاده‌سازی نشده است."
                Toast.makeText(this, "پخش پیش‌نمایش آغاز شد؛ خروجی ترکیبی هنوز آماده نیست", Toast.LENGTH_LONG).show()
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { dialog.dismiss(); chooseVideosForVector() }
        }
        dialog.show()
    }

    private fun showAdvancedEditDialog() {
        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 8, 24, 8) }
        fun addControl(label: String, maxValue: Int, initial: Int, onValue: (Int) -> Unit) {
            panel.addView(TextView(this).apply { text = "$label: ${initial}%"; setTextColor(Color.WHITE); textSize = 13f })
            panel.addView(SeekBar(this).apply {
                max = maxValue; progress = initial
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                        (panel.getChildAt(panel.childCount - 2) as TextView).text = "$label: ${progress}%"
                        onValue(progress)
                    }
                    override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                    override fun onStopTrackingTouch(seekBar: SeekBar?) {}
                })
            })
        }
        var brightness = 100; var contrast = 100; var gamma = 100
        addControl("نور", 200, 100) { brightness = it }
        addControl("کنتراست", 200, 100) { contrast = it }
        addControl("گاما", 300, 100) { gamma = it }
        AlertDialog.Builder(this).setTitle("ویرایش پیشرفته")
            .setView(ScrollView(this).apply { addView(panel) })
            .setPositiveButton("اعمال", null).setNegativeButton("بستن", null).show()
            .getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                Toast.makeText(this, "مقادیر نور $brightness٪، کنتراست $contrast٪ و گاما $gamma٪ انتخاب شدند؛ اتصال این تنظیمات به موتور خروجی در نسخهٔ بعدی لازم است.", Toast.LENGTH_LONG).show()
            }
    }

    private fun chooseVideosForMerge() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "video/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivityForResult(intent, PICK_MERGE_VIDEOS)
    }

    @Deprecated("Uses Activity result callback for compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) return
        if (requestCode == PICK_MERGE_VIDEOS) {
            val picked = mutableListOf<Uri>()
            data.clipData?.let { clip -> for (i in 0 until clip.itemCount) picked.add(clip.getItemAt(i).uri) }
            data.data?.let { if (it !in picked) picked.add(it) }
            mergeUris.clear(); mergeUris.addAll(picked.distinct())
            if (mergeUris.size < 2) {
                mergeUris.clear()
                Toast.makeText(this, "برای Merge حداقل دو ویدیو انتخاب کنید", Toast.LENGTH_LONG).show()
                return
            }
            findViewById<Button>(R.id.mergeButton).text = "Merge (${mergeUris.size})"
            statusText.text = "${mergeUris.size} ویدیو برای ادغام آماده است؛ دکمهٔ Merge را دوباره بزنید"
        } else if (requestCode == PICK_VECTOR_VIDEOS) {
            val picked = mutableListOf<Uri>()
            data.clipData?.let { clip -> for (i in 0 until clip.itemCount) picked.add(clip.getItemAt(i).uri) }
            data.data?.let { if (it !in picked) picked.add(it) }
            vectorUris.clear(); vectorUris.addAll(picked.distinct().take(6))
            if (vectorUris.isEmpty()) return
            showVectorPreview()
        } else if (requestCode == REQUEST_VIDEO) {
            val uri = data.data ?: return
            loadSelectedVideo(uri, true)
        } else if (requestCode == PICK_REFERENCE_IMAGE) {
            referenceImageUri = data.data ?: return
            if (selectedUri == null || videoDurationMs <= 0L) {
                Toast.makeText(this, "حالا ویدیوی موردنظر را انتخاب کنید؛ سپس دوباره جست‌وجو با عکس را بزنید", Toast.LENGTH_LONG).show()
                return
            }
            searchVideoForImage(referenceImageUri!!, selectedUri!!)
        }
    }

    private fun chooseReferenceImage() {
        if (selectedUri == null || videoDurationMs <= 0L) {
            Toast.makeText(this, "ابتدا ویدیویی انتخاب کنید", Toast.LENGTH_LONG).show()
            return
        }
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        try { startActivityForResult(intent, PICK_REFERENCE_IMAGE) }
        catch (_: Exception) { Toast.makeText(this, "انتخاب‌گر عکس در دسترس نیست", Toast.LENGTH_LONG).show() }
    }

    private data class ImageMatch(val timeMs: Long, val score: Double, val bitmap: Bitmap)

    private fun searchVideoForImage(imageUri: Uri, videoUri: Uri) {
        statusText.text = "در حال جست‌وجوی صحنه‌های مشابه عکس…"
        findViewById<Button>(R.id.imageSceneSearchButton).isEnabled = false
        worker.execute {
            var retriever: MediaMetadataRetriever? = null
            try {
                val ref = contentResolver.openInputStream(imageUri)?.use { BitmapFactoryCompat.decode(it) }
                    ?: throw IllegalStateException("خواندن عکس ناموفق بود")
                val refSmall = Bitmap.createScaledBitmap(ref, 32, 32, true)
                ref.recycle()
                retriever = MediaMetadataRetriever().apply { setDataSource(this@MainActivity, videoUri) }
                val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                if (duration <= 0L) throw IllegalStateException("مدت ویدیو خوانده نشد")
                val matches = mutableListOf<ImageMatch>()
                var t = 0L
                while (t < duration) {
                    val frame = retriever.getFrameAtTime(t * 1000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    if (frame != null) {
                        val small = Bitmap.createScaledBitmap(frame, 32, 32, true)
                        val score = imageSimilarity(refSmall, small)
                        if (score >= 0.72) matches.add(ImageMatch(t, score, Bitmap.createScaledBitmap(frame, 240, 135, true)))
                        small.recycle(); frame.recycle()
                    }
                    t += 1000L
                }
                refSmall.recycle()
                val filtered = matches.sortedByDescending { it.score }.fold(mutableListOf<ImageMatch>()) { acc, item ->
                    if (acc.none { kotlin.math.abs(it.timeMs - item.timeMs) < 2500L }) acc.add(item)
                    acc
                }.sortedBy { it.timeMs }.take(60)
                mainHandler.post {
                    findViewById<Button>(R.id.imageSceneSearchButton).isEnabled = true
                    statusText.text = "جست‌وجو تمام شد؛ ${filtered.size} صحنهٔ مشابه پیدا شد"
                    showImageMatches(filtered)
                }
            } catch (e: Exception) {
                mainHandler.post {
                    findViewById<Button>(R.id.imageSceneSearchButton).isEnabled = true
                    statusText.text = "جست‌وجوی عکس ناموفق بود: ${e.message ?: "خطا"}"
                    Toast.makeText(this, statusText.text, Toast.LENGTH_LONG).show()
                }
            } finally { try { retriever?.release() } catch (_: Exception) { } }
        }
    }

    private fun imageSimilarity(a: Bitmap, b: Bitmap): Double {
        val pa = IntArray(32 * 32); val pb = IntArray(32 * 32)
        a.getPixels(pa, 0, 32, 0, 0, 32, 32); b.getPixels(pb, 0, 32, 0, 0, 32, 32)
        var diff = 0.0
        for (i in pa.indices) {
            val ca = pa[i]; val cb = pb[i]
            diff += (kotlin.math.abs(Color.red(ca)-Color.red(cb)) + kotlin.math.abs(Color.green(ca)-Color.green(cb)) + kotlin.math.abs(Color.blue(ca)-Color.blue(cb))) / 765.0
        }
        return 1.0 - diff / pa.size
    }

    private fun showImageMatches(matches: List<ImageMatch>) {
        if (matches.isEmpty()) {
            AlertDialog.Builder(this).setTitle("نتیجهٔ جست‌وجو")
                .setMessage("صحنهٔ مشابهی با آستانهٔ فعلی پیدا نشد. عکس واضح‌تر یا فریم نزدیک‌تر را امتحان کنید.")
                .setPositiveButton("باشه", null).show()
            return
        }
        val scroll = ScrollView(this)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(8), dp(8), dp(8)) }
        matches.forEach { match ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(4), 0, dp(4)) }
            val image = ImageView(this).apply { setImageBitmap(match.bitmap); layoutParams = LinearLayout.LayoutParams(dp(142), dp(82)); scaleType = ImageView.ScaleType.FIT_CENTER }
            val label = TextView(this).apply { text = "${formatTime(match.timeMs)}  •  شباهت ${(match.score * 100).toInt()}٪\nبرای رفتن به این صحنه لمس کنید"; setTextColor(Color.WHITE); textSize = 13f; setPadding(dp(8), 0, 0, 0) }
            row.addView(image); row.addView(label); row.setOnClickListener { videoView.seekTo(match.timeMs.toInt()); videoView.start() }
            list.addView(row)
        }
        scroll.addView(list)
        AlertDialog.Builder(this).setTitle("اسلایدشو: ${matches.size} صحنهٔ مشابه")
            .setView(scroll).setPositiveButton("بستن", null).show()
    }

    private object BitmapFactoryCompat {
        fun decode(input: java.io.InputStream): Bitmap? = android.graphics.BitmapFactory.decodeStream(input)
    }

    private fun mergeSelectedVideos() {
        val sources = mergeUris.toList()
        if (sources.size < 2) { chooseVideosForMerge(); return }
        val button = findViewById<Button>(R.id.mergeButton)
        button.isEnabled = false
        statusText.text = "در حال ادغام ${sources.size} ویدیو…"
        val output = File(cacheDir, "evc_merge_${System.currentTimeMillis()}.mp4")
        try {
            val items = sources.map { uri -> EditedMediaItem.Builder(MediaItem.fromUri(uri)).build() }
            val sequence = EditedMediaItemSequence.Builder(items).build()
            val composition = Composition.Builder(sequence).build()
            val transformer = Transformer.Builder(this).addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    activeTransformer = null
                    try {
                        val values = ContentValues().apply {
                            put(MediaStore.Video.Media.DISPLAY_NAME, "EVC_Merged_${System.currentTimeMillis()}.mp4")
                            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                            if (android.os.Build.VERSION.SDK_INT >= 29) {
                                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/EVC")
                                put(MediaStore.Video.Media.IS_PENDING, 1)
                            }
                        }
                        val outUri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                            ?: throw IllegalStateException("ساخت فایل در Movies/EVC ممکن نشد")
                        contentResolver.openOutputStream(outUri)?.use { out -> FileInputStream(output).use { it.copyTo(out) } }
                            ?: throw IllegalStateException("نوشتن فایل ادغام‌شده ناموفق بود")
                        if (android.os.Build.VERSION.SDK_INT >= 29) contentResolver.update(outUri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
                        writeOutputInfoReport(outUri)
                        output.delete(); mergeUris.clear(); button.text = "Merge"; button.isEnabled = true
                        statusText.text = "ویدیوها در Movies/EVC ادغام شدند"
                        Toast.makeText(this@MainActivity, "فایل ادغام‌شده ذخیره شد", Toast.LENGTH_LONG).show()
                    } catch (e: Exception) { output.delete(); button.isEnabled = true; statusText.text = "ذخیرهٔ Merge ناموفق بود: ${e.message}" }
                }
                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                    activeTransformer = null; output.delete(); button.isEnabled = true
                    statusText.text = "ادغام ناموفق بود: ${exportException.message ?: "خطای پردازش ویدیو"}"
                    Toast.makeText(this@MainActivity, statusText.text, Toast.LENGTH_LONG).show()
                }
            }).build()
            activeTransformer = transformer
            transformer.start(composition, output.absolutePath)
        } catch (e: Exception) {
            activeTransformer = null; output.delete(); button.isEnabled = true
            statusText.text = "شروع Merge ناموفق بود: ${e.message ?: "خطا"}"
        }
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
        val saturation = findViewById<SeekBar>(R.id.saturationSeek)
        val saturationValue = findViewById<TextView>(R.id.saturationValue)
        val redValue = findViewById<TextView>(R.id.redColorValue)
        val greenValue = findViewById<TextView>(R.id.greenColorValue)
        val blueValue = findViewById<TextView>(R.id.blueColorValue)
        fun updateLabels() {
            redValue.text = "قرمز ${"%.2f".format(java.util.Locale.US, red.progress / 100f)}"
            greenValue.text = "سبز ${"%.2f".format(java.util.Locale.US, green.progress / 100f)}"
            blueValue.text = "آبی ${"%.2f".format(java.util.Locale.US, blue.progress / 100f)}"
            saturationValue.text = "اشباع رنگ ${"%.2f".format(java.util.Locale.US, saturation.progress / 100f)}"
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
        saturation.setOnSeekBarChangeListener(listener)
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
            val profile = if (selectedFrames.isEmpty() || index in selectedFrames) current else frameColorProfiles[index] ?: RgbProfile(1f, 1f, 1f, 1f)
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
            val r = (Color.red(c) * profile.red).coerceIn(0f, 255f)
            val g = (Color.green(c) * profile.green).coerceIn(0f, 255f)
            val b = (Color.blue(c) * profile.blue).coerceIn(0f, 255f)
            val gray = 0.213f * r + 0.715f * g + 0.072f * b
            val sat = profile.saturation
            pixels[i] = Color.argb(Color.alpha(c),
                (gray + (r - gray) * sat).toInt().coerceIn(0, 255),
                (gray + (g - gray) * sat).toInt().coerceIn(0, 255),
                (gray + (b - gray) * sat).toInt().coerceIn(0, 255))
        }
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { it.setPixels(pixels, 0, w, 0, 0, w, h) }
    }

    private fun currentRgbProfile() = RgbProfile(
        findViewById<SeekBar>(R.id.redColorSeek).progress / 100f,
        findViewById<SeekBar>(R.id.greenColorSeek).progress / 100f,
        findViewById<SeekBar>(R.id.blueColorSeek).progress / 100f,
        findViewById<SeekBar>(R.id.saturationSeek).progress / 100f
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
            val rangeStart = storyboardStartMs.coerceIn(0L, videoDurationMs)
            val rangeEnd = (if (storyboardEndMs > rangeStart) storyboardEndMs else videoDurationMs)
                .coerceIn(rangeStart, videoDurationMs)
            if (rangeEnd <= rangeStart) throw IllegalStateException("بازهٔ زمانی استوری‌بورد معتبر نیست")

            // Export only intervals for which the user explicitly saved a color profile.
            // Do not add neutral/unselected storyboard intervals to this output.
            val coloredIndices = frameColorProfiles.keys
                .filter { it in 0 until total && it !in deletedStoryboardFrames }
                .sorted()
            val intervals = coloredIndices.mapNotNull { index ->
                val startMs = storyboardTimestampForIndex(index, total, rangeStart, rangeEnd)
                val endMs = if (storyboardIntervalMs != null && storyboardIntervalMs!! > 0L) {
                    minOf(rangeEnd, startMs + storyboardIntervalMs!!)
                } else if (index + 1 < total) {
                    minOf(rangeEnd, storyboardTimestampForIndex(index + 1, total, rangeStart, rangeEnd))
                } else rangeEnd
                if (startMs >= rangeEnd || endMs <= startMs) null else Triple(index, startMs, endMs)
            }
            val items = intervals.map { (index, startMs, endMs) ->
                val profile = frameColorProfiles[index] ?: throw IllegalStateException("تنظیم رنگ اسلاید پیدا نشد")
                val effect = RgbAdjustment.Builder()
                    .setRedScale(profile.red).setGreenScale(profile.green).setBlueScale(profile.blue).build()
                val saturationEffect = HslAdjustment.Builder().adjustSaturation(((profile.saturation - 1f) * 100f).coerceIn(-100f, 100f)).build()
                val clippedMedia = MediaItem.Builder().setUri(source)
                    .setClippingConfiguration(MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(startMs).setEndPositionMs(endMs).build()).build()
                EditedMediaItem.Builder(clippedMedia).setEffects(Effects(emptyList(), listOf(effect, saturationEffect))).build()
            }
            if (items.isEmpty()) throw IllegalStateException("ابتدا برای اسلایدهای موردنظر تنظیم رنگ ذخیره کنید؛ هیچ بازهٔ رنگی برای خروجی وجود ندارد")
            val selectedDurationMs = intervals.sumOf { it.third - it.second }.coerceAtLeast(1L)
            // Estimate bitrate from the total duration of colored intervals, not the full source video.
            val totalBitsPerSecond = (targetMb.toDouble() * 1024.0 * 1024.0 * 8.0 / (selectedDurationMs / 1000.0)).toInt()
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
                                put(MediaStore.Video.Media.DISPLAY_NAME, "EVC_colored_intervals_${System.currentTimeMillis()}.mp4")
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
                            writeOutputInfoReport(outUri)
                            output.delete()
                            statusText.text = "فقط ${intervals.size} بازهٔ دارای تنظیم رنگ در Movies/EVC ذخیره شد؛ حجم هدف حدود ${targetMb} MB بود و رمزگذاری مجدد انجام شد."
                            Toast.makeText(this@MainActivity, "فقط بازه‌های رنگ‌گذاری‌شده ذخیره شدند؛ حجم نهایی ممکن است با هدف تفاوت داشته باشد", Toast.LENGTH_LONG).show()
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
                strip.addView(cell, LinearLayout.LayoutParams(dp(64), dp(42)).apply { setMargins(0, 0, dp(1), 0) })
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
            scaleType = ImageView.ScaleType.CENTER_CROP
            if (bitmap != null) setImageBitmap(bitmap) else setImageResource(android.R.drawable.ic_media_play)
            if (bitmap == null) setColorFilter(Color.rgb(116, 150, 198))
            contentDescription = "فریم ${index + 1}"
        }
        cell.addView(image, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val labelHeight = dp(12)
        val timeLabel = TextView(this).apply {
            text = label; textSize = 8f; setTextColor(Color.WHITE)
            setPadding(dp(2), 0, dp(2), 0); gravity = Gravity.CENTER
            // Semi-transparent label: the thumbnail remains visible beneath it.
            setBackgroundColor(0x6607142F)
        }
        cell.addView(timeLabel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, labelHeight, Gravity.BOTTOM or Gravity.START))
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
            val timeMs = storyboardTimestampForIndex(index, ROWS * FRAMES_PER_ROW, rangeStart, rangeEnd)
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

    /** Returns the timestamp represented by a storyboard cell. Blank interval keeps the automatic spacing. */
    private fun storyboardTimestampForIndex(index: Int, count: Int, startMs: Long, endMs: Long): Long {
        val interval = storyboardIntervalMs
        return if (interval != null && interval > 0L) {
            startMs + index.toLong() * interval
        } else if (count <= 1) startMs else startMs + (endMs - startMs) * index / count
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
                    val timestampMs = storyboardTimestampForIndex(index, count, rangeStart, rangeEnd)
                    if (timestampMs >= rangeEnd) continue
                    // Fast path: sync-frame seeking avoids decoding forward/backward to the
                    // exact requested timestamp for every one of the 120 storyboard cells.
                    // Thumbnails are deliberately small; the player still shows the original.
                    val bitmap: Bitmap? = try {
                        if (android.os.Build.VERSION.SDK_INT >= 27) {
                            retriever.getScaledFrameAtTime(timestampMs * 1000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 128, 80)
                        } else {
                            retriever.getFrameAtTime(timestampMs * 1000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                        }
                    } catch (_: Exception) { null }
                    if (bitmap != null) {
                        val scaled = scaleBitmapPreservingAspect(bitmap, 128, 80)
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
                            retriever.getScaledFrameAtTime(time * 1000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 160, 90)
                        } else {
                            retriever.getFrameAtTime(time * 1000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                        }
                    } catch (_: Exception) { null }
                    if (bitmap != null) frames.add(time to scaleBitmapPreservingAspect(bitmap, 160, 90))
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

    /** Sets the storyboard range endpoint to the current playback position without opening a dialog. */
    private fun setStoryboardTimeFromPlayback(isStart: Boolean) {
        if (selectedUri == null || videoDurationMs <= 0L) {
            Toast.makeText(this, "ابتدا ویدیو را انتخاب کنید", Toast.LENGTH_SHORT).show()
            return
        }
        val position = try { videoView.currentPosition.toLong() } catch (_: Exception) { timelineView.playheadMs }
        val at = position.coerceIn(0L, videoDurationMs)
        val fieldId = if (isStart) R.id.storyboardStartTime else R.id.storyboardEndTime
        findViewById<android.widget.EditText>(fieldId).apply {
            setText(formatTime(at))
            error = null
        }
        statusText.text = if (isStart) {
            "زمان شروع استوری‌بورد روی ${formatTime(at)} ثبت شد؛ برای نمایش بازه، «اعمال زمان» را بزنید"
        } else {
            "زمان پایان استوری‌بورد روی ${formatTime(at)} ثبت شد؛ برای نمایش بازه، «اعمال زمان» را بزنید"
        }
    }

    private fun applyStoryboardTimeRange() {
        val uri = selectedUri
        if (uri == null || videoDurationMs <= 0L) {
            Toast.makeText(this, "ابتدا ویدیو را انتخاب کنید", Toast.LENGTH_SHORT).show(); return
        }
        val start = parseTimeInput(findViewById<android.widget.EditText>(R.id.storyboardStartTime).text.toString())
        val end = parseTimeInput(findViewById<android.widget.EditText>(R.id.storyboardEndTime).text.toString())
        val intervalField = findViewById<android.widget.EditText>(R.id.storyboardIntervalTime)
        val intervalText = intervalField.text.toString().trim()
        val interval = if (intervalText.isEmpty()) null else parseTimeInput(intervalText)
        if (start == null || end == null || start >= end || end > videoDurationMs) {
            findViewById<android.widget.EditText>(R.id.storyboardEndTime).error = "زمان پایان باید بعد از شروع و در محدودهٔ ویدیو باشد"
            Toast.makeText(this, "زمان شروع و پایان را به‌شکل دقیقه:ثانیه وارد کنید؛ مثلاً 01:25", Toast.LENGTH_LONG).show(); return
        }
        if (intervalText.isNotEmpty() && (interval == null || interval <= 0L)) {
            intervalField.error = "مثلاً 10 برای 10 ثانیه یا 01:30 برای یک دقیقه و نیم"
            Toast.makeText(this, "فاصله را به ثانیه یا دقیقه:ثانیه وارد کنید", Toast.LENGTH_LONG).show(); return
        }
        intervalField.error = null
        storyboardIntervalMs = interval
        storyboardStartMs = start
        storyboardEndMs = end
        reloadStoryboardForRange(uri)
        statusText.text = if (interval == null) "استوری‌بورد بازهٔ ${formatTime(start)} تا ${formatTime(end)} با فاصلهٔ خودکار در حال آماده‌سازی است" else "استوری‌بورد با فاصلهٔ ${formatTime(interval)} بین اسلایدها در حال آماده‌سازی است"
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
        // A previously selected timeline segment must not override storyboard selection.
        // The export button is shared by both workflows, so clear that stale selection
        // whenever the user explicitly switches to storyboard export/delete mode.
        selectedTimelineRange = null
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
    /** Writes a MediaInfo-style sidecar report from the actual saved output file. */
    private fun writeOutputInfoReport(videoUri: Uri) {
        try {
            val displayName = contentResolver.query(videoUri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            } ?: "EVC_output.mp4"
            val size = contentResolver.openAssetFileDescriptor(videoUri, "r")?.use { it.length } ?: -1L
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(this, videoUri)
            fun meta(key: Int): String = retriever.extractMetadata(key)?.takeIf { it.isNotBlank() } ?: "Not available"
            val duration = meta(MediaMetadataRetriever.METADATA_KEY_DURATION).toLongOrNull()?.let { "${it} ms" } ?: "Not available"
            val width = meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
            val height = meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
            val frameRate = meta(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)
            val date = meta(MediaMetadataRetriever.METADATA_KEY_DATE)
            val extractor = MediaExtractor()
            extractor.setDataSource(this, videoUri, null)
            val report = StringBuilder()
            report.append("General\nComplete name : ").append(displayName).append("\nFormat : ").append(if (displayName.endsWith(".mkv", true)) "Matroska (MKV)" else "MP4 container").append("\nFile size : ")
                .append(if (size >= 0) String.format(java.util.Locale.US, "%.2f KiB", size / 1024.0) else "Not available")
                .append("\nDuration : ").append(duration).append("\nEncoded date : ").append(date).append("\n\n")
            var videoIndex = 0
            var audioIndex = 0
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: "Not available"
                if (mime.startsWith("video/")) {
                    videoIndex++
                    report.append("Video #").append(videoIndex).append("\nFormat : ").append(mime).append("\nCodec ID : ").append(mime)
                    report.append("\nWidth : ").append(if (f.containsKey(MediaFormat.KEY_WIDTH)) f.getInteger(MediaFormat.KEY_WIDTH) else width).append(" pixels")
                    report.append("\nHeight : ").append(if (f.containsKey(MediaFormat.KEY_HEIGHT)) f.getInteger(MediaFormat.KEY_HEIGHT) else height).append(" pixels")
                    if (f.containsKey(MediaFormat.KEY_FRAME_RATE)) report.append("\nFrame rate : ").append(f.getInteger(MediaFormat.KEY_FRAME_RATE)).append(" FPS") else report.append("\nFrame rate : ").append(frameRate)
                    report.append("\nBit rate : ").append(if (f.containsKey(MediaFormat.KEY_BIT_RATE)) "${f.getInteger(MediaFormat.KEY_BIT_RATE)} b/s" else "Not available")
                    report.append("\nProfile : ").append(if (f.containsKey(MediaFormat.KEY_PROFILE)) f.getInteger(MediaFormat.KEY_PROFILE) else "Not available")
                    report.append("\nColor format : ").append(if (f.containsKey(MediaFormat.KEY_COLOR_FORMAT)) f.getInteger(MediaFormat.KEY_COLOR_FORMAT) else "Not available").append("\n\n")
                } else if (mime.startsWith("audio/")) {
                    audioIndex++
                    report.append("Audio #").append(audioIndex).append("\nFormat : ").append(mime).append("\nCodec ID : ").append(mime)
                    report.append("\nBit rate : ").append(if (f.containsKey(MediaFormat.KEY_BIT_RATE)) "${f.getInteger(MediaFormat.KEY_BIT_RATE)} b/s" else "Not available")
                    report.append("\nSampling rate : ").append(if (f.containsKey(MediaFormat.KEY_SAMPLE_RATE)) "${f.getInteger(MediaFormat.KEY_SAMPLE_RATE)} Hz" else "Not available")
                    report.append("\nChannel(s) : ").append(if (f.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) f.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else "Not available")
                    report.append("\nLanguage : ").append(if (f.containsKey(MediaFormat.KEY_LANGUAGE)) f.getString(MediaFormat.KEY_LANGUAGE) else "Not available").append("\n\n")
                }
            }
            extractor.release(); retriever.release()
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName.substringBeforeLast('.', displayName) + "_Info.txt")
                put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                if (android.os.Build.VERSION.SDK_INT >= 29) {
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/EVC_Info")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
            }
            val reportUri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return
            contentResolver.openOutputStream(reportUri)?.bufferedWriter(Charsets.UTF_8)?.use { it.write(report.toString()) }
            if (android.os.Build.VERSION.SDK_INT >= 29) contentResolver.update(reportUri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (_: Exception) { /* Report failure must not invalidate a successfully exported video. */ }
    }

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
                        put(MediaStore.Video.Media.DISPLAY_NAME, "EVC_segment_${(index + 1).toString().padStart(3, '0')}_${System.currentTimeMillis()}.${outputExtension()}")
                        put(MediaStore.Video.Media.MIME_TYPE, outputMimeType())
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
                    writeOutputInfoReport(outUri!!)
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

    /** Configure and save crop margins, then export the actual cropped video through Media3 Transformer. */
    private fun showCropOptions() {
        if (selectedUri == null) {
            Toast.makeText(this, "ابتدا یک ویدیو انتخاب کنید", Toast.LENGTH_LONG).show()
            return
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 12, 28, 4)
        }
        val info = TextView(this).apply {
            text = "برش آزاد: مقدار هر لبه را جداگانه تنظیم کنید. پس از ثبت، با «خروجی Crop» برش روی فایل واقعی اعمال می‌شود."
            textSize = 14f
            setTextColor(Color.WHITE)
        }
        panel.addView(info)
        val sliders = mutableListOf<SeekBar>()
        val values = mutableListOf<TextView>()
        val labels = listOf("چپ", "بالا", "راست", "پایین")
        val initial = listOf(cropLeftPct, cropTopPct, cropRightPct, cropBottomPct)
        labels.forEachIndexed { index, label ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val value = TextView(this).apply { text = "$label: ${initial[index]}٪"; setTextColor(Color.WHITE); textSize = 13f }
            val slider = SeekBar(this).apply { max = 45; progress = initial[index] }
            values.add(value); sliders.add(slider)
            slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    value.text = "$label: ${progress}٪"
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
            row.addView(value); row.addView(slider)
            panel.addView(row)
        }
        val presets = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun presetButton(text: String, l: Int, t: Int, r: Int, b: Int) {
            val button = Button(this).apply {
                this.text = text
                textSize = 10f
                setOnClickListener {
                    val preset = listOf(l, t, r, b)
                    sliders.forEachIndexed { i, seek -> seek.progress = preset[i] }
                }
            }
            presets.addView(button, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        presetButton("بدون برش", 0, 0, 0, 0)
        presetButton("مرکزی", 10, 10, 10, 10)
        presetButton("عمودی", 22, 0, 22, 0)
        panel.addView(presets)

        val dialog = AlertDialog.Builder(this)
            .setTitle("Crop • برش آزاد")
            .setView(ScrollView(this).apply { addView(panel) })
            .setPositiveButton("ثبت برش", null)
            .setNeutralButton("خروجی Crop", null)
            .setNegativeButton("لغو", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val m = sliders.map { it.progress }
                if (m[0] + m[2] >= 90 || m[1] + m[3] >= 90) {
                    Toast.makeText(this, "باید بخشی از تصویر در هر دو جهت باقی بماند", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                cropLeftPct = m[0]; cropTopPct = m[1]; cropRightPct = m[2]; cropBottomPct = m[3]
                cropSettingsSaved = true
                // Keep the preview visibly marked as modified; actual crop is applied during export.
                val zoomX = (100f / (100 - cropLeftPct - cropRightPct)).coerceIn(1f, 2.5f)
                val zoomY = (100f / (100 - cropTopPct - cropBottomPct)).coerceIn(1f, 2.5f)
                videoView.scaleX = zoomX
                videoView.scaleY = zoomY
                playerColorPreview.scaleX = zoomX
                playerColorPreview.scaleY = zoomY
                statusText.text = "Crop ثبت شد: چپ $cropLeftPct٪، بالا $cropTopPct٪، راست $cropRightPct٪، پایین $cropBottomPct٪؛ برای ساخت فایل، خروجی Crop را بزنید"
                Toast.makeText(this, "تنظیمات برش ثبت شد", Toast.LENGTH_SHORT).show()
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                val m = sliders.map { it.progress }
                if (m[0] + m[2] >= 90 || m[1] + m[3] >= 90) {
                    Toast.makeText(this, "باید بخشی از تصویر در هر دو جهت باقی بماند", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                cropLeftPct = m[0]; cropTopPct = m[1]; cropRightPct = m[2]; cropBottomPct = m[3]
                cropSettingsSaved = true
                dialog.dismiss()
                exportCroppedVideo()
            }
        }
        dialog.show()
    }

    private fun exportCroppedVideo() {
        val source = selectedUri
        if (source == null) {
            Toast.makeText(this, "ابتدا یک ویدیو انتخاب کنید", Toast.LENGTH_LONG).show()
            return
        }
        if (!cropSettingsSaved) {
            Toast.makeText(this, "ابتدا تنظیمات Crop را ثبت کنید", Toast.LENGTH_LONG).show()
            return
        }
        val output = File(cacheDir, "evc_crop_${System.currentTimeMillis()}.mp4")
        val button = findViewById<Button>(R.id.cropButton)
        button.isEnabled = false
        statusText.text = "در حال اعمال Crop واقعی و رمزگذاری مجدد ویدیو…"
        try {
            val left = (-1f + 2f * cropLeftPct / 100f).coerceIn(-1f, 0.98f)
            val right = (1f - 2f * cropRightPct / 100f).coerceIn(-0.98f, 1f)
            val top = (1f - 2f * cropTopPct / 100f).coerceIn(-0.98f, 1f)
            val bottom = (-1f + 2f * cropBottomPct / 100f).coerceIn(-1f, 0.98f)
            val cropEffect = Crop(left, right, bottom, top)
            val item = EditedMediaItem.Builder(MediaItem.fromUri(source))
                .setEffects(Effects(emptyList(), listOf(cropEffect)))
                .build()
            val composition = Composition.Builder(EditedMediaItemSequence.Builder(item).build()).build()
            val transformer = Transformer.Builder(this)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        activeTransformer = null
                        try {
                            val values = ContentValues().apply {
                                put(MediaStore.Video.Media.DISPLAY_NAME, "EVC_Crop_${System.currentTimeMillis()}.mp4")
                                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                                if (android.os.Build.VERSION.SDK_INT >= 29) {
                                    put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/EVC")
                                    put(MediaStore.Video.Media.IS_PENDING, 1)
                                }
                            }
                            val outUri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                                ?: throw IllegalStateException("ساخت فایل در Movies/EVC ممکن نشد")
                            contentResolver.openOutputStream(outUri)?.use { out -> FileInputStream(output).use { it.copyTo(out) } }
                                ?: throw IllegalStateException("نوشتن فایل Crop ناموفق بود")
                            if (android.os.Build.VERSION.SDK_INT >= 29) contentResolver.update(outUri, ContentValues().apply {
                                put(MediaStore.Video.Media.IS_PENDING, 0)
                            }, null, null)
                            writeOutputInfoReport(outUri)
                            output.delete()
                            button.isEnabled = true
                            statusText.text = "خروجی Crop واقعی در Movies/EVC ذخیره شد. برای اعمال برش، ویدیو رمزگذاری مجدد شده است."
                            Toast.makeText(this@MainActivity, statusText.text, Toast.LENGTH_LONG).show()
                        } catch (e: Exception) {
                            output.delete(); button.isEnabled = true
                            statusText.text = "ذخیرهٔ Crop ناموفق بود: ${e.message ?: "خطا"}"
                            Toast.makeText(this@MainActivity, statusText.text, Toast.LENGTH_LONG).show()
                        }
                    }
                    override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                        activeTransformer = null; output.delete(); button.isEnabled = true
                        statusText.text = "خروجی Crop ناموفق بود: ${exportException.message ?: "خطای رمزگذاری"}"
                        Toast.makeText(this@MainActivity, statusText.text, Toast.LENGTH_LONG).show()
                    }
                }).build()
            activeTransformer = transformer
            transformer.start(composition, output.absolutePath)
        } catch (e: Exception) {
            activeTransformer = null; output.delete(); button.isEnabled = true
            statusText.text = "شروع خروجی Crop ناموفق بود: ${e.message ?: "خطا"}"
            Toast.makeText(this, statusText.text, Toast.LENGTH_LONG).show()
        }
    }

    private fun exportRemainingVideo(onlySelected: Boolean = false) {
        // Storyboard selection has explicit priority when its mode is active. Do this
        // BEFORE checking the timeline range: a stale timeline selection must never
        // redirect a storyboard export into the whole-video/timeline export path.
        if (onlySelected) {
            selectedTimelineRange = null
            exportSelectedStoryboardFramesSeparately()
            return
        }
        // A range explicitly selected on the timeline belongs to the timeline section.
        // Export only that range; never mix it with storyboard deletions or other ranges.
        val timelineRange = selectedTimelineRange
        if (timelineRange != null) {
            exportOnlyTimelineRange(timelineRange)
            return
        }
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
                // Storyboard export is isolated: do not import deletions from the timeline section.
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
                            put(MediaStore.Video.Media.DISPLAY_NAME, "EVC_edited_${System.currentTimeMillis()}.${outputExtension()}")
                            put(MediaStore.Video.Media.MIME_TYPE, outputMimeType())
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
                        writeOutputInfoReport(outUri)
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



    private fun exportOnlyTimelineRange(range: Pair<Long, Long>) {
        val source = selectedUri
        if (source == null || videoDurationMs <= 0L) {
            Toast.makeText(this, "ابتدا یک ویدیوی معتبر انتخاب کنید", Toast.LENGTH_SHORT).show(); return
        }
        val start = range.first.coerceIn(0L, videoDurationMs)
        val end = range.second.coerceIn(0L, videoDurationMs)
        if (end <= start) { Toast.makeText(this, "بازهٔ انتخاب‌شده معتبر نیست", Toast.LENGTH_SHORT).show(); return }
        val button = findViewById<Button>(R.id.exportButton)
        button.isEnabled = false
        statusText.text = "در حال خروجی گرفتن فقط از ${formatTime(start)} تا ${formatTime(end)}…"
        worker.execute {
            var result: File? = null
            var error: String? = null
            try { result = muxRemainingRanges(source, listOf(start to end)) }
            catch (e: Exception) { error = e.message ?: e.javaClass.simpleName }
            mainHandler.post {
                button.isEnabled = true
                val file = result
                if (file == null || !file.exists() || file.length() == 0L) {
                    statusText.text = "خروجی بازهٔ نوار زمان ناموفق بود: ${error ?: "خطای ناشناخته"}"
                    Toast.makeText(this, statusText.text, Toast.LENGTH_LONG).show(); return@post
                }
                try {
                    val values = ContentValues().apply {
                        put(MediaStore.Video.Media.DISPLAY_NAME, "EVC_timeline_${System.currentTimeMillis()}.${outputExtension()}")
                        put(MediaStore.Video.Media.MIME_TYPE, outputMimeType())
                        if (android.os.Build.VERSION.SDK_INT >= 29) {
                            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/EVC")
                            put(MediaStore.Video.Media.IS_PENDING, 1)
                        }
                    }
                    val outUri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                        ?: throw IllegalStateException("ساخت فایل خروجی ممکن نشد")
                    contentResolver.openOutputStream(outUri)?.use { out -> FileInputStream(file).use { input -> input.copyTo(out) } }
                        ?: throw IllegalStateException("نوشتن فایل خروجی ناموفق بود")
                    if (android.os.Build.VERSION.SDK_INT >= 29) contentResolver.update(outUri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
                    writeOutputInfoReport(outUri)
                    file.delete()
                    selectedTimelineRange = null
                    statusText.text = "فقط بازهٔ انتخاب‌شده در Movies/EVC ذخیره شد؛ تنظیمات استوری‌بورد و حذف‌ها دخالت داده نشدند"
                    Toast.makeText(this, statusText.text, Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    statusText.text = "ذخیرهٔ فایل ناموفق بود: ${e.message ?: "خطا"}"
                    Toast.makeText(this, statusText.text, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /** Exports ONLY the selected storyboard time intervals, concatenated in selection order.
     *  The intervals are built from the storyboard's active start/end range, not the full video.
     *  No complement/remaining-video ranges are ever added to this export.
     */
    private fun exportSelectedStoryboardFramesSeparately() {
        val source = selectedUri
        if (source == null || videoDurationMs <= 0L) {
            Toast.makeText(this, "ابتدا یک ویدیوی معتبر انتخاب کنید", Toast.LENGTH_SHORT).show()
            return
        }
        val total = ROWS * FRAMES_PER_ROW
        val chosen = selectedFrames.toSet()
            .filter { it in 0 until total && it !in deletedStoryboardFrames }
            .sorted()
        if (chosen.isEmpty()) {
            statusText.text = "خروجی انجام نشد: هیچ اسلایدی برای خروجی انتخاب نشده است"
            Toast.makeText(this, "خروجی لغو شد؛ ابتدا اسلایدهای موردنظر را انتخاب کنید", Toast.LENGTH_LONG).show()
            return
        }

        val rangeStart = storyboardStartMs.coerceIn(0L, videoDurationMs)
        val rangeEnd = (if (storyboardEndMs > rangeStart) storyboardEndMs else videoDurationMs)
            .coerceIn(rangeStart, videoDurationMs)
        if (rangeEnd <= rangeStart) {
            Toast.makeText(this, "بازهٔ زمانی استوری‌بورد معتبر نیست", Toast.LENGTH_LONG).show()
            return
        }

        // One exact interval per selected storyboard cell; never calculate the inverse
        // (unselected/remaining intervals) and never fall back to full-video export.
        val selectedRanges = chosen.mapNotNull { index ->
            val a = storyboardTimestampForIndex(index, total, rangeStart, rangeEnd)
            val b = if (storyboardIntervalMs != null && storyboardIntervalMs!! > 0L) {
                minOf(rangeEnd, a + storyboardIntervalMs!!)
            } else rangeStart + (rangeEnd - rangeStart) * (index + 1) / total
            if (a >= rangeEnd || b <= a) return@mapNotNull null
            if (b > a) a to b else null
        }
        if (selectedRanges.isEmpty()) {
            Toast.makeText(this, "بازهٔ زمانی معتبری در اسلایدهای انتخاب‌شده وجود ندارد", Toast.LENGTH_LONG).show()
            return
        }

        val button = findViewById<Button>(R.id.exportButton)
        button.isEnabled = false
        statusText.text = "در حال ساخت یک فایل فقط از ${selectedRanges.size} بازهٔ انتخاب‌شده؛ بازه‌های انتخاب‌نشده اضافه نمی‌شوند…"
        worker.execute {
            var temp: File? = null
            var outUri: Uri? = null
            var error: String? = null
            try {
                temp = muxRemainingRanges(source, selectedRanges)
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, "EVC_selected_storyboard_${System.currentTimeMillis()}.${outputExtension()}")
                    put(MediaStore.Video.Media.MIME_TYPE, outputMimeType())
                    if (android.os.Build.VERSION.SDK_INT >= 29) {
                        put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/EVC")
                        put(MediaStore.Video.Media.IS_PENDING, 1)
                    }
                }
                outUri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                    ?: throw IllegalStateException("ساخت فایل خروجی ناموفق بود")
                contentResolver.openOutputStream(outUri)?.use { output ->
                    FileInputStream(temp).use { input -> input.copyTo(output) }
                } ?: throw IllegalStateException("نوشتن فایل خروجی ناموفق بود")
                if (android.os.Build.VERSION.SDK_INT >= 29) {
                    contentResolver.update(outUri, ContentValues().apply {
                        put(MediaStore.Video.Media.IS_PENDING, 0)
                    }, null, null)
                }
                writeOutputInfoReport(outUri!!)
            } catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName
                outUri?.let { try { contentResolver.delete(it, null, null) } catch (_: Exception) { } }
            } finally {
                temp?.delete()
            }
            mainHandler.post {
                button.isEnabled = true
                if (error == null && outUri != null) {
                    statusText.text = "فقط ${selectedRanges.size} بازهٔ انتخاب‌شده در یک فایل ذخیره شد؛ بازه‌های انتخاب‌نشده وارد خروجی نشدند"
                } else {
                    statusText.text = "خروجی اسلایدهای انتخاب‌شده ناموفق بود: ${error ?: "خطای ناشناخته"}"
                }
                Toast.makeText(this, statusText.text, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun outputExtension(): String = if (outputContainerMatroska) "mkv" else "mp4"
    private fun outputMimeType(): String = if (outputContainerMatroska) "video/x-matroska" else "video/mp4"

    /**
     * Builds an MKV output using FFmpeg stream-copy only: no audio/video re-encoding.
     * The source URI is copied to cache first so document-provider URIs work too.
     * Stream-copy cuts are keyframe-dependent and may not land on the exact requested frame.
     */
    private fun muxRangesToMatroska(source: Uri, ranges: List<Pair<Long, Long>>): File {
        val stamp = System.currentTimeMillis()
        val input = File(cacheDir, "evc_mkv_source_$stamp.bin")
        val segments = mutableListOf<File>()
        val concatList = File(cacheDir, "evc_mkv_concat_$stamp.txt")
        val output = File(cacheDir, "evc_export_$stamp.mkv")
        try {
            contentResolver.openInputStream(source)?.use { src ->
                input.outputStream().use { dst -> src.copyTo(dst) }
            } ?: throw IllegalStateException("خواندن فایل ویدیوی اصلی ممکن نشد")
            if (input.length() == 0L) throw IllegalStateException("فایل ویدیوی اصلی خالی است")

            ranges.filter { it.second > it.first }.forEachIndexed { index, range ->
                val segment = File(cacheDir, "evc_mkv_part_${stamp}_$index.mkv")
                val start = range.first.coerceAtLeast(0L) / 1000.0
                val duration = (range.second - range.first).coerceAtLeast(0L) / 1000.0
                if (duration <= 0.0) return@forEachIndexed
                val args = arrayOf(
                    "-y", "-ss", String.format(java.util.Locale.US, "%.3f", start),
                    "-i", input.absolutePath, "-t", String.format(java.util.Locale.US, "%.3f", duration),
                    "-map", "0", "-c", "copy", "-avoid_negative_ts", "make_zero",
                    "-f", "matroska", segment.absolutePath
                )
                val session = FFmpegKit.executeWithArguments(args)
                if (!ReturnCode.isSuccess(session.returnCode) || !segment.exists() || segment.length() == 0L) {
                    throw IllegalStateException("ساخت قطعهٔ MKV ناموفق بود: ${session.allLogsAsString.takeLast(900)}")
                }
                segments.add(segment)
            }
            if (segments.isEmpty()) throw IllegalStateException("هیچ بازهٔ معتبری برای خروجی MKV وجود ندارد")
            concatList.writeText(segments.joinToString("\n") { segment ->
                "file '${segment.absolutePath.replace("'", "'\\''")}'"
            } + "\n")
            val concatArgs = arrayOf(
                "-y", "-f", "concat", "-safe", "0", "-i", concatList.absolutePath,
                "-map", "0", "-c", "copy", "-f", "matroska", output.absolutePath
            )
            val concatSession = FFmpegKit.executeWithArguments(concatArgs)
            if (!ReturnCode.isSuccess(concatSession.returnCode) || !output.exists() || output.length() == 0L) {
                throw IllegalStateException("ادغام قطعات MKV ناموفق بود: ${concatSession.allLogsAsString.takeLast(900)}")
            }
            return output
        } catch (e: Exception) {
            output.delete()
            throw e
        } finally {
            input.delete()
            concatList.delete()
            segments.forEach { it.delete() }
        }
    }

    private fun muxRemainingRanges(source: Uri, ranges: List<Pair<Long, Long>>): File {
        if (outputContainerMatroska) return muxRangesToMatroska(source, ranges)
        val probe = MediaExtractor()
        probe.setDataSource(this, source, null)
        val videoTrack = (0 until probe.trackCount).firstOrNull {
            probe.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
        } ?: run { probe.release(); throw IllegalStateException("ترک ویدیویی پیدا نشد") }
        // Android's MPEG-4 MediaMuxer only supports a subset of MP4 tracks. Never silently
        // discard subtitles, timed metadata, or other non-A/V tracks during a modified export.
        val unsupportedTracks = mutableListOf<String>()
        for (i in 0 until probe.trackCount) {
            val format = probe.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: "unknown/unknown"
            if (!mime.startsWith("video/") && !mime.startsWith("audio/")) unsupportedTracks.add("#$i $mime")
        }
        if (unsupportedTracks.isNotEmpty()) {
            probe.release()
            throw IllegalStateException(
                "این ویدیو ترک‌های غیرصوتی/تصویری دارد (${unsupportedTracks.joinToString()})؛ " +
                "خروجی‌گیر فعلی اندروید قادر به حفظ مطمئن زیرنویس/داده‌های آن نیست. برای جلوگیری از حذف بی‌صدا، خروجی لغو شد. " +
                "برای حفظ همهٔ ترک‌ها باید خروجی‌گیر FFmpeg با پشتیبانی از کپی همهٔ ترک‌ها اضافه شود."
            )
        }
        val output = File(cacheDir, "evc_export_${System.currentTimeMillis()}.mp4")
        val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val trackMap = mutableMapOf<Int, Int>()
        for (i in 0 until probe.trackCount) {
            val format = probe.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("video/") || mime.startsWith("audio/")) {
                try {
                    trackMap[i] = muxer.addTrack(format)
                    probe.selectTrack(i)
                } catch (e: Exception) {
                    try { muxer.release() } catch (_: Exception) { }
                    probe.release()
                    throw IllegalStateException("حفظ ترک $i با کدک $mime توسط خروجی‌گیر MP4 اندروید پشتیبانی نشد؛ برای جلوگیری از حذف ترک، خروجی لغو شد.", e)
                }
            }
        }
        if (trackMap.isEmpty()) { probe.release(); muxer.release(); throw IllegalStateException("ترک قابل خروجی پیدا نشد") }
        muxer.start()
        val buffer = java.nio.ByteBuffer.allocate(8 * 1024 * 1024)
        val info = MediaCodec.BufferInfo()
        var outputBaseUs = 0L
        try {
            for ((requestedStartMs, requestedEndMs) in ranges) {
                if (requestedEndMs <= requestedStartMs) continue
                val syncProbe = MediaExtractor()
                syncProbe.setDataSource(this, source, null)
                syncProbe.selectTrack(videoTrack)
                syncProbe.seekTo(requestedStartMs * 1000L, MediaExtractor.SEEK_TO_NEXT_SYNC)
                val actualStartUs = if (syncProbe.sampleTrackIndex >= 0) syncProbe.sampleTime else -1L
                syncProbe.release()
                if (actualStartUs < 0L || actualStartUs >= requestedEndMs * 1000L) continue

                // First lightweight pass finds the first video PTS for timestamp normalization.
                val startProbe = MediaExtractor()
                startProbe.setDataSource(this, source, null)
                startProbe.selectTrack(videoTrack)
                startProbe.seekTo(actualStartUs, MediaExtractor.SEEK_TO_NEXT_SYNC)
                val segmentStartUs = if (startProbe.sampleTrackIndex == videoTrack) startProbe.sampleTime else actualStartUs
                startProbe.release()

                // Start this output strictly at the selected slide's own sync frame.
                // Seeking to PREVIOUS_SYNC can land inside the preceding storyboard
                // interval; use NEXT_SYNC and still enforce the selected start timestamp.
                probe.seekTo(actualStartUs, MediaExtractor.SEEK_TO_NEXT_SYNC)
                while (probe.sampleTrackIndex >= 0) {
                    val track = probe.sampleTrackIndex
                    val pts = probe.sampleTime
                    if (pts >= requestedEndMs * 1000L) break
                    if (track in trackMap && pts >= actualStartUs) {
                        buffer.clear()
                        val size = probe.readSampleData(buffer, 0)
                        if (size > buffer.capacity()) throw IllegalStateException("نمونهٔ رسانه از ظرفیت بافر بیشتر است")
                        if (size > 0) {
                            info.set(0, size, outputBaseUs + (pts - segmentStartUs).coerceAtLeast(0L), probe.sampleFlags)
                            muxer.writeSampleData(trackMap.getValue(track), buffer, info)
                        }
                    }
                    if (!probe.advance()) break
                }
                outputBaseUs += (requestedEndMs * 1000L - segmentStartUs).coerceAtLeast(1L)
            }
        } finally {
            try { muxer.stop() } catch (_: Exception) { }
            muxer.release()
            probe.release()
        }
        if (!output.exists() || output.length() == 0L) {
            output.delete()
            throw IllegalStateException("هیچ بازهٔ قابل‌خروجی باقی نماند؛ ممکن است برش این کدک با حفظ کدک پشتیبانی نشود")
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
    fun setPlayhead(timeMs: Long) {
        playheadMs = timeMs.coerceIn(0L, durationMs)
        // Auto-follow playback: let the playhead travel across the visible strip,
        // then move the timeline window when it approaches the right edge.
        if (durationMs > 0L) {
            val visible = visibleDuration()
            val end = windowStartMs + visible
            val followThreshold = windowStartMs + (visible * 0.82f).toLong()
            if (playheadMs > followThreshold && playheadMs < durationMs) {
                windowStartMs = (playheadMs - (visible * 0.72f).toLong())
                    .coerceIn(0L, max(0L, durationMs - visible))
            } else if (playheadMs < windowStartMs && playheadMs > 0L) {
                windowStartMs = (playheadMs - (visible * 0.15f).toLong())
                    .coerceIn(0L, max(0L, durationMs - visible))
            }
        }
        invalidate()
    }
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
        val top = height * .03f; val bottom = height * .97f
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
                    // Fill each timeline time-cell like a thumbnail preview; do not shrink
                    // the cell or leave letterbox gaps. Center-crop only the image to the cell.
                    val bitmapRatio = bitmap.width.toFloat() / bitmap.height.toFloat()
                    val targetRatio = dst.width() / dst.height()
                    val source = if (bitmapRatio > targetRatio) {
                        val cropWidth = (bitmap.height * targetRatio).toInt().coerceIn(1, bitmap.width)
                        val leftCrop = (bitmap.width - cropWidth) / 2
                        Rect(leftCrop, 0, leftCrop + cropWidth, bitmap.height)
                    } else {
                        val cropHeight = (bitmap.width / targetRatio).toInt().coerceIn(1, bitmap.height)
                        val topCrop = (bitmap.height - cropHeight) / 2
                        Rect(0, topCrop, bitmap.width, topCrop + cropHeight)
                    }
                    paint.color = Color.WHITE
                    canvas.drawBitmap(bitmap, source, dst, paint)
                }
            }
            canvas.restoreToCount(save)
            paint.color = Color.argb(18, 8, 17, 32)
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
        val px = xAt(playheadMs); canvas.drawLine(px, top, px, bottom, paint)
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
