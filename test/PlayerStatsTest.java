// SDK=~/Library/Android/sdk:
// javac -implicit:none -cp "$SDK/platforms/android-34/android.jar:$(ls libs/*.jar | tr '\n' ':')" -sourcepath src:build -d /tmp/pstats src/com/rainkingx/kino/PlayerStats.java test/PlayerStatsTest.java && java -cp "/tmp/pstats:$(ls libs/*.jar | tr '\n' ':')" com.rainkingx.kino.PlayerStatsTest
package com.rainkingx.kino;

public class PlayerStatsTest {
    static int fails;

    static void check(String name, Object got, Object want) {
        if (!got.equals(want)) { fails++; System.out.println("FAIL " + name + ": got " + got + " want " + want); }
    }

    public static void main(String[] a) {
        check("bits mbps", PlayerStats.bits(4_200_000), "4.2 Mbps");
        check("bits kbps", PlayerStats.bits(128_000), "128 kbps");
        check("bits small", PlayerStats.bits(900), "900 bps");
        check("bits unknown", PlayerStats.bits(-1), "?");
        check("pct", PlayerStats.pct(3, 1200), "0.3%");
        check("pct of nothing", PlayerStats.pct(0, 0), "0%");
        check("pct all", PlayerStats.pct(5, 5), "100.0%");
        check("bytes mb", PlayerStats.bytes(123_456_789), "117.7 MB");
        check("bytes kb", PlayerStats.bytes(2048), "2 KB");
        check("bytes gb", PlayerStats.bytes(3L << 30), "3.00 GB");
        check("trim fps", PlayerStats.trim(23.976f), "23.98");
        check("trim whole", PlayerStats.trim(24f), "24");
        check("trim one", PlayerStats.trim(1f), "1");
        check("h264", PlayerStats.codec("video/avc"), "H.264");
        check("unknown codec", PlayerStats.codec("video/x-foo"), "x-foo");
        check("null codec", PlayerStats.codec(null), "?");
        check("google decoder is software", PlayerStats.software("c2.android.avc.decoder"), true);
        check("vendor decoder is hardware", PlayerStats.software("c2.qti.avc.decoder"), false);
        check("samsung sw", PlayerStats.software("OMX.SEC.avc.sw.dec"), true);
        check("no colour info is SDR", PlayerStats.range(null), "SDR");
        System.out.println(fails == 0 ? "ok" : fails + " failed");
        if (fails > 0) System.exit(1);
    }
}
