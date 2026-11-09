package com.rainkingx.kino;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.Choreographer;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.webkit.WebView;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.util.HashSet;
import java.util.Set;

/**
 * TV remote pointer mode. Hold OK for 5 s to switch between focus mode (the page's own highlight)
 * and a round pointer that glides with the arrows, taps with OK and scrolls when pushed past an edge.
 * A short OK press still clicks in both modes.
 */
final class Pointer {
    interface Host {
        View target();            // what touches go to: the page, or the full-screen video
        WebView web();
        boolean deliver(KeyEvent e);
        /** A tap at window x,y on the app's own controls over the page (the address bar); true = handled, the page gets nothing. */
        default boolean tapAt(float x, float y) { return false; }
    }

    static final long HOLD_MS = 5000, RING_AFTER_MS = 600;
    // Scrolls the nearest scrollable box under the pointer (view px in, CSS px out)
    static final String SCROLL = "((x,y,dx,dy)=>{const s=devicePixelRatio*(window.visualViewport?visualViewport.scale:1);"
        + "let e=document.elementFromPoint(x/s,y/s);while(e&&e!==document.body&&e!==document.documentElement){"
        + "const c=getComputedStyle(e);if((dy&&/(auto|scroll|overlay)/.test(c.overflowY)&&e.scrollHeight>e.clientHeight+2)||"
        + "(dx&&/(auto|scroll|overlay)/.test(c.overflowX)&&e.scrollWidth>e.clientWidth+2)){e.scrollBy(dx/s,dy/s);return}e=e.parentElement}"
        + "(document.scrollingElement||document.documentElement).scrollBy(dx/s,dy/s)})";

    final Host host;
    final FrameLayout decor;
    final Dot dot;
    final Ring ring;
    final TextView badge;
    final float density;
    volatile boolean on;
    KeyEvent okDown;
    boolean okSwitched;
    final Set<Integer> held = new HashSet<>();
    long lastFrame, lastScroll, lastInput = SystemClock.uptimeMillis();
    static volatile long videoAt;   // last time a page said a big video is playing (spatnav.js, every second while it plays)

    // While a video plays and no key was pressed for 10 s, the cursor fades out; any key brings it back
    final Runnable idle = new Runnable() {
        @Override public void run() {
            long t = SystemClock.uptimeMillis();
            if (on && t - videoAt < 2500 && t - lastInput > 10000 && dot.getAlpha() == 1f) dot.animate().alpha(0).setDuration(400).start();
            App.main.postDelayed(this, 1000);
        }
    };

    void woke() {
        lastInput = SystemClock.uptimeMillis();
        if (dot.getAlpha() < 1f) { dot.animate().cancel(); dot.setAlpha(1f); }
    }

    static final int[] SIZES = {75, 100, 125, 150, 200};

    /** Cursor size in percent (Settings, Cursor size). */
    static int size() {
        return App.prefs.getInt("cursorSize", 100);
    }
    float heldFor, pendingDx, pendingDy;

    Pointer(Activity a, Host host) {
        this.host = host;
        decor = (FrameLayout) a.getWindow().getDecorView();
        density = a.getResources().getDisplayMetrics().density;
        dot = new Dot(a, density);
        dot.setVisibility(View.GONE);
        // The mode sticks between sites and app restarts
        on = App.prefs.getBoolean("pointer", true);
        if (on) dot.setVisibility(View.VISIBLE);
        ring = new Ring(a, density, this::switchMode);
        ring.setVisibility(View.GONE);
        badge = new TextView(a);
        badge.setTextColor(Color.WHITE);
        badge.setTextSize(16);
        badge.setPadding(dp(18), dp(10), dp(18), dp(10));
        badge.setBackground(Home.rounded(0xE61E1828, dp(22)));
        badge.setVisibility(View.GONE);
        FrameLayout.LayoutParams top = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        top.topMargin = dp(28);
        decor.addView(dot, new FrameLayout.LayoutParams(-1, -1));
        decor.addView(ring, new FrameLayout.LayoutParams(-1, -1));
        decor.addView(badge, top);
        App.main.postDelayed(idle, 1000);
    }

