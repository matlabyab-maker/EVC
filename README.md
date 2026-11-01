# EVC — Version 0.1.0

First incremental build: video selection and playback only.

## Included
- Android video picker using the system document picker (`video/*`).
- Video playback with Android `VideoView` and built-in media controls.
- Custom EVC launcher icon.
- GitHub Actions workflow based on the supplied Android workflow pattern, producing an `EVC-debug-apk` artifact.

## Build
Open this folder as an Android project in Android Studio, or push the project files to a new GitHub repository named `EVC`. GitHub Actions runs on push and manual dispatch.

No `.git` directory is included: initialize a fresh repository for EVC so the old EsCut remote/history is not accidentally reused.

## Scope of this first version
This version does not cut video yet. The only app capability is selecting and playing a video. Build and device playback still need verification in GitHub Actions / on the target device.


## v0.2.2 storyboard fixes
- Fixes the cleared frame-view map so extracted thumbnails are attached to visible cells.
- Uses a visible yellow foreground border and check badge for selected frames.
- Adds time-based view and on-device ML Kit person-detection view.
- Adds removal of selected cards from the storyboard. This removes storyboard cards only; it does not modify/export the source video yet.


## v0.2.7 — خروجی ویدیویی از بازه‌های باقی‌مانده
- حذف اسلاید در استوری‌بورد اکنون آن بازهٔ زمانی را از خروجی علامت‌گذاری می‌کند؛ فایل منبع دست‌نخورده می‌ماند.
- دکمهٔ «ساخت ویدیوی خروجی» بازه‌های باقی‌مانده را به یک MP4 تبدیل می‌کند و در `Movies/EVC` می‌گذارد.
- این حالت بدون رمزگذاری مجدد است؛ برش دقیق در هر نقطه برای همهٔ کدک‌ها تضمین نمی‌شود و برای قابل‌پخش ماندن، شروع قطعه به فریم کلیدی بعدی می‌رود. حالت تعیین حجم با رمزگذاری مجدد هنوز در این نسخه پیاده‌سازی نشده است.


## Version 0.2.8
- Storyboard studio expanded from 6 rows to 8 rows, with 15 frame slots per row (120 sample points total).
- Reduced vertical row-label padding and removed extra margins between storyboard cells to minimize gaps.


## Version 0.2.9
- Added a Select All button for all storyboard frames that have not been deleted.
- Updated the selection counter to use the configured storyboard capacity (8 × 15 = 120).


## v0.2.11 — Distinct people mode
- Added an “افراد متفاوت” storyboard mode that selects representative slides containing visually different detected faces.
- This is a lightweight face-appearance heuristic, not reliable identity recognition; similar faces, angles, lighting, or low resolution may affect grouping.


## Version 0.2.12 — Interactive timeline
- Added a timeline strip above the storyboard rows. Drag with one finger to scrub and seek; pinch with two fingers to zoom the visible time range.
- Added split-at-playhead, select-a-segment, delete-selected-segment, and restore timeline edits. Deleted timeline segments are combined with storyboard-deleted intervals when preparing the remaining video.
- The timeline is a lightweight native custom view and adds no new library dependency.
- Build/test status: not run in this environment because the ZIP does not include a Gradle wrapper and no local Gradle executable was verified.

## Version 0.2.13 — انتخاب هدف اسلایدها
- Added two explicit storyboard selection modes: «انتخاب اسلاید برای حذف» and «انتخاب اسلاید برای خروجی».
- In delete mode, selected slides are marked as intervals to exclude from the output; the original source file remains unchanged.
- In selected-output mode, the output contains only the time intervals represented by selected slides. Unselected intervals and any timeline-deleted ranges are excluded.
- The export button label changes with the active mode, and the delete action is blocked while selected-output mode is active to reduce accidental operations.
- Build/test status: not run in this environment because no local Gradle executable or project Gradle wrapper is available; GitHub Actions is configured to build the project.


## Version 0.2.14 — هم‌ترازسازی زمان اسلاید و خروجی

- زمان نمونه‌برداری اسلایدها، زمان حرکت پخش به اسلاید و مرزبندی بازه‌های خروجی اکنون از تقسیم‌بندی یکسانِ ۱۲۰ بازه استفاده می‌کنند. این تغییر اختلاف مرزی نسخهٔ قبل را برطرف می‌کند.
- این نسخه هنوز رنگی‌سازی را فقط به‌صورت پیش‌نمایش اسلایدها انجام می‌دهد؛ رنگی‌سازی واقعی فایل ویدیویی در این نسخه فعال نشده است و برای آن باید مسیر رمزگذاری مجدد فریم‌ها به‌صورت جداگانه پیاده‌سازی و آزمایش شود.
- بیلد اندروید و رفتار خروجی روی دستگاه باید با GitHub Actions و یک فایل آزمایشی بررسی شود؛ ساخت ZIP به‌تنهایی تأییدکنندهٔ اجرای صحیح نیست.


## Version 0.2.15 — حذف فاصلهٔ مصنوعی بین قطعه‌های خروجی

