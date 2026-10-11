package com.nscb.spiritscan.senses;

import com.nscb.spiritscan.dsp.Fft;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Active ultrasonic sonar using only the phone's speaker and microphone.
 *
 * TX: a Hann-windowed 18.0..20.5 kHz chirp (10.7 ms) repeated every 21.3 ms (46.9 frames/s).
 * RX: every frame is matched-filtered by FFT against the chirp (circular, so the unknown start offset does not matter),
 * giving a complex impulse response out to 3.4 m (6.9 cm range resolution). The strongest return is the direct
 * speaker-to-mic path; every other lag is re-referenced to its phase, which removes clock/timing jitter. The echo is
 * demodulated to baseband and summed in 8.6 cm range bins.
 *  - Static clutter is removed per range bin (slow complex background), leaving MOTION energy in dB over a learned floor.
 *  - A 5.5 s complex FFT per bin gives micro-Doppler (signed velocity, 0.89 cm/s per Hz at 19.25 kHz); a narrow low-velocity
 *    peak is tagged BREATH-LIKE (chest-wall motion), a narrow fast peak PERIODIC (fans), anything else MOTION.
 * It sees movement and rough range, not direction, and not through walls. Pets can hear 18+ kHz: keep the volume low.
 */
public final class SonarEngine {
    public static final int FS = 48000;
    public static final int N = 1024;
    public static final int CHIRP = 512;
    public static final double F0 = 18000.0;
    public static final double F1 = 20500.0;
    /**
     * Demodulation frequency: the chirp centre rounded to a whole number of cycles per frame, so the carrier is exactly
     * periodic over the circular correlation and re-referencing to the direct path cannot introduce phase steps.
     */
    public static final double FC = Math.round(0.5 * (F0 + F1) * N / FS) * (double) FS / N;
    public static final int BIN_LAGS = 24;
    public static final int NB = 40;
    public static final double M_PER_LAG = 343.0 / (2.0 * FS);
    public static final double M_PER_BIN = M_PER_LAG * BIN_LAGS;
    public static final double FRAME_HZ = (double) FS / N;
    /** cm/s of radial velocity per Hz of Doppler shift */
    public static final double CMS_PER_HZ = 0.5 * (343.0 / FC) * 100.0;
    private static final int DOP_N = 256;
    private static final int WARM_FROM = 60;
    private static final int WARM = 150;
    private static final int ROWS = 90;
    private static final int ROW_EVERY = 4;

    public static final class Target {
        public int id;
        public float rangeM;
        public float db;
        public float velCms;
        /** micro-Doppler peak over its own spectral median, dB (long-integration evidence) */
        public float dopDb;
        public String kind = "";
        public float ageSec;
        public boolean living;
    }

    public static final class Snapshot {
        public boolean ready;
        public boolean signal;
        public float directDb;
        public float[] motionDb;
        public float[] staticDb;
        public float[] dopHz;
        public float[] dopDb;
        public List<Target> targets;
        public String note = "";
        public int nb;
        public float mPerBin;
        public int rows;
        public int[] waterfall;
    }

    private static final class Track {
        int id;
        double bin;
        int frames;
        int missed;
        boolean matched;
        double peakDb;
        String kind = "MOTION";
        double vel;
        double dop;
        boolean living;
    }

    private final Fft fft = new Fft(N);
    private final Fft fftD = new Fft(DOP_N);
    private final double[] tr = new double[N];
    private final double[] ti = new double[N];
    private final double[] cosT = new double[N];
    private final double[] sinT = new double[N];
    private final double[] blk = new double[N];
    private int bpos;
    private final double[] re = new double[N];
    private final double[] im = new double[N];
    private final double[] yre = new double[N];
    private final double[] yim = new double[N];
    private final double[] mag = new double[N];
    private final double[] bbR = new double[N];
    private final double[] bbI = new double[N];
    private final double[] tmp2 = new double[2];

