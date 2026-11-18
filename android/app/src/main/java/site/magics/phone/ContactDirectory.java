package site.magics.phone;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.provider.ContactsContract;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ContactDirectory {
    private ContactDirectory() {}

    public static final class PhoneContact {
        public final String name;
        public final String phone;
        public PhoneContact(String name, String phone) {
            this.name = name;
            this.phone = phone;
        }
        @Override public String toString() { return name + " — " + phone; }
    }

    public static List<PhoneContact> find(Context context, String wanted) {
        String target = CallCommandParser.key(wanted);
        List<PhoneContact> exact = new ArrayList<>();
        List<PhoneContact> partial = new ArrayList<>();
        if (target.isEmpty()) return exact;
        ContentResolver resolver = context.getContentResolver();
        String[] columns = {
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        };
        // No contact data is saved, logged, or transmitted.
        try (Cursor cursor = resolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            columns, null, null, null
        )) {
            if (cursor == null) return exact;
            Map<String, PhoneContact> foundExact = new LinkedHashMap<>();
            Map<String, PhoneContact> foundPartial = new LinkedHashMap<>();
            while (cursor.moveToNext()) {
                String name = cursor.getString(0);
                String phone = cursor.getString(1);
                if (name == null || phone == null) continue;
                String key = CallCommandParser.key(name);
                PhoneContact contact = new PhoneContact(name, phone);
                String uniqueKey = key + "|" + phone.replaceAll("[^0-9+]", "");
                if (key.equals(target)) foundExact.putIfAbsent(uniqueKey, contact);
                else if (key.contains(target) && target.length() >= 3) {
                    foundPartial.putIfAbsent(uniqueKey, contact);
                }
            }
            exact.addAll(foundExact.values());
            partial.addAll(foundPartial.values());
        }
        return !exact.isEmpty() ? exact : partial;
    }
}
