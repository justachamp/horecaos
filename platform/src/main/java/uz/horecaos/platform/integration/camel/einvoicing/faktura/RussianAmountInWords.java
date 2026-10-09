package uz.horecaos.platform.integration.camel.einvoicing.faktura;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * An amount in sums written out in Russian ("Один миллион двести тысяч сум 00 тийин"), because
 * Faktura.uz's invoice document marks its amounts-in-words fields required (its Swagger model
 * for {@code column_summary_values_in_words}, ADR 0096).
 *
 * <p>Numerals agree in gender and number the way a printed invoice's do: a thousand is
 * feminine ("две тысячи", "двадцать одна тысяча"), a million masculine, and the currency
 * names do not decline ("сум", "тийин"). Up to a trillion, which no HorecaOS statement
 * approaches.
 */
final class RussianAmountInWords {

    private static final String[] UNITS_MASCULINE = {
        "", "один", "два", "три", "четыре", "пять", "шесть", "семь", "восемь", "девять"
    };
    private static final String[] UNITS_FEMININE = {
        "", "одна", "две", "три", "четыре", "пять", "шесть", "семь", "восемь", "девять"
    };
    private static final String[] TEENS = {
        "десять",
        "одиннадцать",
        "двенадцать",
        "тринадцать",
        "четырнадцать",
        "пятнадцать",
        "шестнадцать",
        "семнадцать",
        "восемнадцать",
        "девятнадцать"
    };
    private static final String[] TENS = {
        "", "", "двадцать", "тридцать", "сорок", "пятьдесят", "шестьдесят", "семьдесят", "восемьдесят", "девяносто"
    };
    private static final String[] HUNDREDS = {
        "", "сто", "двести", "триста", "четыреста", "пятьсот", "шестьсот", "семьсот", "восемьсот", "девятьсот"
    };

    /** One, few, many -- the three forms a Russian noun takes after a numeral. */
    private static final String[][] SCALES = {
        {"", "", ""},
        {"тысяча", "тысячи", "тысяч"},
        {"миллион", "миллиона", "миллионов"},
        {"миллиард", "миллиарда", "миллиардов"},
        {"триллион", "триллиона", "триллионов"}
    };

    private RussianAmountInWords() {}

    /** The amount rounded to a tiyin: whole sums in words, then the tiyin as two digits. */
    static String sums(BigDecimal amount) {
        BigDecimal rounded = amount.setScale(2, RoundingMode.HALF_UP);
        long whole = rounded.toBigInteger().longValueExact();
        int tiyin = rounded.remainder(BigDecimal.ONE).movePointRight(2).abs().intValue();
        return capitalise(words(whole)) + " сум %02d тийин".formatted(tiyin);
    }

    static String words(long number) {
        if (number == 0) {
            return "ноль";
        }
        List<String> parts = new ArrayList<>();
        int scale = 0;
        long rest = number;
        while (rest > 0) {
            int group = (int) (rest % 1000);
            if (group != 0) {
                String text = group(group, scale == 1);
                String[] forms = SCALES[scale];
                parts.add(scale == 0 ? text : text + " " + forms[form(group)]);
            }
            rest /= 1000;
            scale++;
        }
        List<String> ordered = new ArrayList<>();
        for (int i = parts.size() - 1; i >= 0; i--) {
            ordered.add(parts.get(i));
        }
        return String.join(" ", ordered);
    }

    private static String group(int group, boolean feminine) {
        List<String> words = new ArrayList<>();
        words.add(HUNDREDS[group / 100]);
        int rest = group % 100;
        if (rest >= 10 && rest < 20) {
            words.add(TEENS[rest - 10]);
        } else {
            words.add(TENS[rest / 10]);
            words.add((feminine ? UNITS_FEMININE : UNITS_MASCULINE)[rest % 10]);
        }
        return String.join(" ", words.stream().filter(word -> !word.isEmpty()).toList());
    }

    /** Which noun form follows a group: 1 takes the first, 2 to 4 the second, everything else the third. */
    private static int form(int group) {
        int lastTwo = group % 100;
        if (lastTwo >= 11 && lastTwo <= 14) {
            return 2;
        }
        return switch (group % 10) {
            case 1 -> 0;
            case 2, 3, 4 -> 1;
            default -> 2;
        };
    }

    private static String capitalise(String text) {
        return text.isEmpty() ? text : Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }
}
