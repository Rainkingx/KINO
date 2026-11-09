package com.rainkingx.kino;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * OpenSubtitles' legacy REST search (no key, no login): per-language subtitle lists for an IMDb title or episode, far more
 * complete than the Stremio addon's sample. Android-free; the caller supplies the connection. Files: see SubFetch.
 */
final class SubOs {
    /** The legacy API refuses requests without a User-Agent; Http implementations must send this one. */
    static final String UA = "TemporaryUserAgent";
    static final String BASE = "https://rest.opensubtitles.org/search/";
    static final int MAX_RESULTS = 100;
    static final int MAX_ANSWER = 4 * 1024 * 1024;

    /** Opens a GET (following redirects) and returns the body; the implementation sets {@link #UA} and its timeouts. */
    interface Http {
        InputStream open(String url) throws IOException;
    }

    static final class Result {
        String url, lang, release, format, encoding;
        int downloads;
        boolean hearingImpaired;
    }

    /**
     * The one form the server answers directly (any other segment order 302s to it): segments sorted by name, imdb id
     * zero-padded to 7 digits. Movies: imdbid-0133093/sublanguageid-ara. Episodes: episode-1/imdbid-0903747/season-1/sublanguageid-eng.
     */
    static String url(String imdb, int season, int episode, String lang) throws IOException {
        String digits = imdb == null ? "" : imdb.trim().replaceFirst("^(?i)tt", "");
        if (!digits.matches("\\d{1,9}")) throw new IOException("bad IMDb id: " + imdb);
        boolean ep = season > 0 || episode > 0;
        StringBuilder u = new StringBuilder(BASE);
        if (ep) u.append("episode-").append(episode).append('/');
        u.append("imdbid-").append(String.format(Locale.ROOT, "%07d", Long.parseLong(digits)));
        if (ep) u.append("/season-").append(season);
        String l = lang == null ? "" : lang.trim().toLowerCase(Locale.ROOT);
        if (!l.isEmpty()) {
            if (!l.matches("[a-z]{3}(,[a-z]{3})*")) throw new IOException("bad language: " + lang);
            u.append("/sublanguageid-").append(l);
        }
        return u.toString();
    }

    /** lang "" = all languages (the server then returns a mixed top 100). Season and episode 0,0 = a movie. */
    static List<Result> search(Http http, String imdb, int season, int episode, String lang) throws IOException {
        String body;
        try (InputStream in = http.open(url(imdb, season, episode, lang))) {
            body = new String(read(in, MAX_ANSWER, "answer"), StandardCharsets.UTF_8).trim();
        }
        if (!body.startsWith("[")) {
            String s = body.replaceAll("\\s+", " ");
            throw new IOException("not a subtitle list: " + s.substring(0, Math.min(s.length(), 80)));
        }
        JSONArray a;
        try {
            a = new JSONArray(body);
        } catch (JSONException e) {
            throw new IOException("bad subtitle list: " + e.getMessage());
        }
        List<Result> out = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            Result r = new Result();
            r.url = o.optString("SubDownloadLink", "");
            r.format = o.optString("SubFormat", "").toLowerCase(Locale.ROOT);
            // .sub is MicroDVD (frame numbers), which no player here reads
            if (r.url.isEmpty() || "1".equals(o.optString("SubBad")) || !(r.format.isEmpty() || r.format.matches("srt|ass|ssa|vtt"))) continue;
            r.lang = o.optString("SubLanguageID", "");
            r.release = o.optString("SubFileName", "");
            if (r.release.isEmpty()) r.release = o.optString("MovieReleaseName", "");
            r.encoding = o.optString("SubEncoding", "");
            r.downloads = (int) Math.min(Integer.MAX_VALUE, Math.max(0, o.optLong("SubDownloadsCnt", 0)));
            r.hearingImpaired = "1".equals(o.optString("SubHearingImpaired"));
            out.add(r);
        }
        Collections.sort(out, new Comparator<Result>() {
            @Override public int compare(Result x, Result y) { return Integer.compare(y.downloads, x.downloads); }
        });
        return out.size() > MAX_RESULTS ? new ArrayList<>(out.subList(0, MAX_RESULTS)) : out;
    }

    /** Reads to the end of the stream, failing instead of passing max bytes. */
    static byte[] read(InputStream in, int max, String what) throws IOException {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        for (int n; (n = in.read(buf)) != -1; ) {
            if (o.size() + n > max) throw new IOException(what + " too large");
            o.write(buf, 0, n);
        }
        return o.toByteArray();
    }
}
