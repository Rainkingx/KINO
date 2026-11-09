// javac -d /tmp/subsync src/com/rainkingx/kino/SubSync.java test/SubSyncTest.java && java -cp /tmp/subsync com.rainkingx.kino.SubSyncTest
package com.rainkingx.kino;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

public class SubSyncTest {
    static int fails;

    static void check(String name, Object got, Object want) {
        if (!String.valueOf(got).equals(String.valueOf(want))) { fails++; System.out.println("FAIL " + name + ": got " + got + " want " + want); }
    }

    /** Speech where the lines are, shifted by `shift` ms (the file is late by -shift), plus noise and some loud music. */
    static float[] level(List<long[]> cues, long shift, long playedMs, Random r) {
        float[] l = new float[30 * 60 * 10];
        Arrays.fill(l, -1);
        for (int i = 0; i < playedMs / 100; i++) l[i] = 0.002f + r.nextFloat() * 0.01f;
        for (long[] c : cues) for (long t = (c[0] + shift) / 100; t <= (c[1] + shift) / 100; t++) if (t >= 0 && t < playedMs / 100) l[(int) t] = 0.05f + r.nextFloat() * 0.08f;
        for (int i = 0; i < 600 && i < playedMs / 100; i++) l[(int) (r.nextInt((int) (playedMs / 100)))] = 0.2f;   // bangs and music
        return l;
    }

    public static void main(String[] a) {
        String srt = "1\n00:00:01,120 --> 00:00:04,189\nhello\n\n2\n01:02:03,5 --> 01:02:04,250\nbye\n";
        List<long[]> c = SubSync.cues(srt);
        check("srt lines", c.size(), 2);
        check("srt times", c.get(1)[0] + "-" + c.get(1)[1], "3723500-3724250");
        check("vtt short times", SubSync.cues("WEBVTT\n\n01:02.500 --> 01:04.000\nx\n").get(0)[0], 62500);
        check("ass times", SubSync.cues("[Events]\nDialogue: 0,0:00:05.12,0:00:07.00,Default,,0,0,0,,Hi\n").get(0)[0], 5120);

        Random r = new Random(7);
        List<long[]> cues = new ArrayList<>();
        for (long t = 150_000; t < 40 * 60_000; ) { long len = 800 + r.nextInt(3500); cues.add(new long[]{t, t + len}); t += len + 300 + r.nextInt(6000); }
        // the file is 1:52 late (its release had a recap): the lines must come 112 s earlier
        Long d = SubSync.offset(level(cues, -112_000, 6 * 60_000, r), cues);
        check("finds -112 s", d == null ? null : Math.abs(d + 112_000) <= 200, true);
        Long e = SubSync.offset(level(cues, 2_300, 5 * 60_000, r), cues);
        check("finds +2.3 s", e == null ? null : Math.abs(e - 2_300) <= 200, true);
        check("too little measured", SubSync.offset(level(cues, 0, 40_000, r), cues), null);
        float[] noise = new float[3 * 60 * 10];
        for (int i = 0; i < noise.length; i++) noise[i] = r.nextFloat();
        check("no speech pattern, no guess", SubSync.offset(noise, cues), null);
        System.out.println(fails == 0 ? "all passed" : fails + " failed");
        if (fails > 0) System.exit(1);
    }
}
