package com.rainkingx.kino;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Where an episode's (or movie's) intro, recap, credits and next-episode preview are: TheIntroDB by TMDB id, else AniSkip by
 * MyAnimeList id for anime. Both are free and need no key. Times are in the release those sites timed; a site's copy may
 * be a few seconds off.
 */
final class Skips {
    static final class Part {
        final String kind;      // intro, recap, credits, preview
        final long start, end;  // ms; end -1 = to the end of the video

        Part(String kind, long start, long end) {
            this.kind = kind;
            this.start = start;
            this.end = end;
        }

        boolean closing() {
            return kind.equals("credits") || kind.equals("preview");
        }

        /** Credits come near the end. TheIntroDB also lists opening titles as "credits" (Death Note: 0:00 to 2:03). */
        boolean usable(long duration) {
            return !closing() || start >= duration * 2 / 3;
        }

        boolean has(long pos, long duration) {
            return usable(duration) && pos >= start && pos < (end < 0 ? duration : end);
        }
    }

    /** Off the main thread. season and episode 0 for a movie. Empty when neither knows the title. */
    static List<Part> find(Catalog.Title t, int season, int episode) {
        List<Part> out = new ArrayList<>();
        if (!t.tmdb.isEmpty())
            out = introDb(Catalog.get("https://api.theintrodb.org/v2/media?tmdb_id=" + t.tmdb + (episode > 0 ? "&season=" + Math.max(1, season) + "&episode=" + episode : ""), true));
        if (out.isEmpty() && t.anime && episode > 0) {
            Map<String, String> ids = Catalog.animeEntry(t, season, episode);
            if (ids.containsKey("mal"))
                out = aniSkip(Catalog.get("https://api.aniskip.com/v2/skip-times/" + ids.get("mal") + "/" + ids.get("episode")
                    + "?types=op&types=ed&types=mixed-op&types=mixed-ed&types=recap&episodeLength=0", true));
        }
        return out;
    }

    /** {"intro":[{"start_ms":null,"end_ms":112303}],"credits":[{"start_ms":3084906,"end_ms":null}], "recap": ..., "preview": ...} */
    static List<Part> introDb(String json) {
        List<Part> out = new ArrayList<>();
        if (json == null) return out;
        try {
            JSONObject o = new JSONObject(json);
            for (String kind : new String[]{"recap", "intro", "credits", "preview"}) {
                JSONArray a = o.optJSONArray(kind);
                for (int i = 0; a != null && i < a.length(); i++) {
                    JSONObject p = a.getJSONObject(i);
                    long start = p.isNull("start_ms") ? 0 : p.getLong("start_ms"), end = p.isNull("end_ms") ? -1 : p.getLong("end_ms");
                    if (end < 0 || end > start) out.add(new Part(kind, start, end));
                }
            }
        } catch (JSONException ignored) { }
        return out;
    }

    /** {"found":true,"results":[{"interval":{"startTime":1.35,"endTime":91.35},"skipType":"op"}, ...]} (seconds) */
    static List<Part> aniSkip(String json) {
        List<Part> out = new ArrayList<>();
        if (json == null) return out;
        try {
            JSONArray a = new JSONObject(json).optJSONArray("results");
            for (int i = 0; a != null && i < a.length(); i++) {
                JSONObject r = a.getJSONObject(i), in = r.getJSONObject("interval");
                String type = r.optString("skipType");
                String kind = type.endsWith("op") ? "intro" : type.endsWith("ed") ? "credits" : type.equals("recap") ? "recap" : null;
                if (kind != null) out.add(new Part(kind, Math.round(in.getDouble("startTime") * 1000), Math.round(in.getDouble("endTime") * 1000)));
            }
        } catch (JSONException ignored) { }
        return out;
    }
}