    private int frames;
    private int prevLag0 = -1;
    private double prevMag;
    private double magMed = 1.0;
    private boolean signal;
    private double directDb = -120;
    private String note = "starting";

    private final double[] bgR = new double[NB];
    private final double[] bgI = new double[NB];
    private boolean bgInit;
    private final double[] floorE = new double[NB];
    private final double[] sumE = new double[NB];
    private final double[] motionDb = new double[NB];
    private final double[] staticDb = new double[NB];
    private final int[] runs = new int[NB];

    private final double[][] serR = new double[NB][DOP_N];
    private final double[][] serI = new double[NB][DOP_N];
    private int serPos;
    private int serCount;
    private final double[] dopHz = new double[NB];
    private final double[] dopDb = new double[NB];
    private final int[] dopRun = new int[NB];
    private boolean commonMode;
    /** a narrowband micro-Doppler peak this far above its own spectral median is evidence on its own (dB) */
    private static final double DOP_HIT_DB = 16.0;
    private final double[] dre = new double[DOP_N];
    private final double[] dim = new double[DOP_N];
    private final double[] dopWin = new double[DOP_N];

    private final List<Track> tracks = new ArrayList<Track>();
    private int nextId = 1;
    private List<Target> targets = new ArrayList<Target>();

    private final byte[] hist = new byte[ROWS * NB];
    private int head;
    private int rowCount;
    private final float[] rowAcc = new float[NB];
    private static final int[] LUT = Palette.inferno256();

    public SonarEngine() {
        double[] c = chirp();
        for (int i = 0; i < N; i++) {
            re[i] = c[i];
            im[i] = 0;
        }
        fft.forward(re, im);
        for (int k = 0; k < N; k++) {
            tr[k] = re[k];
            ti[k] = im[k];
        }
        for (int j = 0; j < N; j++) {
            double a = 2.0 * Math.PI * Math.round(FC * N / FS) * j / N;
            cosT[j] = Math.cos(a);
            sinT[j] = Math.sin(a);
        }
        for (int i = 0; i < DOP_N; i++) dopWin[i] = 0.5 - 0.5 * Math.cos(2.0 * Math.PI * i / (DOP_N - 1));
    }

    private static double[] chirp() {
        double[] c = new double[N];
        double tc = (double) CHIRP / FS;
        double k = (F1 - F0) / tc;
        for (int i = 0; i < CHIRP; i++) {
            double t = (double) i / FS;
            double w = 0.5 - 0.5 * Math.cos(2.0 * Math.PI * i / (CHIRP - 1));
            c[i] = w * Math.sin(2.0 * Math.PI * (F0 * t + 0.5 * k * t * t));
        }
        return c;
    }

    /** One transmit frame (N samples) to be played back-to-back. amplitude 0..1 of full scale. */
    public static short[] txFrame(double amplitude) {
        double[] c = chirp();
        short[] s = new short[N];
        for (int i = 0; i < N; i++) s[i] = (short) Math.round(c[i] * amplitude * 32767.0);
        return s;
    }

    public synchronized void reset() {
        frames = 0;
        bgInit = false;
        prevLag0 = -1;
        serCount = 0;
        serPos = 0;
        Arrays.fill(runs, 0);
        Arrays.fill(dopRun, 0);
        Arrays.fill(floorE, 0);
        Arrays.fill(sumE, 0);
        tracks.clear();
        targets = new ArrayList<Target>();
        note = "learning the quiet room (3 s)";
    }

    public synchronized void push(short[] pcm, int n) {
        for (int i = 0; i < n; i++) {
            blk[bpos++] = pcm[i];
            if (bpos == N) {
                bpos = 0;
                frame();
            }
        }
    }

