package com.rainkingx.kino;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * DLNA / UPnP media renderers (smart TVs from Samsung, LG, Sony and others, Kodi, some speakers): found with an SSDP search,
 * driven over SOAP (AVTransport for play/pause/seek/stop, RenderingControl for volume). Like Chromecast, the TV can't send a
 * site's headers, so the stream goes through the phone's relay (Chromecast.Relay). Renderers that can't play HLS won't play
 * HLS streams; plain files and MPEG-TS streams work on most.
 */
final class Dlna implements Cast.Tv {
    static final String AVT = "urn:schemas-upnp-org:service:AVTransport:1", RC = "urn:schemas-upnp-org:service:RenderingControl:1";

    // ---- finding renderers ----

    /** One SSDP search (about 4 s): every renderer that answers is described and put into `into` as name -> "avtUrl|rcUrl". */
    static void search(Map<String, String> into, Object lock, Runnable changed) {
        Set<String> seen = new HashSet<>();
        try (DatagramSocket s = new DatagramSocket()) {
            s.setSoTimeout(800);
            byte[] m = ("M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\n"
                + "ST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n\r\n").getBytes(StandardCharsets.US_ASCII);
            InetSocketAddress group = new InetSocketAddress(InetAddress.getByName("239.255.255.250"), 1900);
            long end = System.currentTimeMillis() + 4000;
            int sent = 0;
            byte[] buf = new byte[4096];
            while (System.currentTimeMillis() < end) {
                if (sent < 3 && System.currentTimeMillis() > end - 4000 + sent * 1000L) { s.send(new DatagramPacket(m, m.length, group)); sent++; }
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                try { s.receive(p); } catch (SocketTimeoutException e) { continue; }
                String location = header(new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8), "LOCATION");
                if (location == null || !seen.add(location)) continue;
                try {
                    String[] r = describe(location, get(location));
                    if (r == null) continue;
                    synchronized (lock) { into.put(r[0], r[1] + "|" + r[2]); }
                    changed.run();
                } catch (IOException ignored) { }
            }
        } catch (IOException ignored) { }
    }

    static String header(String response, String name) {
        for (String line : response.split("\r?\n")) {
            int c = line.indexOf(':');
            if (c > 0 && line.substring(0, c).trim().equalsIgnoreCase(name)) return line.substring(c + 1).trim();
        }
        return null;
    }

    /** {friendly name, AVTransport control URL, RenderingControl control URL (may be "")} from a device description, or null. */
    static String[] describe(String location, String xml) throws IOException {
        String name = text(xml, "friendlyName");
        String base = text(xml, "URLBase");
        URL at = new URL(base != null && !base.isEmpty() ? base : location);
        String avt = null, rc = "";
        Matcher m = Pattern.compile("<(?:\\w+:)?service>(.*?)</(?:\\w+:)?service>", Pattern.DOTALL).matcher(xml);
        while (m.find()) {
            String type = text(m.group(1), "serviceType"), control = text(m.group(1), "controlURL");
            if (type == null || control == null) continue;
            if (type.startsWith("urn:schemas-upnp-org:service:AVTransport:")) avt = new URL(at, control).toString();
            else if (type.startsWith("urn:schemas-upnp-org:service:RenderingControl:")) rc = new URL(at, control).toString();
        }
        if (avt == null) return null;
        return new String[]{name == null || name.isEmpty() ? new URL(location).getHost() : name, avt, rc};
    }

    /** The text of the first <name> (any namespace prefix), XML entities decoded. */
    static String text(String xml, String name) {
        Matcher m = Pattern.compile("<(?:\\w+:)?" + name + "(?:\\s[^>]*)?>(.*?)</(?:\\w+:)?" + name + ">", Pattern.DOTALL).matcher(xml);
        return m.find() ? unescape(m.group(1).trim()) : null;
    }

    static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    static String unescape(String s) {
        return s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&");
    }

    static String get(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection(Proxy.NO_PROXY);
        c.setConnectTimeout(3000);
        c.setReadTimeout(4000);
        try (InputStream in = c.getInputStream()) { return read(in); } finally { c.disconnect(); }
    }

    static String read(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        for (int n; (n = in.read(buf)) > 0; ) b.write(buf, 0, n);
        return b.toString("UTF-8");
    }

    /** One SOAP action; returns the response body, throws with the device's fault text on an error. */
    static String soap(String control, String service, String action, String args) throws IOException {
        String body = "<?xml version=\"1.0\" encoding=\"utf-8\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" "
            + "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body><u:" + action + " xmlns:u=\"" + service + "\">"
            + args + "</u:" + action + "></s:Body></s:Envelope>";
        HttpURLConnection c = (HttpURLConnection) new URL(control).openConnection(Proxy.NO_PROXY);
        c.setConnectTimeout(4000);
        c.setReadTimeout(8000);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"");
        c.setRequestProperty("SOAPAction", "\"" + service + "#" + action + "\"");
        try (OutputStream o = c.getOutputStream()) { o.write(body.getBytes(StandardCharsets.UTF_8)); }
        int code = c.getResponseCode();
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        String r = in == null ? "" : read(in);
        c.disconnect();
        if (code >= 400) {
            String why = text(r, "errorDescription");
            throw new IOException(action + " failed (" + code + (why != null ? ", " + why : "") + ")");
        }
        return r;
    }

    /** DIDL-Lite metadata for SetAVTransportURI: the title and the stream with its type, which many TVs need to accept it. */
    static String didl(String title, String url, String mime) {
        return "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\" "
            + "xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\"><item id=\"0\" parentID=\"-1\" restricted=\"1\"><dc:title>"
            + escape(title) + "</dc:title><upnp:class>object.item.videoItem</upnp:class><res protocolInfo=\"http-get:*:" + mime
            + ":*\">" + escape(url) + "</res></item></DIDL-Lite>";
    }

    static String clock(long ms) {
        long s = Math.max(0, ms / 1000);
        return String.format(Locale.US, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60);
    }

    /** "1:02:03" (also with fractions, or NOT_IMPLEMENTED) to milliseconds; 0 when unknown. */
    static long ms(String time) {
        if (time == null || !time.matches("\\d+:\\d{1,2}:\\d{1,2}(\\.\\d+)?")) return 0;
        String[] p = time.split(":");
        return (long) ((Long.parseLong(p[0]) * 3600 + Long.parseLong(p[1]) * 60 + Double.parseDouble(p[2])) * 1000);
    }

    // ---- one cast ----

    final String avt, rc;
    String title = "";
    boolean live, started;
    volatile boolean closed;
    int volume = -1;
    Chromecast.Relay relay;

    /** target = "avtUrl|rcUrl" as search() stored it */
    Dlna(String target) {
        int bar = target.indexOf('|');
        avt = target.substring(0, bar);
        rc = target.substring(bar + 1);
    }

    @Override public String start(JSONObject v) throws Exception {
        title = v.optString("title");
        live = v.optBoolean("live");
        Map<String, String> headers = new HashMap<>();
        JSONObject h = new JSONObject(v.optString("headers", "{}"));
        for (Iterator<String> it = h.keys(); it.hasNext(); ) { String k = it.next(); headers.put(k, h.getString(k)); }
        // The phone address the TV can reach: the one a socket towards the TV would use
        InetAddress local;
        try (DatagramSocket probe = new DatagramSocket()) {
            probe.connect(InetAddress.getByName(new URL(avt).getHost()), 9);
            local = probe.getLocalAddress();
        }
        relay = new Chromecast.Relay(local, headers);
        String url = relay.wrap(v.getString("url"));
        String mime = Chromecast.type(v, v.getString("url"));
        try {
            soap(avt, AVT, "Stop", "<InstanceID>0</InstanceID>");   // some TVs refuse a new URI while something is loaded
        } catch (IOException ignored) { }
        try {
            soap(avt, AVT, "SetAVTransportURI", "<InstanceID>0</InstanceID><CurrentURI>" + escape(url) + "</CurrentURI><CurrentURIMetaData>"
                + escape(didl(title, url, mime)) + "</CurrentURIMetaData>");
            soap(avt, AVT, "Play", "<InstanceID>0</InstanceID><Speed>1</Speed>");
        } catch (IOException e) {
            close();
            return App.t(R.string.cast_dlna_refused);
        }
        Chromecast.awake(true);
        long at = v.optLong("at");
        if (!live && at > 10000) new Thread(() -> {
            // TVs only seek once the video has loaded
            for (int n = 0; n < 20 && !closed; n++) {
                try {
                    Thread.sleep(1000);
                    if (state().equals("PLAYING")) { send("seek", at); return; }
                } catch (Exception ignored) { }
            }
        }, "dlna-seek").start();
        return null;
    }

    String state() throws IOException {
        String s = text(soap(avt, AVT, "GetTransportInfo", "<InstanceID>0</InstanceID>"), "CurrentTransportState");
        return s == null ? "" : s;
    }

    @Override public JSONObject status() throws Exception {
        if (closed) return new JSONObject().put("idle", true);
        String st = state();
        if (st.equals("PLAYING") || st.equals("PAUSED_PLAYBACK")) started = true;
        if (started && (st.equals("STOPPED") || st.equals("NO_MEDIA_PRESENT"))) return new JSONObject().put("idle", true);
        if (!started) return new JSONObject().put("loading", true);
        String p = soap(avt, AVT, "GetPositionInfo", "<InstanceID>0</InstanceID>");
        long dur = ms(text(p, "TrackDuration"));
        return new JSONObject().put("title", title).put("pos", ms(text(p, "RelTime"))).put("dur", live ? 0 : dur)
            .put("playing", !st.equals("PAUSED_PLAYBACK")).put("live", live || dur == 0);
    }

    @Override public void send(String a, long to) throws Exception {
        switch (a) {
            case "toggle":
                if (state().equals("PLAYING")) soap(avt, AVT, "Pause", "<InstanceID>0</InstanceID>");
                else soap(avt, AVT, "Play", "<InstanceID>0</InstanceID><Speed>1</Speed>");
                break;
            case "seek": soap(avt, AVT, "Seek", "<InstanceID>0</InstanceID><Unit>REL_TIME</Unit><Target>" + clock(to) + "</Target>"); break;
            case "stop": soap(avt, AVT, "Stop", "<InstanceID>0</InstanceID>"); break;
            case "vol+": case "vol-":
                if (rc.isEmpty()) return;
                if (volume < 0) {
                    String v = text(soap(rc, RC, "GetVolume", "<InstanceID>0</InstanceID><Channel>Master</Channel>"), "CurrentVolume");
                    volume = v == null ? 20 : Integer.parseInt(v.trim());
                }
                volume = Math.max(0, Math.min(100, volume + (a.equals("vol+") ? 5 : -5)));
                soap(rc, RC, "SetVolume", "<InstanceID>0</InstanceID><Channel>Master</Channel><DesiredVolume>" + volume + "</DesiredVolume>");
                break;
        }
    }

    @Override public void close() {
        closed = true;
        if (relay != null) relay.close();
        Chromecast.awake(false);
    }
}
