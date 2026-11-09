// javac -d /tmp/cats src/com/rainkingx/kino/Cats.java test/CatsTest.java && java -cp /tmp/cats com.rainkingx.kino.CatsTest
package com.rainkingx.kino;

import java.util.Arrays;
import java.util.List;

public class CatsTest {
    static int fails;
    static void check(String name, List<String> got, String... want) {
        if (!got.equals(Arrays.asList(want))) { fails++; System.out.println("FAIL " + name + ": got " + got + " want " + Arrays.toString(want)); }
    }
    static List<String> of(String section, String... tags) { return Cats.of(section, Arrays.asList(tags)); }

    public static void main(String[] a) {
        check("aggregator movies tv anime", of("Stream Aggregators", "Movies", "TV", "Anime", "Auto-Next"), "Movies", "TV", "Anime");
        check("p-stream fork", of("P-Stream Forks", "Movies", "TV", "Anime", "Auto-Next", "4K"), "Movies", "TV", "Anime");
        check("multi-server movies only", of("Multi-Server", "Movies", "Auto-Next"), "Movies");
        check("tubi", of("Free w/ Ads", "Movies", "TV", "720p"), "Movies", "TV");
        check("anime section ignores tags", of("Anime Streaming", "Sub", "Dub"), "Anime");
        check("live sports", of("Live Sports", "Live Events"), "Sports");
        check("sports replays", of("Sports Replays", "Football Replays"), "Sports");
        check("live tv with sports", of("Live TV", "TV", "Sports", "Aggregator"), "Live TV", "Sports");
        check("live tv channels", of("Live TV", "TV Channel Index"), "Live TV");
        check("cartoon site", of("Cartoon Streaming", "TV", "Movies", "Anime"), "TV", "Movies", "Anime");
        check("tv streaming", of("TV Streaming", "TV", "3rd Party Hosts"), "TV");
        check("drama no tags movies", of("Drama Streaming", "TV", "Movies"), "TV", "Movies");
        check("classics", of("Classics / Public Domain", "Classic Films"), "Movies");
        check("film archive no tags", of("Film Archives"), "Movies");
        check("lounge", of("Stream Lounges", "TV"), "More");
        check("aggregator no tags", of("Stream Aggregators"), "More");
        check("download section ignored", of("Download Sites", "Movies"));
        System.out.println(fails == 0 ? "all category expectations pass" : fails + " FAILED");
        System.exit(fails == 0 ? 0 : 1);
    }
}
