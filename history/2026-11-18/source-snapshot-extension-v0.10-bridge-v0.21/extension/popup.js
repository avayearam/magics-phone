const q = s => document.querySelector(s);
async function redraw(){
  const {armed,token,lastDetected,lastResult,scriptLoadedAt,scriptState,lastMessageMode,scriptVersion} =
    await chrome.storage.local.get(['armed','token','lastDetected','lastResult','scriptLoadedAt','scriptState','lastMessageMode','scriptVersion']);
  q('#arm').textContent = armed ? 'غیرفعال‌سازی' : 'فعال‌سازی';
  q('#status').textContent=`وضعیت: ${armed?'فعال':'خاموش'}\nکد اتصال: ${token?'ذخیره‌شده':'تنظیم‌نشده'}`;
  q('#diagnostics').textContent=`اسکریپت ChatGPT: ${scriptState||'هنوز بارگذاری تأیید نشده'}\nنسخه اسکریپت: ${scriptVersion||'—'}\nزمان: ${scriptLoadedAt||'—'}\nآخرین پیام: ${lastDetected||'ثبت نشده'}\nنوع ورودی: ${lastMessageMode==='composer_submit'?'ارسال مستقیم از کادر پیام':'—'}\nآخرین نتیجه: ${lastResult||'ثبت نشده'}`;
}
q('#save').addEventListener('click',async()=>{
  const token=q('#token').value.trim();
  if(token.length<32){q('#connection').textContent='کد اتصال معتبر نیست.';return;}
  await chrome.storage.local.set({token});q('#token').value='';
  q('#connection').textContent='کد ذخیره شد؛ حالا تست اتصال را بزن.';await redraw();
});
q('#arm').addEventListener('click',async()=>{
  const {armed,token}=await chrome.storage.local.get(['armed','token']);
  if(!token){q('#connection').textContent='اول کد اتصال محلی را وارد کن.';return;}
  await chrome.storage.local.set({armed:!armed});await redraw();
});
q('#test').addEventListener('click',async()=>{
  q('#connection').textContent='در حال بررسی، بدون تماس...';
  const {token}=await chrome.storage.local.get('token');
  if(!token){q('#connection').textContent='کد اتصال ذخیره نشده.';return;}
  try {
    const resp=await fetch('http://127.0.0.1:18763/health',{
      method:'GET',headers:{'X-Magic-Token':token},signal:AbortSignal.timeout(5000)
    });
    const data=await resp.json();
    q('#connection').textContent=(resp.ok&&data.ok)
      ? `کنترلر متصل v${data.version} | ${data.contacts_count} مخاطب محلی`
      : `خطای اتصال: ${data.reason||resp.status}`;
  }catch{q('#connection').textContent='کنترلر محلی در دسترس نیست.';}
  await redraw();
});
q('#probe').addEventListener('click',async()=>{
  q('#tabstate').textContent='در حال بررسی صفحه ChatGPT...';
  try{
    const res=await chrome.runtime.sendMessage({type:'probe_tab'});
    q('#tabstate').textContent=res?.ok
      ? `متصل به ChatGPT؛ نسخه ${res.version}؛ کادر پیام: ${res.composer ? "پیدا شد" : "فعلاً پیدا نشد"}. یک فرمان جدید بفرست.`
      : `اتصال به صفحه ناموفق: ${res?.reason||'unknown'}. صفحه chatgpt.com را فعال کن.`;
  }catch{q('#tabstate').textContent='خطا در اتصال به تب Chrome';}
  await redraw();
});
redraw();
