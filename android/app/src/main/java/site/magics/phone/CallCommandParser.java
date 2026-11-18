package site.magics.phone;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Pure Java command parser; does not access Android APIs or send data anywhere. */
public final class CallCommandParser {
    private static final Pattern CALL = Pattern.compile(
        "^(?:جان\\s*جان[،,:\\s]*)?(?:لطفا\\s+)?(?:به\\s+)?(.+?)\\s+" +
        "(?:زنگ\\s*بزن(?:ید)?|تماس\\s*بگیر(?:ید)?)$"
    );
    private static final Pattern CONTACT_CALL = Pattern.compile(
        "^(?:جان\\s*جان[،,:\\s]*)?(?:لطفا\\s+)?با\\s+(.+?)\\s+" +
        "تماس\\s*بگیر(?:ید)?$"
    );

    private CallCommandParser() {}

    public static String recipient(String command) {
        if (command == null) return null;
        String normalized = normalizeInput(command).replaceAll("[.!؟?،,]+$", "").trim();
        Matcher match = CONTACT_CALL.matcher(normalized);
        if (!match.matches()) match = CALL.matcher(normalized);
        if (!match.matches()) return null;
        String name = match.group(1).trim();
        if (name.length() < 2 || name.length() > 80) return null;
        return name;
    }

    public static String key(String value) {
        if (value == null) return "";
        return normalizeInput(value)
            .toLowerCase(Locale.ROOT)
            .replaceAll("[^\\p{L}\\p{N}+]", "");
    }

    public static String normalizeInput(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFKC)
            .replace('ي', 'ی')
            .replace('ك', 'ک')
            .replace('أ', 'ا')
            .replace('إ', 'ا')
            .replace('ۀ', 'ه')
            .replaceAll("[\\u064B-\\u065F]", "")
            .replace('\u200C', ' ')
            .replace('\u200F', ' ')
            .replace('\u200E', ' ')
            .replaceAll("\\s+", " ")
            .trim();
    }
}
