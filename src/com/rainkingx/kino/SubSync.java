package com.rainkingx.kino;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lines a subtitle file up with the video's speech (ffsubsync's idea, small): the player measures how loud the dialogue
 * band is every 0.1 s (SpeechMeter); the file's lines are tried at every shift up to 4 minutes either way, and the shift
 * where the lines sit on the loud parts wins, when it clearly beats every other. Plain Java (test/SubSyncTest.java).
 */
final class SubSync {
    static final int BIN_MS = 100, RANGE_MS = 240_000;

    /** Start and end (ms) of every line in an .srt, .vtt or .ass file. */
    static List<long[]> cues(File f) throws IOException {
        return cues(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
    }

    static final Pattern ARROW = Pattern.compile("(\\d+):(\\d{2}):(\\d{2})[,.](\\d{1,3})\\s*-->\\s*(\\d+):(\\d{2}):(\\d{2})[,.](\\d{1,3})");
    static final Pattern SHORT_ARROW = Pattern.compile("(?m)^(\\d{2}):(\\d{2})[.](\\d{3})\\s*-->\\s*(\\d{2}):(\\d{2})[.](\\d{3})");   // WebVTT mm:ss.ttt
    static final Pattern ASS = Pattern.compile("(?m)^Dialogue:\\s*[^,]*,(\\d+):(\\d{2}):(\\d{2})\\.(\\d{2}),(\\d+):(\\d{2}):(\\d{2})\\.(\\d{2}),");

    static List<long[]> cues(String text) {
        List<long[]> out = new ArrayList<>();
        Matcher m = ARROW.matcher(text);
        while (m.find()) out.add(new long[]{ms(m, 1), ms(m, 5)});
        if (out.isEmpty()) {
            Matcher v = SHORT_ARROW.matcher(text);
            while (v.find()) out.add(new long[]{(Long.parseLong(v.group(1)) * 60 + Long.parseLong(v.group(2))) * 1000 + Long.parseLong(v.group(3)),
                (Long.parseLong(v.group(4)) * 60 + Long.parseLong(v.group(5))) * 1000 + Long.parseLong(v.group(6))});
        }
        Matcher a = ASS.matcher(text);
        while (a.find()) out.add(new long[]{ms(a, 1), ms(a, 5)});
        return out;
    }

    private static long ms(Matcher m, int g) {
        String frac = m.group(g + 3);
        long f = Long.parseLong(frac) * (frac.length() == 1 ? 100 : frac.length() == 2 ? 10 : 1);
        return ((Long.parseLong(m.group(g)) * 60 + Long.parseLong(m.group(g + 1))) * 60 + Long.parseLong(m.group(g + 2))) * 1000 + f;
    }

    /**
     * The shift (ms; positive = show the lines later) that puts the lines on the speech, or null when unsure: too little
     * measured, too few lines in it, or no clear winner. level[i] = loudness of the dialogue band in 0.1 s bin i (from the
     * start of the video), negative = not measured (not played yet).
     */
    static Long offset(float[] level, List<long[]> cues) {
        int n = level.length, measured = 0;
        double sum = 0, sq = 0;
        for (float v : level) if (v >= 0) { double l = Math.log(v + 1e-9); measured++; sum += l; sq += l * l; }
        if (measured < 900) return null;                       // 90 s of sound at least
        double mean = sum / measured, sd = Math.sqrt(Math.max(1e-12, sq / measured - mean * mean));
        int[] bins = new int[measured];
        float[] z = new float[measured];
        for (int i = 0, k = 0; i < n; i++) if (level[i] >= 0) { bins[k] = i; z[k++] = (float) ((Math.log(level[i] + 1e-9) - mean) / sd); }

        long last = 0;
        for (long[] c : cues) last = Math.max(last, c[1]);
        int span = (int) (last / BIN_MS) + 2;
        boolean[] on = new boolean[span];
        for (long[] c : cues) for (long t = c[0] / BIN_MS; t <= c[1] / BIN_MS && t < span; t++) if (t >= 0) on[(int) t] = true;

        int range = RANGE_MS / BIN_MS;
        double[] score = new double[2 * range + 1];
        int[] count = new int[2 * range + 1];
        for (int d = -range; d <= range; d++) {
            double s = 0;
            int c = 0;
            for (int k = 0; k < measured; k++) {
                int t = bins[k] - d;   // with the lines moved d later, video bin k shows the line timed at k - d
                if (t >= 0 && t < span && on[t]) { s += z[k]; c++; }
            }
            score[d + range] = c > 0 ? s / Math.sqrt(c) : 0;
            count[d + range] = c;
        }
        int best = 0;
        for (int i = 1; i < score.length; i++) if (score[i] > score[best]) best = i;
        if (count[best] < 150 || score[best] <= 0) return null;   // at least 15 s of lines over the measured part
        double second = 0;
        for (int i = 0; i < score.length; i++) if (Math.abs(i - best) > 20 && score[i] > second) second = score[i];   // 2 s away
        if (score[best] < 1.25 * second) return null;
        return (long) (best - range) * BIN_MS;
    }
}
