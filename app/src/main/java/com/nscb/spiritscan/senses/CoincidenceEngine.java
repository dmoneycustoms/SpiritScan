package com.nscb.spiritscan.senses;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Cross-channel coincidence engine with an empirical chance baseline.
 *
 * Each independent channel (magnetometer, audio, sonar, camera, passive novelty, magnetic map) reports once a second whether
 * it is active. A channel counts as "recently active" for W = 3 s. An EPISODE is a stretch where at least 2 (or 3) different
 * channels are recently active together. The engine counts real episodes over the retained history (up to 1 h) and compares
 * them with SURROGATES: the same channels, each circularly time-shifted by a random amount. Shifting keeps every channel's own
 * rate and burstiness but destroys timing relationships, so the surrogate episode count is what chance alone produces for
 * THIS session. The p-value is the share of surrogates with at least as many episodes as the real data.
 *
 * What it cannot do: coincidences caused by a COMMON cause (a door slam, a truck, you walking) are not chance, and they
 * still show up as coincidences. It tells you "not chance", never "not mundane".
 */
public final class CoincidenceEngine {
    public static final int CAP = 3600;
    public static final int WINDOW = 3;
    public static final int SURROGATES = 150;
    private static final int MIN_LEN = 600;
    /** seconds between chance re-estimates (tests raise it) */
    public static int EVAL_EVERY = 30;

    public static final class Episode {
        public long id;
        public long startMs;
        public int peakK;
        public int durSec;
        public String channels = "";
        public boolean open;
    }

    public static final class Snapshot {
        public String[] names;
        public boolean[] activeNow;
        public boolean[][] last60;
        public int k;
        public int seconds;
        public boolean ready;
        public int real2;
        public int real3;
        public double exp2;
        public double exp3;
        public double p2;
        public double p3;
        public double perHour2;
        public double expPerHour2;
        public List<Episode> episodes = new ArrayList<Episode>();
        public String verdict = "";
    }

    private final int n;
    private final String[] names;
    private final boolean[][] bits;
    private int head;
    private int len;
    private final Random rnd = new Random(987654321L);
    private long tick;
    private long nextId = 1;
    private final List<Episode> episodes = new ArrayList<Episode>();
    private Episode open;
    private int belowRun;
    private int real2;
    private int real3;
    private double exp2;
    private double exp3;
    private double p2 = 1.0;
    private double p3 = 1.0;
    private long lastSurrogateTick = -1000;
    private int[] sinceActive;
    private int curK;

    public CoincidenceEngine(String[] channelNames) {
        n = channelNames.length;
        names = channelNames.clone();
        bits = new boolean[n][CAP];
        sinceActive = new int[n];
        for (int i = 0; i < n; i++) sinceActive[i] = 1000;
    }

    public synchronized void reset() {
        head = 0;
        len = 0;
        tick = 0;
        episodes.clear();
        open = null;
        belowRun = 0;
        real2 = 0;
        real3 = 0;
        exp2 = 0;
        exp3 = 0;
        p2 = 1.0;
        p3 = 1.0;
        lastSurrogateTick = -1000;
        for (int i = 0; i < n; i++) sinceActive[i] = 1000;
    }

