package com.modocument.infrastructure.extraction.web;

import java.util.List;
import java.util.regex.Pattern;

/**
 * The parsed robots.txt rules that apply to our user-agent. Path matching
 * follows the de-facto standard: longest (most specific) matching pattern
 * wins, Allow wins ties, '*' is a wildcard, '$' anchors the end.
 */
public class RobotsRules {

    /** An Allow/Disallow line. */
    public record Rule(String pattern, boolean allow) {
    }

    private final List<Rule> rules;
    private final long crawlDelayMillis;

    public RobotsRules(List<Rule> rules, long crawlDelayMillis) {
        this.rules = List.copyOf(rules);
        this.crawlDelayMillis = crawlDelayMillis;
    }

    public static RobotsRules allowAll() {
        return new RobotsRules(List.of(), 0);
    }

    public static RobotsRules disallowAll() {
        return new RobotsRules(List.of(new Rule("/", false)), 0);
    }

    public boolean isAllowed(String path) {
        if (path == null || path.isEmpty()) {
            path = "/";
        }
        Rule best = null;
        int bestLength = -1;
        for (Rule rule : rules) {
            if (matches(rule.pattern(), path)) {
                int length = rule.pattern().length();
                // Longest pattern wins; Allow wins a tie.
                if (length > bestLength || (length == bestLength && rule.allow())) {
                    best = rule;
                    bestLength = length;
                }
            }
        }
        return best == null || best.allow();
    }

    public long crawlDelayMillis() {
        return crawlDelayMillis;
    }

    static boolean matches(String pattern, String path) {
        if (pattern.isEmpty()) {
            return false;
        }
        if (!pattern.contains("*") && !pattern.endsWith("$")) {
            return path.startsWith(pattern);
        }
        StringBuilder regex = new StringBuilder("^");
        String body = pattern.endsWith("$") ? pattern.substring(0, pattern.length() - 1) : pattern;
        for (String literal : body.split("\\*", -1)) {
            regex.append(Pattern.quote(literal)).append(".*");
        }
        regex.setLength(regex.length() - 2); // drop the trailing wildcard from split
        if (pattern.endsWith("$")) {
            regex.append("$");
        }
        return Pattern.compile(regex.toString()).matcher(path).find();
    }
}
