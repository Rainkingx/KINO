package com.rainkingx.kino;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stalker / Ministra portals (MAG boxes, MAC-address login). The account is kept with your playlists as "portal/c/?mac=..";
 * its live channels become an M3U whose links are "stalker:<account>#<cmd>", turned into a real link right before each play.
 * Plain Java (no Android) so test/StalkerTest.java runs it against a fake portal.
 */
final class Stalker {
    static final String SCHEME = "stalker:";
    static final String UA = "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 (KHTML, like Gecko) MAG200 stbapp ver: 2 rev: 250 Safari/533.3";
    // account to {api address, token}, from the last handshake
    static final Map<String, String[]> sessions = new ConcurrentHashMap<>();

    /** The account as stored with the playlists, or null when the MAC is not six hex pairs. */
    static String account(String portal, String mac) {
        String m = mac.trim().replace('-', ':').toUpperCase(Locale.ROOT);
        if (!m.matches("([0-9A-F]{2}:){5}[0-9A-F]{2}")) return null;
        // As people type it: host, http://host:port/c/, http://host/stalker_portal/c/index.html, or the API address itself
        String base = portal.trim().replaceFirst("[?#].*$", "").replaceFirst("(?i)(/c(/[^/]*)?|/portal\\.php|/server/load\\.php)?/*$", "");
        if (!base.matches("(?i)https?://.+")) base = "http://" + base;
        return base + "/c/?mac=" + m;
    }

    static boolean isAccount(String url) {
        return url.matches("(?i)https?://.+/c/\\?mac=([0-9A-F]{2}:){5}[0-9A-F]{2}");
    }

    static String mac(String account) {
        return account.substring(account.indexOf("/c/?mac=") + 8);
    }

