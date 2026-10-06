package com.nscb.spiritscan.dsp;

import java.util.ArrayList;
import java.util.List;

/**
 * Full-band acoustic anomaly scanner (20 Hz .. ~23 kHz at 48 kHz sampling).
 *
 * STFT (4096-pt Hann, 1024 hop) -> 96 log-spaced bands. Each band keeps its OWN adaptive noise floor and spread
 * (anomaly-gated learning, so a tone is not learned while it is being detected). Bands that depart from their floor are
 * grouped into spectral blobs, tracked across frames, classified (TONE / BLIP / CLICK / BROADBAND, ULTRASONIC) and
 * explained where possible (mains hum harmonics, the spirit box's own output frequency).
 * Whatever is left is reported UNEXPLAINED, which means "unclassified here", nothing more.
 */
public final class BandScanner {

    public static final class Anomaly {
        public int id;
        public float centerHz;
        public float loHz;
        public float hiHz;
        public float excessDb;
        public float ageSec;
        public String kind = "";
        public String cause = "";
        public boolean explained;
        /** false = already ended, kept on screen for ~3 s so short events are not missed. */
        public boolean active = true;
    }

    private static final class Track {
        int id;
        double center;
        int lo;
        int hi;
        int frames;
        int missed;
        double peakEx;
        boolean matched;
        int coreLo;
        int coreHi;
        boolean locked;
        String kind = "";
        String cause = "";
        boolean explained;
        float lockLo;
        float lockHi;
    }

    public final int fs;
    public final int nfft;
    public final int hop;
    public final int bands;
    public final int histRows;
    public final float[] edgesHz;
    public final float[] centerHz;

    private static final int INIT_FRAMES = 48;
    private static final int ROW_EVERY = 6;
    private static final int LINGER_FRAMES = 140;
    private static final double THR_Z = 4.5;
    private static final double THR_DB = 7.0;

    private final Fft fft;
    private final double[] win;
    private final double winSum;
    private final double[] ring;
    private int wpos;
    private long total;
    private int sinceHop;
    private double dcX;
    private double dcY;

    private final double[] re;
    private final double[] im;
    private final int[] binLo;
    private final int[] binHi;

    private final double[] floorDb;
    private final double[] sdDb;
    private final double[] initSum;
    private final double[] initSq;
    private int frames;
    private int fastAdapt;
    private int burstHold;

    private final double[] lvl;
    private final float[] excessNow;
    private final byte[] hist;
    private int head;
    private int rowCount;
    private final float[] rowAcc;

    private final List<Track> tracks = new ArrayList<Track>();
    private int nextId = 1;
    private final List<Anomaly> current = new ArrayList<Anomaly>();
    private double ownHz;
    private double levelDb = -120.0;
    private long confirmedCount;

    private static final int[] LUT = buildLut();

    public BandScanner(int fs, int nfft, int hop, int bands, int histRows, double fMin, double fMax) {
        this.fs = fs;
        this.nfft = nfft;
        this.hop = hop;
        this.bands = bands;
        this.histRows = histRows;
        fft = new Fft(nfft);
        win = new double[nfft];
        double s = 0;
        for (int i = 0; i < nfft; i++) {
            win[i] = 0.5 - 0.5 * Math.cos(2.0 * Math.PI * i / nfft);
            s += win[i];
        }
        winSum = s;
        ring = new double[nfft];
        re = new double[nfft];
        im = new double[nfft];
        edgesHz = new float[bands + 1];
        centerHz = new float[bands];
        binLo = new int[bands];
        binHi = new int[bands];
        double df = (double) fs / nfft;
        double ratio = fMax / fMin;
        for (int b = 0; b <= bands; b++) {
            edgesHz[b] = (float) (fMin * Math.pow(ratio, (double) b / bands));
        }
        int maxBin = nfft / 2 - 1;
        for (int b = 0; b < bands; b++) {
            centerHz[b] = (float) Math.sqrt((double) edgesHz[b] * edgesHz[b + 1]);
            int lo = (int) Math.floor(edgesHz[b] / df);
            int hi = (int) Math.ceil(edgesHz[b + 1] / df) - 1;
            if (lo < 1) lo = 1;
            if (hi < lo) hi = lo;
            if (hi > maxBin) hi = maxBin;
            if (lo > hi) lo = hi;
            binLo[b] = lo;
            binHi[b] = hi;
        }
        floorDb = new double[bands];
        sdDb = new double[bands];
        initSum = new double[bands];
        initSq = new double[bands];
        lvl = new double[bands];
        excessNow = new float[bands];
        hist = new byte[histRows * bands];
        rowAcc = new float[bands];
    }