    private void frame() {
        for (int i = 0; i < N; i++) {
            re[i] = blk[i] / 32768.0;
            im[i] = 0;
        }
        fft.forward(re, im);
        int half = N / 2;
        for (int k = 0; k <= half; k++) {
            double yr = re[k] * tr[k] + im[k] * ti[k];
            double yi = im[k] * tr[k] - re[k] * ti[k];
            re[k] = yr;
            im[k] = -yi;
        }
        for (int k = half + 1; k < N; k++) {
            re[k] = 0;
            im[k] = 0;
        }
        fft.forward(re, im);
        double[] tmp = new double[N];
        for (int l = 0; l < N; l++) {
            yre[l] = re[l] / N;
            yim[l] = -im[l] / N;
            mag[l] = Math.hypot(yre[l], yim[l]);
            tmp[l] = mag[l];
        }
        Arrays.sort(tmp);
        double med = tmp[N / 2] + 1e-15;
        int lag0 = 0;
        double mx = 0;
        for (int l = 0; l < N; l++) {
            if (mag[l] > mx) {
                mx = mag[l];
                lag0 = l;
            }
        }
        directDb = 20.0 * Math.log10(mx + 1e-12);
        signal = mx > 8.0 * med && mx > 2e-4;
        if (!signal) {
            note = "no echo signal: raise the media volume (speaker or mic may be blocked)";
            frames++;
            return;
        }
        // timing / level glitch (audio underrun, volume change): relearn
        if (prevLag0 >= 0) {
            int d = Math.abs(lag0 - prevLag0);
            d = Math.min(d, N - d);
            double ratio = mx / (prevMag + 1e-15);
            if (d > 4 || ratio > 2.0 || ratio < 0.5) {
                resetKeepSignal();
                note = "audio glitch or volume change: relearning";
            }
        }
        prevLag0 = lag0;
        prevMag = 0.9 * prevMag + 0.1 * mx;
        if (frames == 0) prevMag = mx;
        magMed = med;

        // carrier-free (baseband) correlation: smooth, so it can be interpolated at fractional lags
        for (int l = 0; l < N; l++) {
            bbR[l] = yre[l] * cosT[l] + yim[l] * sinT[l];
            bbI[l] = yim[l] * cosT[l] - yre[l] * sinT[l];
        }
        // sub-sample position of the direct path (parabolic fit on the envelope) so integer lag steps never move the bins
        double mm = mag[(lag0 - 1 + N) % N];
        double m0 = mag[lag0];
        double mp = mag[(lag0 + 1) % N];
        double den = mm - 2.0 * m0 + mp;
        double delta = Math.abs(den) > 1e-18 ? 0.5 * (mm - mp) / den : 0.0;
        if (delta > 0.5) delta = 0.5;
        if (delta < -0.5) delta = -0.5;
        double lstar = lag0 + delta;
        // refine on the interpolated envelope with shrinking steps (the one-shot parabolic fit has a periodic bias)
        for (int it = 0; it < 3; it++) {
            double h = 0.3 / (it + 1);
            double[] v = interp(lstar - h);
            double e1 = Math.hypot(v[0], v[1]);
            v = interp(lstar);
            double e2 = Math.hypot(v[0], v[1]);
            v = interp(lstar + h);
            double e3 = Math.hypot(v[0], v[1]);
            double d2 = e1 - 2.0 * e2 + e3;
            if (Math.abs(d2) > 1e-18) {
                double dd = 0.5 * h * (e1 - e3) / d2;
                if (dd > h) dd = h;
                if (dd < -h) dd = -h;
                lstar += dd;
            }
        }
        double[] ref = interp(lstar);
        double rn = Math.hypot(ref[0], ref[1]) + 1e-30;
        double refR = ref[0] / rn;
        double refI = ref[1] / rn;
        double[] bR = new double[NB];
        double[] bI = new double[NB];
        for (int j = 0; j < NB * BIN_LAGS; j++) {
            double[] v = interp(lstar + j);
            double zr = v[0] * refR + v[1] * refI;
            double zi = v[1] * refR - v[0] * refI;
            int b = j / BIN_LAGS;
            bR[b] += zr;
            bI[b] += zi;
        }
        frames++;
        if (!bgInit) {
            for (int b = 0; b < NB; b++) {
                bgR[b] = bR[b];
                bgI[b] = bI[b];
            }
            bgInit = true;
        }
        // time series for micro-Doppler
        for (int b = 0; b < NB; b++) {
            serR[b][serPos] = bR[b];
            serI[b][serPos] = bI[b];
        }
        serPos = (serPos + 1) % DOP_N;
        if (serCount < DOP_N) serCount++;

        double[] ratio = new double[NB];
        for (int b = 0; b < NB; b++) {
            double mr = bR[b] - bgR[b];
            double mi = bI[b] - bgI[b];
            double e = mr * mr + mi * mi;
            if (frames > WARM_FROM && frames <= WARM) sumE[b] += e;
            if (frames == WARM) floorE[b] = Math.max(sumE[b] / (WARM - WARM_FROM), 1e-18);
            double r = floorE[b] > 0 ? e / floorE[b] : 1.0;
            ratio[b] = r;
            double alpha = (frames > WARM && r > 6.0) ? 0.002 : 0.02;
            bgR[b] += alpha * mr;
            bgI[b] += alpha * mi;
            if (frames > WARM) {
                double fa = r < 6.0 ? 0.01 : 0.0003;
                floorE[b] += fa * (e - floorE[b]);
                if (floorE[b] < 1e-18) floorE[b] = 1e-18;
            }
            motionDb[b] = frames > WARM ? 10.0 * Math.log10(Math.max(r, 1e-3)) : 0.0;
            staticDb[b] = 20.0 * Math.log10(Math.hypot(bgR[b], bgI[b]) + 1e-12);
            if (motionDb[b] > rowAcc[b]) rowAcc[b] = (float) motionDb[b];
        }
        rowCount++;
        if (rowCount >= ROW_EVERY) {
            rowCount = 0;
            int base = head * NB;
            for (int b = 0; b < NB; b++) {
                double v = Math.max(0.0, Math.min(30.0, rowAcc[b]));
                hist[base + b] = (byte) (int) Math.round(v * 255.0 / 30.0);
                rowAcc[b] = 0f;
            }
            head = (head + 1) % ROWS;
        }

        if (frames <= WARM) {
            note = "learning the quiet room (" + (int) (100.0 * frames / WARM) + "%): stay still";
            return;
        }
        if (frames % 16 == 0 && serCount >= DOP_N) doppler();

        boolean[] hit = new boolean[NB];
        for (int b = 1; b < NB; b++) {
            if (ratio[b] >= 12.0) runs[b]++;
            else runs[b] = 0;
            hit[b] = runs[b] >= 3 || dopRun[b] >= 2;
        }
        for (Track t : tracks) t.matched = false;
        int b0 = 1;
        while (b0 < NB) {
            if (!hit[b0]) {
                b0++;
                continue;
            }
            int lo = b0;
            int hi = b0;
            int b1 = b0 + 1;
            while (b1 < NB && (hit[b1] || (b1 + 1 < NB && hit[b1 + 1]))) {
                if (hit[b1]) hi = b1;
                b1++;
            }
            double wsum = 0, csum = 0, pk = 0;
            int pkb = lo;
            for (int b = lo; b <= hi; b++) {
                double w = Math.max(ratio[b], 0.0);
                wsum += w;
                csum += w * b;
                if (ratio[b] > pk) {
                    pk = ratio[b];
                    pkb = b;
                }
            }
            double center = wsum > 0 ? csum / wsum : 0.5 * (lo + hi);
            Track best = null;
            double bd = 2.6;
            for (Track t : tracks) {
                if (t.matched) continue;
                double d = Math.abs(t.bin - center);
                if (d < bd) {
                    bd = d;
                    best = t;
                }
            }
            if (best == null) {
                best = new Track();
                best.id = nextId++;
                tracks.add(best);
            }
            best.matched = true;
            best.missed = 0;
            best.frames++;
            best.bin = center;
            double pdb = 10.0 * Math.log10(Math.max(pk, 1e-3));
            best.peakDb = Math.max(best.peakDb * 0.98, pdb);
            double fd = dopHz[pkb];
            double fdb = dopDb[pkb];
            double fastDb = pdb;
            best.vel = fd * CMS_PER_HZ;
            best.dop = fdb;
            if (pkb <= 3) {
                best.kind = "NEAR-FIELD";
                best.living = true;
            } else if (fdb >= 10.0 && Math.abs(fd) >= 0.18 && Math.abs(fd) <= 1.2 && fastDb < 25.0) {
                best.kind = "BREATH-LIKE";
                best.living = true;
            } else if (fdb >= 14.0 && Math.abs(fd) > 1.2) {
                best.kind = "PERIODIC";
                best.living = false;
            } else {
                best.kind = "MOTION";
                best.living = false;
            }
            hi = Math.max(hi, lo);
            b0 = hi + 1;
        }
        for (int i = tracks.size() - 1; i >= 0; i--) {
            Track t = tracks.get(i);
            if (!t.matched) {
                t.missed++;
                if (t.missed > 40) tracks.remove(i);
            }
        }
        List<Target> out = new ArrayList<Target>();
        for (Track t : tracks) {
            if (t.frames < 3) continue;
            Target g = new Target();
            g.id = t.id;
            g.rangeM = (float) ((t.bin * BIN_LAGS + BIN_LAGS * 0.5) * M_PER_LAG);
            g.db = (float) t.peakDb;
            g.velCms = (float) t.vel;
            g.dopDb = (float) t.dop;
            g.kind = t.kind;
            g.living = t.living;
            g.ageSec = (float) (t.frames / FRAME_HZ);
            out.add(g);
        }
        targets = out;
        note = out.isEmpty() ? "quiet: no motion above the learned floor" : out.size() + " moving target(s)";
        if (commonMode) note += " (common-mode phase wander removed)";
    }

