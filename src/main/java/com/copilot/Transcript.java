package com.copilot;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The running transcript. When both the mic and the system audio are recorded, speakers (not headphones) make
 * the mic re-hear the other side, so the same sentence shows up as "Them" and again as "Me". This class drops
 * a "Me" line whose words mostly appear in what "Them" said in the last few seconds.
 */
public final class Transcript {
    private record Entry(String speaker, String text, long timeMs) {
        String line() { return speaker == null ? text : speaker + ": " + text; }
    }

    private static final long ECHO_WINDOW_MS = 15_000;
    private static final double ECHO_OVERLAP = 0.6;  // share of the mic line's words that the system line contains
    private static final int MIN_WORDS = 3;           // shorter lines ("yes", "okay") are never treated as echo
    private static final Pattern NON_WORD = Pattern.compile("[^\\p{L}\\p{N}']+");

    private final List<Entry> entries = new ArrayList<>();

    /** Adds a line; returns false if it was dropped as an echo. Other speakers' echoed "Me" lines are removed. */
    public synchronized boolean add(String speaker, String text, long nowMs) {
        if ("Me".equals(speaker) && isEchoOfThem(text, nowMs, null)) return false;
        entries.add(new Entry(speaker, text, nowMs));
        if ("Them".equals(speaker)) {
            // The system audio can arrive after its mic echo: remove any recent "Me" lines it explains.
            entries.removeIf(e -> "Me".equals(e.speaker()) && nowMs - e.timeMs() <= ECHO_WINDOW_MS
                && isEchoOfThem(e.text(), nowMs, e));
        }
        return true;
    }

    public synchronized List<String> lines() {
        List<String> out = new ArrayList<>(entries.size());
        for (Entry e : entries) out.add(e.line());
        return out;
    }

    // True if most words of `meText` occur in the recent "Them" lines (taken together, since the two sources
    // are chunked at different moments and a mic line can span two system lines).
    private boolean isEchoOfThem(String meText, long nowMs, Entry ignored) {
        Set<String> me = words(meText);
        if (me.size() < MIN_WORDS) return false;
        Set<String> them = new HashSet<>();
        for (Entry e : entries) {
            if (e != ignored && "Them".equals(e.speaker()) && nowMs - e.timeMs() <= ECHO_WINDOW_MS) them.addAll(words(e.text()));
        }
        if (them.isEmpty()) return false;
        long shared = me.stream().filter(them::contains).count();
        return shared >= ECHO_OVERLAP * me.size();
    }

    private static Set<String> words(String text) {
        Set<String> out = new HashSet<>();
        for (String w : NON_WORD.split(text.toLowerCase(Locale.ROOT))) if (!w.isEmpty()) out.add(w);
        return out;
    }
}
