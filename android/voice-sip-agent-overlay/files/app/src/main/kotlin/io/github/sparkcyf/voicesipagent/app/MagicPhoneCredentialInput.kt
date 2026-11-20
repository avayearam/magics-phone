package io.github.sparkcyf.voicesipagent.app

import java.util.Locale

/** Sanitizes copied credentials without weakening the device authorization contract. */
internal object MagicPhoneCredentialInput {
    private val tokenPattern = Regex("(?i)(?<![A-Za-z0-9_])mpa_[a-f0-9]{48}(?![A-Za-z0-9_])")
    private val keyPattern = Regex("^(?:AQ\\.[A-Za-z0-9_-]{17,253}|[A-Za-z0-9_-]{20,256})$")
    private fun cleaned(raw: String): String =
        raw.filterNot { it in "\u200B\u200C\u200D\u200E\u200F\uFEFF" }.trim()

    fun deviceToken(raw: String): String? =
        tokenPattern.findAll(cleaned(raw)).toList().singleOrNull()?.value?.lowercase(Locale.ROOT)

    fun deviceTokenMessage(raw: String): String {
        if (raw.isBlank()) return "توکن دستگاه را از مدیریت ایجنت‌ها صادر و کپی کن."
        if (deviceToken(raw) != null) return "فرمت توکن صحیح است. برای ذخیره روی دکمه بزن."
        val value = cleaned(raw)
        if (value.startsWith("AQ.", true) || value.startsWith("AIza", true))
            return "اینجا توکن دستگاه لازم است، نه کلید Google. کلید Google را در تب اتصال API وارد کن."
        if (value.startsWith("mpa_", true))
            return "توکن ناقص یا اشتباه است؛ فرمت صحیح mpa_ به‌علاوه ۴۸ رقم هگز است."
        return "توکن قدیمی Worker پذیرفته نمی‌شود؛ از پنل Magic Phone یک توکن دستگاه جدید بگیر."
    }

    fun googleApiKey(raw: String): String? {
        val value = cleaned(raw)
        // Device identity tokens also satisfy the broad legacy Google-key charset.
        // Never let users register a device token as their Google credential.
        if (deviceToken(value) != null) return null
        return value.takeIf { keyPattern.matches(it) }
    }
    fun googleApiKeyMessage(raw: String): String = when {
        raw.isBlank() -> "کلید Google Gemini مخصوص این گوشی را وارد کن."
        googleApiKey(raw) != null -> "فرمت کلید قابل بررسی است؛ اعتبار واقعی از سرور بررسی می‌شود."
        deviceToken(raw) != null -> "این توکن دستگاه است؛ کلید Google را در این کادر وارد کن."
        else -> "فرمت کلید Google صحیح نیست. آن را کامل و بدون فاصله اضافه وارد کن."
    }
}
