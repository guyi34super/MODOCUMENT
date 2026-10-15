package com.modocument.doccreation.pdftemplate;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Label, heading and value-shape heuristics shared by the detectors. */
final class Labels {

    static final Pattern LEADER = Pattern.compile("_{3,}|\\.{5,}|…{2,}|(?:\\. ){4,}\\.?");
    static final Pattern PHOTO = Pattern.compile("(?iu)\\b(photo(graph)?s?|picture|passport|affix|logo|image|stamp|"
            + "foto|photographie)\\b");
    static final Pattern SIGN = Pattern.compile("(?iu)\\b(signature|signed|sign here|sign|initials|unterschrift)\\b");
    static final Pattern DATE_WORD = Pattern.compile("(?iu)\\b((?<!to )date|dob|birth|born|day|issued|expiry|expires|"
            + "valid (from|until)|datum|fecha)\\b");
    static final Pattern NUMBER_WORD = Pattern.compile("(?iu)\\b(amount|qty|quantity|total|sub-?total|price|cost|"
            + "sum|fee|age|salary|balance|count|number of|rate|tax|vat|hours|units|score|points|percent(age)?)\\b");
    static final Pattern MULTI_WORD = Pattern.compile("(?iu)\\b(address|remarks?|comments?|description|notes?|"
            + "details|reason|explanation|summary|observations?)\\b");

    // value shapes
    static final Pattern DATE_VALUE = Pattern.compile("(?iu)^(\\d{1,4}[./-]\\d{1,2}[./-]\\d{1,4}|"
            + "\\d{1,2}(st|nd|rd|th)? (jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\\.? \\d{2,4}|"
            + "(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\\.? \\d{1,2},? \\d{2,4})$");
    static final Pattern AMOUNT_VALUE = Pattern.compile("^[-+]?(?:[$€£¥₹]|[A-Z]{3} ?)?\\s?\\d{1,3}(?:[, ]?\\d{3})*"
            + "(?:[.,]\\d{1,2})?\\s?(?:[$€£¥₹]|[A-Z]{3})?$");
    static final Pattern NUMBER_VALUE = Pattern.compile("^[-+]?\\d+(?:[.,]\\d+)?%?$");
    static final Pattern EMAIL_VALUE = Pattern.compile("(?i)^[\\w.+-]+@[\\w-]+(\\.[\\w-]+)+$");
    static final Pattern PHONE_VALUE = Pattern.compile("^\\+?[\\d ()./-]{7,20}$");
    static final Pattern ID_VALUE = Pattern.compile("(?i)^(?=.*\\d)[A-Z0-9]{1,6}[-/ ]?[A-Z0-9-/]{3,24}$");
    static final Pattern IBAN_VALUE = Pattern.compile("(?i)^[A-Z]{2}\\d{2}(?: ?[A-Z0-9]{4}){2,7}(?: ?[A-Z0-9]{1,4})?$");
    static final Pattern CHECK_GLYPH = Pattern.compile("^[☒☑✓✔✗✘Xx]$");
    static final Pattern TIME_COLON = Pattern.compile("\\d:\\d");

    /** Words that make a table body cell static (row titles that belong to the template). */
    private static final Set<String> STATIC_CELL_WORDS = Set.of("total", "subtotal", "sub-total", "grand total",
            "tax", "vat", "net", "gross", "balance", "discount", "shipping", "amount due", "total due");

    private Labels() {
    }

    /** Label text without leaders, trailing colon, asterisk or dots. */
    static String clean(String raw) {
        if (raw == null) {
            return "";
        }
        String s = LEADER.matcher(raw).replaceAll(" ");
        s = s.replaceAll("[\\s:*.;,_-]+$", "").replaceAll("^[\\s:*.;,_\\-•·]+", "").replaceAll("\\s+", " ").strip();
        if (s.length() > 200) {
            s = s.substring(0, 200).strip();
        }
        return s;
    }

    static boolean isRequiredMark(String raw) {
        return raw != null && raw.contains("*");
    }

    /** A short phrase that reads like a field label ("Full name", "Date of birth"). */
    static boolean looksLikeLabel(String text) {
        String t = clean(text);
        if (t.isEmpty() || t.length() > 48) {
            return false;
        }
        if (!Character.isLetter(t.codePointAt(0))) {
            return false;
        }
        int words = t.split("\\s+").length;
        return words <= 6 && !isValueShape(t);
    }

    static int words(String s) {
        String t = s == null ? "" : s.strip();
        return t.isEmpty() ? 0 : t.split("\\s+").length;
    }

