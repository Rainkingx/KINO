// SDK=~/Library/Android/sdk; org.json from maven (android.jar only has stubs):
// javac -implicit:none -cp "$SDK/platforms/android-34/android.jar:$(ls libs/*.jar | tr '\n' ':')json.jar" -sourcepath src -d /tmp/skips src/com/rainkingx/kino/Skips.java test/SkipsTest.java && java -cp /tmp/skips:json.jar com.rainkingx.kino.SkipsTest
package com.rainkingx.kino;

import java.util.List;

public class SkipsTest {
    static int fails;

    static void check(String name, Object got, Object want) {
        if (!got.equals(want)) { fails++; System.out.println("FAIL " + name + ": got " + got + " want " + want); }
    }

    public static void main(String[] a) {
        List<Skips.Part> p = Skips.introDb("{\"tmdb_id\":209867,\"intro\":[{\"start_ms\":null,\"end_ms\":90044}],\"credits\":[{\"start_ms\":1370109,\"end_ms\":1460083}],\"preview\":[{\"start_ms\":1460304,\"end_ms\":null}]}");
        check("introdb parts", p.size(), 3);
        check("intro from the start", p.get(0).start, 0L);
        check("intro kind", p.get(0).kind, "intro");
        check("preview to the end", p.get(2).end, -1L);
        check("inside the preview near the end", p.get(2).has(1_469_000, 1_470_000), true);
        check("not inside the intro after it", p.get(0).has(90_044, 1_470_000), false);
        List<Skips.Part> s = Skips.aniSkip("{\"found\":true,\"results\":[{\"interval\":{\"startTime\":1.35,\"endTime\":91.35},\"skipType\":\"op\"},{\"interval\":{\"startTime\":1367,\"endTime\":1459},\"skipType\":\"mixed-ed\"}]}");
        check("aniskip parts", s.size(), 2);
        check("aniskip op is the intro", s.get(0).kind + s.get(0).start + "-" + s.get(0).end, "intro1350-91350");
        check("aniskip mixed-ed is the credits", s.get(1).kind, "credits");
        check("nothing for nothing", Skips.introDb(null).size() + Skips.aniSkip("not json").size(), 0);
        // TheIntroDB, Death Note S1E2: the opening titles are listed as credits too
        List<Skips.Part> dn = Skips.introDb("{\"intro\":[{\"start_ms\":null,\"end_ms\":85336}],\"credits\":[{\"start_ms\":20,\"end_ms\":123018},{\"start_ms\":1290659,\"end_ms\":1355494}]}");
        check("opening titles are not credits", dn.get(1).has(100_000, 1_377_000), false);
        check("the real credits are", dn.get(2).has(1_300_000, 1_377_000), true);
        check("the intro still counts", dn.get(0).has(10_000, 1_377_000), true);
        System.out.println(fails == 0 ? "all passed" : fails + " failed");
        if (fails > 0) System.exit(1);
    }
}