    /** Frequency (Hz) the spirit box is currently emitting, or 0. Events near it are explained as "OWN BOX". */
    public synchronized void setOwnHz(double hz) {
        ownHz = hz;
    }

    /** Re-learn floors quickly for ~3 s (call when the box turns on/off or the room changes). */
    public synchronized void fastAdapt() {
        fastAdapt = 140;
    }

    public synchronized void reset() {
        frames = 0;
        tracks.clear();
        current.clear();
        java.util.Arrays.fill(initSum, 0);
        java.util.Arrays.fill(initSq, 0);
        java.util.Arrays.fill(hist, (byte) 0);
        java.util.Arrays.fill(excessNow, 0f);
        rowCount = 0;
    }

    public synchronized void push(short[] pcm, int n) {
        for (int i = 0; i < n; i++) {
            double x = pcm[i] / 32768.0;
            double y = x - dcX + 0.9995 * dcY;
            dcX = x;
            dcY = y;
            ring[wpos] = y;
            wpos++;
            if (wpos == nfft) wpos = 0;
            total++;
            sinceHop++;
            if (sinceHop >= hop && total >= nfft) {
                sinceHop = 0;
                frame();
            }
        }
    }

    private void frame() {
        double e = 0;
        for (int i = 0; i < nfft; i++) {
            double v = ring[(wpos + i) % nfft];
            e += v * v;
            re[i] = v * win[i];
            im[i] = 0;
        }
        levelDb = 10.0 * Math.log10(e / nfft + 1e-14);
        fft.forward(re, im);
        double norm = (winSum / 2.0) * (winSum / 2.0);
        for (int b = 0; b < bands; b++) {
            double mx = 0;
            for (int k = binLo[b]; k <= binHi[b]; k++) {
                double p = re[k] * re[k] + im[k] * im[k];
                if (p > mx) mx = p;
            }
            lvl[b] = 10.0 * Math.log10(mx / norm + 1e-13);
        }
        frames++;
        if (frames <= INIT_FRAMES) {
            for (int b = 0; b < bands; b++) {
                initSum[b] += lvl[b];
                initSq[b] += lvl[b] * lvl[b];
            }
            if (frames == INIT_FRAMES) {
                for (int b = 0; b < bands; b++) {
                    double m = initSum[b] / INIT_FRAMES;
                    double v = initSq[b] / INIT_FRAMES - m * m;
                    floorDb[b] = m;
                    sdDb[b] = Math.max(1.5, Math.sqrt(Math.max(v, 0.0)));
                }
            }
            return;
        }

        boolean[] hit = new boolean[bands];
        for (int b = 0; b < bands; b++) {
            double ex = lvl[b] - floorDb[b];
            double z = ex / Math.max(sdDb[b], 1.5);
            excessNow[b] = (float) ex;
            hit[b] = z > THR_Z && ex > THR_DB;
            double alpha = (fastAdapt > 0) ? 0.08 : (z > 3.0 ? 0.0004 : 0.01);
            floorDb[b] += alpha * (lvl[b] - floorDb[b]);
            if (z < 3.0 || fastAdapt > 0) {
                double d = lvl[b] - floorDb[b];
                double v = sdDb[b] * sdDb[b];
                v += 0.01 * (d * d - v);
                sdDb[b] = Math.max(1.5, Math.sqrt(v));
            }
            if (ex > rowAcc[b]) rowAcc[b] = (float) ex;
        }
        if (fastAdapt > 0) {
            fastAdapt--;
            java.util.Arrays.fill(hit, false);
        }

        // A wideband burst (click/thump) lights most bands at once and then rings in the low bands for ~0.25 s.
        // Treat it as ONE event and ignore the ringing instead of spawning hundreds of tracks.
        int nHit = 0;
        int firstHit = -1;
        int lastHit = -1;
        for (int b = 0; b < bands; b++) {
            if (hit[b]) {
                nHit++;
                if (firstHit < 0) firstHit = b;
                lastHit = b;
            }
        }
        if (nHit >= bands * 0.4) {
            burstHold = 12;
            for (int b = firstHit; b <= lastHit; b++) hit[b] = true;
        } else if (burstHold > 0) {
            burstHold--;
            java.util.Arrays.fill(hit, false);
        }

        // group hit bands (gap of one band allowed)
        List<int[]> groups = new ArrayList<int[]>();
        int b0 = 0;
        while (b0 < bands) {
            if (!hit[b0]) {
                b0++;
                continue;
            }
            int lo = b0;
            int hi = b0;
            int b1 = b0 + 1;
            while (b1 < bands && (hit[b1] || (b1 + 1 < bands && hit[b1 + 1]))) {
                if (hit[b1]) hi = b1;
                b1++;
            }
            groups.add(new int[]{lo, hi});
            b0 = hi + 1;
        }

        for (Track t : tracks) t.matched = false;
        double hopSec = (double) hop / fs;
        for (int[] g : groups) {
            double wsum = 0;
            double csum = 0;
            double peak = 0;
            for (int b = g[0]; b <= g[1]; b++) {
                double ex = Math.max(excessNow[b], 0.0);
                wsum += ex;
                csum += ex * b;
                if (ex > peak) peak = ex;
            }
            double center = wsum > 0 ? csum / wsum : 0.5 * (g[0] + g[1]);
            int cLo = g[1];
            int cHi = g[0];
            for (int b = g[0]; b <= g[1]; b++) {
                if (excessNow[b] >= peak - 15.0) {
                    if (b < cLo) cLo = b;
                    if (b > cHi) cHi = b;
                }
            }
            Track best = null;
            double bd = 2.6;
            for (Track t : tracks) {
                if (t.matched || t.missed > 6) continue;
                double d = Math.abs(t.center - center);
                if (d < bd) {
                    bd = d;
                    best = t;
                }
            }
            if (best == null) {
                best = new Track();
                best.id = nextId++;
                best.lo = g[0];
                best.hi = g[1];
                best.coreLo = cLo;
                best.coreHi = cHi;
                tracks.add(best);
            }
            best.matched = true;
            best.missed = 0;
            best.frames++;
            best.center = center;
            best.lo = Math.min(best.lo, g[0]);
            best.hi = Math.max(best.hi, g[1]);
            // latest span, not a lifetime union: switch-on splatter must not widen a steady tone forever
            best.coreLo = cLo;
            best.coreHi = cHi;
            best.peakEx = Math.max(best.peakEx, peak);
        }
        for (int i = tracks.size() - 1; i >= 0; i--) {
            Track t = tracks.get(i);
            if (!t.matched) {
                t.missed++;
                int keepFrames = t.frames >= (t.peakEx >= 20.0 ? 2 : 3) ? LINGER_FRAMES : 6;
                if (t.missed > keepFrames) tracks.remove(i);
            }
        }

        current.clear();
        for (Track t : tracks) {
            int need = t.peakEx >= 20.0 ? 2 : 3;
            if (t.frames < need) continue;
            Anomaly a = new Anomaly();
            a.id = t.id;
            int ci = (int) Math.round(t.center);
            ci = Math.max(0, Math.min(bands - 1, ci));
            a.centerHz = centerHz[ci];
            a.active = t.missed == 0;
            a.loHz = edgesHz[t.coreLo];
            a.hiHz = edgesHz[t.coreHi + 1];
            a.excessDb = (float) t.peakEx;
            a.ageSec = (float) (t.frames * hopSec);
            double df = (double) fs / nfft;
            double widthHz = a.hiHz - a.loHz;
            boolean narrow = widthHz <= Math.max(6.0 * df, 0.10 * a.centerHz);
            boolean wide = widthHz >= Math.max(20.0 * df, 0.45 * a.centerHz);
            boolean steady = a.ageSec >= 0.4f;
            String kind;
            if (!steady) {
                kind = wide ? "CLICK" : "BLIP";
            } else {
                kind = narrow ? "TONE" : (wide ? "BROADBAND" : "BAND");
            }
            if (a.centerHz > 18000f) kind = "ULTRASONIC " + kind;
            a.kind = kind;
            if (steady && narrow && nearMains(a.centerHz)) {
                a.cause = "MAINS HUM";
                a.explained = true;
            } else if (ownHz > 0 && Math.abs(a.centerHz - ownHz) / ownHz < 0.15) {
                a.cause = "OWN BOX";
                a.explained = true;
            } else {
                a.cause = "UNEXPLAINED";
                a.explained = false;
                if (t.frames == need) confirmedCount++;
            }
            // Classification is frozen once steady, so a tone's switch-off splatter cannot relabel it.
            if (t.locked) {
                a.kind = t.kind;
                a.cause = t.cause;
                a.explained = t.explained;
                a.loHz = t.lockLo;
                a.hiHz = t.lockHi;
            } else if (steady) {
                t.locked = true;
                t.kind = a.kind;
                t.cause = a.cause;
                t.explained = a.explained;
                t.lockLo = a.loHz;
                t.lockHi = a.hiHz;
            }
            current.add(a);
        }

        rowCount++;
        if (rowCount >= ROW_EVERY) {
            rowCount = 0;
            int base = head * bands;
            for (int b = 0; b < bands; b++) {
                double v = Math.max(0.0, Math.min(40.0, rowAcc[b]));
                hist[base + b] = (byte) (int) Math.round(v * 255.0 / 40.0);
                rowAcc[b] = 0f;
            }
            head++;
            if (head >= histRows) head = 0;
        }
    }