    /** Strong value shapes: dates, amounts, numbers, emails, phones, IDs, IBANs. */
    static boolean isValueShape(String raw) {
        String t = raw == null ? "" : raw.strip();
        if (t.isEmpty()) {
            return false;
        }
        return DATE_VALUE.matcher(t).matches() || EMAIL_VALUE.matcher(t).matches()
                || NUMBER_VALUE.matcher(t).matches() || AMOUNT_VALUE.matcher(t).matches() && t.matches(".*\\d.*")
                || PHONE_VALUE.matcher(t).matches() && t.replaceAll("\\D", "").length() >= 7
                || IBAN_VALUE.matcher(t).matches()
                || ID_VALUE.matcher(t).matches() && t.replaceAll("\\D", "").length() >= 3
                && !t.matches("(?i)^(page|p\\.)\\s.*");
    }

    static boolean isStaticCellWord(String text) {
        return STATIC_CELL_WORDS.contains(clean(text).toLowerCase(Locale.ROOT));
    }

    static RegionKind kindFor(String label, RegionKind fallback) {
        String l = label == null ? "" : label;
        if (SIGN.matcher(l).find()) {
            return RegionKind.SIGNATURE;
        }
        if (PHOTO.matcher(l).find()) {
            return RegionKind.IMAGE;
        }
        if (DATE_WORD.matcher(l).find()) {
            return RegionKind.DATE;
        }
        if (NUMBER_WORD.matcher(l).find()) {
            return RegionKind.NUMBER;
        }
        return fallback;
    }

    static RegionKind kindForValue(String value) {
        String t = value == null ? "" : value.strip();
        if (DATE_VALUE.matcher(t).matches()) {
            return RegionKind.DATE;
        }
        if (NUMBER_VALUE.matcher(t).matches() || AMOUNT_VALUE.matcher(t).matches() && t.matches(".*\\d.*")
                && !PHONE_VALUE.matcher(t).matches()) {
            return RegionKind.NUMBER;
        }
        return null;
    }

    /** A heading: bigger than body text, or bold/capitals and short, never ending with a colon. */
    static boolean isHeading(TextRun run, PageModel page) {
        String t = run.trimmed();
        if (t.length() < 2 || t.length() > 90 || t.endsWith(":") || LEADER.matcher(t).find()) {
            return false;
        }
        if (!t.codePoints().anyMatch(Character::isLetter)) {
            return false;
        }
        int w = words(t);
        if (run.size >= page.bodySize * 1.18f && w <= 14) {
            return true;
        }
        PageModel.Cell cell = page.cellAt(run.box.cx(), run.box.cy());
        boolean inTable = cell != null && page.cells.stream().filter(c -> c.table() == cell.table()).count() > 1;
        if (inTable) {
            return false;
        }
        if (run.bold && w <= 8) {
            return true;
        }
        String letters = t.replaceAll("[^\\p{L}]", "");
        return letters.length() >= 4 && w <= 6 && letters.equals(letters.toUpperCase(Locale.ROOT))
                && !letters.equals(letters.toLowerCase(Locale.ROOT));
    }

    /** Field key: lowercase ASCII slug of the label, at most 60 chars (room for a dedupe suffix). */
    static String slug(String label) {
        String s = transliterate(label == null ? "" : label);
        s = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        s = s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
        if (s.length() > 60) {
            s = s.substring(0, 60).replaceAll("_+$", "");
        }
        return s.isEmpty() ? "field" : s;
    }

    private static final String GREEK = "αβγδεζηθικλμνξοπρσςτυφχψω";
    private static final String[] GREEK_LATIN = {"a", "v", "g", "d", "e", "z", "i", "th", "i", "k", "l", "m", "n",
        "x", "o", "p", "r", "s", "s", "t", "y", "f", "ch", "ps", "o"};
    private static final String CYRILLIC = "абвгдеёжзийклмнопрстуфхцчшщъыьэюя";
    private static final String[] CYRILLIC_LATIN = {"a", "b", "v", "g", "d", "e", "e", "zh", "z", "i", "y", "k", "l",
        "m", "n", "o", "p", "r", "s", "t", "u", "f", "kh", "ts", "ch", "sh", "shch", "", "y", "", "e", "yu", "ya"};

    /** Greek and Cyrillic letters to Latin, so keys of non-Latin labels stay readable. */
    static String transliterate(String s) {
        String lower = Normalizer.normalize(s.toLowerCase(Locale.ROOT), Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        StringBuilder sb = new StringBuilder(lower.length());
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            int g = GREEK.indexOf(c);
            int k = CYRILLIC.indexOf(c);
            if (g >= 0) {
                sb.append(GREEK_LATIN[g]);
            } else if (k >= 0) {
                sb.append(CYRILLIC_LATIN[k]);
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    static String uniqueKey(String base, java.util.Set<String> used) {
        String key = base;
        int n = 2;
        while (used.contains(key)) {
            key = base + "_" + n++;
        }
        used.add(key);
        return key;
    }
}
