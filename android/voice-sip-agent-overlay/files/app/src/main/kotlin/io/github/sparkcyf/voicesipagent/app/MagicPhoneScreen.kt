package io.github.sparkcyf.voicesipagent.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.sparkcyf.voicesipagent.privileged.AudioMethod
import io.github.sparkcyf.voicesipagent.privileged.CallAudioStatus
import io.github.sparkcyf.voicesipagent.privileged.ShizukuConnector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val purple=Color(0xFF7441D5)
private val ink=Color(0xFF251C2C)
private val gray=Color(0xFF706578)
private val lilac=Color(0xFFEDE5F7)

@Composable
internal fun MagicPhoneTheme(content:@Composable ()->Unit) {
    val family=FontFamily(Font(R.font.vazirmatn_regular,FontWeight.Normal),Font(R.font.vazirmatn_bold,FontWeight.Bold))
    val standard=Typography()
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        MaterialTheme(colorScheme=lightColorScheme(primary=purple,background=Color(0xFFFCFAFF),
            onBackground=ink,surface=Color.White,onSurface=ink,outline=lilac),
            typography=Typography(
                headlineSmall=standard.headlineSmall.copy(fontFamily=family,fontWeight=FontWeight.Bold),
                titleMedium=standard.titleMedium.copy(fontFamily=family,fontWeight=FontWeight.Bold),
                bodyLarge=standard.bodyLarge.copy(fontFamily=family),bodyMedium=standard.bodyMedium.copy(fontFamily=family),
                bodySmall=standard.bodySmall.copy(fontFamily=family),labelLarge=standard.labelLarge.copy(fontFamily=family)),
            content=content)
    }
}