    /** Catmull-Rom cubic interpolation of the complex baseband correlation at fractional lag p (circular). */
    private double[] interp(double p) {
        double fl = Math.floor(p);
        double f = p - fl;
        int i1 = ((int) fl % N + N) % N;
        int i0 = (i1 - 1 + N) % N;
        int i2 = (i1 + 1) % N;
        int i3 = (i1 + 2) % N;
        double f2 = f * f;
        double f3 = f2 * f;
        tmp2[0] = 0.5 * (2.0 * bbR[i1] + (-bbR[i0] + bbR[i2]) * f
                + (2.0 * bbR[i0] - 5.0 * bbR[i1] + 4.0 * bbR[i2] - bbR[i3]) * f2
                + (-bbR[i0] + 3.0 * bbR[i1] - 3.0 * bbR[i2] + bbR[i3]) * f3);
        tmp2[1] = 0.5 * (2.0 * bbI[i1] + (-bbI[i0] + bbI[i2]) * f
                + (2.0 * bbI[i0] - 5.0 * bbI[i1] + 4.0 * bbI[i2] - bbI[i3]) * f2
                + (-bbI[i0] + 3.0 * bbI[i1] - 3.0 * bbI[i2] + bbI[i3]) * f3);
        return tmp2;
    }

    private void resetKeepSignal() {
        frames = 0;
        bgInit = false;
        serCount = 0;
        serPos = 0;
        Arrays.fill(runs, 0);
        Arrays.fill(dopRun, 0);
        Arrays.fill(floorE, 0);
        Arrays.fill(sumE, 0);
        tracks.clear();
        targets = new ArrayList<Target>();
    }

