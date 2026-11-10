package com.copilot;

import java.util.Locale;
import java.util.Set;

/** Turns a transcript line in any language into English: local Whisper hears it, the LLM translates it. */
public final class Translator {
    // Very common English words: a line made mostly of these is English and needs no round trip to the LLM.
    private static final Set<String> ENGLISH = Set.of(
        "the", "a", "an", "and", "or", "but", "is", "are", "was", "were", "be", "to", "of", "in", "on", "at", "for", "with",
        "it", "this", "that", "i", "you", "we", "they", "he", "she", "my", "your", "our", "can", "could", "would", "should",
        "will", "do", "does", "did", "have", "has", "not", "so", "if", "then", "what", "when", "how", "why", "yes", "no",
        "ok", "okay", "please", "thanks", "thank", "think", "just", "about", "from", "by", "as", "there", "here");

    private Translator() {}

    /** True if the text is very likely already English (checked locally, no network). */
    public static boolean looksEnglish(String text) {
        String[] words = text.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}' ]", " ").trim().split("\\s+");
        if (words.length == 0) return true;
        for (char c : text.toCharArray()) {
            if (Character.isLetter(c) && c > 0x24F) return false; // Devanagari, Arabic, CJK, ... is never English
        }
        long common = java.util.Arrays.stream(words).filter(ENGLISH::contains).count();
        return common >= Math.max(2, Math.round(words.length * 0.25));
    }

    /** English text for the line; on a translation failure the original is returned rather than losing the line. */
    public static String toEnglish(String text) {
        if (text.isBlank() || looksEnglish(text)) return text;
        try {
            String english = Llm.translateToEnglish(text);
            return english.isBlank() ? text : english;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return text;
        } catch (Exception e) {
            return text;
        }
    }
}
