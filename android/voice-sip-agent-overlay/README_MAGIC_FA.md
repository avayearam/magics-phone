# Magic Phone 0.5.6-direct — اتصال مستقیم Origin و آرشیو مکالمات

این نسخه از سورس واقعی GitHub و submodule پین‌شده ساخته می‌شود؛ هیچ ZIP یا Backup مبنای Build نیست.

## مسیر صوتی

گوشی و موتور تماس Shizuku → **wss://voice.magics.site/ws?mode=phone** → Node.js Direct Bridge روی cPanel → Gemini Live.

هدر Origin برای کنترل دسترسی همچنان `https://ai.magics.site` است؛ اما جریان صوتی WebSocket مستقیماً به `voice.magics.site` می‌رود.

**در APK هیچ مسیر Cloudflare Worker یا fallback وجود ندارد.** DNS دامنه `voice.magics.site` باید DNS-only باشد و Node.js Passenger روی cPanel باید فعال و پاسخ‌گو باشد. PHP احراز هویت و آرشیو همچنان از `ai.magics.site` استفاده می‌کنند.

## شناسایی ایجنت

در `/manager/call-agents.php` برای هر گوشی یک توکن دستگاه از نوع `mpa_<48 hex>` بسازید. هر ایجنت می‌تواند به چند گوشی با توکن متفاوت وصل شود. توکن در Android Keystore به‌صورت AES-GCM رمزگذاری می‌شود؛ رمز Google هرگز به گوشی ارسال نمی‌شود. توکن قدیمی Worker پذیرفته نمی‌شود.

## Build

نیازمند JDK 17، SDK Platform 37، NDK 28.2.13676358، SWIG و ابزارهای Native است. روش اصلی: کلون `avayearam/magics-phone` از **SHA دقیق**، init submodules، اجرای `apply.sh` (و دریافت فونت Vazirmatn با SHA معتبر)، ساخت PJSUA2 pinned native، تست Gradle و `:app:assembleDebug`. Build از GitHub Actions در مخزن عمومی `avayearam/Sobhan` اجرا می‌شود؛ لینک Artifact فقط بعد از موفقیت Build معتبر است.

شناسه بسته: `site.magics.phone.agent`؛ `versionCode=14` و `versionName=0.5.6-direct`. امضای Debug runner ممکن است با APK قدیمی متفاوت باشد؛ در این صورت نصب به‌عنوان Update ممکن نیست و باید قبل از حذف نسخه قدیمی، داده‌های ضروری را بررسی کرد.

**Production Verification و سلامت صدا صرفاً بعد از نصب، تست دسترسی Shizuku، تأیید موفق WebSocket و تماس آزمایشی دوطرفه قابل اعلام است.**


## آرشیو مکالمات و شناسه تماس‌گیرنده

در تماس ورودی سیم‌کارت، شماره از Party مربوط به سمت دور تماس در CallBridge می‌آید؛ این Party توسط Android Telecom از Call.Details.handle ساخته می‌شود. اپ فقط شماره معتبر را در هدر X-Magic-Phone-Caller روی WebSocket احرازشده و TLS به Origin می‌فرستد. شماره در URL، لاگ یا توکن دستگاه قرار نمی‌گیرد. سرور فقط پس از اعتبارسنجی توکن دستگاه، شماره را به نشست آرشیو متصل می‌کند.

## BYOK, agent settings and device-owned caller archive — 0.5.6

- Each device token can store its own Google Gemini API key. The app sends the key to the HTTPS device API; it is encrypted at rest on the server and never embedded in the APK or returned to the app.
- Agent prompt, voice and language are shared by all device tokens assigned to that agent.
- Caller lists, full transcripts and deletion actions are scoped to the authenticated device token.
- A concise conversation summary is generated server-side after a call ends when the device has a valid API key. Summary generation failure does not delete or invalidate the transcript.
- The app includes tabs for calls, agent settings, caller history and API-key management.
