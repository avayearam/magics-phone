package site.magics.phone;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.ContactsContract;
import android.speech.RecognizerIntent;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Local-first Android pilot: command -> contact -> explicit confirmation -> SIM call. */
public final class MainActivity extends Activity {
    private static final int REQ_CONTACTS = 100;
    private static final int REQ_CALL = 101;
    private static final int REQ_SPEECH = 102;
    private static final int REQ_PICK = 103;

    private EditText commandInput;
    private TextView resultText;
    private TextView statusText;
    private String pendingRecipient;
    private ContactDirectory.PhoneContact pendingCall;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_main);
        getWindow().setNavigationBarColor(getColor(R.color.background));
        getWindow().setStatusBarColor(getColor(R.color.background));

        commandInput = findViewById(R.id.commandInput);
        resultText = findViewById(R.id.resultText);
        statusText = findViewById(R.id.statusText);
        Button executeButton = findViewById(R.id.executeButton);
        Button voiceButton = findViewById(R.id.voiceButton);
        Button pickButton = findViewById(R.id.pickContactButton);
        executeButton.setOnClickListener(view -> handleCommand(commandInput.getText().toString()));
        voiceButton.setOnClickListener(view -> requestSpeech());
        pickButton.setOnClickListener(view -> openContactPicker());
    }

    private void handleCommand(String raw) {
        String target = CallCommandParser.recipient(raw);
        if (target == null) {
            showResult("فرمان شناسایی نشد. نمونه: «به J A N زنگ بزن» یا «با مریم تماس بگیر».");
            return;
        }
        pendingRecipient = target;
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            new AlertDialog.Builder(this)
                .setTitle("اجازه دسترسی به مخاطبین")
                .setMessage("برای پیدا کردن «" + target + "» در دفترچه گوشی، اجازه خواندن مخاطبین لازم است. هیچ اطلاعاتی ارسال یا ذخیره نمی‌شود.")
                .setPositiveButton("اجازه می‌دهم", (dialog, which) ->
                    requestPermissions(new String[]{Manifest.permission.READ_CONTACTS}, REQ_CONTACTS))
                .setNegativeButton("لغو", null)
                .show();
            return;
        }
        resolveRecipient(target);
    }

    private void resolveRecipient(String target) {
        try {
            List<ContactDirectory.PhoneContact> matches = ContactDirectory.find(this, target);
            if (matches.isEmpty()) {
                showResult("مخاطب «" + target + "» پیدا نشد. می‌توانی از دکمه انتخاب مخاطب استفاده کنی.");
                return;
            }
            if (matches.size() == 1) {
                confirmCall(matches.get(0));
                return;
            }
            // Ambiguous matches require a person to choose; never guess a number.
            String[] choices = new String[Math.min(matches.size(), 30)];
            for (int i = 0; i < choices.length; i++) {
                choices[i] = matches.get(i).toString();
            }
            new AlertDialog.Builder(this)
                .setTitle("چند شماره پیدا شد؛ انتخاب کن")
                .setItems(choices, (dialog, which) -> confirmCall(matches.get(which)))
                .setNegativeButton("لغو", null)
                .show();
        } catch (SecurityException exception) {
            showResult("دسترسی به مخاطبین مجاز نیست؛ می‌توانی از انتخابگر مخاطب استفاده کنی.");
        } catch (Exception exception) {
            showResult("خواندن دفترچه مخاطبین ناموفق بود. دوباره امتحان کن.");
        }
    }

    private String cleanNumber(String raw) {
        if (raw == null) return "";
        String digits = raw.trim().replaceAll("[\\s()\\-\\u200e\\u200f]", "");
        digits = digits.replaceAll("[^0-9+]", "");
        // Avoid USSD/service codes and suspicious dial strings.
        if (!digits.matches("\\+?[0-9]{7,15}")) return "";
        if (digits.startsWith("+")) return digits;
        if (digits.startsWith("0098")) return "+98" + digits.substring(4);
        return digits;
    }

    private void confirmCall(ContactDirectory.PhoneContact contact) {
        String number = cleanNumber(contact.phone);
        if (number.isEmpty()) {
            showResult("شماره این مخاطب معتبر نیست یا شامل کد خدماتی است؛ تماس آغاز نشد.");
            return;
        }
        pendingCall = new ContactDirectory.PhoneContact(contact.name, number);
        new AlertDialog.Builder(this)
            .setTitle("تأیید تماس سیم‌کارتی")
            .setMessage("مخاطب: " + contact.name + "\nشماره: " + number +
                "\n\nتماس با سیم‌کارت خود گوشی آغاز می‌شود. این اپ هنوز صدای AI را به تماس تزریق نمی‌کند.")
            .setPositiveButton("تماس بگیر", (dialog, which) -> startDirectCall())
            .setNeutralButton("نمایش شماره‌گیر", (dialog, which) -> launchDialer(pendingCall.phone))
            .setNegativeButton("انصراف", (dialog, which) -> pendingCall = null)
            .show();
    }

    private void startDirectCall() {
        if (pendingCall == null) return;
        if (checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CALL_PHONE}, REQ_CALL);
            return;
        }
        String phone = pendingCall.phone;
        // Exactly one user-confirmed call. No repeat, scheduling, or background dialing.
        pendingCall = null;
        try {
            Intent call = new Intent(Intent.ACTION_CALL, Uri.fromParts("tel", phone, null));
            startActivity(call);
            showResult("درخواست تماس به سیستم تلفن ارسال شد. اتصال تماس هنوز تأیید نشده است.");
        } catch (SecurityException | ActivityNotFoundException error) {
            launchDialer(phone);
        }
    }

    private void launchDialer(String phone) {
        if (TextUtils.isEmpty(phone)) return;
        pendingCall = null;
        try {
            startActivity(new Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", phone, null)));
            showResult("شماره‌گیر باز شد؛ برای تماس از دکمه تماس سامسونگ استفاده کن.");
        } catch (ActivityNotFoundException error) {
            showResult("برنامه شماره‌گیر سازگار روی گوشی پیدا نشد.");
        }
    }

    private void requestSpeech() {
        Intent speech = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        speech.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
            RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        speech.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fa-IR");
        speech.putExtra(RecognizerIntent.EXTRA_PROMPT, "فرمان تماس را بگو؛ مثلاً به مریم زنگ بزن");
        try {
            startActivityForResult(speech, REQ_SPEECH);
        } catch (ActivityNotFoundException error) {
            showResult("روی این گوشی سرویس تشخیص گفتار موجود نیست. فرمان متنی را وارد کن.");
        }
    }

    private void openContactPicker() {
        Intent pick = new Intent(Intent.ACTION_PICK);
        pick.setType(ContactsContract.CommonDataKinds.Phone.CONTENT_TYPE);
        try {
            startActivityForResult(pick, REQ_PICK);
        } catch (ActivityNotFoundException error) {
            showResult("انتخابگر مخاطبین روی گوشی پیدا نشد.");
        }
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (result != RESULT_OK || data == null) return;
        if (request == REQ_SPEECH) {
            ArrayList<String> candidates = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
            if (candidates == null || candidates.isEmpty()) return;
            String heard = candidates.get(0);
            commandInput.setText(heard);
            showResult("تشخیص گفتار: «" + heard + "»");
            handleCommand(heard);
        } else if (request == REQ_PICK) {
            Uri uri = data.getData();
            if (uri == null) return;
            try (Cursor cursor = getContentResolver().query(uri,
                new String[]{ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER}, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    String name = cursor.getString(0);
                    String number = cursor.getString(1);
                    confirmCall(new ContactDirectory.PhoneContact(name, number));
                }
            } catch (Exception error) {
                showResult("خواندن شماره انتخاب‌شده ناموفق بود.");
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int request, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(request, permissions, grants);
        boolean granted = grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED;
        if (request == REQ_CONTACTS && granted && pendingRecipient != null) {
            String recipient = pendingRecipient;
            pendingRecipient = null;
            resolveRecipient(recipient);
        } else if (request == REQ_CALL && granted && pendingCall != null) {
            startDirectCall();
        } else if (!granted) {
            showResult("دسترسی داده نشد. تماس خودکار اجرا نمی‌شود؛ می‌توانی از انتخابگر مخاطب و شماره‌گیر استفاده کنی.");
        }
    }

    private void showResult(String message) {
        resultText.setText(message);
        statusText.setText("MVP 0.1 • کنترل تماس با تأیید شما");
    }
}
