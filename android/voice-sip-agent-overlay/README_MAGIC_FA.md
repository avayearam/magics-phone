# Magic Phone — اتصال تماس سیم‌کارت به Gemini Live

این پروژه از کد [Voice SIP Agent](https://github.com/sparkcyf/android-voice-sip-agent) به‌عنوان موتور تصاحب تماس و صدای سلولی استفاده می‌کند و آن را به **همان Gemini Live فعلی Magic Phone** وصل می‌کند.

## معماری
- سیم‌کارت گوشی → CellularEndpoint و مسیر صوتی Shizuku
- CellularEndpoint → CallBridge → GeminiLiveEndpoint
- GeminiLiveEndpoint → `wss://phone.magics.site/ws?mode=phone`
- Worker فعلی → همان Gemini Live و همان تنظیمات موجود

هیچ ElevenLabs، SIP Trunk، n8n، شماره واسط یا کامپیوتر در زمان اجرای تماس استفاده نمی‌شود. کد SIP در upstream باقی می‌ماند اما در حالت Gemini انتخاب نمی‌شود.

## دریافت و آماده‌سازی سورس

این مخزن، سورس upstream را به‌صورت Submodule در Commit ثابت نگه می‌دارد تا تغییرات upstream بی‌صدا وارد پروژه نشوند:

```bash
git clone --recurse-submodules https://github.com/avayearam/magics-phone.git
cd magics-phone
git submodule update --init --recursive
bash android/voice-sip-agent-overlay/apply.sh
```

در Windows PowerShell:
```powershell
git submodule update --init --recursive
.\android\voice-sip-agent-overlay\apply.ps1
```

پس از اجرای overlay، پروژه Android در `android/voice-sip-agent` قرار دارد.

## راه‌اندازی روی گوشی

1. APK جدید با نام Magic Phone نصب می‌شود. شناسه بسته `site.magics.phone.agent` است و کنار نسخه اصلی Voice SIP Agent نصب می‌شود.
2. Shizuku را اجرا و دسترسی Magic Phone را جداگانه تأیید کن.
3. در برنامه **Take over calls** را روشن کن و تا آماده‌شدن وضعیت صدا صبر کن.
4. همان `BRIDGE_ACCESS_TOKEN` صفحه وب را وارد و ذخیره کن. رمز با Android Keystore و AES-GCM رمزگذاری می‌شود.
5. گزینه **پاسخ‌گویی آزمایشی با Gemini Live** را فقط برای یک تماس هماهنگ‌شده روشن کن.
6. پس از تست، Gemini Live را خاموش کن.

روی گوشی فقط `BRIDGE_ACCESS_TOKEN` لازم است؛ `GEMINI_API_KEY` روی Cloudflare باقی می‌ماند. URL و Origin با Worker فعلی ثابت شده‌اند. رمز در log و فایل تنظیمات export نمی‌شود.

## رفتار نسخه اول
- حالت Gemini پیش‌فرض خاموش است و پس از Restart خودکار روشن نمی‌شود.
- هنگام روشن‌بودن، تماس‌های ورودی SIMهای تحت پوشش به‌صورت خودکار به Gemini سپرده می‌شوند.
- تغییر حالت حین تماس، بعد از پایان تماس اعمال می‌شود.
- انتخاب دستی تک‌تک تماس‌ها و fallback فقط برای تماس‌های بی‌پاسخ در این نسخه نیست.
- تماس‌گیرنده باید از پردازش ابری صدا مطلع باشد و رضایت بدهد.

## Build APK

پیش‌نیاز: JDK 17، Android SDK Platform 37، Android NDK `28.2.13676358`، SWIG و دسترسی دریافت وابستگی‌های Gradle و سورس native.

```bash
cd android/voice-sip-agent/sip-pjsua2/native
./build-pjsua2.sh
cd ../..
./gradlew :app:assembleDebug
```

APK در `app/build/outputs/apk/debug/app-debug.apk` ساخته می‌شود. Build کامل Android و تست واقعی Gemini از تماس سلولی هنوز باید روی Galaxy S23 Ultra تأیید شوند. GitHub Actions در این پروژه اجرا نشده است.