@Composable
internal fun MagicPhoneScreen(rt:GatewayRuntime,modifier:Modifier=Modifier,
    setTakeover:(Boolean)->Unit,chooseDialer:()->Unit,roleMessage:String) {
    val context=LocalContext.current
    val tokenStore=remember(context){GeminiTokenStore(context)}
    val api=remember{MagicPhoneApi()}
    val scope=rememberCoroutineScope()
    val h by rt.health.collectAsState()
    val settings by rt.settingsFlow.collectAsState()
    val live by rt.geminiAgentEnabledFlow.collectAsState()
    val tokenReady by rt.geminiTokenConfiguredFlow.collectAsState()
    val liveStatus by rt.geminiStatusFlow.collectAsState()
    val audio by rt.callAudioStatus.collectAsState()
    val events by AppLog.tail.collectAsState()
    var remote by remember{mutableStateOf<MagicPhoneRemoteSettings?>(null)}
    val idle=h.calls.count==0 && h.session==null
    val canEnable=settings.takeover && h.gatewayRunning && tokenReady && remote?.apiKeyConfigured==true && idle && !h.settingsPending

    var tab by rememberSaveable{mutableIntStateOf(0)}
    var tokenDraft by remember{mutableStateOf("")}
    var apiKeyDraft by remember{mutableStateOf("")}
    var apiKeyTested by remember{mutableStateOf(false)}
    var promptDraft by remember{mutableStateOf("")}
    var voiceDraft by remember{mutableStateOf("Kore")}
    var languageDraft by remember{mutableStateOf("fa-IR")}
    var voiceMenu by remember{mutableStateOf(false)}
    var languageMenu by remember{mutableStateOf(false)}
    var diagnostic by rememberSaveable{mutableStateOf(false)}
    var callers by remember{mutableStateOf<List<MagicPhoneCaller>>(emptyList())}
    var selectedCall by remember{mutableStateOf<MagicPhoneCall?>(null)}
    var busy by remember{mutableStateOf(false)}
    var feedback by remember{mutableStateOf("")}
    var confirmClear by remember{mutableStateOf(false)}
    var confirmDeleteCall by remember{mutableStateOf(false)}
    var confirmDeleteCaller by remember{mutableStateOf(false)}

    suspend fun loadRemote(token:String) {
        val value=withContext(Dispatchers.IO){api.loadSettings(token)}
        remote=value;promptDraft=value.prompt;voiceDraft=value.voice;languageDraft=value.language
    }

    LaunchedEffect(tokenReady,tab) {
        if(tokenReady) {
            val token=tokenStore.load()
            if(token!=null) {
                busy=true
                try {
                    loadRemote(token)
                    if(tab==2)callers=withContext(Dispatchers.IO){api.listCallers(token)}
                } catch(e:Exception){feedback=e.message ?: "ارتباط با سرور برقرار نشد."}
                finally{busy=false}
            }
        }
    }

    if(confirmClear) AlertDialog(onDismissRequest={confirmClear=false},
        title={Text("حذف کلید API")},
        text={Text("کلید این دستگاه حذف می‌شود و تماس‌های جدید تا ثبت کلید تازه به Gemini متصل نمی‌شوند.")},
        confirmButton={TextButton(onClick={
            confirmClear=false
            tokenStore.load()?.let{token->scope.launch{
                busy=true
                try{withContext(Dispatchers.IO){api.clearApiKey(token)};remote=remote?.copy(apiKeyConfigured=false,apiKeyHint="");if(live)rt.setGeminiAgentEnabled(false);feedback="کلید این دستگاه حذف شد."}
                catch(e:Exception){feedback=e.message ?: "حذف کلید ناموفق بود."}
                finally{busy=false}
            }}
        }){Text("حذف کلید")}},
        dismissButton={TextButton(onClick={confirmClear=false}){Text("انصراف")}})
    if(confirmDeleteCall) AlertDialog(onDismissRequest={confirmDeleteCall=false},
        title={Text("حذف مکالمه")},text={Text("متن و خلاصه این مکالمه برای همیشه حذف شود؟")},
        confirmButton={TextButton(onClick={
            confirmDeleteCall=false
            val key=selectedCall?.sessionKey
            val token=tokenStore.load()
            if(key!=null && token!=null)scope.launch{
                busy=true
                try{withContext(Dispatchers.IO){api.deleteCall(token,key)};selectedCall=null;callers=withContext(Dispatchers.IO){api.listCallers(token)};feedback="مکالمه حذف شد."}
                catch(e:Exception){feedback=e.message ?: "حذف مکالمه ناموفق بود."}
                finally{busy=false}
            }
        }){Text("حذف")}},
        dismissButton={TextButton(onClick={confirmDeleteCall=false}){Text("انصراف")}})
    if(confirmDeleteCaller) AlertDialog(onDismissRequest={confirmDeleteCaller=false},
        title={Text("حذف تاریخچه شماره")},text={Text("تمام مکالمات این شماره که با توکن همین دستگاه ثبت شده‌اند حذف شوند؟ این کار برگشت‌پذیر نیست.")},
        confirmButton={TextButton(onClick={
            confirmDeleteCaller=false
            val number=selectedCall?.callerNumber.orEmpty()
            val token=tokenStore.load()
            if(number.isNotBlank() && token!=null)scope.launch{
                busy=true
                try{val count=withContext(Dispatchers.IO){api.deleteCaller(token,number)};selectedCall=null;callers=withContext(Dispatchers.IO){api.listCallers(token)};feedback="$count مکالمه حذف شد."}
                catch(e:Exception){feedback=e.message ?: "حذف تاریخچه ناموفق بود."}
                finally{busy=false}
            }
        }){Text("حذف همه")}},
        dismissButton={TextButton(onClick={confirmDeleteCaller=false}){Text("انصراف")}})

    Column(modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFFF0E8FF),Color(0xFFFCFAFF),Color.White)))) {
        Column(Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=12.dp)) {
            Text("✦  مجیک فون",style=MaterialTheme.typography.headlineSmall,color=purple)
            Text("مدیریت دستیارهای صوتی و تماس‌ها",style=MaterialTheme.typography.bodySmall,color=gray)
        }
        ScrollableTabRow(selectedTabIndex=tab,edgePadding=12.dp,containerColor=Color.Transparent) {
            listOf("تماس","تنظیمات ایجنت","تماس‌گیرندگان","اتصال API").forEachIndexed{index,label->
                Tab(selected=tab==index,onClick={tab=index;selectedCall=null},text={Text(label)})
            }
        }
        when(tab) {
            0 -> CallTab(rt,h,settings,live,tokenReady,remote?.apiKeyConfigured==true,liveStatus,audio,events,idle,canEnable,tokenDraft,{tokenDraft=it},
                diagnostic,{diagnostic=it},setTakeover,chooseDialer,roleMessage)
            1 -> AgentTab(remote,tokenReady,busy,feedback,promptDraft,{promptDraft=it},voiceDraft,{voiceDraft=it},
                languageDraft,{languageDraft=it},voiceMenu,{voiceMenu=it},languageMenu,{languageMenu=it},
                api,tokenStore,scope,{busy=it},{feedback=it},{loadRemote(it)})
            2 -> CallersTab(tokenReady,busy,feedback,callers,selectedCall,{selectedCall=it},api,tokenStore,scope,
                {busy=it},{feedback=it},{callers=it},{confirmDeleteCall=true},{confirmDeleteCaller=true})
            3 -> ApiKeyTab(tokenReady,busy,feedback,remote,apiKeyDraft,{apiKeyDraft=it;apiKeyTested=false},
                apiKeyTested,{apiKeyTested=it},api,tokenStore,scope,{busy=it},{feedback=it},
                {remote=it},{confirmClear=true})
        }
    }
}

