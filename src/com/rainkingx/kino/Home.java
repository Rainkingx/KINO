package com.rainkingx.kino;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.util.LruCache;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Start screen: Continue watching, then FMHY's streaming sites by category, then the user's own sites. */
public class Home extends Activity {
    static final int BG = 0xFF090B16, CARD = 0xFF17192B, ACCENT = 0xFF9670FF, TEXT = 0xFFF6F4FF, MUTED = 0xFFA6A3BC;
    static final LruCache<String, Bitmap> images = new LruCache<String, Bitmap>(24 * 1024 * 1024) {
        @Override protected int sizeOf(String k, Bitmap b) { return b.getByteCount(); }
    };

    static final java.util.concurrent.ConcurrentHashMap<
        String, java.util.concurrent.FutureTask<Bitmap>> imageJobs =
        new java.util.concurrent.ConcurrentHashMap<>();
    ScrollView page;
    LinearLayout col;
    static final int GREEN = 0xFF6EE7A0, AMBER = 0xFFFFC857;
    int side, slack, cardW = 220, cardH = 92, gapDp = 14;

    /** TV value on a TV, tighter value on a phone (dp / sp). */
    int u(float tv, float phone) { return dp(App.tv ? tv : phone); }
    float sp(float tv, float phone) { return App.tv ? tv : phone; }
    TextView warpChip, adChip;
    View firstCard;
    final Runnable warpChanged = this::updateChips;