    private static boolean nearMains(double f) {
        double[] base = {50.0, 60.0};
        for (double f0 : base) {
            for (int k = 1; k <= 12; k++) {
                double h = f0 * k;
                if (Math.abs(f - h) / h < 0.04) return true;
            }
        }
        return false;
    }

    public synchronized List<Anomaly> anomalies() {
        return new ArrayList<Anomaly>(current);
    }

    public synchronized float[] excess() {
        return excessNow.clone();
    }

    public synchronized float levelDb() {
        return (float) levelDb;
    }

    public synchronized long confirmedCount() {
        return confirmedCount;
    }

    public synchronized boolean ready() {
        return frames > INIT_FRAMES;
    }

    /** Newest row first. out.length must be bands * histRows. Colour = excess over floor (0..40 dB). */
    public synchronized void fillWaterfall(int[] out) {
        for (int y = 0; y < histRows; y++) {
            int src = (head - 1 - y + histRows * 2) % histRows;
            int sb = src * bands;
            int ob = y * bands;
            for (int b = 0; b < bands; b++) {
                out[ob + b] = LUT[hist[sb + b] & 0xFF];
            }
        }
    }

    private static int[] buildLut() {
        double[][] c = {
            {0.00021894, 0.00165100, -0.01948090},
            {0.10651341, 0.56395644, 3.93271230},
            {11.602494, -3.9728539, -15.942394},
            {-41.703995, 17.436398, 44.354145},
            {77.162935, -33.402359, -81.80731},
            {-71.319428, 32.626064, 73.20952},
            {25.13113, -12.242669, -23.070327}
        };
        int[] lut = new int[256];
        for (int i = 0; i < 256; i++) {
            double t = i / 255.0;
            int[] rgb = new int[3];
            for (int ch = 0; ch < 3; ch++) {
                double v = 0;
                for (int d = 6; d >= 0; d--) v = v * t + c[d][ch];
                rgb[ch] = (int) Math.round(Math.max(0.0, Math.min(1.0, v)) * 255.0);
            }
            lut[i] = 0xFF000000 | (rgb[0] << 16) | (rgb[1] << 8) | rgb[2];
        }
        return lut;
    }
}
