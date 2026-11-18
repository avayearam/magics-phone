package io.github.sparkcyf.voicesipagent.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.sparkcyf.voicesipagent.privileged.AudioMethod
import io.github.sparkcyf.voicesipagent.privileged.ShizukuConnector

private val purple=Color(0xFF7441D5)
private val ink=Color(0xFF251C2C)
private val gray=Color(0xFF706578)
private val lilac=Color(0xFFEDE5F7)

@Composable
internal fun MagicPhoneTheme(content: @Composable () -> Unit) {
    val family=FontFamily(Font(R.font.vazirmatn_regular, FontWeight.Normal),
        Font(R.font.vazirmatn_bold, FontWeight.Bold))
    val standard=Typography()
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        MaterialTheme(
            colorScheme=lightColorScheme(primary=purple, background=Color(0xFFFCFAFF),
                onBackground=ink, surface=Color.White, onSurface=ink, outline=lilac),
            typography=Typography(
                headlineSmall=standard.headlineSmall.copy(fontFamily=family, fontWeight=FontWeight.Bold),
                titleMedium=standard.titleMedium.copy(fontFamily=family, fontWeight=FontWeight.Bold),
                bodyLarge=standard.bodyLarge.copy(fontFamily=family),
                bodyMedium=standard.bodyMedium.copy(fontFamily=family),
                bodySmall=standard.bodySmall.copy(fontFamily=family),
                labelLarge=standard.labelLarge.copy(fontFamily=family)),
            content=content)
    }
}