    static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    int dp(float v) {
        return dp(this, v);
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        GifView.prefetch();   // the loading backdrop's pictures, fetched once
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        ScrollView sv = page = new ScrollView(this);
        sv.setBackgroundColor(BG);
        sv.setFillViewport(true);
        col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        // One margin on every side; rows get a little slack past it so the grown focus ring never clips
        side = u(40, 12);
        slack = u(10, 6);
        cardW = App.tv ? 220 : 156;
        cardH = App.tv ? 92 : 62;
        gapDp = App.tv ? 14 : 8;
        col.setPadding(side, App.tv ? (tabs() ? dp(8) : side) : dp(6), side, App.tv ? side : dp(12));   // tabs: the top and tab bars sit above
        col.setClipToPadding(false);
        sv.addView(col);
        if (tabs()) {
            // Tabs sit on top on a TV (D-pad up from the first row) and at the bottom on a phone (thumb reach)
            if (state == null) tab = 0;
            LinearLayout root = new LinearLayout(this);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setBackgroundColor(BG);
            tabBar = tabBar();
            root.addView(topBar());
            if (App.tv) root.addView(tabBar);
            root.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));
            if (!App.tv) root.addView(tabBar);
            setContentView(root);
        } else setContentView(sv);
        Warp.listeners.add(warpChanged);

        // Site lists and IPTV channels refresh whenever the app starts and they are older than 12 hours
        if (Sites.stale() || Iptv.stale()) Warp.whenSettled(() -> App.io.execute(() -> {
            boolean sites = Sites.stale() && Sites.refresh();
            boolean iptv = Iptv.stale() && Iptv.refresh();
            if (sites || iptv) runOnUiThread(this::build);
        }));
        Warp.whenSettled(() -> App.io.execute(Iptv::refreshLinks));
        // Test the channels that were not checked lately (a few at a time, in the background)
        Warp.whenSettled(() -> App.io.execute(Iptv::sweep));
        // A newer release on GitHub shows as a line on Home
        if (Update.due()) App.io.execute(() -> { if (Update.check()) runOnUiThread(this::build); });   // GitHub's API is open: no waiting for WARP
    }

    @Override protected void onResume() {
        super.onResume();
        build();
    }

    @Override public boolean dispatchKeyEvent(android.view.KeyEvent e) {
        return holdBack(this, e) || super.dispatchKeyEvent(e);
    }

    @Override public boolean onKeyUp(int k, android.view.KeyEvent e) {
        View f = getCurrentFocus();
        if (k != android.view.KeyEvent.KEYCODE_MENU || f == null) return super.onKeyUp(k, e);
        f.performLongClick();
        return true;
    }

    @Override protected void onDestroy() {
        kinoStopRotation();
        Warp.listeners.remove(warpChanged);
        super.onDestroy();
    }

    /** Draws the selected tab. Subclasses (search, category, title) replace this whole method. */
    void build() {
        kinoStopRotation();
        col.removeAllViews();
        firstCard = null;
        built++;
        cardW = App.tv ? 220 : 156;   // a site grid on another tab resizes the shared card width
        styleTabs();
        switch (tab) {
            case 0: buildHome(); break;
            case 1: catalogTab("movie", Cats.MOVIES, ""); break;
            case 2: catalogTab("series", Cats.TV, ""); break;
            case 3: sportsTab(); break;
            case 4: catalogTab("series", Cats.ANIME, Catalog.POPULAR_ANIME); break;
            case 5: iptvTab(); break;
            default: settingsTab();
        }
        View first = firstCard;
        if (App.tv && first != null && !skipFocus) col.postDelayed(first::requestFocus, 60);
    }

    void buildHome() {
        col.addView(header());
        kinoFeaturedHero();
        if (!Update.available().isEmpty()) col.addView(updateLine(Update.available()));

        List<History.Entry> history = History.list();
        if (!history.isEmpty()) {
            col.addView(heading(App.t(R.string.home_continue_watching)));
            LinearLayout row = row();
            for (History.Entry e : history) row.addView(historyCard(e));
        }

        List<Sites.Site> favs = Sites.favorites();
        if (!favs.isEmpty()) {   // only once something is marked (Add a site is also in Settings, Sites)
            LinearLayout favHead = new LinearLayout(this);
            favHead.setGravity(Gravity.BOTTOM);
            favHead.addView(heading(App.t(R.string.home_favorites_heart)), new LinearLayout.LayoutParams(0, -2, 1));
            favHead.addView(iconButton(R.drawable.ic_delete, App.t(R.string.home_clear_all_favorites), v -> confirmClearFavorites(favs.size())));
            col.addView(favHead);
            LinearLayout favRow = row();
            for (Sites.Site s : favs) favRow.addView(siteCard(s, false));
            favRow.addView(addCard());
        }

        List<Catalog.Title> mine = Catalog.mine();
        if (!mine.isEmpty()) {
            col.addView(heading(App.t(R.string.home_my_list)));
            LinearLayout row = row();
            for (Catalog.Title t : mine) row.addView(posterCard(t, posterW()));
        }
        // Each kind: what just came out, then what everyone watches (anime: Kitsu's trending)
        String fresh = App.t(R.string.home_new), popular = App.t(R.string.home_popular);
        col.addView(section(App.t(R.string.home_tab_movies)));
        titleRow(fresh, "movie", "genre=" + Catalog.NEW, 1);
        titleRow(popular, "movie", "", 1);
        col.addView(section(App.t(R.string.home_tab_tv)));
        titleRow(fresh, "series", "genre=" + Catalog.NEW, 2);
        titleRow(popular, "series", "", 2);
        col.addView(section(App.t(R.string.home_tab_anime)));
        titleRow(fresh, "series", Catalog.NEW_ANIME, 4);
        titleRow(popular, "series", Catalog.TRENDING_ANIME, 4);

        List<Sites.Site> more = Sites.load().get(Cats.MORE);
        if (more != null) {
            col.addView(heading(App.t(R.string.home_more)));
            siteRow(Cats.MORE, more);
        }

        Map<String, List<Sites.Site>> langs = Sites.languages();
        if (!langs.isEmpty()) {
            col.addView(heading(App.t(R.string.home_other_languages)));
            LinearLayout row = row();
            for (Map.Entry<String, List<Sites.Site>> l : langs.entrySet()) row.addView(languageCard(l.getKey(), l.getValue().size()));
        }
    }

    // KINO cinematic hero carousel
    FrameLayout kinoHeroView;
    Runnable kinoHeroRotation;
    final List<Catalog.Title> kinoHeroTitles = new ArrayList<>();
    int kinoHeroIndex;
    float kinoTouchX, kinoTouchY;

    void kinoStopRotation() {
        if (kinoHeroRotation != null && kinoHeroView != null)
            kinoHeroView.removeCallbacks(kinoHeroRotation);
        kinoHeroRotation = null;
    }

    void kinoFeaturedHero() {
        FrameLayout hero = new FrameLayout(this);
        hero.setLayoutParams(new LinearLayout.LayoutParams(-1, u(440, 312)));
        hero.setBackground(rounded(CARD, dp(18)));
        hero.setClipToOutline(true);
        kinoHeroView = hero;

        TextView loading = text("FEATURED ON KINO", sp(23, 18), TEXT, true);
        loading.setGravity(Gravity.CENTER);
        hero.addView(loading, new FrameLayout.LayoutParams(-1, -1));
        col.addView(hero);

        final int generation = built;
        List<Catalog.Title> cached = Catalog.cached("movie", "");
        if (cached != null && !cached.isEmpty())
            kinoShowFeatured(hero, cached);

        App.io.execute(() -> {
            List<Catalog.Title> movies = Catalog.list("movie", "");
            runOnUiThread(() -> {
                if (generation == built && tab == 0
                        && hero == kinoHeroView && movies != null)
                    kinoShowFeatured(hero, movies);
            });
        });
    }

    void kinoShowFeatured(FrameLayout hero, List<Catalog.Title> movies) {
        kinoStopRotation();
        kinoHeroTitles.clear();

        if (movies != null) {
            int year = java.time.LocalDate.now().getYear();

            for (Catalog.Title t : movies) {
                if (t.id.isEmpty() || t.background.isEmpty()) continue;
                if (!t.year.startsWith("" + year)
                        && !t.year.startsWith("" + (year - 1))) continue;
                kinoHeroTitles.add(t);
                if (kinoHeroTitles.size() == 8) break;
            }

            for (Catalog.Title t : movies) {
                if (kinoHeroTitles.size() == 8) break;
                if (t.id.isEmpty() || t.background.isEmpty()
                        || kinoHeroTitles.contains(t)) continue;
                kinoHeroTitles.add(t);
            }

            for (Catalog.Title t : movies) {
                if (kinoHeroTitles.size() == 8) break;
                if (t.id.isEmpty() || t.poster.isEmpty()
                        || kinoHeroTitles.contains(t)) continue;
                kinoHeroTitles.add(t);
            }
        }

        kinoHeroIndex = 0;
        kinoRenderHero(hero);
    }

    void kinoAdvanceHero(int amount) {
        if (kinoHeroView == null || kinoHeroTitles.size() < 2) return;
        kinoHeroIndex = (kinoHeroIndex + amount
                + kinoHeroTitles.size()) % kinoHeroTitles.size();
        kinoRenderHero(kinoHeroView);
    }

    void kinoRenderHero(FrameLayout hero) {
        kinoStopRotation();
        hero.removeAllViews();

        if (kinoHeroTitles.isEmpty()) {
            TextView browse = text("DISCOVER MOVIES", sp(22, 18), TEXT, true);
            browse.setGravity(Gravity.CENTER);
            hero.addView(browse, new FrameLayout.LayoutParams(-1, -1));
            focusable(hero, dp(18));
            hero.setOnClickListener(v -> selectTab(1));
            if (firstCard == null) firstCard = hero;
            return;
        }

        final Catalog.Title title = kinoHeroTitles.get(kinoHeroIndex);

        ImageView artwork = new ImageView(this);
        artwork.setScaleType(ImageView.ScaleType.CENTER_CROP);
        hero.addView(artwork, new FrameLayout.LayoutParams(-1, -1));

        String image = title.background.isEmpty()
                ? title.poster : title.background;
        if (!image.isEmpty()) loadImage(artwork, image, null);

        View shade = new View(this);
        shade.setBackground(new GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            new int[]{0x00090B16, 0x33090B16, 0xE8090B16}));
        hero.addView(shade, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(u(30, 18), dp(14), u(30, 18), u(24, 18));

        content.addView(text(
            "FEATURED ON KINO",
            sp(13, 11), 0xFFBBADFF, true));

        TextView name = text(title.name, sp(42, 29), Color.WHITE, true);
        name.setMaxLines(2);
        name.setEllipsize(TextUtils.TruncateAt.END);
        content.addView(name);

        String meta = title.year;
        if (!title.rating.isEmpty())
            meta += (meta.isEmpty() ? "" : "  |  ") + "IMDb " + title.rating;
        content.addView(text(meta, sp(15, 12), 0xFFDED9F0, false));

        if (!title.overview.isEmpty()) {
            TextView synopsis = text(
                title.overview, sp(14, 12), 0xFFF2EFFA, false);
            synopsis.setMaxLines(App.tv ? 3 : 2);
            synopsis.setEllipsize(TextUtils.TruncateAt.END);
            synopsis.setPadding(0, dp(8), 0, dp(8));
            content.addView(synopsis);
        }

        LinearLayout dots = new LinearLayout(this);
        dots.setGravity(Gravity.CENTER_VERTICAL);

        for (int i = 0; i < kinoHeroTitles.size(); i++) {
            TextView dot = text(
                "●", sp(18, 14),
                i == kinoHeroIndex ? ACCENT : 0x99FFFFFF, true);
            dot.setPadding(0, 0, dp(7), 0);
            dots.addView(dot);
        }

        if (!App.tv) {
            TextView hint = text(
                "SWIPE TO EXPLORE", sp(12, 10), 0xFFD4CFE8, false);
            hint.setPadding(dp(12), 0, 0, 0);
            dots.addView(hint);
        }

        content.addView(dots);
        hero.addView(content,
            new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));

        focusable(hero, dp(18));
        hero.setContentDescription(
            "Featured movie: " + title.name + ". Open details.");

        hero.setOnClickListener(v -> startActivity(
            new Intent(this, TitleActivity.class)
                .putExtra("id", title.id)
                .putExtra("type", title.type)
                .putExtra("name", title.name)
                .putExtra("year", title.year)
                .putExtra("poster", title.poster)
                .putExtra("anime", title.anime)
        ));

        hero.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    kinoTouchX = event.getX();
                    kinoTouchY = event.getY();
                    return true;

                case android.view.MotionEvent.ACTION_MOVE:
                    float mx = event.getX() - kinoTouchX;
                    float my = event.getY() - kinoTouchY;
                    if (Math.abs(mx) > dp(12)
                            && Math.abs(mx) > Math.abs(my))
                        v.getParent().requestDisallowInterceptTouchEvent(true);
                    return true;

                case android.view.MotionEvent.ACTION_UP:
                    float dx = event.getX() - kinoTouchX;
                    float dy = event.getY() - kinoTouchY;

                    if (Math.abs(dx) > dp(48)
                            && Math.abs(dx) > Math.abs(dy) * 1.2f)
                        kinoAdvanceHero(dx < 0 ? 1 : -1);
                    else if (Math.abs(dx) < dp(20)
                            && Math.abs(dy) < dp(20))
                        v.performClick();
                    return true;

                case android.view.MotionEvent.ACTION_CANCEL:
                    return true;

                default:
                    return true;
            }
        });

        hero.setOnKeyListener((v, key, event) -> {
            if (event.getAction()
                    != android.view.KeyEvent.ACTION_UP) return false;
            if (key == android.view.KeyEvent.KEYCODE_DPAD_RIGHT) {
                kinoAdvanceHero(1);
                return true;
            }
            if (key == android.view.KeyEvent.KEYCODE_DPAD_LEFT) {
                kinoAdvanceHero(-1);
                return true;
            }
            return false;
        });

        if (firstCard == null) firstCard = hero;

        if (kinoHeroTitles.size() > 1) {
            // Load the next two hero images before they appear.
            for (int offset = 1;
                    offset <= Math.min(2, kinoHeroTitles.size() - 1);
                    offset++) {
                Catalog.Title next = kinoHeroTitles.get(
                    (kinoHeroIndex + offset) % kinoHeroTitles.size());

                String nextUrl = next.background.isEmpty()
                    ? next.poster : next.background;

                if (!nextUrl.isEmpty() && images.get(nextUrl) == null) {
                    final String preloadUrl = nextUrl;
                    App.io.execute(() -> kinoCachedFetch(preloadUrl));
                }
            }
            kinoHeroRotation = () -> {
                if (hero.isAttachedToWindow()
                        && tab == 0 && hero == kinoHeroView)
                    kinoAdvanceHero(1);
            };
            hero.postDelayed(kinoHeroRotation, 8000);
        }
    }
    /** One row of site cards. Picks come first; a long category shows its first batch and an "All" card for the rest. */
    void siteRow(String category, List<Sites.Site> sites) {
        LinearLayout row = row();
        boolean more = sites.size() > Sites.ROW_MAX + 2;
        for (Sites.Site s : more ? sites.subList(0, Sites.ROW_MAX) : sites) row.addView(siteCard(s, true));
        if (more) row.addView(allCard(category, sites.size()));
    }

    void confirmClearFavorites(int n) {
        confirm(App.t(R.string.home_clear_favorites_title), App.t(n == 1 ? R.string.home_clear_favorites_msg_one : R.string.home_clear_favorites_msg_many, n),
            App.t(R.string.home_clear_all), () -> { Sites.clearFavorites(); build(); toast(App.t(R.string.home_favorites_cleared)); });
    }

    // ---- voice input: the device's own speech recognizer (Google TV voice on a TV, the Google app on a phone) ----

    static final int REQ_VOICE = 77;
    java.util.function.Consumer<String> voiceDone;

    void listen(java.util.function.Consumer<String> then) {
        Intent i = new Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, App.t(R.string.home_voice_prompt))
            .putExtra(android.speech.RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        voiceDone = then;
        try {
            startActivityForResult(i, REQ_VOICE);
        } catch (android.content.ActivityNotFoundException e) {
            toast(App.t(R.string.home_no_voice));
        }
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_VOICE || res != RESULT_OK || data == null || voiceDone == null) return;
        java.util.ArrayList<String> said = data.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS);
        if (said != null && !said.isEmpty()) voiceDone.accept(said.get(0));
    }

    /** A mic-only chip next to Search. */
    TextView voiceChip(View.OnClickListener click) {
        TextView t = chip(click);
        iconize(t, R.drawable.ic_mic, TEXT);
        t.setCompoundDrawablePadding(0);
        t.setContentDescription(App.t(R.string.home_voice_search));
        return t;
    }

    void confirm(String title, String message, String yes, Runnable then) {
        new AlertDialog.Builder(this).setTitle(title).setMessage(message)
            .setPositiveButton(yes, (d, w) -> then.run())
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    // ---- tabs ----

    static final int[] TABS = {R.string.home_tab_home, R.string.home_tab_movies, R.string.home_tab_tv, R.string.home_tab_sports, R.string.home_tab_anime,
        R.string.home_tab_iptv, R.string.home_tab_settings};
    static final int SETTINGS = 6;
    final List<TextView> tabViews = new ArrayList<>();
    static final int[] TAB_ICONS = {R.drawable.ic_home, R.drawable.ic_movie, R.drawable.ic_tv, R.drawable.ic_trophy, R.drawable.ic_sparkle, R.drawable.ic_live, R.drawable.ic_settings};
    static int tab;
    static final Map<Integer, String> genres = new HashMap<>();
    static String focusGenre;
    LinearLayout tabBar;
    int built;
    boolean skipFocus;

    /** Search, category and title screens are pages on top of the tabs and have no tab bar. */
    boolean tabs() { return true; }

    /** Posters say Movie, TV Series or Anime (Search, where the kinds are mixed). */
    boolean kindBadges() { return false; }

    LinearLayout tabBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        if (App.tv) bar.setPadding(side - slack, dp(6), side - slack, dp(2));
        else bar.setBackgroundColor(CARD);
        for (int i = 0; i < TABS.length; i++) {
            final int n = i;
            TextView t = text(App.t(TABS[i]), App.tv ? 16 : 11, MUTED, true);
            t.setContentDescription(t.getText());
            tabViews.add(t);
            t.setGravity(Gravity.CENTER);
            if (App.tv) {
                t.setPadding(dp(12), dp(9), dp(12), dp(9));
                t.setSingleLine(true);
                // Each tab as wide as its label, the spare room shared evenly: spread across the row and "Settings" never wraps
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2, 1);
                lp.setMargins(slack, 0, slack, 0);
                t.setLayoutParams(lp);
                t.setBackground(rounded(CARD, dp(22)));
                focusable(t, dp(22));
            } else {
                t.setPadding(0, dp(8), 0, dp(7));
                t.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));
                // One line: a long name ("Einstellungen", "Configuración") gets a smaller font instead of wrapping
                t.setSingleLine(true);
                t.addOnLayoutChangeListener((v, l, top, r, b, ol, ot, or, ob) -> {
                    float room = r - l - dp(14);   // clear of the neighbours and of a rounded screen corner
                    if (room > 0 && t.getPaint().measureText(t.getText().toString()) > room && t.getTextSize() > dp(8))
                        t.post(() -> t.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, t.getTextSize() * room / t.getPaint().measureText(t.getText().toString())));
                });
                t.setBackground(rounded(CARD, dp(12)));
                focusable(t, dp(12));
            }
            t.setOnClickListener(v -> selectTab(n));
            bar.addView(t);
        }
        return bar;
    }

    /** Selected tab = accent colour; the others stay quiet. */
    void styleTabs() {
        if (tabBar == null) return;
        for (int i = 0; i < tabViews.size(); i++) {
            TextView t = tabViews.get(i);
            boolean on = i == tab;
            android.graphics.drawable.Drawable d = getDrawable(TAB_ICONS[i]).mutate();
            int size = dp(App.tv ? 20 : 22);
            d.setBounds(0, 0, size, size);
            int color = App.tv ? (on ? Color.WHITE : TEXT) : (on ? ACCENT : MUTED);
            d.setTint(color);
            t.setTextColor(color);
            if (App.tv) {
                t.setCompoundDrawablesRelative(d, null, null, null);
                t.setCompoundDrawablePadding(dp(8));
                // Focus shows as a ring in the background itself (the shared foreground ring did not draw on these tabs):
                // red on a quiet tab, white on the selected red one
                GradientDrawable idle = rounded(on ? ACCENT : CARD, dp(22)), focused = rounded(on ? ACCENT : 0xFF2A2236, dp(22));
                focused.setStroke(dp(3), on ? Color.WHITE : ACCENT);
                StateListDrawable bg = new StateListDrawable();
                bg.addState(new int[]{android.R.attr.state_focused}, focused);
                bg.addState(new int[0], idle);
                t.setBackground(bg);
            } else {
                t.setCompoundDrawablesRelative(null, d, null, null);
                t.setCompoundDrawablePadding(dp(3));
            }
        }
    }

    void selectTab(int n) {
        if (n == tab) return;
        tab = n;
        skipFocus = true;
        build();
        skipFocus = false;
        page.scrollTo(0, 0);
    }

    @Override public void onBackPressed() {
        if (tabs() && tab != 0) selectTab(0);
        else super.onBackPressed();
    }

    /** Poster width in dp for a row of posters. */
    int posterW() { return App.tv ? 150 : 108; }

    View note(String s) {
        TextView t = text(s, sp(15, 13), MUTED, false);
        t.setPadding(dp(4), dp(10), dp(4), dp(10));
        return t;
    }

    View tabHeader(String title) {
        LinearLayout h = new LinearLayout(this);
        h.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = text(title, sp(26, 20), TEXT, true);
        h.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
        return h;   // Search and voice are in the top bar
    }

    /** On every tab: the logo (and name on a TV), WARP and ad block, then Search filling the rest, and voice. The tabs (with Settings) sit below it on a TV, at the bottom on a phone. */
    View topBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(BG);
        if (App.tv) bar.setPadding(side, dp(10), side - slack, 0);
        else bar.setPadding(dp(12), dp(6), dp(6), dp(2));
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.kino_logo);
        logo.setScaleType(ImageView.ScaleType.CENTER_CROP);
        bar.addView(logo, new LinearLayout.LayoutParams(u(40, 28), u(40, 28)));
        TextView name = text("KINO", sp(24, 18), TEXT, true);
        // Written like the project banner: FMHY in white, Play in the accent red
        android.text.SpannableString brand = new android.text.SpannableString("KINO");
        brand.setSpan(new android.text.style.ForegroundColorSpan(ACCENT), 0, 1, 0);
        name.setText(brand);
        name.setPadding(dp(4), 0, dp(8), 0);
        // A phone shows the logo alone, so Search gets the room; a TV has width for the name
        bar.addView(name);
        ((LinearLayout.LayoutParams) logo.getLayoutParams()).rightMargin = dp(8);
        // WARP and ad block state left of Search on every tab (icons only on a phone, where the row is narrow)
        warpChip = chip(v -> {
            boolean on = Warp.state == Warp.State.ON || Warp.state == Warp.State.CONNECTING;
            App.prefs.edit().putBoolean("warp", !on).apply();
            if (on) Warp.stop(); else Warp.start();
        });
        adChip = chip(v -> {
            App.prefs.edit().putBoolean("adblock", !AdBlock.enabled()).apply();
            updateChips();
        });
        updateChips();
        // One search for everything, from every tab: movies, series, IPTV channels and sites
        TextView search = chip(v -> startActivity(new Intent(this, SearchActivity.class)));
        search.setText(App.t(App.tv ? R.string.home_search_hint : R.string.home_search));
        search.setTextColor(MUTED);
        search.setSingleLine(true);
        search.setEllipsize(TextUtils.TruncateAt.END);
        iconize(search, R.drawable.ic_search, TEXT);
        LinearLayout.LayoutParams sl = (LinearLayout.LayoutParams) search.getLayoutParams();
        sl.width = 0;
        sl.weight = 1;   // fills whatever the screen leaves
        // KINO compact search and right-aligned controls
        if (!App.tv) {
            search.setText("");
            search.setGravity(Gravity.CENTER);
            search.setPadding(dp(10), dp(8), dp(10), dp(8));
            search.setCompoundDrawablePadding(0);
            sl.width = dp(44);
            sl.weight = 0;

            search.setOnClickListener(v ->
                startActivity(new Intent(this, SearchActivity.class)));

            View spacer = new View(this);
            bar.addView(spacer,
                new LinearLayout.LayoutParams(0, 1, 1));
        }
        bar.addView(search);
        bar.addView(voiceChip(v -> startActivity(new Intent(this, SearchActivity.class).putExtra("voice", true))));
        return bar;
    }

    /** A poster row that draws at once from disk if it can, else fills in when the catalog answers. */
    void titleRow(String head, String type, String extra, int moreTab) {
        col.addView(heading(head));
        LinearLayout row = row();
        final int id = built;
        List<Catalog.Title> hit = Catalog.cached(type, extra);
        if (hit != null) { fillPosters(row, hit, extra, moreTab); return; }
        row.addView(note(App.t(R.string.home_loading)));
        App.io.execute(() -> {
            List<Catalog.Title> list = Catalog.list(type, extra);
            runOnUiThread(() -> {
                if (id != built) return;
                row.removeAllViews();
                if (list == null || list.isEmpty()) row.addView(note(App.t(R.string.home_catalog_offline)));
                else fillPosters(row, list, extra, moreTab);
            });
        });
    }

    /** `extra` "genre=X": More opens the tab on that genre chip. moreTab -1: no More (the row is on that tab already). */
    void fillPosters(LinearLayout row, List<Catalog.Title> list, String extra, int moreTab) {
        for (Catalog.Title t : list.subList(0, Math.min(14, list.size()))) row.addView(posterCard(t, posterW()));
        if (moreTab < 0) return;
        TextView more = text(App.t(R.string.home_more_arrow), sp(17, 15), TEXT, true);
        more.setGravity(Gravity.CENTER);
        more.setLayoutParams(cardParams(posterW(), posterW() * 3 / 2));
        more.setBackground(rounded(0xFF2A2236, dp(12)));
        focusable(more, dp(12));
        more.setOnClickListener(v -> { if (extra.isEmpty() || extra.startsWith("genre=")) genres.put(moreTab, extra.replaceFirst("^genre=", "")); selectTab(moreTab); });
        row.addView(more);
    }

    /** Movies, TV and Anime: the sites that carry them, then the native catalog (genre chips, posters, load more). */
    void catalogTab(String type, String category, String fixedGenre) {
        col.addView(tabHeader(App.t(TABS[tab])));
        List<Sites.Site> sites = Sites.load().get(category);
        if (sites != null) {
            col.addView(heading(App.t(R.string.home_sites)));
            siteRow(category, sites);
        }
        if (category.equals(Cats.ANIME)) titleRow(App.t(R.string.home_new_anime), "series", Catalog.NEW_ANIME, -1);
        String genre = fixedGenre.isEmpty() ? genres.getOrDefault(tab, Catalog.NEW) : fixedGenre;
        // Genre names are Cinemeta's own keys and stay as they are; only the Anime tab fixes one (Animation)
        col.addView(heading(fixedGenre.isEmpty() ? (genre.isEmpty() ? App.t(R.string.home_popular) : genre.equals(Catalog.NEW) ? App.t(R.string.home_newly_released)
            : genre) : App.t(R.string.home_popular_animation)));
        if (fixedGenre.isEmpty()) col.addView(genreChips());
        LinearLayout holder = new LinearLayout(this);
        holder.setOrientation(LinearLayout.VERTICAL);
        col.addView(holder);
        loadPage(type, genre, 0, holder, new Grid(holder, App.tv ? 6 : 3), built);
    }

    View genreChips() {
        LinearLayout chips = new LinearLayout(this);
        chips.setClipChildren(false);
        String now = genres.getOrDefault(tab, Catalog.NEW);
        String[] all = new String[Catalog.GENRES.length + 2];
        System.arraycopy(Catalog.GENRES, 0, all, 2, Catalog.GENRES.length);
        all[0] = Catalog.NEW;
        all[1] = "";
        for (String g : all) {
            TextView c = chip(v -> { genres.put(tab, g); focusGenre = g; build(); });
            c.setText(g.isEmpty() ? App.t(R.string.home_popular) : g.equals(Catalog.NEW) ? App.t(R.string.home_new) : g);
            c.setTextColor(g.equals(now) ? Color.WHITE : TEXT);
            c.setBackground(rounded(g.equals(now) ? ACCENT : CARD, dp(22)));
            focusable(c, dp(22));
            if (App.tv && g.equals(focusGenre)) { focusGenre = null; c.post(c::requestFocus); }
            chips.addView(c);
        }
        HorizontalScrollView sc = new HorizontalScrollView(this);
        sc.setHorizontalScrollBarEnabled(false);
        sc.setClipToPadding(false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(-slack, 0, -slack, 0);
        sc.setLayoutParams(lp);
        sc.setPadding(slack, 0, slack, dp(6));
        sc.addView(chips);
        return sc;
    }

    /** Adds one page of posters (50 titles) to the grid, then a "Load more" button. `id` drops answers for a screen that was rebuilt. */
    void loadPage(String type, String genre, int skip, LinearLayout holder, Grid grid, int id) {
        String extra = (genre.isEmpty() ? "" : "genre=" + genre) + (genre.isEmpty() || skip == 0 ? "" : "&") + (skip == 0 ? "" : "skip=" + skip);
        List<Catalog.Title> hit = Catalog.cached(type, extra);
        if (hit != null) { showPage(type, genre, skip, holder, grid, id, hit); return; }
        View loading = note(App.t(R.string.home_loading));
        holder.addView(loading);
        App.io.execute(() -> {
            List<Catalog.Title> list = Catalog.list(type, extra);
            runOnUiThread(() -> {
                if (id != built) return;
                holder.removeView(loading);
                if (list == null || list.isEmpty()) {
                    TextView retry = text(App.t(list == null ? R.string.home_catalog_retry : R.string.home_nothing_here), sp(15, 13), TEXT, true);
                    retry.setPadding(dp(14), dp(12), dp(14), dp(12));
                    retry.setBackground(rounded(CARD, dp(14)));
                    focusable(retry, dp(14));
                    retry.setOnClickListener(v -> { holder.removeView(retry); loadPage(type, genre, skip, holder, grid, id); });
                    holder.addView(retry);
                } else showPage(type, genre, skip, holder, grid, id, list);
            });
        });
    }

    void showPage(String type, String genre, int skip, LinearLayout holder, Grid grid, int id, List<Catalog.Title> list) {
        // Kitsu lists each season on its own under one IMDb id: a page of 20 can come back shorter, and a show can come again
        boolean anime = genre.equals(Catalog.POPULAR_ANIME);
        @SuppressWarnings("unchecked") java.util.Set<String> seen = holder.getTag() instanceof java.util.Set ? (java.util.Set<String>) holder.getTag() : new java.util.HashSet<>();
        holder.setTag(seen);
        for (Catalog.Title t : list) if (seen.add(t.id)) grid.add(posterCard(t, grid.cell));
        if (list.isEmpty() || (!anime && list.size() < 20) || genre.equals(Catalog.NEW)) return;   // New is one list
        TextView more = text(App.t(R.string.home_load_more), sp(16, 14), TEXT, true);
        more.setGravity(Gravity.CENTER);
        more.setPadding(0, dp(14), 0, dp(14));
        more.setBackground(rounded(0xFF2A2236, dp(14)));
        focusable(more, dp(14));
        more.setOnClickListener(v -> { holder.removeView(more); loadPage(type, genre, skip + (anime ? 20 : list.size()), holder, grid, id); });
        holder.addView(more);
    }

    void sportsTab() {
        col.addView(tabHeader(App.t(R.string.home_tab_sports)));
        for (String cat : new String[]{Cats.SPORTS, Cats.LIVE}) {
            List<Sites.Site> sites = Sites.load().get(cat);
            if (sites == null) continue;
            col.addView(heading(App.t(cat.equals(Cats.LIVE) ? R.string.home_live_tv_count : R.string.home_sports_sites_count, sites.size())));
            Grid grid = new Grid(col);
            for (Sites.Site s : sites) grid.add(s);
        }
    }

    void iptvTab() {
        LinearLayout h = new LinearLayout(this);
        h.setGravity(Gravity.CENTER_VERTICAL);
        h.addView(text("IPTV", sp(26, 20), TEXT, true), new LinearLayout.LayoutParams(0, -2, 1));
        TextView add = chip(v -> addPlaylistDialog());   // channels are in the top bar's Search, with movies and sites
        add.setText(App.t(R.string.home_add_playlist_chip));
        h.addView(add);
        col.addView(h);

        List<Iptv.Channel> all = Iptv.all();
        if (all.isEmpty()) {
            col.addView(note(App.t(Iptv.loading ? R.string.home_loading_channels : R.string.home_no_channels)));
            if (!Iptv.loading) Warp.whenSettled(() -> App.io.execute(() -> {
                boolean ok = Iptv.refresh();
                runOnUiThread(() -> { if (ok && tab == 5) build(); });
            }));
            return;
        }
        String status = Iptv.statusLine();
        if (!status.isEmpty()) col.addView(note(status));
        else if (App.tv && !Iptv.history().isEmpty()) col.addView(note(App.t(R.string.home_hint_hold_channel)));
        int cols = App.tv ? 6 : 3;
        // What you added yourself comes first: a row per playlist under the name you gave it, single stream links together
        List<String> keys = new ArrayList<>(), titles = new ArrayList<>();
        for (String url : Iptv.mine()) { keys.add(url); titles.add(Iptv.playlistName(url)); }
        keys.add(Iptv.STREAMS);
        titles.add(groupName(Iptv.STREAMS));
        for (int k = 0; k < keys.size(); k++) {
            String key = keys.get(k), title = titles.get(k);
            List<Iptv.Channel> own = Iptv.list("mine", key);
            if (!key.equals(Iptv.STREAMS)) own.removeIf(c -> c.group.equals(Iptv.STREAMS));
            if (own.isEmpty()) continue;
            col.addView(heading(title + "  \u00B7  " + own.size()));
            LinearLayout mr = row();
            for (Iptv.Channel c : own.subList(0, Math.min(14, own.size()))) mr.addView(channelCard(c, cols, "mine"));
            if (own.size() > 14) {
                TextView more = text(App.t(R.string.home_all_count, own.size()), sp(17, 15), TEXT, true);
                more.setGravity(Gravity.CENTER);
                more.setLayoutParams(cardParams(squareDp(cols), squareDp(cols)));
                more.setBackground(rounded(0xFF2A2236, dp(14)));
                focusable(more, dp(14));
                more.setOnClickListener(v -> openIptv("mine", key, title, false));
                mr.addView(more);
            }
        }
        // Pinned lists (a country or category with its chips), each a row with an All tile that opens it filtered
        for (Iptv.Pin p : Iptv.pins()) {
            List<Iptv.Channel> in = Iptv.filter(Iptv.list(p.type, p.key), p.cat, p.lang);
            if (in.isEmpty()) continue;
            String title = p.title + (p.cat.isEmpty() ? "" : "  \u00B7  " + groupName(p.cat)) + (p.lang.isEmpty() ? "" : "  \u00B7  " + p.lang);
            col.addView(heading("\uD83D\uDCCC  " + title + "  \u00B7  " + in.size()));
            LinearLayout pr = row();
            for (Iptv.Channel c : in.subList(0, Math.min(14, in.size()))) pr.addView(channelCard(c, cols, "pin"));
            TextView allTile = text(App.t(R.string.home_all_count, in.size()), sp(17, 15), TEXT, true);
            allTile.setGravity(Gravity.CENTER);
            allTile.setLayoutParams(cardParams(squareDp(cols), squareDp(cols)));
            allTile.setBackground(rounded(0xFF2A2236, dp(14)));
            focusable(allTile, dp(14));
            allTile.setOnClickListener(v -> startActivity(new Intent(this, IptvActivity.class).putExtra("type", p.type).putExtra("key", p.key)
                .putExtra("title", p.title).putExtra("cat", p.cat).putExtra("lang", p.lang)));
            pr.addView(allTile);
        }
        // Recently watched channels and stars, as rows like Continue watching on Home (movies stay on Home, channels stay here)
        List<Iptv.Channel> recent = Iptv.history();
        if (!recent.isEmpty()) {
            col.addView(heading(App.t(R.string.home_continue_watching)));
            LinearLayout rr = row();
            for (Iptv.Channel c : recent) rr.addView(channelCard(c, cols, "history"));
        }
        // Channels that worked: what played fine for you, then what passed the background check
        List<Iptv.Channel> works = Iptv.working();
        if (!works.isEmpty()) {
            col.addView(heading(App.t(R.string.home_working_count, works.size())));
            LinearLayout wr = row();
            int shown = 0;
            for (Iptv.Channel c : works) {   // the first 14 in a row (ones that played for you lead), the rest behind All
                if (shown == 14) break;
                wr.addView(channelCard(c, cols, "working"));
                shown++;
            }
            TextView more = text(App.t(R.string.home_all_count, works.size()), sp(17, 15), TEXT, true);
            more.setGravity(Gravity.CENTER);
            more.setLayoutParams(cardParams(squareDp(cols), squareDp(cols)));
            more.setBackground(rounded(0xFF2A2236, dp(14)));
            focusable(more, dp(14));
            more.setOnClickListener(v -> openIptv("working", "", App.t(R.string.home_working_channels), false));
            wr.addView(more);
        }
        List<Iptv.Channel> stars = Iptv.favorites();
        if (!stars.isEmpty()) {
            col.addView(heading(App.t(R.string.home_favorites)));
            LinearLayout fr = row();
            for (Iptv.Channel c : stars.subList(0, Math.min(14, stars.size()))) fr.addView(channelCard(c, cols, "fav"));
            TextView more = text(App.t(R.string.home_more_arrow), sp(17, 15), TEXT, true);
            more.setGravity(Gravity.CENTER);
            more.setLayoutParams(cardParams(squareDp(cols), squareDp(cols)));
            more.setBackground(rounded(0xFF2A2236, dp(14)));
            focusable(more, dp(14));
            more.setOnClickListener(v -> openIptv("fav", "", App.t(R.string.home_favorites), false));
            fr.addView(more);
        }
        // Square tiles: 6 across on a TV, 3 on a phone; an emoji icon (flag for countries) over the name and count
        col.addView(heading(App.t(R.string.home_categories)));
        Grid cats = new Grid(col, cols);
        for (Map.Entry<String, List<Iptv.Channel>> e : Iptv.groups("cat").entrySet()) cats.add(iptvTile(cats.cell, Iptv.emoji(e.getKey()), groupName(e.getKey()), e.getValue().size(), "cat", e.getKey()));
        col.addView(heading(App.t(R.string.home_countries)));
        Grid countries = new Grid(col, cols);
        for (Map.Entry<String, List<Iptv.Channel>> e : Iptv.groups("country").entrySet()) countries.add(iptvTile(countries.cell, Iptv.flag(e.getKey()), e.getKey(), e.getValue().size(), "country", e.getKey()));
    }

    /** Tile edge in dp for a row of `cols` square tiles. */
    int squareDp(int cols) {
        int gap = dp(gapDp);
        return Math.round((winW() - 2 * side - gap * (cols - 1)) / (float) cols / getResources().getDisplayMetrics().density) - 1;
    }

    int builtW;   // the window width the tiles were last sized for

    int winW() {
        return builtW = getResources().getDisplayMetrics().widthPixels;
    }

    /**
     * The phone turned (a fullscreen video, a page in landscape): tiles sized for the old width are drawn again. Coming back from a
     * landscape player, onResume can draw while the window is still landscape, and the turn back to portrait arrives after it.
     */
    @Override public void onConfigurationChanged(android.content.res.Configuration c) {
        super.onConfigurationChanged(c);
        if (builtW != 0 && col != null && getResources().getDisplayMetrics().widthPixels != builtW) build();
    }

    /** Back to Home from any screen in one step: everything above it closes (the position is saved as each one stops). */
    static Runnable holdRun;
    static boolean held;   // a Back hold already sent us Home: the release must not go back one more screen

    /**
     * Holding Back for 3 seconds goes Home from any screen (a remote has no Home button on screen). Call it first in dispatchKeyEvent;
     * true means the key is used up (the release that ends a hold). A normal press is left to the screen.
     */
    static boolean holdBack(Activity a, android.view.KeyEvent e) {
        if (e.getKeyCode() != android.view.KeyEvent.KEYCODE_BACK) return false;
        if (e.getAction() == android.view.KeyEvent.ACTION_DOWN) {
            if (e.getRepeatCount() == 0 && a.getClass() != Home.class) {
                App.main.removeCallbacks(holdRun);
                held = false;
                holdRun = () -> { held = true; Toast.makeText(App.app, App.t(R.string.home_going), Toast.LENGTH_SHORT).show(); goHome(a); };
                App.main.postDelayed(holdRun, 3000);
            }
            return held;
        }
        App.main.removeCallbacks(holdRun);
        boolean was = held;
        held = false;
        return was;
    }

    static void goHome(Activity a) {
        a.startActivity(new Intent(a, Home.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
    }

    View channelCard(Iptv.Channel c, int cols, String row) {
        int px = dp(squareDp(cols));
        View t = channelTile(c, null, px);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px, px);
        lp.setMargins(0, 0, dp(gapDp), 0);
        t.setLayoutParams(lp);
        focusable(t, dp(14));
        t.setOnClickListener(v -> Iptv.play(this, c));
        // Hold OK (or press Menu): take it off this row, or star it
        t.setOnLongClickListener(v -> {
            List<String> items = new ArrayList<>();
            List<Runnable> actions = new ArrayList<>();
            if (row.equals("history")) { items.add(App.t(R.string.home_remove_continue)); actions.add(() -> Iptv.removeFromHistory(c.url)); }
            if (row.equals("working")) { items.add(App.t(R.string.home_remove_working)); actions.add(() -> Iptv.forgetWorking(c.url)); }
            items.add(App.t(Iptv.isFavorite(c) ? R.string.home_remove_favorite : R.string.home_add_favorite));
            actions.add(() -> Iptv.toggleFavorite(c));
            new AlertDialog.Builder(this).setTitle(c.name).setItems(items.toArray(new String[0]), (d, i) -> { actions.get(i).run(); build(); }).show();
            return true;
        });
        return t;
    }

    /** A square channel tile: logo (or its first letter) over the name and group. Reused by the channel grid. */
    View channelTile(Iptv.Channel c, View reuse, int size) {
        FrameLayout t, box;
        LinearLayout info;
        TextView letter, name, sub;
        ImageView logo;
        if (reuse == null) {
            t = new FrameLayout(this);
            t.setBackground(rounded(CARD, dp(14)));
            box = new FrameLayout(this);
            letter = text("", sp(34, 28), 0xCCFFFFFF, true);
            letter.setGravity(Gravity.CENTER);
            box.addView(letter, new FrameLayout.LayoutParams(-1, -1));
            logo = new ImageView(this);
            logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
            logo.setPadding(dp(14), dp(12), dp(14), dp(4));
            box.addView(logo, new FrameLayout.LayoutParams(-1, -1));
            t.addView(box, new FrameLayout.LayoutParams(-1, size * 11 / 20, Gravity.TOP));
            info = new LinearLayout(this);
            info.setOrientation(LinearLayout.VERTICAL);
            info.setGravity(Gravity.CENTER_HORIZONTAL);
            info.setPadding(dp(8), 0, dp(8), dp(8));
            name = text("", sp(14, 12), TEXT, true);
            name.setMaxLines(2);
            name.setEllipsize(TextUtils.TruncateAt.END);
            name.setGravity(Gravity.CENTER);
            sub = text("", sp(11, 10), MUTED, false);
            sub.setSingleLine();
            sub.setEllipsize(TextUtils.TruncateAt.END);
            info.addView(name);
            info.addView(sub);
            t.addView(info, new FrameLayout.LayoutParams(-1, size * 9 / 20, Gravity.BOTTOM));
        } else {
            t = (FrameLayout) reuse;
            box = (FrameLayout) t.getChildAt(0);
            letter = (TextView) box.getChildAt(0);
            logo = (ImageView) box.getChildAt(1);
            info = (LinearLayout) t.getChildAt(1);
            name = (TextView) info.getChildAt(0);
            sub = (TextView) info.getChildAt(1);
        }
        box.setBackground(rounded(colorFor(c.name), dp(14)));
        letter.setText(c.name.substring(0, 1).toUpperCase(java.util.Locale.ROOT));
        letter.setVisibility(View.VISIBLE);
        logo.setImageDrawable(null);
        logo.setTag(null);
        if (c.logo.startsWith("http")) loadImage(logo, c.logo, letter);
        name.setText((Iptv.isFavorite(c) ? "\u2665 " : "") + c.name);
        List<String> cats = new ArrayList<>();
        for (String k : c.cats) cats.add(groupName(k));
        StringBuilder s = new StringBuilder(TextUtils.join(", ", cats));
        if (c.adult && !c.source.isEmpty()) s = new StringBuilder(c.source);   // adult channels: which list they came from
        else if (c.cats[0].equals(Iptv.MINE) && !c.group.isEmpty()) s = new StringBuilder(groupName(c.group));   // your own playlists: their own group names
        else if (!c.country.isEmpty()) s.append("  \u00B7  ").append(c.country);
        sub.setText(s);
        return t;
    }

    /** IPTV group keys the app makes up itself, in the viewer's language (the key stays English); playlist group names pass through. */
    static String groupName(String key) {
        switch (key) {
            case Iptv.ADULT_NAME: return App.t(R.string.home_group_mature);
            case Iptv.MINE: return App.t(R.string.home_group_my_playlists);
            case Iptv.STREAMS: return App.t(R.string.home_my_streams);
            case "Other": return App.t(R.string.home_group_other);
            case "Live": return App.t(R.string.home_group_live);
            default: return key;
        }
    }

    View iptvTile(int size, String icon, String label, int n, String type, String key) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER);
        card.setLayoutParams(cardParams(size, size));
        card.setPadding(dp(6), dp(6), dp(6), dp(6));
        TextView ico = text(icon, sp(40, 30), TEXT, false);
        ico.setGravity(Gravity.CENTER);
        card.addView(ico);
        TextView name = text(label, sp(16, 13), TEXT, true);
        name.setSingleLine();
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setGravity(Gravity.CENTER);
        name.setPadding(0, dp(6), 0, 0);
        card.addView(name);
        TextView sub = text(App.t(n == 1 ? R.string.home_channels_one : R.string.home_channels_many, n), sp(12, 11), MUTED, false);
        sub.setGravity(Gravity.CENTER);
        card.addView(sub);
        card.setBackground(rounded(CARD, dp(14)));
        focusable(card, dp(14));
        card.setOnClickListener(v -> openIptv(type, key, label, false));
        return card;
    }

    /** Set (or clear) the link to your own extra source list, then fetch it. */
    void extrasDialog() {
        EditText in = WebActivity.field(this, "https://example.com/sources.txt", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        in.setText(Iptv.extrasUrl());
        new AlertDialog.Builder(this).setTitle(App.t(R.string.home_extras_title)).setMessage(App.t(R.string.home_extras_msg)).setView(WebActivity.box(this, in))
            .setPositiveButton(App.t(R.string.home_save), (d, w) -> {
                App.prefs.edit().putString("extrasUrl", in.getText().toString().trim()).apply();
                toast(App.t(R.string.home_fetching));
                Warp.whenSettled(() -> App.io.execute(() -> {
                    boolean ok = Iptv.refresh();
                    runOnUiThread(() -> { toast(App.t(ok ? R.string.home_source_updated : R.string.home_list_offline)); build(); });
                }));
            })
            .setNeutralButton(App.t(R.string.home_default), (d, w) -> {
                App.prefs.edit().remove("extrasUrl").apply();
                Warp.whenSettled(() -> App.io.execute(() -> {
                    Iptv.refresh();
                    runOnUiThread(this::build);
                }));
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    /** Ask for an M3U link and a name, download it, and give its channels their own row on the IPTV tab. */
    void addPlaylistDialog() {
        EditText in = WebActivity.field(this, "https://example.com/playlist.m3u", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        EditText name = WebActivity.field(this, App.t(R.string.home_playlist_name_hint), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        TextView stalker = chip(null);   // the dialog's three buttons are taken: Stalker sits under the fields
        stalker.setText(App.t(R.string.stalker_button));
        AlertDialog dlg = new AlertDialog.Builder(this).setTitle(App.t(R.string.home_add_playlist_title)).setMessage(App.t(R.string.home_add_playlist_msg)).setView(WebActivity.box(this, in, name, stalker))
            .setPositiveButton(App.t(R.string.home_add), (d, w) -> {
                String url = in.getText().toString().trim(), label = name.getText().toString();
                if (url.isEmpty()) return;
                toast(App.t(R.string.home_downloading_playlist));
                Warp.whenSettled(() -> App.io.execute(() -> {
                    String err = Iptv.addPlaylist(url, label);
                    runOnUiThread(() -> {
                        toast(err == null ? App.t(R.string.home_added_iptv) : err);
                        build();
                    });
                    if (err == null) Iptv.sweep();
                }));
            })
            .setNeutralButton(App.t(R.string.home_xtream_login), (d, w) -> xtreamDialog())
            .setNegativeButton(android.R.string.cancel, null)
            .show();
        stalker.setOnClickListener(v -> { dlg.dismiss(); stalkerDialog(); });
    }

    /** A Stalker (Ministra) portal: its address and the MAC address the provider registered. Its live channels get their own row. */
    void stalkerDialog() {
        EditText name = WebActivity.field(this, App.t(R.string.home_xtream_name_hint), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        EditText portal = WebActivity.field(this, App.t(R.string.stalker_portal_hint), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        EditText mac = WebActivity.field(this, App.t(R.string.stalker_mac_hint), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        mac.setText("00:1A:79:");   // MAG boxes' prefix, which most providers register
        AlertDialog dlg = new AlertDialog.Builder(this).setTitle(App.t(R.string.stalker_title)).setMessage(App.t(R.string.stalker_msg))
            .setView(WebActivity.box(this, name, portal, mac))
            .setPositiveButton(App.t(R.string.home_add), null)
            .setNegativeButton(android.R.string.cancel, null)
            .show();
        // Set here so a wrong MAC keeps the dialog (and what was typed) open
        dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (portal.getText().toString().trim().isEmpty()) { portal.requestFocus(); return; }
            String url = Stalker.account(portal.getText().toString(), mac.getText().toString());
            if (url == null) { mac.setError(App.t(R.string.stalker_bad_mac)); mac.requestFocus(); return; }
            dlg.dismiss();
            toast(App.t(R.string.home_signing_in));
            Warp.whenSettled(() -> App.io.execute(() -> {
                String err = Iptv.addPlaylist(url, name.getText().toString());
                runOnUiThread(() -> {
                    toast(err == null ? App.t(R.string.home_channels_added) : err);
                    build();
                });
            }));
        });
    }

    /** The DNS servers WARP asks first for Cloudflare's sign-up address (a network that blocks DNS: type your ISP's, from the router). */
    void warpDnsDialog() {
        EditText field = WebActivity.field(this, App.t(R.string.warp_dns_hint), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        field.setText(App.prefs.getString("warpDns", ""));
        new AlertDialog.Builder(this).setTitle(App.t(R.string.warp_dns_title)).setMessage(App.t(R.string.warp_dns_msg))
            .setView(WebActivity.box(this, field))
            .setPositiveButton(App.t(R.string.warp_dns_save), (d, w) -> saveWarpDns(field.getText().toString()))
            .setNeutralButton(App.t(R.string.warp_dns_auto), (d, w) -> saveWarpDns(""))
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    /** Keeps only valid-looking IPv4/IPv6 addresses, then starts WARP again with them (when it is switched on). */
    void saveWarpDns(String typed) {
        StringBuilder ok = new StringBuilder();
        for (String ip : typed.split("[,;\\s]+")) if (ip.matches("[0-9a-fA-F:.]{3,45}") && (ip.contains(".") || ip.contains(":"))) ok.append(ok.length() > 0 ? "," : "").append(ip);
        App.prefs.edit().putString("warpDns", ok.toString()).apply();
        if (App.prefs.getBoolean("warp", true)) { Warp.stop(); Warp.start(); }
        build();
    }

    /** An IPTV provider's Xtream Codes login (server, username, password): its live channels get their own row. */
    void xtreamDialog() {
        EditText name = WebActivity.field(this, App.t(R.string.home_xtream_name_hint), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        EditText server = WebActivity.field(this, App.t(R.string.home_xtream_server_hint), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        EditText user = WebActivity.field(this, App.t(R.string.home_username), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        EditText pass = WebActivity.field(this, App.t(R.string.home_password), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        new AlertDialog.Builder(this).setTitle(App.t(R.string.home_xtream_login)).setMessage(App.t(R.string.home_xtream_msg))
            .setView(WebActivity.box(this, name, server, user, pass))
            .setPositiveButton(App.t(R.string.home_add), (d, w) -> {
                if (server.getText().toString().trim().isEmpty() || user.getText().toString().trim().isEmpty()) return;
                String url = Iptv.xtreamUrl(server.getText().toString(), user.getText().toString(), pass.getText().toString());
                toast(App.t(R.string.home_signing_in));
                Warp.whenSettled(() -> App.io.execute(() -> {
                    String err = Iptv.addPlaylist(url, name.getText().toString());
                    runOnUiThread(() -> {
                        toast(err == null ? App.t(R.string.home_channels_added) : err);
                        build();
                    });
                }));
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    void openIptv(String type, String key, String title, boolean typing) {
        openIptv(type, key, title, typing, false);
    }

    void openIptv(String type, String key, String title, boolean typing, boolean voice) {
        startActivity(new Intent(this, IptvActivity.class).putExtra("type", type).putExtra("key", key).putExtra("title", title).putExtra("typing", typing).putExtra("voice", voice));
    }

    /** One line: a newer version exists, with Install and Later. */
    View updateLine(String version) {
        LinearLayout line = new LinearLayout(this);
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.setPadding(u(18, 14), u(6, 4), u(8, 6), u(6, 4));
        line.setBackground(rounded(CARD, dp(14)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(8);
        line.setLayoutParams(lp);
        line.addView(text(App.t(R.string.home_update_available, version), sp(15, 13), TEXT, true), new LinearLayout.LayoutParams(0, -2, 1));
        TextView install = chip(null);
        TextView news = chip(v -> whatsNew(version, () -> { if (install.isEnabled()) install.performClick(); }));
        news.setText(App.t(R.string.home_whats_new));
        install.setText(App.t(R.string.home_install));
        install.setBackground(rounded(ACCENT, dp(22)));
        install.setOnClickListener(v -> {
            install.setEnabled(false);
            Update.install(status -> {
                boolean failed = status.startsWith(App.t(R.string.update_failed, ""));
                install.setText(failed ? App.t(R.string.home_install) : status);
                install.setEnabled(failed);
                if (failed) toast(status);
            });
        });
        TextView later = chip(v -> { Update.skip(version); build(); });
        later.setText(App.t(R.string.home_later));
        later.setTextColor(MUTED);
        if (!Update.notes().isEmpty()) line.addView(news);
        line.addView(install);
        line.addView(later);
        if (firstCard == null) firstCard = install;
        return line;
    }

    void settingsTab() {
        col.addView(tabHeader(App.t(R.string.home_tab_settings)));
        col.addView(heading(App.t(R.string.home_connection)));
        warpValue = new TextView(this);
        col.addView(settingRow("WARP", App.t(R.string.home_warp_help), warpValue, v -> {
            boolean on = Warp.state == Warp.State.ON || Warp.state == Warp.State.CONNECTING;
            App.prefs.edit().putBoolean("warp", !on).apply();
            if (on) Warp.stop(); else Warp.start();
        }));
        TextView dnsValue = new TextView(this);
        String savedDns = App.prefs.getString("warpDns", "");
        col.addView(settingRow(App.t(R.string.warp_dns_title), App.t(R.string.warp_dns_help), dnsValue, v -> warpDnsDialog()));
        dnsValue.setText(savedDns.isEmpty() ? App.t(R.string.warp_dns_auto) : savedDns);
        dnsValue.setTextColor(savedDns.isEmpty() ? MUTED : GREEN);
        adValue = new TextView(this);
        col.addView(settingRow(App.t(R.string.home_ad_block), App.t(R.string.home_ad_block_help), adValue, v -> {
            App.prefs.edit().putBoolean("adblock", !AdBlock.enabled()).apply();
            updateChips();
        }));
        if (App.tv) {
            // Casts from phones open the player by themselves only with "Display over other apps" (Android blocks a closed app otherwise)
            TextView castValue = new TextView(this);
            boolean ready = Cast.canOpen(this);
            col.addView(settingRow(App.t(R.string.home_cast_title), App.t(ready ? R.string.home_cast_help_ready : R.string.home_cast_help_allow, Cast.tvName(this)), castValue, v -> {
                try {
                    startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())));
                } catch (Exception e) {
                    startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())));
                }
            }));
            castValue.setText(App.t(ready ? R.string.home_ready : R.string.home_allow));
            castValue.setTextColor(ready ? GREEN : ACCENT);
        }
        TextView urlValue = new TextView(this);
        col.addView(settingRow(App.t(R.string.home_address_bar), App.t(App.tv ? R.string.home_address_bar_help_tv : R.string.home_address_bar_help_phone), urlValue, v -> {
            App.prefs.edit().putBoolean("urlbar", !App.prefs.getBoolean("urlbar", false)).apply();
            build();
        }));
        urlValue.setText(App.t(App.prefs.getBoolean("urlbar", false) ? R.string.home_on : R.string.home_off));
        urlValue.setTextColor(App.prefs.getBoolean("urlbar", false) ? GREEN : MUTED);
        if (App.tv) {
            TextView cursor = new TextView(this);
            col.addView(settingRow(App.t(R.string.home_cursor_on_sites), App.t(R.string.home_cursor_help), cursor, v -> {
                App.prefs.edit().putBoolean("pointer", !App.prefs.getBoolean("pointer", true)).apply();
                build();
            }));
            boolean on = App.prefs.getBoolean("pointer", true);
            cursor.setText(App.t(on ? R.string.home_on : R.string.home_off));
            cursor.setTextColor(on ? GREEN : MUTED);
            TextView lookValue = new TextView(this);
            col.addView(settingRow(App.t(R.string.home_cursor_look), App.t(R.string.home_cursor_look_help), lookValue, v -> {
                String[] items = new String[Pointer.LOOKS.length];
                for (int i = 0; i < items.length; i++)
                    items[i] = Pointer.lookIcon(i) + "   " + Pointer.lookLabel(i) + (Pointer.LOOKS[i].equals(Pointer.look()) ? "  \u2713" : "");
                new AlertDialog.Builder(this).setTitle(App.t(R.string.home_cursor_look)).setItems(items, (d, i) -> {
                    App.prefs.edit().putString("cursorLook", Pointer.LOOKS[i]).apply();
                    build();
                }).show();
            }));
            lookValue.setText(Pointer.lookName());
            lookValue.setTextColor(TEXT);
            TextView sizeValue = new TextView(this);
            col.addView(settingRow(App.t(R.string.home_cursor_size), App.t(R.string.home_cursor_size_help), sizeValue, v -> {
                int next = Pointer.SIZES[0];
                for (int i = 0; i < Pointer.SIZES.length - 1; i++) if (Pointer.SIZES[i] == Pointer.size()) next = Pointer.SIZES[i + 1];
                App.prefs.edit().putInt("cursorSize", next).apply();
                sizeValue.setText(next + "%");
            }));
            sizeValue.setText(Pointer.size() + "%");
            sizeValue.setTextColor(TEXT);
        }
        col.addView(settingRow(App.t(R.string.home_warp_log), App.t(R.string.home_warp_log_help), null, v -> warpLog()));

        col.addView(heading(App.t(R.string.home_your_data)));
        List<Sites.Site> favs = Sites.favorites();
        col.addView(settingRow(App.t(R.string.home_clear_favorites), App.t(favs.size() == 1 ? R.string.home_sites_one : R.string.home_sites_many, favs.size()), null, v -> confirmClearFavorites(favs.size())));
        int seen = History.list().size();
        col.addView(settingRow(App.t(R.string.home_clear_history), App.t(seen == 1 ? R.string.home_history_count_one : R.string.home_history_count_many, seen), null, v ->
            confirm(App.t(R.string.home_clear_history_title), App.t(R.string.home_clear_history_msg), App.t(R.string.home_clear), () -> { History.save(new ArrayList<>()); build(); toast(App.t(R.string.home_history_cleared)); })));
        int watched = Iptv.history().size();
        col.addView(settingRow(App.t(R.string.home_clear_iptv_history), App.t(watched == 1 ? R.string.home_iptv_history_count_one : R.string.home_iptv_history_count_many, watched), null, v ->
            confirm(App.t(R.string.home_clear_iptv_history_title), App.t(R.string.home_clear_iptv_history_msg), App.t(R.string.home_clear), () -> { App.prefs.edit().remove("iptvhist").apply(); build(); toast(App.t(R.string.home_iptv_history_cleared)); })));
        int listed = Catalog.mine().size();
        col.addView(settingRow(App.t(R.string.home_clear_mylist), App.t(listed == 1 ? R.string.home_titles_one : R.string.home_titles_many, listed), null, v ->
            confirm(App.t(R.string.home_clear_mylist_title), App.t(R.string.home_clear_mylist_msg, listed), App.t(R.string.home_clear), () -> { App.prefs.edit().remove("mylist").apply(); build(); toast(App.t(R.string.home_mylist_cleared)); })));

        col.addView(heading("IPTV"));
        if (Iptv.adultAvailable()) {
            TextView adultValue = new TextView(this);
            col.addView(settingRow(App.t(R.string.home_adult), App.t(R.string.home_adult_help), adultValue, v -> {
                App.prefs.edit().putBoolean("adult", !Iptv.adultOn()).apply();
                build();
            }));
            adultValue.setText(App.t(Iptv.adultOn() ? R.string.home_on : R.string.home_off));
            adultValue.setTextColor(Iptv.adultOn() ? GREEN : MUTED);
        }
        TextView extrasValue = new TextView(this);
        col.addView(settingRow(App.t(R.string.home_extras_title), App.t(R.string.home_extras_help), extrasValue, v -> extrasDialog()));
        extrasValue.setText(App.t(Iptv.extrasUrl().isEmpty() ? R.string.home_none : Iptv.extrasUrl().equals(Iptv.DEFAULT_EXTRAS) ? R.string.home_default : R.string.home_custom));
        extrasValue.setTextColor(Iptv.extrasUrl().isEmpty() ? MUTED : GREEN);
        col.addView(settingRow(App.t(R.string.home_add_playlist_title), App.t(R.string.home_add_playlist_help), null, v -> addPlaylistDialog()));
        for (String url : Iptv.mine()) {
            int n = Iptv.parse(Iptv.read(Iptv.mineFile(url)), false).size();
            String name = Iptv.playlistName(url);
            col.addView(settingRow(name, App.t(n == 1 ? R.string.home_playlist_channels_one : R.string.home_playlist_channels_many, n), null, v ->
                confirm(App.t(R.string.home_remove_playlist_title), name, App.t(R.string.home_remove), () -> { Iptv.removePlaylist(url); build(); toast(App.t(R.string.home_playlist_removed)); })));
        }
        TextView deadValue = new TextView(this);
        col.addView(settingRow(App.t(R.string.home_hide_offline), App.t(R.string.home_hide_offline_help), deadValue, v -> {
            App.prefs.edit().putBoolean("hideDead", !Iptv.hideDead()).apply();
            build();
        }));
        deadValue.setText(App.t(Iptv.hideDead() ? R.string.home_on : R.string.home_off));
        deadValue.setTextColor(Iptv.hideDead() ? GREEN : MUTED);
        col.addView(settingRow(App.t(R.string.home_check_channels), Iptv.sweeping ? App.t(R.string.home_checking_progress, Iptv.sweepDone, Iptv.sweepTotal) : App.t(R.string.home_offline_now, Iptv.deadCount()), null, v -> {
            if (Iptv.sweeping) { toast(App.t(R.string.home_already_checking)); return; }
            Iptv.health.clear();
            toast(App.t(R.string.home_checking_background));
            Iptv.sweep();
        }));
        col.addView(settingRow(App.t(R.string.home_refresh_iptv), App.t(R.string.home_refresh_iptv_help), null, v -> {
            toast(App.t(R.string.home_downloading_channels));
            App.io.execute(() -> {
                boolean ok = Iptv.refresh();
                runOnUiThread(() -> toast(App.t(ok ? R.string.home_channels_updated : R.string.home_github_offline_warp)));
            });
        }));

        col.addView(heading(App.t(R.string.home_player)));
        TextView subValue = new TextView(this);
        col.addView(settingRow(App.t(R.string.home_subtitles), App.t(R.string.home_subtitles_help), subValue, v ->
            Subs.panel(this, false, () -> subValue.setText(Subs.sizeLabel() + " \u00B7 " + Subs.offsetLabel()), this::build)));
        subValue.setText(Subs.sizeLabel() + " \u00B7 " + Subs.offsetLabel());
        subValue.setTextColor(Subs.size() == 100 && Subs.offset() == 0 && Subs.background() == 50 && Subs.colorIndex() == 0 ? MUTED : GREEN);
        TextView boostValue = new TextView(this);
        col.addView(settingRow(App.t(R.string.home_volume_boost), App.t(R.string.home_volume_boost_help), boostValue, v -> {
            int next = PlayerActivity.BOOSTS[0];
            for (int i = 0; i < PlayerActivity.BOOSTS.length - 1; i++) if (PlayerActivity.BOOSTS[i] == PlayerActivity.boostDb()) next = PlayerActivity.BOOSTS[i + 1];
            App.prefs.edit().putInt("boost", next).apply();
            build();
        }));
        boostValue.setText(PlayerActivity.boostDb() == 0 ? App.t(R.string.home_off) : "+" + PlayerActivity.boostDb() + " dB");
        boostValue.setTextColor(PlayerActivity.boostDb() == 0 ? MUTED : GREEN);
        TextView voiceValue = new TextView(this);
        col.addView(settingRow(App.t(R.string.home_dialog_boost), App.t(R.string.home_dialog_boost_help), voiceValue, v -> {
            App.prefs.edit().putBoolean("dialogBoost", !PlayerActivity.dialogBoost()).apply();
            build();
        }));
        voiceValue.setText(App.t(PlayerActivity.dialogBoost() ? R.string.home_on : R.string.home_off));
        voiceValue.setTextColor(PlayerActivity.dialogBoost() ? GREEN : MUTED);
        TextView autoSub = new TextView(this);
        col.addView(settingRow(App.t(R.string.home_auto_subs), App.t(R.string.home_auto_subs_help, Subs.language(Subs.preferred())), autoSub, v -> {
            App.prefs.edit().putBoolean("subAuto", !Subs.auto()).apply();
            build();
        }));
        autoSub.setText(App.t(Subs.auto() ? R.string.home_on : R.string.home_off));
        autoSub.setTextColor(Subs.auto() ? GREEN : MUTED);
        TextView autoSkip = new TextView(this);
        boolean skipOn = App.prefs.getBoolean("autoSkip", false);
        col.addView(settingRow(App.t(R.string.home_auto_skip), App.t(R.string.home_auto_skip_help), autoSkip, v -> {
            App.prefs.edit().putBoolean("autoSkip", !skipOn).apply();
            build();
        }));
        autoSkip.setText(App.t(skipOn ? R.string.home_on : R.string.home_off));
        autoSkip.setTextColor(skipOn ? GREEN : MUTED);
        TextView simklValue = new TextView(this);
        col.addView(settingRow("Simkl", App.t(R.string.home_simkl_help), simklValue, v -> {
            if (Simkl.signedIn()) confirm(App.t(R.string.home_simkl_disconnect_title), App.t(R.string.home_simkl_disconnect_msg), App.t(R.string.home_disconnect), () -> { Simkl.signOut(); build(); });
            else simklDialog();
        }));
        simklValue.setText(App.t(Simkl.signedIn() ? R.string.home_connected : R.string.home_connect));
        simklValue.setTextColor(Simkl.signedIn() ? GREEN : ACCENT);
        TextView ppValue = new TextView(this);
        col.addView(settingRow("PunchPlay", App.t(R.string.punchplay_help), ppValue, v -> {
            if (PunchPlay.signedIn()) confirm(App.t(R.string.punchplay_disconnect_title), App.t(R.string.punchplay_disconnect_msg), App.t(R.string.home_disconnect), () -> { PunchPlay.signOut(); build(); });
            else punchPlayDialog();
        }));
        ppValue.setText(App.t(PunchPlay.signedIn() ? R.string.home_connected : R.string.home_connect));
        ppValue.setTextColor(PunchPlay.signedIn() ? GREEN : ACCENT);

        col.addView(heading(App.t(R.string.home_updates)));
        String newer = Update.latest();
        TextView updValue = new TextView(this);
        col.addView(settingRow(App.t(R.string.home_check_updates), App.t(R.string.home_check_updates_help, Update.installed()), updValue, v -> checkUpdate(updValue)));
        updValue.setText(newer.isEmpty() ? App.t(R.string.home_up_to_date) : App.t(R.string.home_version_available, newer));
        updValue.setTextColor(newer.isEmpty() ? MUTED : GREEN);
        TextView autoValue = new TextView(this);
        col.addView(settingRow(App.t(R.string.home_check_at_start), App.t(R.string.home_check_at_start_help), autoValue, v -> {
            App.prefs.edit().putBoolean("updateCheck", !Update.auto()).apply();
            build();
        }));
        autoValue.setText(App.t(Update.auto() ? R.string.home_on : R.string.home_off));
        autoValue.setTextColor(Update.auto() ? GREEN : MUTED);

        col.addView(heading(App.t(R.string.home_sites)));
        col.addView(settingRow(App.t(R.string.home_add_site), App.t(R.string.home_add_site_help), null, v -> addSiteDialog()));
        col.addView(settingRow(App.t(R.string.home_refresh_fmhy), App.t(R.string.home_refresh_fmhy_help), null, v -> {
            toast(App.t(R.string.home_checking_fmhy));
            App.io.execute(() -> {
                boolean changed = Sites.refresh();
                runOnUiThread(() -> toast(App.t(changed ? R.string.home_site_list_updated : R.string.home_site_list_current)));
            });
        }));

        // Which build is this? (the TV and phone APKs share a package, so say it on screen)
        String ver = "";
        try { ver = getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception ignored) { }
        TextView about = text(App.t(App.tv ? R.string.home_about_tv : R.string.home_about_phone, ver), sp(12, 11), MUTED, false);
        about.setPadding(0, u(28, 14), 0, 0);
        col.addView(about);
        updateChips();
    }

    /** Shows a code to enter at simkl.com/pin (where you can also make the free account) and waits for it. */
    void simklDialog() {
        TextView steps = text(App.t(R.string.home_getting_code), sp(17, 15), TEXT, false), code = text("", sp(40, 32), TEXT, true);
        code.setLetterSpacing(0.15f);
        code.setPadding(0, dp(14), 0, dp(6));
        AlertDialog d = new AlertDialog.Builder(this).setTitle(App.t(R.string.home_connect_simkl)).setView(WebActivity.box(this, steps, code)).setNegativeButton(android.R.string.cancel, null).create();
        boolean[] open = {true};
        d.setOnDismissListener(x -> open[0] = false);
        d.show();
        App.io.execute(() -> {
            Simkl.Code c = Simkl.newCode();
            runOnUiThread(() -> {
                if (!open[0]) return;
                if (c == null) { steps.setText(App.t(R.string.home_simkl_offline)); return; }
                steps.setText(App.t(R.string.home_simkl_steps, c.page.replaceFirst("^https?://", "")));
                code.setText(c.user);
                waitForSimkl(c, c.interval, d, open, steps);
            });
        });
    }

    void waitForSimkl(Simkl.Code c, int every, AlertDialog d, boolean[] open, TextView steps) {
        App.main.postDelayed(() -> {
            if (!open[0]) return;
            App.io.execute(() -> {
                String r = Simkl.check(c);
                runOnUiThread(() -> {
                    if (!open[0]) return;
                    switch (r) {
                        case "ok": d.dismiss(); toast(App.t(R.string.home_simkl_connected)); build(); break;
                        case "expired": steps.setText(App.t(R.string.home_simkl_expired)); break;
                        case "error": steps.setText(App.t(R.string.home_simkl_refused)); break;
                        default: waitForSimkl(c, r.equals("slow") ? every + 5 : every, d, open, steps);
                    }
                });
            });
        }, every * 1000L);
    }

    /** Shows a code to enter at punchplay.tv/link (where you can also make the free account) and waits for it, as for Simkl. */
    void punchPlayDialog() {
        TextView steps = text(App.t(R.string.home_getting_code), sp(17, 15), TEXT, false), code = text("", sp(40, 32), TEXT, true);
        code.setLetterSpacing(0.15f);
        code.setPadding(0, dp(14), 0, dp(6));
        AlertDialog d = new AlertDialog.Builder(this).setTitle(App.t(R.string.punchplay_connect)).setView(WebActivity.box(this, steps, code)).setNegativeButton(android.R.string.cancel, null).create();
        boolean[] open = {true};
        d.setOnDismissListener(x -> open[0] = false);
        d.show();
        App.io.execute(() -> {
            PunchPlay.Code c = PunchPlay.newCode();
            runOnUiThread(() -> {
                if (!open[0]) return;
                if (c == null) { steps.setText(App.t(R.string.punchplay_offline)); return; }
                steps.setText(App.t(R.string.home_simkl_steps, c.page.replaceFirst("^https?://", "")));
                code.setText(c.user);
                Runnable[] poll = new Runnable[1];
                poll[0] = () -> App.io.execute(() -> {
                    String r = PunchPlay.check(c);
                    runOnUiThread(() -> {
                        if (!open[0]) return;
                        switch (r) {
                            case "ok": d.dismiss(); toast(App.t(R.string.punchplay_connected)); build(); break;
                            case "expired": steps.setText(App.t(R.string.punchplay_expired)); break;
                            case "error": steps.setText(App.t(R.string.punchplay_refused)); break;
                            default: App.main.postDelayed(poll[0], c.interval * 1000L);
                        }
                    });
                });
                App.main.postDelayed(poll[0], c.interval * 1000L);
            });
        });
    }

    /** Update `version`: what's new since this one, then Install runs `install`. */
    void whatsNew(String version, Runnable install) {
        String notes = Update.notes();
        new AlertDialog.Builder(this).setTitle(App.t(R.string.home_update_title, version))
            .setMessage((notes.isEmpty() ? "" : App.t(R.string.home_whats_new) + "\n\n" + notes + "\n\n") + App.t(R.string.home_update_ask, Update.installed()))
            .setPositiveButton(App.t(R.string.home_install), (d, w) -> install.run())
            .setNegativeButton(App.t(R.string.home_later), null).show();
    }

    /** The WARP log in a scrolling box, with Share and Copy. */
    void warpLog() {
        String log = Warp.logText();
        TextView body = text(log, sp(13, 12), TEXT, false);
        body.setTypeface(android.graphics.Typeface.MONOSPACE);
        body.setPadding(dp(20), dp(8), dp(20), dp(8));
        android.widget.ScrollView sc = new android.widget.ScrollView(this);
        sc.addView(body);
        new AlertDialog.Builder(this).setTitle(App.t(R.string.home_warp_log)).setView(sc)
            .setPositiveButton(App.t(R.string.home_share), (d, w) -> {
                try {
                    startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, log), App.t(R.string.home_share_warp_log)));
                } catch (android.content.ActivityNotFoundException e) {
                    toast(App.t(R.string.home_no_share_app));
                }
            })
            .setNeutralButton(android.R.string.copy, (d, w) -> {
                ((android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(android.content.ClipData.newPlainText("WARP log", log));
                toast(App.t(R.string.home_copied));
            })
            .setNegativeButton(App.t(R.string.home_close), null).show();
    }

    /** Settings: ask GitHub now, then offer the install. */
    void checkUpdate(TextView value) {
        value.setText(App.t(R.string.home_checking));
        App.io.execute(() -> {
            boolean ok = Update.check();
            runOnUiThread(() -> {
                String v = Update.latest();
                if (!ok) { value.setText(App.t(R.string.home_github_offline)); toast(App.t(R.string.home_github_offline_warp)); return; }
                if (v.isEmpty()) { value.setText(App.t(R.string.home_up_to_date)); toast(App.t(R.string.home_is_latest, Update.installed())); return; }
                value.setText(App.t(R.string.home_version_available, v));
                whatsNew(v, () -> Update.install(value::setText));
            });
        });
    }

    TextView warpValue, adValue;

    /** A full-width settings row: title and a line of help on the left, current state on the right. */
    View settingRow(String title, String help, TextView value, View.OnClickListener click) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(u(18, 14), u(14, 11), u(18, 14), u(14, 11));
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        left.addView(text(title, sp(17, 15), TEXT, true));
        TextView h = text(help, sp(13, 12), MUTED, false);
        h.setPadding(0, dp(2), 0, 0);
        left.addView(h);
        row.addView(left, new LinearLayout.LayoutParams(0, -2, 1));
        if (value != null) {
            value.setTextSize(sp(16, 14));
            value.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            value.setPadding(dp(12), 0, 0, 0);
            row.addView(value);
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(8);
        row.setLayoutParams(lp);
        row.setBackground(rounded(CARD, dp(14)));
        focusable(row, dp(14));
        row.setOnClickListener(click);
        if (firstCard == null) firstCard = row;
        return row;
    }

    View posterCard(Catalog.Title t, int w) {
        FrameLayout card = new FrameLayout(this);
        card.setLayoutParams(cardParams(w, w * 3 / 2));
        ImageView img = new ImageView(this);
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        card.addView(img, new FrameLayout.LayoutParams(-1, -1));
        TextView letter = text(t.name.substring(0, 1).toUpperCase(), sp(40, 30), 0x55FFFFFF, true);
        letter.setGravity(Gravity.CENTER);
        card.addView(letter, new FrameLayout.LayoutParams(-1, -1));
        if (!t.poster.isEmpty()) loadImage(img, t.poster, letter);

        View shade = new View(this);
        shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0x00000000, 0x00000000, 0xE6000000}));
        card.addView(shade, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setPadding(u(9, 7), 0, u(9, 7), u(8, 6));
        TextView name = text(t.name, sp(14, 12), Color.WHITE, true);
        name.setMaxLines(2);
        name.setEllipsize(TextUtils.TruncateAt.END);
        info.addView(name);
        String meta = (t.year.isEmpty() ? "" : t.year) + (t.rating.isEmpty() ? "" : (t.year.isEmpty() ? "" : "  ·  ") + "★ " + t.rating);
        if (!meta.isEmpty()) info.addView(text(meta, sp(12, 10), 0xFFD8D0E2, false));
        card.addView(info, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        if (kindBadges()) {
            TextView kind = text(App.t(t.anime ? R.string.home_tab_anime : t.series() ? R.string.home_kind_series : R.string.home_kind_movie), sp(11, 10), Color.WHITE, true);
            kind.setPadding(u(8, 6), u(3, 2), u(8, 6), u(3, 2));
            kind.setBackground(rounded(t.anime ? ACCENT : t.series() ? 0xE62E8B60 : 0xE63B5BCC, dp(8)));
            FrameLayout.LayoutParams kp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
            kp.setMargins(u(7, 5), u(7, 5), 0, 0);
            card.addView(kind, kp);
        }

        card.setBackground(rounded(colorFor(t.name), dp(12)));
        card.setClipToOutline(true);
        focusable(card, dp(12));
        card.setOnClickListener(v -> startActivity(new Intent(this, TitleActivity.class)
            .putExtra("id", t.id).putExtra("type", t.type).putExtra("name", t.name).putExtra("year", t.year).putExtra("poster", t.poster).putExtra("anime", t.anime)));
        card.setOnLongClickListener(v -> {
            toast(App.t(Catalog.toggleMine(t) ? R.string.home_added_mylist : R.string.home_removed_mylist));
            if (tab == 0 && tabs()) build();
            return true;
        });
        if (firstCard == null) firstCard = card;
        return card;
    }

    /** Home's first line on a TV: the hold-OK hint (WARP and ad block are in the top bar). */
    View header() {
        if (!App.tv) return new View(this);
        TextView hint = text(App.t(R.string.home_hint_hold_site), 13, MUTED, false);
        hint.setPadding(dp(6), dp(2), 0, 0);
        return hint;
    }

    /** Refreshes the WARP and ad block state shown on the Home chips and in Settings (whichever is on screen). */
    void updateChips() {
        // Icon and text share one colour: green = working, amber = starting, red = failed, grey = off
        String label, word;   // the chip's "WARP on" and Settings' "On"
        int color, icon = R.drawable.ic_shield;
        switch (Warp.state) {
            case ON: label = App.t(R.string.home_warp_on); word = App.t(R.string.home_on); color = GREEN; icon = R.drawable.ic_shield_check; break;
            case CONNECTING: label = App.t(R.string.home_warp_connecting); word = App.t(R.string.home_connecting); color = AMBER; break;
            case FAILED: label = App.t(R.string.home_warp_failed); word = App.t(R.string.home_failed_retry); color = ACCENT; break;
            default: label = App.t(R.string.home_warp_off); word = App.t(R.string.home_off); color = MUTED;
        }
        boolean ads = AdBlock.enabled();
        String adLabel = App.t(ads ? R.string.home_ad_block_on : R.string.home_ad_block_off);
        if (warpChip != null) {
            warpChip.setText(App.tv ? label : "");
            warpChip.setTooltipText(label);
            warpChip.setContentDescription(label);
            iconize(warpChip, icon, color);
            if (!App.tv) warpChip.setCompoundDrawablePadding(0);
        }
        if (adChip != null) {
            adChip.setText(App.tv ? adLabel : "");
            adChip.setTooltipText(adLabel);
            adChip.setContentDescription(adLabel);
            iconize(adChip, R.drawable.ic_block, ads ? GREEN : MUTED);
            if (!App.tv) adChip.setCompoundDrawablePadding(0);
        }
        if (warpValue != null) { warpValue.setText(word); warpValue.setTextColor(color); }
        if (adValue != null) { adValue.setText(App.t(ads ? R.string.home_on : R.string.home_off)); adValue.setTextColor(ads ? GREEN : MUTED); }
    }

    /** Puts a tinted icon in front of a chip's text and colours the text to match. */
    void iconize(TextView t, int res, int color) {
        android.graphics.drawable.Drawable d = getDrawable(res).mutate();
        int size = u(20, 17);
        d.setBounds(0, 0, size, size);
        d.setTint(color);
        t.setCompoundDrawablesRelative(d, null, null, null);
        t.setCompoundDrawablePadding(u(10, 7));
        t.setTextColor(color);
    }

    /** A round icon-only button (with a spoken label for screen readers). */
    ImageView iconButton(int res, String label, View.OnClickListener click) {
        ImageView b = new ImageView(this);
        b.setImageResource(res);
        b.setColorFilter(MUTED);
        b.setContentDescription(label);
        int pad = u(10, 8);
        b.setPadding(pad, pad, pad, pad);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(u(44, 36), u(44, 36));
        lp.topMargin = u(14, 6);
        b.setLayoutParams(lp);
        focusable(b, dp(22));
        b.setOnClickListener(click);
        return b;
    }

    TextView chip(View.OnClickListener click) {
        TextView t = text("", sp(15, 13), TEXT, true);
        t.setPadding(u(16, 12), u(9, 6), u(16, 12), u(9, 6));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.setMargins(0, dp(4), u(10, 6), dp(4));
        t.setLayoutParams(lp);
        focusable(t, dp(22));
        t.setOnClickListener(click);
        return t;
    }

    /** A kind's block on Home (Movies, TV, Anime): bigger than a row's heading. */
    TextView section(String s) {
        TextView t = text(s, sp(26, 20), TEXT, true);
        t.setPadding(0, u(34, 18), 0, 0);
        return t;
    }

    TextView heading(String s) {
        TextView t = text(s, sp(20, 16), TEXT, true);
        t.setPadding(0, u(22, 10), 0, u(6, 6));   // a gap before the cards, also on a phone (tile grids have none of their own)
        return t;
    }

    LinearLayout row() {
        return row(col);
    }

    LinearLayout row(LinearLayout parent) {
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setClipToPadding(false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(-slack, 0, -slack, 0);
        hs.setLayoutParams(lp);
        hs.setPadding(slack, u(10, 4), slack, u(12, 4));
        LinearLayout row = new LinearLayout(this);
        row.setClipChildren(false);
        hs.addView(row);
        parent.addView(hs);
        return row;
    }

    /** Site cards in whole rows across the screen (4 on a TV, 2 on a phone) inside `into`. */
    class Grid {
        final LinearLayout into;
        final int cols, gap = dp(gapDp), cell;   // cell = card width in dp
        LinearLayout row;
        int inRow;

        /** Site cards: 4 across on a TV, 2 on a phone. */
        Grid(LinearLayout into) {
            this(into, App.tv ? 4 : 2);
            cardW = cell;
        }

        Grid(LinearLayout into, int cols) {
            this.into = into;
            this.cols = cols;
            int width = winW() - 2 * side;
            cell = (int) Math.floor((width - gap * (cols - 1)) / (float) cols / getResources().getDisplayMetrics().density) - 1;
        }

        /** The next card starts a fresh row (after a heading). */
        void breakRow() {
            row = null;
            inRow = 0;
        }

        void add(Sites.Site s) {
            add(siteCard(s, true));
        }

        void add(View card) {
            if (row == null || inRow == cols) {
                row = new LinearLayout(Home.this);
                row.setClipChildren(false);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
                lp.bottomMargin = gap;
                row.setLayoutParams(lp);
                into.addView(row);
                inRow = 0;
            }
            ((LinearLayout.LayoutParams) card.getLayoutParams()).setMargins(0, 0, inRow == cols - 1 ? 0 : gap, 0);
            row.addView(card);
            inRow++;
        }
    }

    View historyCard(History.Entry e) {
        FrameLayout card = new FrameLayout(this);
        card.setLayoutParams(cardParams(App.tv ? 260 : 190, App.tv ? 146 : 108));
        ImageView img = new ImageView(this);
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        card.addView(img, new FrameLayout.LayoutParams(-1, -1));
        TextView letter = text(e.title.substring(0, 1).toUpperCase(), sp(44, 32), 0x55FFFFFF, true);
        letter.setGravity(Gravity.CENTER);
        card.addView(letter, new FrameLayout.LayoutParams(-1, -1));
        if (!e.image.isEmpty()) loadImage(img, e.image, letter);

        View shade = new View(this);
        shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0x00000000, 0xE0000000}));
        card.addView(shade, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setPadding(u(12, 9), 0, u(12, 9), u(10, 7));
        TextView title = text(e.title, sp(16, 14), Color.WHITE, true);
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        info.addView(title);
        long now = System.currentTimeMillis();   // under a minute reads "1 minute ago", not "0 minutes ago"
        String ago = DateUtils.getRelativeTimeSpanString(Math.min(e.time, now - 60000), now, DateUtils.MINUTE_IN_MILLIS).toString();
        info.addView(text((e.episode.isEmpty() ? "" : e.episode + "  ·  ") + e.site + "  ·  " + ago, 12, 0xFFD8D0E2, false));
        card.addView(info, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        if (e.pos > 0 && e.dur > 0) {   // how much is watched: the player carries on from there
            info.setPadding(u(12, 9), 0, u(12, 9), u(15, 11));
            float done = (float) Math.min(1.0, (double) e.pos / e.dur);
            LinearLayout track = new LinearLayout(this);   // the card's full width, along its bottom edge
            track.setBackgroundColor(0x55FFFFFF);
            View fill = new View(this);
            fill.setBackgroundColor(ACCENT);
            track.addView(fill, new LinearLayout.LayoutParams(0, -1, done));
            track.addView(new View(this), new LinearLayout.LayoutParams(0, -1, 1 - done));
            card.addView(track, new FrameLayout.LayoutParams(-1, dp(5), Gravity.BOTTOM));
        }

        card.setBackground(rounded(colorFor(e.site), 0));   // square, so the watched line runs straight edge to edge
        card.setClipToOutline(true);
        focusable(card, 0);
        // Resume: the episode's own page, with the backdrop up while the app presses play and hands over to the player
        card.setOnClickListener(v -> startActivity(WebActivity.open(this, e.site, e.siteUrl, e.url).putExtra("play", true).putExtra("name", e.title)));
        card.setOnLongClickListener(v -> {
            new AlertDialog.Builder(this).setTitle(e.title)
                .setItems(new String[]{App.t(R.string.home_open_site_home, e.site), App.t(R.string.home_remove_continue)}, (d, i) -> {
                    if (i == 0) startActivity(WebActivity.open(this, e.site, e.siteUrl, e.siteUrl));
                    else { History.remove(e); build(); }
                }).show();
            return true;
        });
        if (firstCard == null) firstCard = card;
        return card;
    }

    View siteCard(Sites.Site s, boolean markFavorite) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setLayoutParams(cardParams(cardW, cardH));
        card.setPadding(u(16, 10), u(10, 6), u(16, 10), u(10, 6));
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        // Letter tile until (or unless) the site's icon loads
        FrameLayout tile = new FrameLayout(this);
        tile.setBackground(rounded(colorFor(s.name), dp(6)));
        TextView letter = text(s.name.isEmpty() ? "?" : s.name.substring(0, 1).toUpperCase(), sp(14, 12), 0xCCFFFFFF, true);
        letter.setGravity(Gravity.CENTER);
        tile.addView(letter, new FrameLayout.LayoutParams(-1, -1));
        ImageView icon = new ImageView(this);
        String host = String.valueOf(Uri.parse(s.url).getHost());
        tile.addView(icon, new FrameLayout.LayoutParams(-1, -1));
        loadImage(icon, "https://www.google.com/s2/favicons?domain=" + host + "&sz=64", letter);
        top.addView(tile, new LinearLayout.LayoutParams(u(26, 22), u(26, 22)));
        TextView name = text(s.name, sp(17, 15), TEXT, true);
        if (markFavorite && Sites.isFavorite(s.url)) {
            android.text.SpannableString star = new android.text.SpannableString("\u2665  " + s.name);
            star.setSpan(new android.text.style.ForegroundColorSpan(ACCENT), 0, 1, 0);
            name.setText(star);
        }
        name.setSingleLine();
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setPadding(u(10, 8), 0, 0, 0);
        top.addView(name);
        card.addView(top);
        TextView tags = text(s.tags.isEmpty() ? host : s.tags, sp(12, 11), MUTED, false);
        tags.setSingleLine();
        tags.setEllipsize(TextUtils.TruncateAt.END);
        tags.setPadding(0, u(6, 2), 0, 0);
        card.addView(tags);

        card.setBackground(rounded(CARD, dp(14)));
        focusable(card, dp(14));
        card.setOnClickListener(v -> openSite(s));
        // Hold OK (or press Menu) for: favorite, mirrors, remove
        card.setOnLongClickListener(v -> {
            List<String> items = new ArrayList<>();
            List<Runnable> actions = new ArrayList<>();
            items.add(App.t(Sites.isFavorite(s.url) ? R.string.home_remove_favorite : R.string.home_add_favorite));
            actions.add(() -> { Sites.toggleFavorite(s); build(); });
            for (String m : s.mirrors) {
                items.add(App.t(R.string.home_open_mirror, Uri.parse(m).getHost()));
                actions.add(() -> startActivity(WebActivity.open(this, s.name, m, m)));
            }
            new AlertDialog.Builder(this).setTitle(s.name)
                .setItems(items.toArray(new String[0]), (d, i) -> actions.get(i).run()).show();
            return true;
        });
        if (firstCard == null) firstCard = card;
        return card;
    }

    /** What tapping a site card does; the title page overrides it to search for the title there. */
    void openSite(Sites.Site s) {
        startActivity(WebActivity.open(this, s.name, s.url, s.url));
    }

    View languageCard(String language, int count) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setLayoutParams(cardParams(cardW, cardH));
        card.setPadding(u(16, 10), u(10, 6), u(16, 10), u(10, 6));
        TextView name = text(language, sp(17, 15), TEXT, true);
        name.setSingleLine();
        name.setEllipsize(TextUtils.TruncateAt.END);
        card.addView(name);
        TextView n = text(App.t(count == 1 ? R.string.home_sites_one : R.string.home_sites_many, count), sp(12, 11), MUTED, false);
        n.setPadding(0, u(6, 2), 0, 0);
        card.addView(n);
        card.setBackground(rounded(CARD, dp(14)));
        focusable(card, dp(14));
        card.setOnClickListener(v -> startActivity(new Intent(this, CategoryActivity.class).putExtra("language", language)));
        return card;
    }

    View allCard(String category, int count) {
        TextView card = text(App.t(R.string.home_all_count, count), sp(17, 15), TEXT, true);
        card.setGravity(Gravity.CENTER);
        card.setLayoutParams(cardParams(cardW, cardH));
        card.setBackground(rounded(0xFF2A2236, dp(14)));
        focusable(card, dp(14));
        card.setOnClickListener(v -> startActivity(new Intent(this, CategoryActivity.class).putExtra("category", category)));
        return card;
    }

    View addCard() {
        TextView card = text(App.t(R.string.home_add_site_card), sp(17, 15), TEXT, true);
        card.setGravity(Gravity.CENTER);
        card.setLayoutParams(cardParams(cardW, cardH));
        card.setBackground(rounded(0xFF2A2236, dp(14)));
        focusable(card, dp(14));
        card.setOnClickListener(v -> addSiteDialog());
        return card;
    }

    void addSiteDialog() {
        EditText in = WebActivity.field(this, "https://example.com", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        new AlertDialog.Builder(this).setTitle(App.t(R.string.home_add_site)).setView(WebActivity.box(this, in))
            .setPositiveButton(App.t(R.string.home_add), (d, w) -> {
                if (in.getText().toString().trim().isEmpty()) return;
                Sites.addCustom(in.getText().toString());
                build();
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    LinearLayout.LayoutParams cardParams(int w, int h) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(w), dp(h));
        lp.setMargins(0, 0, dp(gapDp), 0);
        return lp;
    }

    // Focus = accent ring inside the card plus a lift; nothing grows, so nothing clips at the margins
    void focusable(View v, int radius) {
        v.setFocusable(true);
        v.setClickable(true);
        GradientDrawable ring = new GradientDrawable();
        ring.setCornerRadius(radius);
        ring.setStroke(dp(3), ACCENT);
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_focused}, ring);
        v.setForeground(new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), states, rounded(Color.WHITE, radius)));
        v.setOnFocusChangeListener((view, has) -> {
            view.animate().translationZ(has ? dp(8) : 0).setDuration(120).start();
            if (has) view.post(() -> reveal(view));
        });
        if (!(v.getBackground() instanceof GradientDrawable) && !(v instanceof FrameLayout)) v.setBackground(rounded(CARD, radius));
    }

    // Keep the focused card (grown + ring) fully on screen with a margin, and its row heading visible
    void reveal(View v) {
        // A delayed focus callback can outlive its card when the rows are rebuilt
        View up = v;
        while (up != null && up != page) up = up.getParent() instanceof View ? (View) up.getParent() : null;
        if (up == null) return;
        Rect r = new Rect();
        if (v.getParent() != null && v.getParent().getParent() instanceof HorizontalScrollView) {
            HorizontalScrollView hs = (HorizontalScrollView) v.getParent().getParent();
            v.getDrawingRect(r);
            hs.offsetDescendantRectToMyCoords(v, r);
            int x = hs.getScrollX(), w = hs.getWidth();
            if (r.left - x < slack) hs.smoothScrollTo(r.left - slack, 0);
            else if (r.right - x > w - slack) hs.smoothScrollTo(r.right - w + slack, 0);
        }
        v.getDrawingRect(r);
        page.offsetDescendantRectToMyCoords(v, r);
        int y = page.getScrollY(), h = page.getHeight();
        if (r.top - dp(64) < y) page.smoothScrollTo(0, Math.max(0, r.top - dp(64)));
        else if (r.bottom + dp(24) > y + h) page.smoothScrollTo(0, r.bottom + dp(24) - h);
    }

    static GradientDrawable rounded(int color, int radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radius);
        return g;
    }

    TextView text(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return t;
    }

    static int colorFor(String s) {
        float[] hsv = {Math.abs(s.hashCode()) % 360, 0.45f, 0.35f};
        return Color.HSVToColor(hsv);
    }

    // Small image loader: memory + disk cache, fetched through WARP
    // Share image requests so identical URLs download and decode only once.
    static Bitmap kinoCachedFetch(String url) {
        Bitmap hit = images.get(url);
        if (hit != null) return hit;

        java.util.concurrent.FutureTask<Bitmap> mine =
            new java.util.concurrent.FutureTask<>(() -> fetch(url));

        java.util.concurrent.FutureTask<Bitmap> task =
            imageJobs.putIfAbsent(url, mine);

        if (task == null) {
            task = mine;
            mine.run();
        }

        try {
            Bitmap result = task.get();
            if (result != null) images.put(url, result);
            return result;
        } catch (Exception e) {
            return null;
        } finally {
            if (task == mine) imageJobs.remove(url, mine);
        }
    }

    void loadImage(ImageView into, String url, View placeholder) {
        if (url == null || url.isEmpty()) return;
        into.setTag(url);

        Bitmap hit = images.get(url);
        if (hit != null) {
            show(into, hit, placeholder);
            return;
        }

        App.io.execute(() -> {
            Bitmap result = kinoCachedFetch(url);
            if (result == null) return;

            runOnUiThread(() -> {
                if (url.equals(into.getTag()))
                    show(into, result, placeholder);
            });
        });
    }
    void show(ImageView into, Bitmap b, View placeholder) {
        into.setImageBitmap(b);
        if (placeholder != null) placeholder.setVisibility(View.GONE);
    }

    static Bitmap fetch(String url) {
        try {
            byte[] sha = MessageDigest.getInstance("SHA-1").digest(url.getBytes("UTF-8"));
            StringBuilder name = new StringBuilder();
            for (byte x : sha) name.append(String.format("%02x", x));
            // Kept in app storage (not cache) so Continue watching posters survive cache clears
            File dir = new File(App.app.getFilesDir(), "img");
            dir.mkdirs();
            File f = new File(dir, name.toString());
            if (!f.exists()) {
                File tmp = new File(dir, name + ".tmp");
                try (InputStream in = Warp.openPublic(url, 6000, 20000); FileOutputStream out = new FileOutputStream(tmp)) {
                    byte[] buf = new byte[16384];
                    for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
                }
                if (!tmp.renameTo(f)) return null;
            }
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getPath(), o);
            o.inSampleSize = 1;
            while (o.outWidth / (o.inSampleSize * 2) >= 480) o.inSampleSize *= 2;
            o.inJustDecodeBounds = false;
            return BitmapFactory.decodeFile(f.getPath(), o);
        } catch (Exception e) {
            return null;
        }
    }

    void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
