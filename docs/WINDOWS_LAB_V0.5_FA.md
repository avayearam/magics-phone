# Magic Phone v0.5 — Windows Portable Discovery Pilot

تاریخ: 2026-11-18

## محدوده انجام‌شده
- رفع ایراد راه‌اندازی مجدد پیش از خروج کامل Worker صوتی: وضعیت `failed` یا Timeout دیگر باعث آزاد شدن زودهنگام Slot یا مقصد اشغال‌شده نمی‌شود. شروع مجدد فقط پس از بسته‌شدن واقعی `done` مجاز است.
- در زمان خطای راه‌اندازی، اگر Worker هنوز مشغول باشد، توقف آن قابل درخواست است؛ دلیل شکست ثبت‌شده پاک نمی‌شود.
- اگر اعلام آمادگی دیر برسد ولی Worker قبلاً خراب شده یا متوقف شده باشد، درخواست شروع موفق گزارش نمی‌شود.
- Agent اکنون ابتدا پورت Loopback را باز می‌کند، سپس با `ShellExecuteW` مرورگر پیش‌فرض را روی `http://127.0.0.1:18764/` باز می‌کند؛ بدون PowerShell، shell script، elevation یا دانلود خودکار.
- خروج با Ctrl+C / SIGTERM باعث Shutdown HTTP و درخواست توقف هر دو مسیر صوتی می‌شود.
- کنترل A/B، شناسایی دستگاه‌ها و رابط فارسی حفظ شده‌اند. Magic Core همچنان خارج از محدوده است.

## تست‌های واقعی در محیط توسعه
- `go test -race -count=1 -v ./...`: ده تست (سناریوهای عمر Worker، Timeout، توقف پس از خطا، HTTP Guard، Start/Stop و غیرفعال‌بودن پیش‌فرض) موفق.
- `node web/dual-route.test.cjs`: موفق.
- `GOOS=windows GOARCH=amd64 CGO_ENABLED=0 go vet ./...`: موفق.
- `GOOS=windows GOARCH=amd64 CGO_ENABLED=0 go build`: یک فایل PE32+ Windows x64 تولید شد؛ کامپایل موفق، ولی اجرا روی Windows انجام نشده است.
- فایل Windows فقط برای آزمایش دستی شناسایی دستگاه‌ها بسته‌بندی شده است؛ امضای دیجیتال و تست Defender انجام نشده است.

## روش تست نسخه قابل اجرای آزمایشی
۱. فایل `MagicPhone-Lab-v0.5-Windows-x64.zip` را دریافت و Extract کنید.
۲. فایل `MagicPhone-Lab-v0.5-win-x64.exe` را با دوبارکلیک اجرا کنید. کنسول باز می‌ماند؛ صفحه مرورگر باید خودکار باز شود.
۳. در صفحه محلی روی «شناسایی دستگاه‌ها» کلیک و وضعیت خروجی، ورودی و جلسات صوتی را بررسی کنید.
۴. تصویر نتیجه را ارسال کنید؛ هیچ تنظیمی در Chrome، Phone Link، VoiceMeeter یا VB-CABLE تغییر ندهید.
۵. برای خروج کنسول را ببندید یا Ctrl+C بزنید.

**گیت ایمنی:** موتور صوتی پیش‌فرض غیرفعال است؛ شروع مسیر مستلزم فعال‌سازی مستقل آزمایشگاهی است. روی دستگاه اصلی پیش از کنترل شرایط انتشار صدا، از شروع مسیرهای فعال خودداری شود. اگر Windows Defender یا SmartScreen هشدار داد، هشدار را دور نزنید و اجرا را متوقف کنید.

## محدودیت‌های باز
- اجرای WASAPI و COM واقعی روی Windows x64، Device Hot-Plug، تغییر فرمت و کیفیت/تأخیر صدای واقعی تأیید نشده است.
- در حال حاضر Loopback به معنای مخلوط کل صدای خروجی است، نه جداسازی Chrome یا Phone Link.
- Endpoint audio stream به دستگاه مقصد خروجی می‌رود، نه مستقیماً به میکروفون نرم‌افزار دیگر. نیاز به مسیر درایور مجازی مجاز باقی است.
- `Setup.exe` و نصب خودکار رسمی آماده نیستند؛ این فایل Portable Lab است و Production محسوب نمی‌شود.

## مستندات Microsoft
- https://learn.microsoft.com/en-us/windows/win32/coreaudio/recovering-from-an-invalid-device-error
- https://learn.microsoft.com/en-us/windows/win32/coreaudio/loopback-recording