@Composable
internal fun MagicPhoneScreen(rt: GatewayRuntime, modifier: Modifier=Modifier,
    setTakeover: (Boolean)->Unit, chooseDialer: ()->Unit, roleMessage: String) {
    val h by rt.health.collectAsState()
    val settings by rt.settingsFlow.collectAsState()
    val live by rt.geminiAgentEnabledFlow.collectAsState()
    val tokenReady by rt.geminiTokenConfiguredFlow.collectAsState()
    val liveStatus by rt.geminiStatusFlow.collectAsState()
    val audio by rt.callAudioStatus.collectAsState()
    val events by AppLog.tail.collectAsState()
    var secret by rememberSaveable { mutableStateOf("") }
    var diagnostic by rememberSaveable { mutableStateOf(false) }
    val idle=h.calls.count==0 && h.session==null
    val canEnable=settings.takeover && h.gatewayRunning && tokenReady && idle && !h.settingsPending
    Column(modifier.fillMaxSize().background(Brush.verticalGradient(
        listOf(Color(0xFFF0E8FF), Color(0xFFFCFAFF), Color.White)))
        .verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement=Arrangement.spacedBy(14.dp)) {
        Column(Modifier.padding(vertical=8.dp)) {
            Text("✦  مجیک فون", style=MaterialTheme.typography.headlineSmall, color=purple)
            Text("دستیار صوتی هوشمند تماس‌های شما", color=gray)
        }
        PhoneCard("آمادگی دستیار") {
            Text(if (h.cellularReady && live) "آماده تست تماس" else "منتظر آماده‌سازی",
                style=MaterialTheme.typography.titleMedium, color=purple)
            Text("اتصال اولیه با مکالمه صوتی دوطرفه یکسان نیست.",
                style=MaterialTheme.typography.bodySmall, color=gray)
            Indicator("شیزوکو", h.shizuku==ShizukuConnector.State.CONNECTED)
            Indicator("دسترسی مدیریت تماس", h.callAccessReady)
            Indicator("دسترسی به صدای تماس", h.audioReady)
            Indicator("رمز اتصال Worker", tokenReady)
            if (h.shizuku==ShizukuConnector.State.PERMISSION_REQUIRED ||
                h.shizuku==ShizukuConnector.State.PERMISSION_DENIED) {
                Button(onClick={rt.shizuku.requestPermission()}) {Text("اجازه شیزوکو")}
            }
            OutlinedButton(onClick={rt.runSetup()}, enabled=settings.takeover && idle) {
                Text("بررسی مجدد دسترسی‌ها")
            }
            if (h.holdsDialer || h.dialerChangeFailed) {
                OutlinedButton(onClick=chooseDialer) {Text("تنظیم برنامه تماس پیش‌فرض")}
            }
            if (roleMessage.isNotBlank()) Text(roleMessage, color=Color(0xFFA72C53))
        }
        PhoneCard("تماس‌های سیم‌کارت") {
            PhoneSwitch("مدیریت تماس", "فقط برای آزمایش‌های هماهنگ‌شده روشن کن.",
                settings.takeover, setTakeover)
            h.sims.sortedBy { it.slot }.forEach { sim ->
                PhoneSwitch("سیم‌کارت " + sim.slot + " — " + sim.carrier,
                    "پاسخ دستیار به تماس‌های این سیم‌کارت",
                    sim.slot !in settings.inboundExcludedSlots,
                    {rt.setSimInbound(sim.slot,it)})
            }
            PhoneSwitch("بی‌صدا کردن بلندگوی گوشی", "برای عیب‌یابی صدای مستقیم خاموش باشد.",
                settings.muteEarpiece, {rt.setMuteEarpiece(it)})
        }
        PhoneCard("مسیر صوتی تماس") {
            Text("در آزمایش قبلی، صدای Gemini با حالت «مستقیم» به مخاطب رسید. انتخاب این حالت، سکوت خروجی گوشی را هم خاموش می‌کند.",
                style=MaterialTheme.typography.bodySmall, color=gray)
            Row(horizontalArrangement=Arrangement.spacedBy(10.dp),
                modifier=Modifier.fillMaxWidth()) {
                OutlinedButton(onClick={rt.applySettings { it.copy(audioMethod=AudioMethod.LEGACY, muteEarpiece=false) }},
                    modifier=Modifier.weight(1f)) {Text(if (settings.audioMethod==AudioMethod.LEGACY) "✓ مستقیم" else "مستقیم")}
                OutlinedButton(onClick={rt.applySettings { it.copy(audioMethod=AudioMethod.AUTO) }},
                    modifier=Modifier.weight(1f)) {Text(if (settings.audioMethod==AudioMethod.AUTO) "✓ خودکار" else "خودکار")}
            }
            if (settings.muteEarpiece && settings.audioMethod==AudioMethod.LEGACY) {
                Text("برای بررسی دریافت صدای مخاطب، بی‌صداکردن بلندگو را خاموش کن.",
                    color=Color(0xFFA72C53), style=MaterialTheme.typography.bodySmall)
            }
            Text("مسیر فعال: " + (audio.backendLabel.ifEmpty { "هنوز فعال نشده" }),
                style=MaterialTheme.typography.bodySmall, color=gray)
        }
        PhoneCard("Gemini Live") {
            Text("کلید Google فقط در Cloudflare نگهداری می‌شود؛ اینجا رمز Worker ذخیره می‌شود.",
                style=MaterialTheme.typography.bodySmall, color=gray)
            OutlinedTextField(value=secret,onValueChange={secret=it},
                label={Text("رمز اتصال امن")},singleLine=true,
                visualTransformation=PasswordVisualTransformation(),
                keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password),
                modifier=Modifier.fillMaxWidth())
            Button(onClick={rt.saveGeminiBridgeToken(secret);secret=""},
                enabled=secret.length>=24 && idle){Text("ذخیره امن رمز")}
            Text(liveStatus,style=MaterialTheme.typography.bodySmall,color=gray)
            PhoneSwitch("پاسخ‌گویی با هوش مصنوعی",
                "تماس‌های سیم‌کارت‌های انتخاب‌شده به‌صورت خودکار پاسخ داده می‌شوند.",
                live,{rt.setGeminiAgentEnabled(it)},enabled=live||canEnable)
            if(live)Text("هشدار: فقط با اطلاع و رضایت تماس‌گیرنده تست کن.",
                color=Color(0xFFA72C53),style=MaterialTheme.typography.bodySmall)
        }
        PhoneCard("تست و عیب‌یابی") {
            PhoneSwitch("بازپخش صدای مخاطب (اکو)",
                "بدون Gemini اجرا می‌شود؛ پس از پایان آزمایش خاموش کن.",
                h.loopbackTest,{rt.setLoopbackTest(it)},
                enabled=settings.takeover && h.gatewayRunning)
            Text("وضعیت: " + (h.session ?: if(h.calls.count>0) "تماس فعال" else "بدون تماس"),
                style=MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick={diagnostic=!diagnostic}) {
                Text(if(diagnostic) "بستن گزارش" else "نمایش گزارش صوتی")
            }
            if(diagnostic){
                Text("گزارش تنها شامل شمارنده‌ها و سطح صداست؛ صدای تماس ضبط نمی‌شود.",
                    style=MaterialTheme.typography.bodySmall,color=gray)
                Text(events.takeLast(35).asReversed().joinToString("\n"),
                    style=MaterialTheme.typography.bodySmall.copy(fontSize=11.sp))
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun PhoneCard(title:String, content:@Composable ()->Unit) {
    Card(modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(22.dp),
        colors=CardDefaults.cardColors(containerColor=Color.White),
        border=BorderStroke(1.dp,lilac),
        elevation=CardDefaults.cardElevation(defaultElevation=2.dp)) {
        Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text(title,style=MaterialTheme.typography.titleMedium,color=ink)
            content()
        }
    }
}

@Composable
private fun Indicator(title:String,ready:Boolean) {
    Row(verticalAlignment=Alignment.CenterVertically) {
        Text(if(ready) "●" else "○",
            color=if(ready) Color(0xFF1B9970) else Color(0xFFA72C53))
        Spacer(Modifier.width(8.dp))
        Text(title,color=gray,modifier=Modifier.weight(1f))
        Text(if(ready) "متصل" else "نیازمند بررسی",
            color=if(ready) Color(0xFF1B9970) else Color(0xFFA72C53),
            style=MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun PhoneSwitch(title:String,detail:String,checked:Boolean,
    onChange:(Boolean)->Unit,enabled:Boolean=true) {
    Row(verticalAlignment=Alignment.CenterVertically,modifier=Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text(title,color=ink)
            Text(detail,style=MaterialTheme.typography.bodySmall,color=gray)
        }
        Spacer(Modifier.width(9.dp))
        Switch(checked=checked,onCheckedChange=onChange,enabled=enabled)
    }
}
