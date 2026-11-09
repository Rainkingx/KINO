// Needs a renderer on the network (scratchpad dlna/renderer.py fakes one). From the project folder:
// javac -nowarn -cp $SDK/platforms/android-34/android.jar:<org.json jar>:build -sourcepath src:build -d /tmp/dlna src/com/rainkingx/kino/Dlna.java test/DlnaTest.java
// java -cp /tmp/dlna:<org.json jar> com.rainkingx.kino.DlnaTest
package com.rainkingx.kino;

import java.util.Map;
import java.util.TreeMap;

public class DlnaTest {
    static int fails;
    static void check(String name, boolean ok) { if (!ok) { fails++; System.out.println("FAIL " + name); } }

    public static void main(String[] a) throws Exception {
        check("clock", Dlna.clock(3723000).equals("1:02:03") && Dlna.ms("1:02:03") == 3723000 && Dlna.ms("0:00:01.500") == 1500 && Dlna.ms("NOT_IMPLEMENTED") == 0);
        check("header", "http://x/d.xml".equals(Dlna.header("HTTP/1.1 200 OK\r\nLocation: http://x/d.xml\r\n\r\n", "LOCATION")));
        String desc = "<root><URLBase>http://10.0.0.5:9000/</URLBase><device><friendlyName>Living &amp; Room</friendlyName><serviceList>"
            + "<service><serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType><controlURL>/upnp/avt</controlURL></service>"
            + "<service><serviceType>urn:schemas-upnp-org:service:RenderingControl:2</serviceType><controlURL>rc</controlURL></service></serviceList></device></root>";
        String[] d = Dlna.describe("http://10.0.0.5:8000/desc.xml", desc);
        check("describe", d != null && d[0].equals("Living & Room") && d[1].equals("http://10.0.0.5:9000/upnp/avt") && d[2].equals("http://10.0.0.5:9000/rc"));
        check("no AVTransport, not a renderer", Dlna.describe("http://h/d.xml", "<root><friendlyName>x</friendlyName></root>") == null);
        check("didl escapes", Dlna.didl("A & B", "http://h/p?a=1&b=2", "video/mp4").contains("A &amp; B") && Dlna.didl("t", "http://h/p?a=1&b=2", "video/mp4").contains("a=1&amp;b=2"));

        Map<String, String> found = new TreeMap<>();
        Dlna.search(found, found, () -> { });
        System.out.println("found " + found);
        String target = found.get("Test Renderer & Co");
        check("SSDP finds the fake renderer", target != null);
        if (target != null) {
            Dlna r = new Dlna(target);
            Dlna.soap(r.avt, Dlna.AVT, "SetAVTransportURI", "<InstanceID>0</InstanceID><CurrentURI>" + Dlna.escape("https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8")
                + "</CurrentURI><CurrentURIMetaData>" + Dlna.escape(Dlna.didl("Test", "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8", "application/x-mpegURL")) + "</CurrentURIMetaData>");
            Dlna.soap(r.avt, Dlna.AVT, "Play", "<InstanceID>0</InstanceID><Speed>1</Speed>");
            check("playing", r.state().equals("PLAYING"));
            r.send("seek", 125000);
            Thread.sleep(1200);
            long pos = Dlna.ms(Dlna.text(Dlna.soap(r.avt, Dlna.AVT, "GetPositionInfo", "<InstanceID>0</InstanceID>"), "RelTime"));
            check("seek to 2:05 (" + pos + ")", pos >= 125000 && pos <= 128000);
            r.send("toggle", 0);
            check("paused", r.state().equals("PAUSED_PLAYBACK"));
            String vol = "<InstanceID>0</InstanceID><Channel>Master</Channel>";
            int before = Integer.parseInt(Dlna.text(Dlna.soap(r.rc, Dlna.RC, "GetVolume", vol), "CurrentVolume"));
            r.send("vol+", 0);
            int after = Integer.parseInt(Dlna.text(Dlna.soap(r.rc, Dlna.RC, "GetVolume", vol), "CurrentVolume"));
            check("volume up by 5 (" + before + " -> " + after + ")", after == Math.min(100, before + 5));
            r.send("stop", 0);
            check("stopped", r.state().equals("STOPPED"));
        }
        System.out.println(fails == 0 ? "dlna tests passed" : fails + " failed");
        System.exit(fails == 0 ? 0 : 1);
    }
}