- هنگام چسباندن بازه‌های باقی‌مانده یا اسلایدهای انتخاب‌شده، دیگر فاصلهٔ زمانی مصنوعی ۴۵۰ میلی‌ثانیه‌ای بین قطعه‌ها درج نمی‌شود.
- هدف این تغییر، پخش پیوسته‌تر و جمع‌وجورتر خروجی است؛ منبع اصلی همچنان دست‌نخورده می‌ماند و خروجی در این حالت بدون رمزگذاری مجدد ساخته می‌شود.
- محدودیت برش در مرز فریم‌های کلیدی کدک همچنان وجود دارد.
- رنگی‌سازی واقعی فریم‌های ویدیوی خروجی هنوز پیاده‌سازی نشده است؛ رنگی‌سازی فعلی فقط پیش‌نمایش استوری‌بورد را تغییر می‌دهد.
- بیلد اندروید و خروجی واقعی باید با GitHub Actions و یک ویدیوی آزمایشی بررسی شود.

## Version 0.2.16 — real video export color effect
- The colorization button now processes the actual video with AndroidX Media3 Transformer and writes a new MP4 under `Movies/EVC`.
- The exported file uses a warm sepia effect on video frames; audio is retained by the transformer when supported by the source.
- This is a color effect, not AI-based reconstruction of missing colors. It does not promise natural object-specific colors or restoration of original colors.
- Export re-encodes the video, so original video codec/quality and file size are not guaranteed. The original source file remains unchanged.
- The existing cut/export workflow is otherwise left untouched.


## Version 0.2.17
- Fix Media3 `Effects` package import for the video color effect.
- Replace unavailable `RgbFilter.createSepiaFilter()` with a supported `RgbAdjustment` warm tint (red 1.12, green 1.00, blue 0.82).
- No unrelated timeline/export timing changes.


## نسخهٔ 0.2.18
- افزودن کنترل دستی ضرایب کانال‌های قرمز، سبز و آبی برای افکت رنگی ویدیوی خروجی.
- مقدار هر کانال از 0.00x تا 2.00x قابل تنظیم است؛ اعمال افکت هنگام فشردن دکمهٔ خروجی انجام می‌شود.
- این افکت تنظیم رنگ کانال‌هاست و رنگی‌سازی هوشمند فیلم سیاه‌وسفید محسوب نمی‌شود.


## EVC 0.2.19
- Removed the human-scene and distinct-face detection modes, including their ML Kit dependencies.
- Kept the time-based storyboard mode and existing manual RGB color controls.


## v0.2.20 — Share videos into EVC
- Adds EVC to Android's share targets for `video/*` content.
- Shared video URIs are opened in the existing editor workflow; the original file is not modified.
- The existing document picker remains available.


## Version 0.2.21
- Assign an RGB combination to one or multiple selected storyboard intervals. Select one slide for an individual combination, or several slides to apply the same combination to all of them.
- Export the video with saved per-interval RGB effects using Media3 Transformer.
- Enter a target output size in MB; the app estimates video bitrate from duration. Actual size may differ due to content and audio.


## Version 0.2.23
- Restored manual storyboard time-range selection using `MM:SS` or `HH:MM:SS` start/end fields.
- Storyboard samples now come from the selected time range.
- Preserved source-frame aspect ratio in storyboard cells and timeline thumbnails; timeline frames are fitted without stretching or cropping.
- Reduced storyboard thumbnail cell size and corner rounding for a more compact natural layout.


## Version 0.2.24
- The `Cut` button records a split at the video player current position at the moment of the tap. Each tap can add another split point; duplicate positions are rejected.


## EVC 0.2.26 — Timeline and storyboard thumbnail performance
- Timeline strip now samples a compact set of 16 frames independently from storyboard extraction.
- Storyboard thumbnail extraction uses scaled frame decoding on Android 8.1+ and only one fallback attempt.
- Timeline thumbnails are no longer rebuilt from and sorted against all storyboard frames after each frame arrives.


## Version 0.2.27
- Added a button to save each interval separated by Cut markers as an individual MP4 in Movies/EVC, using stream-copy without video re-encoding. Intervals fully marked for deletion are skipped.


## Version 0.2.28
- Restores explicit storyboard time selection with native start/end time pickers. Manual time entry remains available; apply the chosen range to reload storyboard frames.


## Version 0.2.32
- Storyboard start/end buttons now capture the current playback position directly; no time-picker dialog opens.


## v0.2.35 — Fix storyboardRows compile error
- Restores the missing `storyboardRows` container inside the storyboard ScrollView.
- Fixes `Unresolved reference storyboardRows` in MainActivity by adding the matching XML ID.
- No unrelated UI or export behavior changed.


## Version 0.2.40 — Separate selected storyboard exports

Selected storyboard export now creates one MP4 file per selected storyboard cell. It no longer concatenates multiple selected cells into one continuous output. Files are saved under `Movies/EVC`. In stream-copy mode, exact boundaries remain limited by source keyframes; a selected interval without a usable keyframe may fail rather than be re-encoded.


## Version 0.2.49 — Double-height timeline and track safety
- Timeline strip height doubled from 68dp to 136dp.
- Modified range exports continue to stream-copy all audio/video tracks supported by Android MP4 MediaMuxer; if subtitle or other unsupported tracks are detected, export is cancelled rather than silently dropping them. Full subtitle/metadata preservation requires an FFmpeg-based muxing path and is not yet claimed in this version.


## Version 0.2.50
- Timeline now follows playback: the playhead moves across the visible timeline, and the timeline window scrolls when playback approaches the right edge. Seeking back also brings the playhead back into view.
