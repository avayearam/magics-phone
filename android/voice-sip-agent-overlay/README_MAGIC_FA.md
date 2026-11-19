# Magic Phone 0.5.3-direct — فقط مسیر مستقیم هاست

این نسخه از سورس واقعی GitHub و submodule پین‌شده ساخته می‌شود؛ هیچ ZIP یا Backup مبنای Build نیست.

## مسیر صوتی

گوشی و موتور تماس Shizuku → **wss://ai.magics.site/call/ws?mode=phone** → سرویس Node.js مستقیم روی هاست AI → Gemini Live.

**مسیر Cloudflare Worker و fallback عمداً حذف شده‌اند.** پیش از فعال‌شدن WebSocket روی هاست و Reverse Proxy، این نسخه به Gemini وصل نمی‌شود و نباید برای پاسخ‌گویی تماس واقعی فعال شود.

## شناسایی ایجنت

در `/manager/call-agents.php` برای هر گوشی یک توکن دستگاه از نوع `mpa_<48 hex>` بسازید. هر ایجنت می‌تواند به چند گوشی با توکن متفاوت وصل شود. توکن در Android Keystore به‌صورت AES-GCM رمزگذاری می‌شود؛ رمز Google هرگز به گوشی ارسال نمی‌شود. توکن قدیمی Worker پذیرفته نمی‌شود.

## Build

نیازمند JDK 17، SDK Platform 37، NDK 28.2.13676358، SWIG و ابزارهای Native است. روش اصلی: کلون `avayearam/magics-phone` از **SHA دقیق**، init submodules، اجرای `apply.sh` (و دریافت فونت Vazirmatn با SHA معتبر)، ساخت PJSUA2 pinned native، تست Gradle و `:app:assembleDebug`. Build از GitHub Actions در مخزن عمومی `avayearam/Sobhan` اجرا می‌شود؛ لینک Artifact فقط بعد از موفقیت Build معتبر است.

شناسه بسته: `site.magics.phone.agent`؛ `versionCode=11` و `versionName=0.5.3-direct`. امضای Debug runner ممکن است با APK قدیمی متفاوت باشد؛ در این صورت نصب به‌عنوان Update ممکن نیست و باید قبل از حذف نسخه قدیمی، داده‌های ضروری را بررسی کرد.

**Production Verification و سلامت صدا صرفاً بعد از نصب، تست دسترسی Shizuku، تأیید موفق WebSocket و تماس آزمایشی دوطرفه قابل اعلام است.**
