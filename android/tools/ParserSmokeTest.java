import site.magics.phone.CallCommandParser;

public final class ParserSmokeTest {
    private static void expect(String message, String wanted) {
        String actual = CallCommandParser.recipient(message);
        if (wanted == null ? actual != null : !wanted.equals(actual)) {
            throw new AssertionError(message + " -> " + actual + " expected " + wanted);
        }
    }
    public static void main(String[] args) {
        expect("به J A N زنگ بزن", "J A N");
        expect("لطفا به JAN زنگ بزن", "JAN");
        expect("با مریم تماس بگیر", "مریم");
        expect("لطفاً با علی تماس بگیرید", "علی");
        expect("جان جان، به رضا زنگ بزن!", "رضا");
        expect("به حسین تماس بگیر", "حسین");
        expect("سلام، چه خبر؟", null);
        expect("به علی پول بفرست", null);
        expect("", null);
        if (!CallCommandParser.key("J A N").equals(CallCommandParser.key("JAN"))) {
            throw new AssertionError("Latin spaced contact normalization failed");
        }
        if (!CallCommandParser.key("مريم").equals(CallCommandParser.key("مریم"))) {
            throw new AssertionError("Arabic/Persian normalization failed");
        }
        System.out.println("PASS: 11 local Java parser assertions");
    }
}
