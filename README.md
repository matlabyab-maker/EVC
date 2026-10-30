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