    /** One API call; the reply's "js" (object, array or text), or null when the portal said no (bad or expired token, not JSON, HTTP error). */
    static Object call(String api, String mac, String token, String query, Proxy proxy) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(api + "?" + query + "&JsHttpRequest=1-xml").openConnection(proxy);
        c.setConnectTimeout(10000);
        c.setReadTimeout(30000);
        c.setRequestProperty("User-Agent", UA);
        c.setRequestProperty("X-User-Agent", "Model: MAG250; Link: WiFi");
        c.setRequestProperty("Referer", api.replaceFirst("(server/load|portal)\\.php$", "c/"));
        c.setRequestProperty("Cookie", "mac=" + mac + "; stb_lang=en; timezone=UTC");
        if (token != null) c.setRequestProperty("Authorization", "Bearer " + token);
        try {
            if (c.getResponseCode() >= 400) return null;
            java.util.Scanner s = new java.util.Scanner(c.getInputStream(), "UTF-8").useDelimiter("\\A");
            return new JSONObject(s.hasNext() ? s.next() : "").opt("js");   // "Authorization failed." and HTML pages land in the catch
        } catch (JSONException e) {
            return null;
        } finally {
            c.disconnect();
        }
    }

    /** Handshake (and profile) on the first API address that answers with a token; {api, token}, or null when none does. */
    static String[] login(String account, Proxy proxy) {
        String base = account.substring(0, account.indexOf("/c/?mac=")), mac = mac(account);
        for (String api : new String[]{base + "/portal.php", base + "/server/load.php", base + "/stalker_portal/server/load.php"}) {
            try {
                Object js = call(api, mac, null, "type=stb&action=handshake&token=&prehash=0", proxy);
                String token = js instanceof JSONObject ? ((JSONObject) js).optString("token") : "";
                if (token.isEmpty()) continue;
                // Some portals only start the session once the box has sent its profile; what it answers doesn't matter
                call(api, mac, token, "type=stb&action=get_profile&hd=1&stb_type=MAG250&image_version=218&num_banks=2&auth_second_step=1&not_valid_token=0", proxy);
                String[] s = {api, token};
                sessions.put(account, s);
                return s;
            } catch (IOException ignored) { }   // try the next address
        }
        return null;
    }

    /** The account's live channels as M3U (group = genre); null when it can't sign in or has no channels. */
    static String playlist(String account, Proxy proxy) throws IOException {
        String[] s = login(account, proxy);
        if (s == null) return null;
        String api = s[0], mac = mac(account);
        Map<String, String> genres = new HashMap<>();
        Object g = call(api, mac, s[1], "type=itv&action=get_genres", proxy);
        for (int i = 0; g instanceof JSONArray && i < ((JSONArray) g).length(); i++) {
            JSONObject o = ((JSONArray) g).optJSONObject(i);
            if (o != null) genres.put(o.optString("id"), o.optString("title"));
        }
        Map<String, JSONObject> chans = new LinkedHashMap<>();   // by cmd
        add(chans, call(api, mac, s[1], "type=itv&action=get_all_channels", proxy));
        // Portals without get_all_channels hand the list out a page (often 14 channels) at a time
        if (chans.isEmpty()) for (int p = 1; p <= 1000; p++) {   // ponytail: 1000 pages at most
            int before = chans.size();
            Object js = call(api, mac, s[1], "type=itv&action=get_ordered_list&genre=*&force_ch_link_check=&fav=0&sortby=number&hd=0&p=" + p, proxy);
            add(chans, js);
            int total = js instanceof JSONObject ? ((JSONObject) js).optInt("total_items", Integer.MAX_VALUE) : Integer.MAX_VALUE;
            if (chans.size() == before || chans.size() >= total) break;   // a page with nothing new also ends it (a portal that ignores p)
        }
        StringBuilder m3u = new StringBuilder("#EXTM3U\n");
        for (Map.Entry<String, JSONObject> e : chans.entrySet()) {
            JSONObject ch = e.getValue();
            String cmd = e.getKey(), logo = ch.optString("logo").trim();
            if (!logo.isEmpty() && !logo.startsWith("http")) logo = api.replaceFirst("(server/load|portal)\\.php$", "misc/logos/320/") + logo;
            String group = genres.containsKey(ch.optString("tv_genre_id")) ? genres.get(ch.optString("tv_genre_id")) : "Live";
            m3u.append("#EXTINF:-1 tvg-logo=\"").append(logo.replace("\"", "")).append("\" group-title=\"").append(group.replace("\"", "'")).append("\",")
                .append(ch.optString("name").replace("\n", " ").trim()).append('\n')
                .append(SCHEME).append(account).append('#').append(URLEncoder.encode(cmd, "UTF-8")).append('\n');
        }
        return chans.isEmpty() ? null : m3u.toString();
    }

    private static void add(Map<String, JSONObject> out, Object js) {
        JSONArray data = js instanceof JSONObject ? ((JSONObject) js).optJSONArray("data") : js instanceof JSONArray ? (JSONArray) js : null;
        for (int i = 0; data != null && i < data.length(); i++) {
            JSONObject ch = data.optJSONObject(i);
            if (ch != null && !ch.optString("cmd").trim().isEmpty()) out.putIfAbsent(ch.optString("cmd").trim(), ch);
        }
    }

    /** "ffmpeg http://.." / "ffrt http://.." / "auto http://.." to the address. */
    static String strip(String cmd) {
        return cmd.trim().replaceFirst("^\\S+\\s+(?=[a-zA-Z][\\w+.-]*://)", "");
    }

    /** A channel's playable link, made by the portal for this play (its links carry a token that runs out); null when there is none. */
    static String link(String channel, Proxy proxy) throws IOException {
        int hash = channel.indexOf('#');
        String account = channel.substring(SCHEME.length(), hash), cmd = URLDecoder.decode(channel.substring(hash + 1), "UTF-8"), mac = mac(account);
        String[] s = sessions.get(account);
        boolean fresh = s == null;
        if (fresh) s = login(account, proxy);
        while (s != null) {
            Object js = call(s[0], mac, s[1], "type=itv&action=create_link&cmd=" + URLEncoder.encode(cmd, "UTF-8") + "&series=&forced_storage=undefined&disable_ad=0&download=0", proxy);
            if (js instanceof JSONArray) js = ((JSONArray) js).optJSONObject(0);
            String url = js instanceof JSONObject ? strip(((JSONObject) js).optString("cmd")) : "";
            if (url.matches("(?i)https?://.+")) return url;
            if (fresh) break;
            s = login(account, proxy);   // the token may have run out: sign in again, once
            fresh = true;
        }
        // No link from the portal: the channel's own address, when it is a real one ("http://localhost/ch/1_" placeholders are not)
        String own = strip(cmd);
        return own.matches("(?i)https?://.+") && !own.matches("(?i)https?://(localhost|127\\.0\\.0\\.1)[:/].*") ? own : null;
    }
}
