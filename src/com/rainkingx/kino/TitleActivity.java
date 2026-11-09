package com.rainkingx.kino;

import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/** One movie or series: poster, details, episodes by season, and Watch on the site you picked (its search box is filled in for you). */
public class TitleActivity extends Home {
    final Catalog.Title t = new Catalog.Title();
    int season = 1;
    boolean refocus = true;   // TV: land on Watch when the screen first appears, not after every season click

    @Override boolean tabs() { return false; }

    @Override protected void onCreate(Bundle state) {
        t.id = getIntent().getStringExtra("id");
        t.type = getIntent().getStringExtra("type");
        t.name = getIntent().getStringExtra("name");
        t.year = String.valueOf(getIntent().getStringExtra("year"));
        t.poster = String.valueOf(getIntent().getStringExtra("poster"));
        t.anime = getIntent().getBooleanExtra("anime", false);
        super.onCreate(state);
        App.io.execute(() -> {
            Catalog.fill(t);
            // An animated title from the movie / TV catalog (or search, My list) that the anime databases know: show anime sites
            runOnUiThread(() -> {
                if (t.series() && Catalog.seasons(t) > 0 && season > Catalog.seasons(t)) season = 1;
                refocus = true;
                build();
            });
        });
    }

    @Override protected void onResume() {
        refocus = true;
        super.onResume();
    }

    @Override void build() {
        col.removeAllViews();
        firstCard = null;

        LinearLayout hero = new LinearLayout(this);
        int w = App.tv ? 190 : 112;
        FrameLayout poster = new FrameLayout(this);
        poster.setBackground(rounded(colorFor(t.name), dp(12)));
        poster.setClipToOutline(true);
        TextView letter = text(t.name.substring(0, 1).toUpperCase(), 40, 0x55FFFFFF, true);
        letter.setGravity(Gravity.CENTER);
        poster.addView(letter, new FrameLayout.LayoutParams(-1, -1));
        ImageView img = new ImageView(this);
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        poster.addView(img, new FrameLayout.LayoutParams(-1, -1));
        if (!t.poster.isEmpty() && !t.poster.equals("null")) loadImage(img, t.poster, letter);
        hero.addView(poster, new LinearLayout.LayoutParams(dp(w), dp(w * 3 / 2)));

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setPadding(u(24, 14), 0, 0, 0);
        TextView name = text(t.name, sp(30, 21), TEXT, true);
        name.setMaxLines(3);
        name.setEllipsize(TextUtils.TruncateAt.END);
        info.addView(name);
        List<String> meta = new ArrayList<>();
        if (!t.year.isEmpty() && !t.year.equals("null")) meta.add(t.year);
        if (!t.runtime.isEmpty()) meta.add(t.runtime);
        if (!t.rating.isEmpty()) meta.add("★ " + t.rating);
        TextView line = text(String.join("  ·  ", meta), sp(15, 13), MUTED, false);
        line.setPadding(0, dp(6), 0, 0);
        info.addView(line);
        if (!t.genres.isEmpty()) {
            TextView genres = text(t.genres, sp(14, 12), MUTED, false);
            genres.setPadding(0, dp(3), 0, 0);
            info.addView(genres);
        }
        if (App.tv) {
            TextView overview = text(t.overview, 15, TEXT, false);
            overview.setPadding(0, dp(12), 0, 0);
            overview.setMaxLines(6);
            overview.setEllipsize(TextUtils.TruncateAt.END);
            info.addView(overview);
        }
        hero.addView(info, new LinearLayout.LayoutParams(0, -2, 1));
        col.addView(hero);

        if (!App.tv && !t.overview.isEmpty()) {
            TextView overview = text(t.overview, 13, TEXT, false);
            overview.setPadding(0, dp(12), 0, 0);
            col.addView(overview);
        }
        // Who made it and who is in it, where it is from, awards, and for a series whether it still runs and how much there is
        List<String[]> facts = new ArrayList<>();
        if (!t.cast.isEmpty()) facts.add(new String[]{App.t(R.string.title_cast), t.cast});
        if (!t.director.isEmpty()) facts.add(new String[]{App.t(R.string.title_director), t.director});
        else if (!t.writer.isEmpty()) facts.add(new String[]{App.t(R.string.title_writer), t.writer});
        if (t.series() && !t.episodes.isEmpty()) {
            int n = Catalog.seasons(t);
            String size = App.t(n == 1 ? R.string.title_size_one : R.string.title_size, n, t.episodes.size());
            facts.add(new String[]{App.t(R.string.title_show), t.status.isEmpty() ? size : size + "  ·  " + t.status});
        }
        if (!t.country.isEmpty()) facts.add(new String[]{App.t(R.string.title_country), t.country});
        if (!t.awards.isEmpty()) facts.add(new String[]{App.t(R.string.title_awards), t.awards});
        if (!facts.isEmpty()) {
            LinearLayout box = new LinearLayout(this);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setPadding(0, u(14, 10), 0, 0);
            for (String[] f : facts) {
                android.text.SpannableStringBuilder fact = new android.text.SpannableStringBuilder(f[0] + "   ");
                fact.setSpan(new android.text.style.ForegroundColorSpan(MUTED), 0, f[0].length(), 0);
                fact.append(f[1]);
                TextView v = text("", sp(15, 13), TEXT, false);
                v.setText(fact);
                v.setMaxLines(2);
                v.setEllipsize(TextUtils.TruncateAt.END);
                v.setPadding(0, dp(3), 0, dp(3));
                box.addView(v);
            }
            col.addView(box);
        }

        LinearLayout buttons = new LinearLayout(this);
        buttons.setPadding(0, u(18, 12), 0, u(4, 2));
        Sites.Site site = chosen();
        TextView watch = chip(v -> watch(null));
        watch.setText(site == null ? App.t(R.string.title_no_site) : App.t(R.string.title_watch_on, site.name));
        watch.setTextColor(0xFFFFFFFF);
        watch.setBackground(rounded(ACCENT, dp(22)));
        focusable(watch, dp(22));
        TextView list = chip(null);
        boolean mine = Catalog.isMine(t.id);
        list.setText(App.t(mine ? R.string.title_in_list : R.string.title_add_list));
        list.setOnClickListener(v -> { toast(App.t(Catalog.toggleMine(t) ? R.string.title_added : R.string.title_removed)); build(); });
        buttons.addView(watch);
        buttons.addView(list);
        android.widget.HorizontalScrollView scroller = new android.widget.HorizontalScrollView(this);
        scroller.setHorizontalScrollBarEnabled(false);
        scroller.setClipToPadding(false);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, -2);
        slp.setMargins(-slack, 0, -slack, 0);
        scroller.setLayoutParams(slp);
        scroller.setPadding(slack, 0, slack, 0);
        buttons.setClipChildren(false);
        scroller.addView(buttons);
        col.addView(scroller);
        if (firstCard == null) firstCard = watch;