    /** One call per second. */
    public synchronized void tick(boolean[] active, long tMs) {
        tick++;
        for (int i = 0; i < n; i++) {
            bits[i][head] = active[i];
            sinceActive[i] = active[i] ? 0 : Math.min(sinceActive[i] + 1, 1000);
        }
        head = (head + 1) % CAP;
        if (len < CAP) len++;
        int k = 0;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            if (sinceActive[i] < WINDOW) {
                k++;
                if (sb.length() > 0) sb.append("+");
                sb.append(names[i]);
            }
        }
        curK = k;
        if (k >= 2) {
            belowRun = 0;
            if (open == null) {
                open = new Episode();
                open.id = nextId++;
                open.startMs = tMs;
                open.open = true;
                episodes.add(0, open);
                while (episodes.size() > 30) episodes.remove(episodes.size() - 1);
            }
            open.peakK = Math.max(open.peakK, k);
            open.durSec++;
            open.channels = sb.toString();
        } else if (open != null) {
            belowRun++;
            if (belowRun >= 1) {
                open.open = false;
                open = null;
            }
        }
        if (len >= MIN_LEN && tick - lastSurrogateTick >= EVAL_EVERY) {
            lastSurrogateTick = tick;
            evaluate();
        }
    }

    public synchronized void evaluateNow() {
        if (len >= MIN_LEN) evaluate();
    }

    /** chronological bits of channel c over the retained history */
    private boolean[] series(int c) {
        boolean[] s = new boolean[len];
        int start = (head - len + CAP) % CAP;
        for (int i = 0; i < len; i++) s[i] = bits[c][(start + i) % CAP];
        return s;
    }

    /** counts of episodes with peak k>=2 and k>=3 for the given per-channel series (offsets = circular shifts) */
    private int[] episodeCounts(boolean[][] s, int[] shift) {
        int ep2 = 0;
        int ep3 = 0;
        boolean inEp = false;
        int peak = 0;
        int[] since = new int[n];
        for (int c = 0; c < n; c++) since[c] = 1000;
        for (int t = 0; t < len; t++) {
            int k = 0;
            for (int c = 0; c < n; c++) {
                int idx = t + shift[c];
                if (idx >= len) idx -= len;
                since[c] = s[c][idx] ? 0 : Math.min(since[c] + 1, 1000);
                if (since[c] < WINDOW) k++;
            }
            if (k >= 2) {
                inEp = true;
                peak = Math.max(peak, k);
            } else if (inEp) {
                ep2++;
                if (peak >= 3) ep3++;
                inEp = false;
                peak = 0;
            }
        }
        if (inEp) {
            ep2++;
            if (peak >= 3) ep3++;
        }
        return new int[]{ep2, ep3};
    }

    private void evaluate() {
        boolean[][] s = new boolean[n][];
        for (int c = 0; c < n; c++) s[c] = series(c);
        int[] zero = new int[n];
        int[] real = episodeCounts(s, zero);
        real2 = real[0];
        real3 = real[1];
        int ge2 = 0;
        int ge3 = 0;
        double sum2 = 0;
        double sum3 = 0;
        int[] shift = new int[n];
        for (int it = 0; it < SURROGATES; it++) {
            for (int c = 0; c < n; c++) shift[c] = 60 + rnd.nextInt(Math.max(1, len - 120));
            int[] sur = episodeCounts(s, shift);
            sum2 += sur[0];
            sum3 += sur[1];
            if (sur[0] >= real2) ge2++;
            if (sur[1] >= real3) ge3++;
        }
        exp2 = sum2 / SURROGATES;
        exp3 = sum3 / SURROGATES;
        p2 = (1.0 + ge2) / (SURROGATES + 1.0);
        p3 = (1.0 + ge3) / (SURROGATES + 1.0);
    }

    public synchronized Snapshot snapshot() {
        Snapshot sn = new Snapshot();
        sn.names = names.clone();
        sn.activeNow = new boolean[n];
        sn.last60 = new boolean[n][60];
        for (int c = 0; c < n; c++) {
            sn.activeNow[c] = sinceActive[c] < WINDOW;
            for (int i = 0; i < 60; i++) {
                int idx = (head - 60 + i + CAP * 2) % CAP;
                sn.last60[c][i] = i < len && bits[c][idx];
            }
        }
        sn.k = curK;
        sn.seconds = len;
        sn.ready = len >= MIN_LEN;
        sn.real2 = real2;
        sn.real3 = real3;
        sn.exp2 = exp2;
        sn.exp3 = exp3;
        sn.p2 = p2;
        sn.p3 = p3;
        double hours = Math.max(len, 1) / 3600.0;
        sn.perHour2 = real2 / hours;
        sn.expPerHour2 = exp2 / hours;
        for (Episode e : episodes) {
            Episode c = new Episode();
            c.id = e.id;
            c.startMs = e.startMs;
            c.peakK = e.peakK;
            c.durSec = e.durSec;
            c.channels = e.channels;
            c.open = e.open;
            sn.episodes.add(c);
        }
        if (!sn.ready) {
            sn.verdict = "collecting " + (len / 60) + "/" + (MIN_LEN / 60) + " min before chance can be estimated";
        } else if (p3 < 0.01 && real3 > exp3) {
            sn.verdict = "MORE 3+ channel coincidences than chance (p=" + String.format("%.3f", p3)
                + "): look for a common cause (door, traffic, you) before anything else";
        } else if (p2 < 0.01 && real2 > exp2) {
            sn.verdict = "more 2-channel coincidences than chance (p=" + String.format("%.3f", p2) + ")";
        } else {
            sn.verdict = "coincidence rate consistent with chance (2-ch p=" + String.format("%.2f", p2)
                + ", 3-ch p=" + String.format("%.2f", p3) + ")";
        }
        return sn;
    }
}