    /** Activity is going away: stop animating and let go of it. */
    void release() {
        Choreographer.getInstance().removeFrameCallback(glide);
        App.main.removeCallbacks(idle);
        held.clear();
        ring.stop();
        badge.animate().cancel();
        decor.removeView(dot);
        decor.removeView(ring);
        decor.removeView(badge);
    }

    int dp(float v) {
        return Math.round(v * density);
    }

    /** Keeps the pointer, hold ring and badge above a full-screen video. */
    void raise() {
        dot.bringToFront();
        ring.bringToFront();
        badge.bringToFront();
    }

    boolean dispatch(KeyEvent e) {
        woke();
        int k = e.getKeyCode();
        if (k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER || k == KeyEvent.KEYCODE_NUMPAD_ENTER) return ok(e);
        if (!on || k < KeyEvent.KEYCODE_DPAD_UP || k > KeyEvent.KEYCODE_DPAD_RIGHT) return host.deliver(e);
        if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) {
            if (held.isEmpty()) {
                heldFor = 0;
                lastFrame = 0;
                Choreographer.getInstance().postFrameCallback(glide);
            }
            held.add(k);
        } else if (e.getAction() == KeyEvent.ACTION_UP) {
            held.remove(k);
        }
        return true;
    }

    // OK is held back until release: short press = click (pointer: tap), 5 s hold = switch mode
    boolean ok(KeyEvent e) {
        if (e.getAction() == KeyEvent.ACTION_DOWN) {
            if (e.getRepeatCount() == 0) {
                okDown = e;
                okSwitched = false;
                ring.start(e.getDownTime(), on);
            }
            return true;
        }
        ring.stop();
        KeyEvent down = okDown;
        okDown = null;
        if (okSwitched) return true;
        if (e.getEventTime() - e.getDownTime() >= HOLD_MS) {
            switchMode();
            return true;
        }
        if (down == null) return host.deliver(e);
        if (on) {
            tap();
            return true;
        }
        host.deliver(down);
        return host.deliver(e);
    }

    void switchMode() {
        if (okSwitched) return;
        okSwitched = true;
        ring.stop();
        on = !on;
        App.prefs.edit().putBoolean("pointer", on).apply();
        held.clear();
        if (on) {
            dot.x = decor.getWidth() / 2f;
            dot.y = decor.getHeight() / 2f;
            dot.setVisibility(View.VISIBLE);
            host.web().evaluateJavascript("window.__fpClear&&__fpClear()", null);
        } else {
            dot.setVisibility(View.GONE);
            host.web().evaluateJavascript("window.__fpResume&&__fpResume()", null);
        }
        badge.setText(App.t(on ? R.string.pointer_mode_on : R.string.pointer_mode_off));
        badge.setAlpha(1);
        badge.setVisibility(View.VISIBLE);
        badge.animate().cancel();
        badge.animate().alpha(0).setStartDelay(2500).setDuration(400).withEndAction(() -> badge.setVisibility(View.GONE)).start();
    }

    // Glides while arrows are held: starts slow for precise aiming, speeds up the longer you hold
    final Choreographer.FrameCallback glide = new Choreographer.FrameCallback() {
        @Override public void doFrame(long now) {
            if (held.isEmpty() || !on) return;
            float dt = lastFrame == 0 ? 1 / 60f : Math.min(0.05f, (now - lastFrame) / 1e9f);
            lastFrame = now;
            heldFor += dt;
            float speed = Math.min(1100, 220 + 700 * heldFor) * density;
            float dx = (held.contains(KeyEvent.KEYCODE_DPAD_RIGHT) ? 1 : 0) - (held.contains(KeyEvent.KEYCODE_DPAD_LEFT) ? 1 : 0);
            float dy = (held.contains(KeyEvent.KEYCODE_DPAD_DOWN) ? 1 : 0) - (held.contains(KeyEvent.KEYCODE_DPAD_UP) ? 1 : 0);
            if (dx != 0 && dy != 0) { dx *= 0.7071f; dy *= 0.7071f; }
            move(dx * speed * dt, dy * speed * dt);
            Choreographer.getInstance().postFrameCallback(this);
        }
    };

    void move(float dx, float dy) {
        float r = dot.radius * 1.8f;
        float nx = Math.max(r, Math.min(decor.getWidth() - r, dot.x + dx));
        float ny = Math.max(r, Math.min(decor.getHeight() - r, dot.y + dy));
        float overX = dot.x + dx - nx, overY = dot.y + dy - ny;
        dot.x = nx;
        dot.y = ny;
        dot.invalidate();
        send(MotionEvent.ACTION_HOVER_MOVE, InputDevice.SOURCE_MOUSE, SystemClock.uptimeMillis());
        if (overX != 0 || overY != 0) scroll(overX, overY);
    }

    void scroll(float dx, float dy) {
        pendingDx += dx * 1.6f;
        pendingDy += dy * 1.6f;
        long t = SystemClock.uptimeMillis();
        if (t - lastScroll < 40) return;
        lastScroll = t;
        int[] at = new int[2];
        host.web().getLocationInWindow(at);
        host.web().evaluateJavascript(SCROLL + "(" + (dot.x - at[0]) + "," + (dot.y - at[1]) + "," + pendingDx + "," + pendingDy + ")", null);
        pendingDx = pendingDy = 0;
    }

    void tap() {
        if (host.tapAt(dot.x, dot.y)) { dot.pulse(); return; }
        long t = SystemClock.uptimeMillis();
        send(MotionEvent.ACTION_DOWN, InputDevice.SOURCE_TOUCHSCREEN, t);
        send(MotionEvent.ACTION_UP, InputDevice.SOURCE_TOUCHSCREEN, t);
        dot.pulse();
    }

    // Synthetic touch/mouse event at the pointer, in the target view's coordinates
    void send(int action, int source, long downTime) {
        View target = host.target();
        int[] at = new int[2];
        target.getLocationInWindow(at);
        MotionEvent.PointerProperties pp = new MotionEvent.PointerProperties();
        pp.toolType = source == InputDevice.SOURCE_MOUSE ? MotionEvent.TOOL_TYPE_MOUSE : MotionEvent.TOOL_TYPE_FINGER;
        MotionEvent.PointerCoords pc = new MotionEvent.PointerCoords();
        pc.x = dot.x - at[0];
        pc.y = dot.y - at[1];
        pc.pressure = 1;
        pc.size = 1;
        MotionEvent m = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, 1,
            new MotionEvent.PointerProperties[]{pp}, new MotionEvent.PointerCoords[]{pc}, 0, 0, 1, 1, 0, 0, source, 0);
        if (source == InputDevice.SOURCE_MOUSE) target.dispatchGenericMotionEvent(m);
        else target.dispatchTouchEvent(m);
        m.recycle();
    }

    /** The pointer in the chosen look (no shadow); a quick pulse on tap. */
    // Cursor looks (Settings, Cursor look): drawn ones that help (an arrow clicks at its tip), then emoji with the click point marked by a small dot
    static final String[] LOOKS = {"dot", "arrow", "cross", "ring", "big", "\uD83D\uDC46", "\uD83C\uDF55", "\uD83E\uDD96", "\uD83D\uDC7B", "\uD83D\uDC31", "\uD83D\uDE80", "\uD83C\uDF69", "\uD83E\uDD86", "\uD83E\uDDC0"};
    // Their names, in the same order (a label may end in " (hint)", which lookName() leaves out)
    static final int[] LOOK_LABELS = {R.string.pointer_look_dot, R.string.pointer_look_arrow, R.string.pointer_look_cross, R.string.pointer_look_ring,
        R.string.pointer_look_big, R.string.pointer_look_finger, R.string.pointer_look_pizza, R.string.pointer_look_dino, R.string.pointer_look_ghost,
        R.string.pointer_look_cat, R.string.pointer_look_rocket, R.string.pointer_look_donut, R.string.pointer_look_duck, R.string.pointer_look_cheese};

    /** A look's name for the picker, in the phone's or TV's language. */
    static String lookLabel(int i) {
        return App.t(LOOK_LABELS[i]);
    }

    static String look() {
        return App.prefs.getString("cursorLook", "dot");
    }

    static final int DRAWN = 5;   // LOOKS before this are drawn, the rest are emoji

    /** What the picker shows in front of a look's name. */
    static String lookIcon(int i) {
        return i < DRAWN ? new String[]{"\u25CF", "\u2196", "\u2295", "\u25EF", "\uD83D\uDFE1"}[i] : LOOKS[i];
    }

    static String lookName() {
        for (int i = 0; i < LOOKS.length; i++) if (LOOKS[i].equals(look())) return lookIcon(i) + "  " + lookLabel(i).replaceFirst(" \\(.*", "");
        return lookLabel(0);
    }

    static final class Dot extends View {
        final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), edge = new Paint(Paint.ANTI_ALIAS_FLAG), emoji = new Paint(Paint.ANTI_ALIAS_FLAG);
        final float radius, density;
        final String look = look();
        float x = -1, y, grow;
        final Paint black, arrowEdge, crossWhite, crossAccent, ringDark, ringAccent, yellow, bigEdge;   // made once: onDraw runs every frame
        final android.graphics.Path arrow = new android.graphics.Path();

        Dot(Context c, float density) {
            super(c);
            density = density * size() / 100f;   // everything below scales with Cursor size
            this.density = density;
            radius = 12 * density;
            fill.setColor(0xF0FFFFFF);
            edge.setColor(Home.ACCENT);
            edge.setStyle(Paint.Style.STROKE);
            edge.setStrokeWidth(3 * density);
            emoji.setTextAlign(Paint.Align.CENTER);
            black = solid(0xFF000000);
            arrowEdge = outline(2 * density, 0xFF000000);
            crossWhite = outline(5 * density, 0xE6FFFFFF);
            crossAccent = outline(2.5f * density, Home.ACCENT);
            ringDark = outline(6 * density, 0x99000000);
            ringAccent = outline(3 * density, Home.ACCENT);
            yellow = solid(0xFFFFD60A);
            bigEdge = outline(3 * density, 0xFF000000);
        }

        void pulse() {
            grow = 1;
            invalidate();
        }

        static Paint outline(float width, int color) {
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(width);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
            p.setColor(color);
            return p;
        }

        static Paint solid(int color) {
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setColor(color);
            return p;
        }

        @Override protected void onDraw(Canvas c) {
            if (x < 0) { x = getWidth() / 2f; y = getHeight() / 2f; }
            float r = radius * (1 + 0.35f * grow), d = density * (1 + 0.35f * grow);
            if (look.equals("dot")) {
                c.drawCircle(x, y, r, fill);
                c.drawCircle(x, y, r, edge);
            } else if (look.equals("arrow")) {   // the classic pointer, its tip on the click point
                android.graphics.Path p = arrow;
                p.reset();
                float u = 1.6f * d;
                p.moveTo(x, y);
                p.lineTo(x, y + 17 * u);
                p.lineTo(x + 4 * u, y + 13 * u);
                p.lineTo(x + 7 * u, y + 20 * u);
                p.lineTo(x + 10 * u, y + 19 * u);
                p.lineTo(x + 7 * u, y + 12 * u);
                p.lineTo(x + 12.5f * u, y + 12 * u);
                p.close();
                c.drawPath(p, fill);
                c.drawPath(p, arrowEdge);
            } else if (look.equals("cross")) {   // thin lines that leave the exact spot open
                float in = 5 * d, out = 18 * d;
                for (int k = 0; k < 2; k++) {
                    Paint pt = k == 0 ? crossWhite : crossAccent;
                    c.drawLine(x - out, y, x - in, y, pt);
                    c.drawLine(x + in, y, x + out, y, pt);
                    c.drawLine(x, y - out, x, y - in, pt);
                    c.drawLine(x, y + in, x, y + out, pt);
                }
                c.drawCircle(x, y, 1.8f * density, fill);
            } else if (look.equals("ring")) {   // hollow, so the button under it stays readable
                c.drawCircle(x, y, 20 * d, ringDark);
                c.drawCircle(x, y, 20 * d, ringAccent);
                c.drawCircle(x, y, 2.5f * density, fill);
            } else if (look.equals("big")) {   // high contrast, for across the room
                c.drawCircle(x, y, r * 1.8f, yellow);
                c.drawCircle(x, y, r * 1.8f, bigEdge);
                c.drawCircle(x, y, 3 * density, black);
            } else {
                emoji.setTextSize(r * 2.8f);
                Paint.FontMetrics fm = emoji.getFontMetrics();
                c.drawText(look, x, y - (fm.ascent + fm.descent) / 2, emoji);
                c.drawCircle(x, y, 3.5f * density, fill);   // where OK clicks
                c.drawCircle(x, y, 3.5f * density, edge);
            }
            if (grow > 0) {
                grow = Math.max(0, grow - 0.12f);
                postInvalidateOnAnimation();
            }
        }
    }

    /** Progress ring shown while OK is held; fires the switch when full. */
    static final class Ring extends View {
        final Paint disc = new Paint(Paint.ANTI_ALIAS_FLAG), track = new Paint(Paint.ANTI_ALIAS_FLAG),
            arc = new Paint(Paint.ANTI_ALIAS_FLAG), label = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Runnable done;
        final float density;
        final RectF box = new RectF();
        long downTime;
        String text;
        boolean active;

        Ring(Context c, float density, Runnable done) {
            super(c);
            this.density = density;
            this.done = done;
            disc.setColor(0xCC120E1A);
            track.setColor(0x40FFFFFF);
            track.setStyle(Paint.Style.STROKE);
            track.setStrokeWidth(6 * density);
            arc.setColor(Home.ACCENT);
            arc.setStyle(Paint.Style.STROKE);
            arc.setStrokeWidth(6 * density);
            arc.setStrokeCap(Paint.Cap.ROUND);
            label.setColor(Color.WHITE);
            label.setTextSize(15 * density);
            label.setTextAlign(Paint.Align.CENTER);
        }

        void start(long downTime, boolean pointerOn) {
            this.downTime = downTime;
            text = App.t(pointerOn ? R.string.pointer_hold_focus : R.string.pointer_hold_pointer);
            active = true;
            setVisibility(View.VISIBLE);
            postInvalidateOnAnimation();
        }

        void stop() {
            active = false;
            setVisibility(View.GONE);
        }

        @Override protected void onDraw(Canvas c) {
            if (!active) return;
            long held = SystemClock.uptimeMillis() - downTime;
            if (held >= HOLD_MS) {
                stop();
                done.run();
                return;
            }
            postInvalidateOnAnimation();
            if (held < RING_AFTER_MS) return;
            float p = (held - RING_AFTER_MS) / (float) (HOLD_MS - RING_AFTER_MS);
            float cx = getWidth() / 2f, cy = getHeight() / 2f, r = 40 * density;
            c.drawCircle(cx, cy, r + 22 * density, disc);
            box.set(cx - r, cy - r, cx + r, cy + r);
            c.drawOval(box, track);
            c.drawArc(box, -90, 360 * p, false, arc);
            c.drawText(text, cx, cy + r + 52 * density, label);
        }
    }
}
