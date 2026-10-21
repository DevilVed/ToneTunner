package com.devilved.tonetunner.cloud;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Pure-Java parsing and application of preferred spellings and literal corrections. */
public final class DictionaryRules {
    private DictionaryRules() { }

    /** Applies explicit corrections in one pass, then normalizes exact vocabulary terms to canonical case. */
    public static String apply(String text, String vocabulary, String replacements) {
        if (text == null || text.isEmpty()) {
            return text;
        }

        String corrected = applyRules(text, replacementRules(replacements));
        List<Rule> canonicalSpellings = new ArrayList<>();
        for (String term : vocabularyTerms(vocabulary)) {
            canonicalSpellings.add(new Rule(term, term));
        }
        return applyRules(corrected, longestFirst(canonicalSpellings));
    }

    /** Throws IllegalArgumentException when a correction is not one nonempty literal mapping per line. */
    public static void validate(String vocabulary, String replacements) {
        vocabularyTerms(vocabulary);
        replacementRules(replacements);
    }

    /** Shared normalization for the system prompt and deterministic exact-name casing. */
    static List<String> vocabularyTerms(String vocabulary) {
        Map<String, String> unique = new LinkedHashMap<>();
        if (vocabulary == null || vocabulary.trim().isEmpty()) {
            return new ArrayList<>();
        }
        String[] values = vocabulary.split("[,\\r\\n]+");
        for (String value : values) {
            String term = value.trim();
            if (term.isEmpty()) {
                continue;
            }
            String key = term.toLowerCase(Locale.ROOT);
            if (!unique.containsKey(key)) {
                unique.put(key, term);
            }
        }
        return new ArrayList<>(unique.values());
    }

    private static List<Rule> replacementRules(String replacements) {
        Map<String, Rule> unique = new LinkedHashMap<>();
        if (replacements == null || replacements.trim().isEmpty()) {
            return new ArrayList<>();
        }
        String[] lines = replacements.split("\\r\\n|\\n|\\r", -1);
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            int separator = trimmed.indexOf("=>");
            if (separator < 0 || separator != trimmed.lastIndexOf("=>")) {
                throw invalidRule();
            }
            String heard = trimmed.substring(0, separator).trim();
            String preferred = trimmed.substring(separator + 2).trim();
            if (heard.isEmpty() || preferred.isEmpty()) {
                throw invalidRule();
            }
            String key = heard.toLowerCase(Locale.ROOT);
            Rule existing = unique.get(key);
            if (existing != null) {
                if (!existing.preferred.equals(preferred)) {
                    throw invalidRule();
                }
                continue;
            }
            unique.put(key, new Rule(heard, preferred));
        }

        return longestFirst(new ArrayList<>(unique.values()));
    }

    private static List<Rule> longestFirst(List<Rule> rules) {
        rules.sort(Comparator.comparingInt((Rule rule) -> rule.heard.codePointCount(0, rule.heard.length()))
                .reversed());
        return rules;
    }

    private static String applyRules(String text, List<Rule> rules) {
        if (rules.isEmpty()) {
            return text;
        }
        Map<Integer, List<Rule>> rulesByFirstCodePoint = new HashMap<>();
        for (Rule rule : rules) {
            int firstCodePoint = caseKey(rule.heard.codePointAt(0));
            List<Rule> candidates = rulesByFirstCodePoint.get(firstCodePoint);
            if (candidates == null) {
                candidates = new ArrayList<>();
                rulesByFirstCodePoint.put(firstCodePoint, candidates);
            }
            candidates.add(rule);
        }

        StringBuilder result = new StringBuilder(text.length());
        int offset = 0;
        while (offset < text.length()) {
            Rule matching = null;
            List<Rule> candidates = rulesByFirstCodePoint.get(caseKey(text.codePointAt(offset)));
            if (candidates != null) {
                for (Rule rule : candidates) {
                    if (matchesAt(text, offset, rule.heard)) {
                        matching = rule;
                        break;
                    }
                }
            }
            if (matching != null) {
                result.append(matching.preferred);
                offset += matching.heard.length();
            } else {
                int codePoint = text.codePointAt(offset);
                result.appendCodePoint(codePoint);
                offset += Character.charCount(codePoint);
            }
        }
        return result.toString();
    }

    private static int caseKey(int codePoint) {
        return Character.toLowerCase(Character.toUpperCase(codePoint));
    }

    private static boolean matchesAt(String text, int offset, String heard) {
        int end = offset + heard.length();
        if (end > text.length() || !text.regionMatches(true, offset, heard, 0, heard.length())) {
            return false;
        }
        int first = heard.codePointAt(0);
        int last = heard.codePointBefore(heard.length());
        if (isWordCodePoint(first) && offset > 0 && isWordCodePoint(text.codePointBefore(offset))) {
            return false;
        }
        return !isWordCodePoint(last) || end >= text.length()
                || !isWordCodePoint(text.codePointAt(end));
    }

    private static boolean isWordCodePoint(int codePoint) {
        int category = Character.getType(codePoint);
        return Character.isLetterOrDigit(codePoint)
                || category == Character.NON_SPACING_MARK
                || category == Character.COMBINING_SPACING_MARK
                || category == Character.ENCLOSING_MARK;
    }

    private static IllegalArgumentException invalidRule() {
        return new IllegalArgumentException("Each correction must be a nonempty 'heard => preferred' rule.");
    }

    private static final class Rule {
        final String heard;
        final String preferred;

        Rule(String heard, String preferred) {
            this.heard = heard;
            this.preferred = preferred;
        }
    }
}
