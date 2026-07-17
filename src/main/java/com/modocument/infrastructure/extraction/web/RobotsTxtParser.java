package com.modocument.infrastructure.extraction.web;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Small, conservative robots.txt parser: User-agent groups with
 * Allow/Disallow/Crawl-delay. The most specific matching user-agent group
 * applies ('*' is the fallback). Anything unparseable in a line is ignored;
 * an unparseable file is the caller's cue to fail closed.
 */
public class RobotsTxtParser {

    /**
     * @param content        the robots.txt body
     * @param userAgentToken our product token (e.g. "MoDocumentBot")
     */
    public RobotsRules parse(String content, String userAgentToken) {
        String token = userAgentToken.toLowerCase(Locale.ROOT);

        List<RobotsRules.Rule> bestRules = null;
        long bestDelay = 0;
        int bestSpecificity = -1;

        List<RobotsRules.Rule> currentRules = null;
        long currentDelay = 0;
        int currentSpecificity = -1;
        boolean groupOpen = false;      // true while collecting user-agent lines
        boolean currentApplies = false;

        for (String rawLine : content.split("\\R")) {
            String line = stripComment(rawLine).trim();
            if (line.isEmpty()) {
                continue;
            }
            int colon = line.indexOf(':');
            if (colon < 0) {
                continue;
            }
            String directive = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String value = line.substring(colon + 1).trim();

            if (directive.equals("user-agent")) {
                if (!groupOpen) {
                    // A new group starts: bank the previous one if it applied.
                    if (currentApplies && currentSpecificity > bestSpecificity) {
                        bestRules = currentRules;
                        bestDelay = currentDelay;
                        bestSpecificity = currentSpecificity;
                    }
                    currentRules = new ArrayList<>();
                    currentDelay = 0;
                    currentSpecificity = -1;
                    currentApplies = false;
                    groupOpen = true;
                }
                String agent = value.toLowerCase(Locale.ROOT);
                if (agent.equals("*") && currentSpecificity < 0) {
                    currentApplies = true;
                    currentSpecificity = 0;
                } else if (!agent.equals("*") && token.contains(agent)) {
                    currentApplies = true;
                    currentSpecificity = Math.max(currentSpecificity, agent.length());
                }
                continue;
            }

            groupOpen = false;
            if (currentRules == null) {
                continue; // directives before any user-agent line
            }
            switch (directive) {
                case "allow" -> {
                    if (!value.isEmpty()) {
                        currentRules.add(new RobotsRules.Rule(value, true));
                    }
                }
                case "disallow" -> {
                    if (!value.isEmpty()) {
                        currentRules.add(new RobotsRules.Rule(value, false));
                    }
                    // Empty Disallow means everything is allowed — no rule needed.
                }
                case "crawl-delay" -> currentDelay = parseDelayMillis(value, currentDelay);
                default -> {
                    // Sitemap and unknown directives are ignored.
                }
            }
        }
        if (currentApplies && currentSpecificity > bestSpecificity) {
            bestRules = currentRules;
            bestDelay = currentDelay;
        }
        return bestRules == null ? RobotsRules.allowAll() : new RobotsRules(bestRules, bestDelay);
    }

    private static String stripComment(String line) {
        int hash = line.indexOf('#');
        return hash < 0 ? line : line.substring(0, hash);
    }

    private static long parseDelayMillis(String value, long fallback) {
        try {
            return (long) (Double.parseDouble(value) * 1000);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
