package com.modocument.application.validation;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Save-time checks for a template field's ws23 extraction hints (aliases,
 * label/value regex, date formats). The regexes come from template authors
 * and run on every uploaded document, so they get {@link SafeRegex#check}
 * (length cap, compile check, nested-quantifier screen) before they are
 * stored; the code extractor re-vets them before every use.
 */
public final class TemplateFieldHints {

    public static final int MAX_ALIASES = 50;
    public static final int MAX_ALIAS_LENGTH = 255;
    public static final int MAX_DATE_FORMATS = 20;
    public static final int MAX_DATE_FORMAT_LENGTH = 100;

    private TemplateFieldHints() {
    }

    /** Human-readable problems; empty when the hints may be saved. */
    public static List<String> problems(List<String> aliases, String labelPattern,
                                        String valuePattern, List<String> dateFormats) {
        List<String> problems = new ArrayList<>();
        if (aliases != null) {
            if (aliases.size() > MAX_ALIASES) {
                problems.add("aliases: at most " + MAX_ALIASES + " aliases per field.");
            }
            for (String alias : aliases) {
                if (alias != null && alias.length() > MAX_ALIAS_LENGTH) {
                    problems.add("aliases: an alias is longer than " + MAX_ALIAS_LENGTH
                            + " characters.");
                    break;
                }
            }
        }
        checkPattern("labelPattern", labelPattern, problems);
        checkPattern("valuePattern", valuePattern, problems);
        if (dateFormats != null) {
            if (dateFormats.size() > MAX_DATE_FORMATS) {
                problems.add("dateFormats: at most " + MAX_DATE_FORMATS + " formats per field.");
            }
            for (String format : dateFormats) {
                if (format == null || format.isBlank()) {
                    continue;
                }
                if (format.length() > MAX_DATE_FORMAT_LENGTH) {
                    problems.add("dateFormats: a format is longer than " + MAX_DATE_FORMAT_LENGTH
                            + " characters.");
                    continue;
                }
                try {
                    DateTimeFormatter.ofPattern(format.trim());
                } catch (IllegalArgumentException e) {
                    problems.add("dateFormats: '" + format.trim() + "' is not a valid date "
                            + "pattern (use letters such as dd, MM, yyyy).");
                }
            }
        }
        return problems;
    }

    /** @throws IllegalArgumentException (a 400) naming every problem */
    public static void require(List<String> aliases, String labelPattern, String valuePattern,
                               List<String> dateFormats) {
        List<String> problems = problems(aliases, labelPattern, valuePattern, dateFormats);
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException(String.join(" ", problems));
        }
    }

    private static void checkPattern(String name, String pattern, List<String> problems) {
        if (pattern == null || pattern.isBlank()) {
            return;
        }
        String problem = SafeRegex.check(pattern);
        if (problem != null) {
            problems.add(name + ": " + problem);
        }
    }
}