    private void doppler() {
        double[] p = new double[DOP_N];
        double[] sorted = new double[DOP_N - 4];
        for (int b = 1; b < NB; b++) {
            double mr = 0, mi = 0;
            for (int k = 0; k < DOP_N; k++) {
                mr += serR[b][k];
                mi += serI[b][k];
            }
            mr /= DOP_N;
            mi /= DOP_N;
            for (int k = 0; k < DOP_N; k++) {
                int idx = (serPos + k) % DOP_N;
                dre[k] = (serR[b][idx] - mr) * dopWin[k];
                dim[k] = (serI[b][idx] - mi) * dopWin[k];
            }
            fftD.forward(dre, dim);
            int bestK = 2;
            double bestP = 0;
            int ns = 0;
            for (int k = 0; k < DOP_N; k++) {
                p[k] = dre[k] * dre[k] + dim[k] * dim[k];
                int kk = k <= DOP_N / 2 ? k : DOP_N - k;
                if (kk >= 2 && kk <= DOP_N / 2 - 2) {
                    if (ns < sorted.length) sorted[ns++] = p[k];
                    if (p[k] > bestP) {
                        bestP = p[k];
                        bestK = k;
                    }
                }
            }
            double[] s2 = Arrays.copyOf(sorted, ns);
            Arrays.sort(s2);
            double med = s2[ns / 2] + 1e-24;
            int signedK = bestK <= DOP_N / 2 ? bestK : bestK - DOP_N;
            dopHz[b] = signedK * FRAME_HZ / DOP_N;
            dopDb[b] = 10.0 * Math.log10(bestP / med + 1e-9);
        }
        // A real target shows a Doppler line in a few neighbouring bins. The SAME line in many bins means the whole
        // phase reference is wandering (audio clock drift between speaker and mic), so it is removed, not reported.
        int[] cnt = new int[DOP_N];
        int strong = 0;
        for (int b = 1; b < NB; b++) {
            if (dopDb[b] >= 12.0) {
                int kk = (int) Math.round(dopHz[b] * DOP_N / FRAME_HZ);
                cnt[((kk % DOP_N) + DOP_N) % DOP_N]++;
                strong++;
            }
        }
        int bestKk = 0;
        int bestCnt = 0;
        for (int k = 0; k < DOP_N; k++) {
            int c3 = cnt[k] + cnt[(k + 1) % DOP_N] + cnt[(k + DOP_N - 1) % DOP_N];
            if (c3 > bestCnt) {
                bestCnt = c3;
                bestKk = k;
            }
        }
        commonMode = bestCnt >= 6 && bestCnt >= 0.6 * strong;
        for (int b = 1; b < NB; b++) {
            if (commonMode) {
                int kk = (int) Math.round(dopHz[b] * DOP_N / FRAME_HZ);
                int kmod = ((kk % DOP_N) + DOP_N) % DOP_N;
                int dk = Math.abs(kmod - bestKk);
                dk = Math.min(dk, DOP_N - dk);
                if (dk <= 1) dopDb[b] = 0.0;
            }
            if (dopDb[b] >= DOP_HIT_DB) dopRun[b]++;
            else dopRun[b] = 0;
        }
    }

    public synchronized Snapshot snapshot() {
        Snapshot s = new Snapshot();
        s.ready = frames > WARM;
        s.signal = signal;
        s.directDb = (float) directDb;
        s.nb = NB;
        s.mPerBin = (float) M_PER_BIN;
        s.motionDb = new float[NB];
        s.staticDb = new float[NB];
        s.dopHz = new float[NB];
        s.dopDb = new float[NB];
        for (int b = 0; b < NB; b++) {
            s.motionDb[b] = (float) motionDb[b];
            s.staticDb[b] = (float) staticDb[b];
            s.dopHz[b] = (float) dopHz[b];
            s.dopDb[b] = (float) dopDb[b];
        }
        s.targets = new ArrayList<Target>(targets);
        s.note = note;
        s.rows = ROWS;
        s.waterfall = new int[ROWS * NB];
        for (int y = 0; y < ROWS; y++) {
            int src = (head - 1 - y + ROWS * 2) % ROWS;
            for (int b = 0; b < NB; b++) s.waterfall[y * NB + b] = LUT[hist[src * NB + b] & 0xFF];
        }
        return s;
    }
}
