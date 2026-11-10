package com.copilot;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Decides whether a transcript line is a question or request that the user will probably need to answer. */
public final class Questions {
    private static final Set<String> QUESTION_STARTERS = Set.of(
        "what", "why", "how", "when", "where", "who", "which", "whose",
        "can", "could", "would", "will", "should", "shall", "do", "does", "did",
        "is", "are", "was", "were", "have", "has", "any");
    private static final Pattern REQUEST = Pattern.compile(
        "\\b(can|could|would|will) you\\b|\\bplease (share|send|confirm|check|update|clarify|explain|let us know|tell)\\b"
            + "|\\bany (update|idea|thoughts)\\b|\\bwhat do you think\\b|\\bshare the\\b",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern SENTENCE_END = Pattern.compile("(?<=[.!?])\\s+");
    private static final int MIN_WORDS = 3;

    private Questions() {}

    /** The question/request sentence in {@code text} (the last one if several), or null if there is none. */
    public static String find(String text) {
        if (text == null) return null;
        String[] sentences = SENTENCE_END.split(text.strip());
        for (int i = sentences.length - 1; i >= 0; i--) {
            String s = sentences[i].strip();
            if (isQuestion(s)) return s;
        }
        return null;
    }

    static boolean isQuestion(String sentence) {
        String[] words = sentence.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}' ?]", " ").trim().split("\\s+");
        if (words.length < MIN_WORDS) return false;
        if (sentence.endsWith("?")) return true;
        Matcher m = REQUEST.matcher(sentence);
        return m.find() || QUESTION_STARTERS.contains(words[0]) && sentence.length() > 18 && !sentence.endsWith(".");
    }
}
