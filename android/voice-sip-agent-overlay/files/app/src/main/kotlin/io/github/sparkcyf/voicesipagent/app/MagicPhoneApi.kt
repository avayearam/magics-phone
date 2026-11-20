package io.github.sparkcyf.voicesipagent.app

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

internal data class MagicPhoneRemoteSettings(
    val deviceLabel: String, val agentId: String, val agentName: String,
    val voice: String, val language: String, val prompt: String,
    val apiKeyConfigured: Boolean, val apiKeyHint: String,
    val voices: List<String>, val languages: Map<String, String>,
)
internal data class MagicPhoneCaller(
    val callerNumber: String, val callCount: Int, val lastCall: String,
    val latestSessionKey: String, val latestSummary: String,
)
internal data class MagicPhoneCallMessage(val role: String, val text: String, val createdAt: String)
internal data class MagicPhoneCall(
    val sessionKey: String, val callerNumber: String, val agentName: String,
    val startedAt: String, val endedAt: String, val durationSeconds: Int,
    val summary: String, val messages: List<MagicPhoneCallMessage>,
)

/** Device-token authenticated API. The Google API key is sent once over TLS and never saved in the APK. */
internal class MagicPhoneApi {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS).callTimeout(25, TimeUnit.SECONDS).build()

    fun loadSettings(token: String): MagicPhoneRemoteSettings {
        val root=request(token)
        val device=root.optJSONObject("device") ?: JSONObject()
        val agent=root.optJSONObject("agent") ?: JSONObject()
        val key=root.optJSONObject("api_key") ?: JSONObject()
        val voices=root.optJSONArray("voices").asStrings()
        val languageJson=root.optJSONObject("languages") ?: JSONObject()
        val languages=linkedMapOf<String,String>()
        languageJson.keys().forEach { languages[it]=languageJson.optString(it) }
        return MagicPhoneRemoteSettings(
            device.optString("label"),agent.optString("id"),agent.optString("name"),
            agent.optString("voice","Kore"),agent.optString("language","fa-IR"),agent.optString("prompt"),
            key.optBoolean("configured"),key.optString("hint"),voices,languages)
    }

    fun testApiKey(token:String,key:String) { request(token,JSONObject().put("action","test_api_key").put("api_key",key)) }
    fun saveApiKey(token:String,key:String):Pair<Boolean,String> {
        val result=request(token,JSONObject().put("action","save_api_key").put("api_key",key))
        val info=result.optJSONObject("api_key") ?: JSONObject()
        return info.optBoolean("configured") to info.optString("hint")
    }
    fun clearApiKey(token:String) { request(token,JSONObject().put("action","clear_api_key")) }
    fun saveAgent(token:String,voice:String,language:String,prompt:String) {
        request(token,JSONObject().put("action","update_agent").put("voice",voice).put("language",language).put("prompt",prompt))
    }
    fun listCallers(token:String):List<MagicPhoneCaller> {
        val rows=request(token,JSONObject().put("action","list_callers")).optJSONArray("callers") ?: JSONArray()
        return buildList {
            for(i in 0 until rows.length()) {
                val item=rows.optJSONObject(i) ?: continue
                add(MagicPhoneCaller(item.optString("caller_number").takeUnless{it=="null"}.orEmpty(),
                    item.optInt("call_count"),item.optString("last_call"),
                    item.optString("latest_session_key"),item.optString("latest_summary")))
            }
        }
    }
    fun getCall(token:String,sessionKey:String):MagicPhoneCall {
        val item=request(token,JSONObject().put("action","get_call").put("session_key",sessionKey))
            .optJSONObject("call") ?: throw IllegalStateException("اطلاعات مکالمه دریافت نشد.")
        val rows=item.optJSONArray("messages") ?: JSONArray()
        val messages=buildList {
            for(i in 0 until rows.length()) {
                val row=rows.optJSONObject(i) ?: continue
                add(MagicPhoneCallMessage(row.optString("role"),row.optString("text"),row.optString("created_at")))
            }
        }
        return MagicPhoneCall(item.optString("session_key"),item.optString("caller_number").takeUnless{it=="null"}.orEmpty(),
            item.optString("agent_name"),item.optString("started_at"),item.optString("ended_at"),
            item.optInt("duration_seconds"),item.optString("summary"),messages)
    }
    fun deleteCall(token:String,key:String) { request(token,JSONObject().put("action","delete_call").put("session_key",key)) }
    fun deleteCaller(token:String,number:String):Int =
        request(token,JSONObject().put("action","delete_caller").put("caller_number",number)).optInt("deleted_count")

    private fun request(token:String,body:JSONObject?=null):JSONObject {
        val normalized = MagicPhoneCredentialInput.deviceToken(token)
            ?: throw IllegalArgumentException("توکن دستگاه معتبر نیست.")
        val builder=Request.Builder().url(DEVICE_API_URL)
            .header("Accept","application/json").header("X-Magic-Phone-Agent-Token",normalized)
        val req=if(body==null) builder.get().build()
            else builder.post(body.toString().toRequestBody(JSON)).build()
        client.newCall(req).execute().use { response ->
            val raw=response.body?.string().orEmpty()
            val json=runCatching{JSONObject(raw)}.getOrNull()
                ?: throw IllegalStateException("پاسخ سرور قابل‌خواندن نیست.")
            if(!response.isSuccessful || !json.optBoolean("ok")) {
                throw IllegalStateException(errorMessage(json.optString("error")))
            }
            return json
        }
    }
    private fun errorMessage(code:String)=when(code) {
        "unauthorized","device_identity_invalid" -> "توکن دستگاه معتبر نیست یا لغو شده است."
        "google_api_key_invalid_or_unavailable" -> "کلید Google معتبر نیست یا دسترسی Generative Language API فعال نیست."
        "device_api_key_required" -> "ابتدا کلید API گوگل را برای این دستگاه ثبت کن."
        "agent_settings_invalid" -> "تنظیمات ایجنت معتبر نیست."
        "call_not_found" -> "مکالمه پیدا نشد یا متعلق به این دستگاه نیست."
        else -> "ارتباط با سرور انجام نشد: $code"
    }
    private fun JSONArray?.asStrings():List<String> {
        if(this==null)return emptyList()
        return buildList { for(i in 0 until length()) add(optString(i)) }
    }
    private companion object {
        const val DEVICE_API_URL="https://ai.magics.site/call/device-api.php"
        val JSON="application/json; charset=utf-8".toMediaType()
    }
}
