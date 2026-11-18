# اتصال واقعی تماس ایرانسل به Gemini Live — بسته ادغام Android

## نتیجه سخت‌افزاری تأییدشده
روی Samsung Galaxy S23 Ultra با Voice SIP Agent، حالت Echo incoming calls (test) فعال شد و یک تماس ورودی واقعی ایرانسل پاسخ داده شد؛ تماس‌گیرنده پژواک صدای خودش را شنید. بنابراین مسیر تصاحب تماس، دریافت PCM و تزریق دوباره صدا روی همین دستگاه در تست واقعی تأیید شده است. این نتیجه هنوز اتصال Gemini را تأیید نمی‌کند.

## کد آماده ادغام
این پوشه یک بسته source برای fork مبتنی بر sparkcyf/android-voice-sip-agent است:
- GeminiLiveEndpoint.kt: یک CallEndpoint جدید که به‌جای SIP به wss://phone.magics.site/ws?mode=phone وصل می‌شود. قرارداد Worker فعلی را رعایت می‌کند: Origin https://ai.magics.site و subprotocolهای magic-phone.v1 و magic-auth.<base64url token>.
- GeminiLiveMediaPort.kt: MediaPort clockless با ورودی 8/16kHz و خروجی 8/16kHz؛ صوت خروجی Gemini را از 24kHz PCM16LE تبدیل می‌کند و ورودی تماس را به 16kHz موردنیاز Worker می‌فرستد. صف پخش حداکثر یک ثانیه است و هنگام interruption پاک می‌شود.
- GeminiTokenStore.kt: رمز را با AES-GCM و کلید Android Keystore ذخیره می‌کند. کلید Google API هرگز روی گوشی قرار نمی‌گیرد.

## وابستگی لازم
در gradle/libs.versions.toml:
    [versions]
    okhttp = "4.12.0"
    [libraries]
    okhttp = { group = "com.squareup.okhttp3", name = "okhttp", version.ref = "okhttp" }

در app/build.gradle.kts:
    implementation(libs.okhttp)

## ادغام در GatewayRuntime
1. یک GeminiTokenStore(app) بساز و GeminiLiveEndpoint({ tokenStore.load() }, audioFormat(), ::log) را مدیریت کن.
2. در حالت geminiAgentEnabled، startSip() را اجرا نکن و وضعیت SIP را فقط به‌عنوان unused نگه دار.
3. در rebuildBridge() ترتیب انتخاب sideB: اول Echo فقط وقتی echo-test روشن است؛ بعد Gemini وقتی حالت AI صریحاً روشن است؛ در غیر این صورت SIP فعلی. مسیر CellularEndpoint و CallBridge تغییر نکند.
4. در stopBridge() و stopGateway() endpoint را close کن. در پایان هر تماس، session و WebSocket باید حتماً بسته شوند.
5. readiness باید در حالت Gemini بر اساس token configured و وضعیت endpoint محاسبه شود؛ خطای SIP نباید مانع حالت Gemini باشد.

## UI لازم
- بخش Gemini Live با فیلد رمز و دکمه ذخیره؛ بعد از ذخیره فیلد پاک شود.
- وضعیت «رمز ذخیره شده» بدون نمایش مقدار آن.
- Toggle مستقل «پاسخ‌گویی آزمایشی با Gemini Live» که پیش‌فرض خاموش باشد.
- هشدار روشن: تا وقتی این حالت فعال است، تماس‌های ورودی سیم‌کارت‌های انتخاب‌شده به‌طور خودکار به Gemini سپرده و پس از آماده‌شدن مدل پاسخ داده می‌شوند. Echo test و Gemini mode هرگز هم‌زمان فعال نباشند.
- دکمه خاموش‌کردن فوری AI mode و بازگرداندن مسیر قبلی SIP/شماره‌گیر.
- رمز در ConfigFile export/import یا log قرار نگیرد.

## محدودیت مهم نسخه اول
این بسته فعلاً مسیر AI auto-answer آزمایشی را هدف می‌گیرد. انتخاب دستی «این تماس را با AI جواب بده» و «فقط تماس‌هایی که پس از N ثانیه بی‌پاسخ ماندند» نیازمند UI/notification و سیاست پاسخ‌گویی جداگانه است و باید بعد از تست end-to-end افزوده شود. تا آن زمان حالت AI به‌صورت پیش‌فرض خاموش است و کاربر فقط برای تماس آزمایشی هماهنگ‌شده آن را روشن می‌کند.

## تست‌های اجباری پیش از APK
1. تست واحد 8k/16k PCM resampling و chunk boundaries.
2. تست fake WebSocket برای subprotocol، setup confirmation، audio frames، interruption و teardown.
3. Build واقعی APK arm64-v8a با OkHttp و بدون خطای lint/manifest.
4. روی S23 Ultra: تماس ورودی هماهنگ‌شده، پاسخ فارسی Gemini، قطع تماس و اطمینان از قطع WSS؛ سپس تست دوباره شماره‌گیر عادی.
5. هیچ تستی با تماس‌گیرنده ناآگاه انجام نشود. صدای تماس برای پردازش ابری به Gemini فرستاده می‌شود؛ قبل از تست، طرف مقابل مطلع و راضی باشد.

## وضعیت ساخت
فایل‌های این پوشه هنوز در پروژه upstream app یکپارچه نشده‌اند و APK تولید نشده است. این‌ها source additions برای مرحله ادغام‌اند، نه اپ نصب‌شدنی مستقل.