        // The sites that carry this kind of title: tap one and its own search box is filled in with the title
        List<Sites.Site> sites = options();
        if (!sites.isEmpty()) {
            // one row that scrolls sideways, so the episodes below are a short move away
            col.addView(heading(App.t(R.string.title_watch_heading, t.name)));
            LinearLayout row = row();
            for (Sites.Site s : sites) row.addView(siteCard(s, true));
        }
        if (t.series() && !t.episodes.isEmpty()) episodes();
        if (App.tv && refocus) col.postDelayed(watch::requestFocus, 60);
        refocus = false;
    }

    void episodes() {
        col.addView(heading(App.t(R.string.title_episodes)));
        LinearLayout seasons = new LinearLayout(this);
        seasons.setClipChildren(false);
        for (int s = 1; s <= Catalog.seasons(t); s++) {
            final int n = s;
            TextView c = chip(v -> { season = n; build(); });
            c.setText(App.t(R.string.title_season, s));
            c.setTextColor(s == season ? 0xFFFFFFFF : TEXT);
            c.setBackground(rounded(s == season ? ACCENT : CARD, dp(22)));
            focusable(c, dp(22));
            seasons.addView(c);
        }
        android.widget.HorizontalScrollView sc = new android.widget.HorizontalScrollView(this);
        sc.setHorizontalScrollBarEnabled(false);
        sc.setClipToPadding(false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(-slack, 0, -slack, 0);
        sc.setLayoutParams(lp);
        sc.setPadding(slack, 0, slack, dp(6));
        sc.addView(seasons);
        col.addView(sc);

        for (Catalog.Episode e : t.episodes) {
            if (e.season != season) continue;
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(u(12, 8), u(10, 7), u(14, 10), u(10, 7));
            ImageView thumb = new ImageView(this);
            thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
            thumb.setBackground(rounded(colorFor(e.title), dp(8)));
            thumb.setClipToOutline(true);
            if (!e.thumb.isEmpty()) loadImage(thumb, e.thumb, null);
            row.addView(thumb, new LinearLayout.LayoutParams(dp(App.tv ? 160 : 100), dp(App.tv ? 90 : 56)));
            LinearLayout text = new LinearLayout(this);
            text.setOrientation(LinearLayout.VERTICAL);
            text.setPadding(u(16, 10), 0, 0, 0);
            TextView title = text(e.number + "  " + (e.title.isEmpty() ? App.t(R.string.title_episode, e.number) : e.title), sp(16, 14), TEXT, true);
            title.setSingleLine();
            title.setEllipsize(TextUtils.TruncateAt.END);
            text.addView(title);
            if (!e.overview.isEmpty()) {
                TextView o = text(e.overview, sp(13, 12), MUTED, false);
                o.setMaxLines(2);
                o.setEllipsize(TextUtils.TruncateAt.END);
                o.setPadding(0, dp(2), 0, 0);
                text.addView(o);
            }
            row.addView(text, new LinearLayout.LayoutParams(0, -2, 1));
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, -2);
            rp.bottomMargin = dp(8);
            row.setLayoutParams(rp);
            row.setBackground(rounded(CARD, dp(14)));
            focusable(row, dp(14));
            row.setOnClickListener(v -> watch(e));
            col.addView(row);
        }
    }

    /**
     * The sites for this kind of title: your favorites first, then FMHY's list. Anime gets only FMHY's anime streaming sites
     * (favorites included only when they are among them), and the TV ones only if there are none.
     */
    List<Sites.Site> options() {
        List<Sites.Site> row = Sites.load().get(t.anime ? Cats.ANIME : t.series() ? Cats.TV : Cats.MOVIES);
        if (t.anime && row != null) {
            // FMHY's anime sites only, not the general ones that also carry anime
            List<Sites.Site> only = new ArrayList<>();
            for (Sites.Site s : row) if (s.group.equals("Anime Streaming")) only.add(s);
            if (!only.isEmpty()) row = only;
        }
        if (t.anime && (row == null || row.isEmpty())) row = Sites.load().get(Cats.TV);
        if (row != null) {
            // the sites that open the title (or episode) directly come first, FMHY's order kept within each half
            String kind = t.anime ? "anime" : t.series() ? "tv" : "movie";
            row = new ArrayList<>(row);
            row.sort(java.util.Comparator.comparing(s -> !Sites.hasLink(s.url, kind)));
        }
        List<Sites.Site> out = new ArrayList<>();
        for (Sites.Site f : Sites.favorites()) if (!t.anime || (row != null && Sites.has(row, f.url))) out.add(f);
        if (row != null) for (Sites.Site s : row) if (!Sites.has(out, s.url) && out.size() < 30) out.add(s);
        return out;
    }

    /** The site picked last time for this kind (anime keeps its own), else the first option. */
    Sites.Site chosen() {
        List<Sites.Site> options = options();
        String url = App.prefs.getString(siteKey(), "");
        for (Sites.Site s : options) if (s.url.equals(url)) return s;
        return options.isEmpty() ? null : options.get(0);
    }

    String siteKey() {
        return t.anime ? "watchsite:anime" : "watchsite";
    }

    @Override void openSite(Sites.Site s) {
        App.prefs.edit().putString(siteKey(), s.url).apply();
        startActivity(withTitle(titleIntent(s)));
    }

    // The title's own page when the site is known (by TMDB id), else the site with a search for its name
    android.content.Intent titleIntent(Sites.Site s) {
        String link = Sites.deeplink(s.url, t);
        android.content.Intent in = WebActivity.open(this, s.name, s.url, link != null ? link : s.url);
        return link != null ? in.putExtra("play", !t.series()) : in.putExtra("q", t.name).putExtra("year", t.year);
    }

    void watch(Catalog.Episode e) {
        Sites.Site s = chosen();
        if (s == null) { toast(App.t(R.string.title_no_site_toast)); return; }
        // A site that has episode pages but no page for the show (most anime sites): Watch starts the first episode
        if (e == null && t.series() && Sites.deeplink(s.url, t) == null && Sites.hasLink(s.url, t.anime ? "anime" : "episode"))
            for (Catalog.Episode x : t.episodes) if (e == null || x.season < e.season || (x.season == e.season && x.number < e.number)) e = x;
        startActivity(withTitle(titleIntent(s))
            .putExtra("season", e == null ? 0 : e.season).putExtra("episode", e == null ? 0 : e.number));
    }

    // What the native player needs to look up subtitles for this title
    android.content.Intent withTitle(android.content.Intent in) {
        return in.putExtra("imdb", t.id).putExtra("series", t.series()).putExtra("name", t.name).putExtra("poster", t.poster)
            .putExtra("tmdb", t.tmdb).putExtra("anime", t.anime);
    }
}
