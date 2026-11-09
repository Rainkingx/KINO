// Runs Stalker.java against the fake portal test/fake_stalker.py (started on port 8013 by this test). From the project folder:
// JSON=~/.gradle/caches/modules-2/files-2.1/org.json/json/20240303/ebb88e8fb5122b7506d5cf1d69f1ccdb790d22a/json-20240303.jar
// javac -cp $JSON -d /tmp/stalker src/com/rainkingx/kino/Stalker.java test/StalkerTest.java && java -cp /tmp/stalker:$JSON com.rainkingx.kino.StalkerTest
package com.rainkingx.kino;

import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;

public class StalkerTest {
    static int fails;

    static void check(String name, Object got, Object want) {
        if (got == null ? want != null : !got.equals(want)) { fails++; System.out.println("FAIL " + name + ": got " + got + " want " + want); }
    }

    static String get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection(Proxy.NO_PROXY);
        java.util.Scanner s = new java.util.Scanner(c.getInputStream(), "UTF-8").useDelimiter("\\A");
        return s.hasNext() ? s.next() : "";
    }

    static int handshakes() throws Exception {
        return new org.json.JSONObject(get("http://127.0.0.1:8013/stats")).getInt("handshakes");
    }

    public static void main(String[] a) throws Exception {
        // The account as typed: the MAC is checked and normalised, the portal trimmed to its base
        String mac = "00:1A:79:12:34:56";
        check("portal /c/", Stalker.account("http://host.tv:8080/c/", mac), "http://host.tv:8080/c/?mac=" + mac);
        check("bare host, lower-case MAC with dashes", Stalker.account(" host.tv:8080 ", "00-1a-79-12-34-56"), "http://host.tv:8080/c/?mac=" + mac);
        check("stalker_portal/c/index.html", Stalker.account("http://host.tv/stalker_portal/c/index.html", mac), "http://host.tv/stalker_portal/c/?mac=" + mac);
        check("API address typed", Stalker.account("https://host.tv/portal.php?x=1", mac), "https://host.tv/c/?mac=" + mac);
        check("short MAC refused", Stalker.account("host.tv", "00:1A:79:12:34"), null);
        check("not hex refused", Stalker.account("host.tv", "00:1A:79:12:34:5G"), null);
        check("prefilled only refused", Stalker.account("host.tv", "00:1A:79:"), null);
        check("is an account", Stalker.isAccount("http://host.tv:8080/c/?mac=" + mac), true);
        check("a playlist is not", Stalker.isAccount("http://host.tv/get.php?username=a&password=b"), false);
        check("strip ffmpeg", Stalker.strip("ffmpeg http://a/b.ts"), "http://a/b.ts");
        check("strip ffrt", Stalker.strip(" ffrt http://a/ch/1 "), "http://a/ch/1");
        check("plain address kept", Stalker.strip("http://a/b c"), "http://a/b c");

        Process server = new ProcessBuilder("python3", "test/fake_stalker.py", "8013").inheritIO().start();
        try {
            for (int i = 0; i < 50; i++) { try { get("http://127.0.0.1:8013/stats"); break; } catch (java.io.IOException e) { Thread.sleep(100); } }
            Proxy none = Proxy.NO_PROXY;

            // Xtream-UI style portal at /portal.php, typed as http://127.0.0.1:8013/c/
            String xui = Stalker.account("http://127.0.0.1:8013/c/", mac);
            String m3u = Stalker.playlist(xui, none);
            check("xui playlist", m3u != null, true);
            String[] lines = m3u == null ? new String[0] : m3u.split("\n");
            check("xui: header + 3 channels", lines.length, 7);
            check("xui: first channel", lines.length > 1 ? lines[1] : "", "#EXTINF:-1 tvg-logo=\"http://img.example/news1.png\" group-title=\"News\",News One");
            check("xui: placeholder", lines.length > 2 ? lines[2] : "", "stalker:" + xui + "#ffmpeg+http%3A%2F%2Flocalhost%2Fch%2F11_");
            check("xui: quotes in a genre", lines.length > 3 && lines[3].contains("group-title=\"Sports 'Live'\""), true);
            check("xui: unknown genre is Live", lines.length > 5 && lines[5].contains("group-title=\"Live\""), true);
            check("xui: API found", Stalker.sessions.get(xui)[0], "http://127.0.0.1:8013/portal.php");

            String link = Stalker.link(lines[2], none);
            check("create_link gives a playable link", link != null && link.matches("http://127\\.0\\.0\\.1:8013/play/live\\.php\\?mac=00:1A:79:12:34:56&stream=11&extension=ts&play_token=\\w+"), true);
            check("the link plays", get(link).startsWith("GGG"), true);

            // The token runs out: one new handshake, then the link comes
            int before = handshakes();
            get("http://127.0.0.1:8013/expire");
            String again = Stalker.link(lines[4], none);
            check("after expiry: a link", again != null && again.contains("stream=12&"), true);
            check("after expiry: one new handshake", handshakes() - before, 1);
            check("no handshake while the token works", Stalker.link(lines[4], none) != null && handshakes() - before == 1, true);

            // The portal makes no link: the channel's own address is used, but never a localhost placeholder
            check("fallback to the cmd's address", Stalker.link(lines[6], none), "http://cdn.example/live/13.ts");
            check("unknown cmd with a localhost address", Stalker.link("stalker:" + xui + "#ffmpeg+http%3A%2F%2Flocalhost%2Fch%2F999_", none), null);

            // Ministra under /ministra/stalker_portal: no get_all_channels, so paged; relative logos
            String min = Stalker.account("http://127.0.0.1:8013/ministra/stalker_portal/c/", mac);
            String m2 = Stalker.playlist(min, none);
            String[] l2 = m2 == null ? new String[0] : m2.split("\n");
            check("ministra: 3 channels over 2 pages", l2.length, 7);
            check("ministra: API found", Stalker.sessions.get(min) == null ? null : Stalker.sessions.get(min)[0], "http://127.0.0.1:8013/ministra/stalker_portal/server/load.php");
            check("ministra: relative logo", l2.length > 1 ? l2[1] : "", "#EXTINF:-1 tvg-logo=\"http://127.0.0.1:8013/ministra/stalker_portal/misc/logos/320/21.png\" group-title=\"News\",Ministra A");
            String ml = l2.length > 6 ? Stalker.link(l2[6], none) : null;
            check("ministra: link", ml != null && ml.contains("stream=23&"), true);

            // A MAC the portal doesn't know, and a portal that isn't there
            check("unknown MAC: can't sign in", Stalker.playlist(Stalker.account("127.0.0.1:8013", "00:1A:79:00:00:01"), none), null);
            check("no portal: can't sign in", Stalker.playlist(Stalker.account("127.0.0.1:8014", mac), none), null);
        } finally {
            server.destroy();
        }
        System.out.println(fails == 0 ? "OK, all Stalker checks passed" : fails + " failed");
        System.exit(fails == 0 ? 0 : 1);
    }
}