@Composable
private fun CallTab(rt:GatewayRuntime,h:HealthState,settings:GatewaySettings,live:Boolean,tokenReady:Boolean,
    apiKeyConfigured:Boolean,liveStatus:String,audio:CallAudioStatus,events:List<String>,idle:Boolean,canEnable:Boolean,
    tokenDraft:String,onToken:(String)->Unit,diagnostic:Boolean,onDiagnostic:(Boolean)->Unit,
    setTakeover:(Boolean)->Unit,chooseDialer:()->Unit,roleMessage:String) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
        PhoneCard("آمادگی دستیار") {
            Text(if(h.cellularReady && live)"آماده تست تماس" else "منتظر آماده‌سازی",style=MaterialTheme.typography.titleMedium,color=purple)
            Text("اتصال اولیه با مکالمه صوتی دوطرفه یکسان نیست.",style=MaterialTheme.typography.bodySmall,color=gray)
            Indicator("شیزوکو",h.shizuku==ShizukuConnector.State.CONNECTED)
            Indicator("دسترسی مدیریت تماس",h.callAccessReady)
            Indicator("دسترسی به صدای تماس",h.audioReady)
            Indicator("توکن اختصاصی دستگاه",tokenReady)
            Indicator("کلید API گوگل",apiKeyConfigured)
            if(tokenReady && !apiKeyConfigured) Text("برای فعال‌کردن پاسخ‌گویی، ابتدا کلید API را در تب «اتصال API» ثبت کن.",color=Color(0xFFA72C53),style=MaterialTheme.typography.bodySmall)
            if(h.shizuku==ShizukuConnector.State.PERMISSION_REQUIRED || h.shizuku==ShizukuConnector.State.PERMISSION_DENIED)
                Button(onClick={rt.shizuku.requestPermission()}){Text("اجازه شیزوکو")}
            OutlinedButton(onClick={rt.runSetup()},enabled=settings.takeover && idle){Text("بررسی مجدد دسترسی‌ها")}
            if(h.holdsDialer || h.dialerChangeFailed)OutlinedButton(onClick=chooseDialer){Text("تنظیم برنامه تماس پیش‌فرض")}
            if(roleMessage.isNotBlank())Text(roleMessage,color=Color(0xFFA72C53))
        }
        PhoneCard("تماس‌های سیم‌کارت") {
            PhoneSwitch("مدیریت تماس","فقط برای آزمایش‌های هماهنگ‌شده روشن کن.",settings.takeover,setTakeover)
            h.sims.sortedBy{it.slot}.forEach{sim->PhoneSwitch("سیم‌کارت "+sim.slot+" — "+sim.carrier,
                "پاسخ دستیار به تماس‌های این سیم‌کارت",sim.slot !in settings.inboundExcludedSlots,{rt.setSimInbound(sim.slot,it)})}
            PhoneSwitch("بی‌صدا کردن بلندگوی گوشی","برای عیب‌یابی صدای مستقیم خاموش باشد.",settings.muteEarpiece,{rt.setMuteEarpiece(it)})
        }
        PhoneCard("مسیر صوتی تماس") {
            Text("در آزمایش قبلی، صدای Gemini با حالت مستقیم به مخاطب رسید. انتخاب این حالت، سکوت خروجی گوشی را هم خاموش می‌کند.",style=MaterialTheme.typography.bodySmall,color=gray)
            Row(horizontalArrangement=Arrangement.spacedBy(10.dp),modifier=Modifier.fillMaxWidth()) {
                OutlinedButton(onClick={rt.applySettings{it.copy(audioMethod=AudioMethod.LEGACY,muteEarpiece=false)}},modifier=Modifier.weight(1f)){Text(if(settings.audioMethod==AudioMethod.LEGACY)"✓ مستقیم" else "مستقیم")}
                OutlinedButton(onClick={rt.applySettings{it.copy(audioMethod=AudioMethod.AUTO)}},modifier=Modifier.weight(1f)){Text(if(settings.audioMethod==AudioMethod.AUTO)"✓ خودکار" else "خودکار")}
            }
            if(settings.muteEarpiece && settings.audioMethod==AudioMethod.LEGACY)Text("برای بررسی دریافت صدای مخاطب، بی‌صداکردن بلندگو را خاموش کن.",color=Color(0xFFA72C53),style=MaterialTheme.typography.bodySmall)
            Text("مسیر فعال: "+audio.backendLabel.ifEmpty{"هنوز فعال نشده"},style=MaterialTheme.typography.bodySmall,color=gray)
        }
        PhoneCard("اتصال ایجنت") {
            Text("توکن اختصاصی دستگاه را از مدیریت ایجنت‌های Magic Phone دریافت کن. توکن قدیمی Worker قابل استفاده نیست.",style=MaterialTheme.typography.bodySmall,color=gray)
            OutlinedTextField(value=tokenDraft,onValueChange=onToken,label={Text("توکن اختصاصی دستگاه")},singleLine=true,
                visualTransformation=PasswordVisualTransformation(),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password),modifier=Modifier.fillMaxWidth())
            Button(onClick={rt.saveGeminiBridgeToken(tokenDraft.trim());onToken("")},
                enabled=Regex("^mpa_[a-f0-9]{48}$").matches(tokenDraft.trim()) && idle){Text("ذخیره امن توکن")}
            Text(liveStatus,style=MaterialTheme.typography.bodySmall,color=gray)
            PhoneSwitch("پاسخ‌گویی با هوش مصنوعی","تماس‌های سیم‌کارت‌های انتخاب‌شده به‌صورت خودکار پاسخ داده می‌شوند.",live,
                {rt.setGeminiAgentEnabled(it)},enabled=live||canEnable)
            if(live)Text("هشدار: فقط با اطلاع و رضایت تماس‌گیرنده تست کن.",color=Color(0xFFA72C53),style=MaterialTheme.typography.bodySmall)
        }
        PhoneCard("تست و عیب‌یابی") {
            PhoneSwitch("بازپخش صدای مخاطب (اکو)","بدون Gemini اجرا می‌شود؛ پس از پایان آزمایش خاموش کن.",h.loopbackTest,
                {rt.setLoopbackTest(it)},enabled=settings.takeover && h.gatewayRunning)
            Text("وضعیت: "+(h.session ?: if(h.calls.count>0)"تماس فعال" else "بدون تماس"),style=MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick={onDiagnostic(!diagnostic)}){Text(if(diagnostic)"بستن گزارش" else "نمایش گزارش صوتی")}
            if(diagnostic){
                Text("گزارش تنها شامل شمارنده‌ها و سطح صداست؛ صدای تماس ضبط نمی‌شود.",style=MaterialTheme.typography.bodySmall,color=gray)
                Text(events.takeLast(35).asReversed().joinToString("\n"),style=MaterialTheme.typography.bodySmall.copy(fontSize=11.sp))
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun AgentTab(remote:MagicPhoneRemoteSettings?,tokenReady:Boolean,busy:Boolean,feedback:String,
    prompt:String,onPrompt:(String)->Unit,voice:String,onVoice:(String)->Unit,language:String,onLanguage:(String)->Unit,
    voiceMenu:Boolean,onVoiceMenu:(Boolean)->Unit,languageMenu:Boolean,onLanguageMenu:(Boolean)->Unit,
    api:MagicPhoneApi,tokenStore:GeminiTokenStore,scope:CoroutineScope,onBusy:(Boolean)->Unit,
    onFeedback:(String)->Unit,loadRemote:suspend(String)->Unit) {
    val uri=LocalUriHandler.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
        if(!tokenReady) PhoneCard("اتصال ایجنت"){Text("ابتدا در تب تماس توکن اختصاصی دستگاه را ثبت کن.",color=gray)}
        else if(remote==null) PhoneCard("تنظیمات ایجنت"){Text(if(busy)"در حال دریافت تنظیمات..." else "تنظیمات هنوز دریافت نشده است.",color=gray)}
        else {
            PhoneCard("ایجنت متصل") {
                Text(remote.agentName,style=MaterialTheme.typography.titleMedium,color=purple)
                Text("شناسه: "+remote.agentId+" · دستگاه: "+remote.deviceLabel,style=MaterialTheme.typography.bodySmall,color=gray)
                Text("پرامپت، صدا و زبان بین تمام دستگاه‌های همین ایجنت مشترک است.",style=MaterialTheme.typography.bodySmall,color=gray)
            }
            PhoneCard("زبان و صدای دستیار") {
                Box {
                    OutlinedButton(onClick={onVoiceMenu(true)},modifier=Modifier.fillMaxWidth()){Text("صدای دستیار: "+voice)}
                    DropdownMenu(expanded=voiceMenu,onDismissRequest={onVoiceMenu(false)}) {
                        remote.voices.forEach{item->DropdownMenuItem(text={Text(item)},onClick={onVoice(item);onVoiceMenu(false)})}
                    }
                }
                Box {
                    OutlinedButton(onClick={onLanguageMenu(true)},modifier=Modifier.fillMaxWidth()){
                        Text("زبان پاسخ‌گویی: "+(remote.languages[language] ?: language))
                    }
                    DropdownMenu(expanded=languageMenu,onDismissRequest={onLanguageMenu(false)}) {
                        remote.languages.forEach{(code,name)->DropdownMenuItem(text={Text(name)},onClick={onLanguage(code);onLanguageMenu(false)})}
                    }
                }
                Text("فارسی برای زبان پیش‌فرض پیشنهاد می‌شود. تغییرات از تماس بعدی اعمال می‌شوند.",style=MaterialTheme.typography.bodySmall,color=gray)
            }
            PhoneCard("پرامپت اختصاصی") {
                OutlinedTextField(value=prompt,onValueChange=onPrompt,label={Text("دستورالعمل و شخصیت ایجنت")},
                    minLines=5,maxLines=10,modifier=Modifier.fillMaxWidth())
                Text(prompt.length.toString()+"/4000",style=MaterialTheme.typography.bodySmall,color=gray)
                Button(onClick={
                    val token=tokenStore.load()
                    if(token!=null)scope.launch{
                        onBusy(true)
                        try{withContext(Dispatchers.IO){api.saveAgent(token,voice,language,prompt)};loadRemote(token);onFeedback("تنظیمات ایجنت ذخیره شد و بین دستگاه‌های همین ایجنت مشترک است.")}
                        catch(e:Exception){onFeedback(e.message ?: "ذخیره تنظیمات ناموفق بود.")}
                        finally{onBusy(false)}
                    }
                },enabled=!busy && prompt.length<=4000 && tokenReady,modifier=Modifier.fillMaxWidth()){
                    Text(if(busy)"در حال ذخیره..." else "ذخیره تنظیمات ایجنت")
                }
            }
            PhoneCard("تست صدا") {
                Text("برای شنیدن صدای انتخاب‌شده، صفحه تست زنده را باز کن. نشست جدید از تنظیمات ذخیره‌شده استفاده می‌کند.",style=MaterialTheme.typography.bodySmall,color=gray)
                OutlinedButton(onClick={runCatching{uri.openUri("https://ai.magics.site/call/")}.onFailure{onFeedback("بازکردن صفحه تست ناموفق بود.")}},
                    modifier=Modifier.fillMaxWidth()){Text("بازکردن تست مکالمه")}
            }
        }
        if(feedback.isNotBlank())Text(feedback,color=gray,style=MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ApiKeyTab(tokenReady:Boolean,busy:Boolean,feedback:String,remote:MagicPhoneRemoteSettings?,
    apiKey:String,onApiKey:(String)->Unit,tested:Boolean,onTested:(Boolean)->Unit,api:MagicPhoneApi,
    tokenStore:GeminiTokenStore,scope:CoroutineScope,onBusy:(Boolean)->Unit,onFeedback:(String)->Unit,
    onRemote:(MagicPhoneRemoteSettings)->Unit,onClear:()->Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
        PhoneCard("اتصال اختصاصی Google Gemini") {
            Text("هر توکن دستگاه کلید API مستقل دارد. کلید داخل گوشی یا APK ذخیره نمی‌شود؛ فقط به‌صورت رمزنگاری‌شده روی سرور نگهداری می‌شود.",style=MaterialTheme.typography.bodySmall,color=gray)
            if(remote?.apiKeyConfigured==true){
                Indicator("کلید API این دستگاه",true)
                Text("کلید ثبت‌شده: •••• "+remote.apiKeyHint,style=MaterialTheme.typography.bodySmall,color=gray)
            }else Indicator("کلید API این دستگاه",false)
            OutlinedTextField(value=apiKey,onValueChange=onApiKey,label={Text("Google Gemini API Key")},singleLine=true,
                visualTransformation=PasswordVisualTransformation(),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password),modifier=Modifier.fillMaxWidth())
            OutlinedButton(onClick={
                val token=tokenStore.load()
                if(token!=null)scope.launch{
                    onBusy(true)
                    try{withContext(Dispatchers.IO){api.testApiKey(token,apiKey.trim())};onTested(true);onFeedback("کلید معتبر است و Google Generative Language API پاسخ داده است.")}
                    catch(e:Exception){onTested(false);onFeedback(e.message ?: "اعتبارسنجی کلید ناموفق بود.")}
                    finally{onBusy(false)}
                }
            },enabled=tokenReady && !busy && apiKey.trim().length>=20,modifier=Modifier.fillMaxWidth()){
                Text(if(busy)"در حال بررسی..." else "بررسی اعتبار کلید")
            }
            Button(onClick={
                val token=tokenStore.load()
                if(token!=null)scope.launch{
                    onBusy(true)
                    try{
                        val saved=withContext(Dispatchers.IO){api.saveApiKey(token,apiKey.trim())}
                        val latest=withContext(Dispatchers.IO){api.loadSettings(token)}
                        onRemote(latest);onApiKey("");onTested(false)
                        onFeedback("کلید API برای این دستگاه ذخیره شد؛ چهار رقم پایانی: "+saved.second)
                    }catch(e:Exception){onFeedback(e.message ?: "ذخیره کلید ناموفق بود.")}
                    finally{onBusy(false)}
                }
            },enabled=tokenReady && !busy && tested && apiKey.trim().length>=20,modifier=Modifier.fillMaxWidth()){Text("ذخیره امن کلید")}
            if(remote?.apiKeyConfigured==true)OutlinedButton(onClick=onClear,enabled=!busy,modifier=Modifier.fillMaxWidth()){Text("حذف کلید ذخیره‌شده")}
        }
        PhoneCard("نحوه مصرف") {
            Text("مصرف Gemini Live و خلاصه‌سازی مکالمات از اعتبار همین کلید کم می‌شود. هر گوشی می‌تواند کلید مستقل داشته باشد؛ تغییر کلید روی دستگاه‌های دیگر اثر ندارد.",style=MaterialTheme.typography.bodySmall,color=gray)
            Text("کلید فقط برای نشست‌های جدید استفاده می‌شود. دسترسی مدل Live هنگام برقراری تماس نیز بررسی خواهد شد.",style=MaterialTheme.typography.bodySmall,color=gray)
        }
        if(feedback.isNotBlank())Text(feedback,color=gray,style=MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun CallersTab(tokenReady:Boolean,busy:Boolean,feedback:String,callers:List<MagicPhoneCaller>,
    selected:MagicPhoneCall?,onSelected:(MagicPhoneCall?)->Unit,api:MagicPhoneApi,tokenStore:GeminiTokenStore,
    scope:CoroutineScope,onBusy:(Boolean)->Unit,onFeedback:(String)->Unit,onCallers:(List<MagicPhoneCaller>)->Unit,
    onDeleteCall:()->Unit,onDeleteCaller:()->Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
        if(!tokenReady)PhoneCard("تماس‌گیرندگان"){Text("ابتدا توکن اختصاصی دستگاه را در تب تماس ثبت کن.",color=gray)}
        else if(selected!=null){
            PhoneCard("جزئیات مکالمه") {
                OutlinedButton(onClick={onSelected(null)}){Text("بازگشت به فهرست")}
                Text(selected.callerNumber.ifBlank{"شماره نامشخص"},style=MaterialTheme.typography.titleMedium,color=purple)
                Text(selected.agentName+" · "+selected.startedAt,style=MaterialTheme.typography.bodySmall,color=gray)
                Text("مدت مکالمه: "+selected.durationSeconds+" ثانیه",style=MaterialTheme.typography.bodySmall,color=gray)
                if(selected.summary.isNotBlank()){Text("خلاصه مکالمه",style=MaterialTheme.typography.titleSmall);Text(selected.summary)}
                else Text("خلاصه هنوز آماده نشده است؛ متن کامل مکالمه همچنان در دسترس است.",style=MaterialTheme.typography.bodySmall,color=gray)
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp),modifier=Modifier.fillMaxWidth()){
                    OutlinedButton(onClick=onDeleteCall,modifier=Modifier.weight(1f)){Text("حذف مکالمه")}
                    OutlinedButton(onClick=onDeleteCaller,enabled=selected.callerNumber.isNotBlank(),modifier=Modifier.weight(1f)){Text("حذف تاریخچه شماره")}
                }
            }
            PhoneCard("متن کامل گفتگو") {
                if(selected.messages.isEmpty())Text("متنی برای این تماس ثبت نشده است.",color=gray)
                selected.messages.forEach{item->
                    Surface(color=if(item.role=="assistant")Color(0xFFF1E9FF) else Color(0xFFEAF7F1),
                        shape=RoundedCornerShape(14.dp),modifier=Modifier.fillMaxWidth()){
                        Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(5.dp)){
                            Text(if(item.role=="assistant")"دستیار" else "تماس‌گیرنده",style=MaterialTheme.typography.labelMedium,color=purple)
                            Text(item.text)
                        }
                    }
                }
            }
        }else{
            PhoneCard("تماس‌گیرندگان این دستگاه") {
                Text("این فهرست فقط تماس‌هایی را نشان می‌دهد که با همین توکن دستگاه ثبت شده‌اند.",style=MaterialTheme.typography.bodySmall,color=gray)
                if(busy)Text("در حال دریافت فهرست...")
                if(callers.isEmpty() && !busy)Text("هنوز شماره‌ای در آرشیو این دستگاه ثبت نشده است.",color=gray)
                callers.forEach{caller->
                    Surface(color=Color(0xFFFBF9FF),shape=RoundedCornerShape(16.dp),border=BorderStroke(1.dp,lilac),modifier=Modifier.fillMaxWidth()){
                        Column(Modifier.padding(13.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){
                            Text(caller.callerNumber.ifBlank{"شماره نامشخص"},style=MaterialTheme.typography.titleMedium,color=purple)
                            Text(caller.callCount.toString()+" تماس · "+caller.lastCall,style=MaterialTheme.typography.bodySmall,color=gray)
                            if(caller.latestSummary.isNotBlank())Text(caller.latestSummary,style=MaterialTheme.typography.bodySmall)
                            Button(onClick={
                                val token=tokenStore.load()
                                if(token!=null)scope.launch{
                                    onBusy(true)
                                    try{onSelected(withContext(Dispatchers.IO){api.getCall(token,caller.latestSessionKey)})}
                                    catch(e:Exception){onFeedback(e.message ?: "دریافت مکالمه ناموفق بود.")}
                                    finally{onBusy(false)}
                                }
                            },enabled=!busy,modifier=Modifier.fillMaxWidth()){Text("مشاهده تاریخچه")}
                        }
                    }
                }
                OutlinedButton(onClick={
                    val token=tokenStore.load()
                    if(token!=null)scope.launch{
                        onBusy(true)
                        try{onCallers(withContext(Dispatchers.IO){api.listCallers(token)})}
                        catch(e:Exception){onFeedback(e.message ?: "بروزرسانی فهرست ناموفق بود.")}
                        finally{onBusy(false)}
                    }
                },enabled=!busy,modifier=Modifier.fillMaxWidth()){Text("بروزرسانی فهرست")}
            }
        }
        if(feedback.isNotBlank())Text(feedback,color=gray,style=MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun PhoneCard(title:String,content:@Composable ()->Unit){
    Card(modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(22.dp),
        colors=CardDefaults.cardColors(containerColor=Color.White),border=BorderStroke(1.dp,lilac),
        elevation=CardDefaults.cardElevation(defaultElevation=2.dp)){
        Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
            Text(title,style=MaterialTheme.typography.titleMedium,color=ink);content()
        }
    }
}
@Composable
private fun Indicator(title:String,ready:Boolean){
    Row(verticalAlignment=Alignment.CenterVertically,modifier=Modifier.fillMaxWidth()){
        Text(if(ready)"●" else "○",color=if(ready)Color(0xFF1B9970) else Color(0xFFA72C53))
        Spacer(Modifier.width(8.dp));Text(title,color=gray,modifier=Modifier.weight(1f))
        Text(if(ready)"متصل" else "نیازمند تنظیم",color=if(ready)Color(0xFF1B9970) else Color(0xFFA72C53),style=MaterialTheme.typography.bodySmall)
    }
}
@Composable
private fun PhoneSwitch(title:String,detail:String,checked:Boolean,onChange:(Boolean)->Unit,enabled:Boolean=true){
    Row(verticalAlignment=Alignment.CenterVertically,modifier=Modifier.fillMaxWidth()){
        Column(Modifier.weight(1f)){Text(title,color=ink);Text(detail,style=MaterialTheme.typography.bodySmall,color=gray)}
        Spacer(Modifier.width(9.dp));Switch(checked=checked,onCheckedChange=onChange,enabled=enabled)
    }
}
