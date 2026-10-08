package com.nscb.spiritscan.dsp;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Passive multi-sensor novelty detector. Pure logic; the neural network runs outside (ONNX Runtime) and is fed through
 * [push] / [finishStep].
 *
 * Every second the app pushes one raw feature vector (12 channels). The core
 *  1. learns a BASELINE (mean/std per channel, covariance) for this phone and room,
 *  2. standardises, keeps a 16 s window and hands it to the autoencoder (192 inputs),
 *  3. scores each window two independent ways, both relative to the baseline:
 *       MODEL: mean of the 8 largest squared reconstruction errors (robust z against the baseline windows)
 *       STATS: Mahalanobis distance of the current vector (robust z against the baseline vectors)
 *  4. opens an EVENT when either score stays >= 6 for 3 s and closes it after 5 s below 3 (tuned on synthetic data:
 *     0 false events/hour with a 5 min baseline, real disturbances caught; real rooms are less stationary).
 * Novelty means "unlike the baseline": a door opening, a phone call or a lamp turning on all qualify.
 */
public final class NoveltyCore {
    public static final int C = 12;
    public static final int T = 16;
    public static final int D = C * T;
    public static final int PH_IDLE = 0;
    public static final int PH_LEARN = 1;
    public static final int PH_CALIB = 2;
    public static final int PH_READY = 3;
    private static final int HIST = 600;
    private static final int TOPK = 8;
    /** Tunable (tests sweep them). A "z" here is relative to the upper-tail spread of the baseline, not a Gaussian sigma. */
    public static double OPEN_Z = 6.0;
    public static double CLOSE_Z = 3.0;
    public static int OPEN_RUN = 3;

    private final String[] names;
    private int phase = PH_IDLE;
    private int learnTarget;
    private final List<float[]> base = new ArrayList<float[]>();

    private final double[] mean = new double[C];
    private final double[] std = new double[C];
    private final boolean[] active = new boolean[C];
    private int nActive;
    private double[][] covInv;
    private double mhMed = 1.0;
    private double mhScale = 1.0;

    private final float[][] ring = new float[T][C];
    private int ringN;
    private int ringPos;

    private boolean aeAvailable;
    private double aeMed = 0.0;
    private double aeScale = 1.0;
    private final double[] chMed = new double[C];
    private final double[] chScale = new double[C];

    private final float[] aeHist = new float[HIST];
    private final float[] mhHist = new float[HIST];
    private int histN;
    private double aeZ;
    private double mhZ;
    private String top = "";
    private float[] latest = new float[C];
    private int seconds;

    private double pendingMh;
    private float[] pendingStd;
    private long pendingMs;

    private final List<NoveltyEvent> events = new ArrayList<NoveltyEvent>();
    private NoveltyEvent open;
    private int aboveRun;
    private int belowRun;
    private long nextId = 1;
    private double peakAe;
    private double peakMh;
    private String peakChannels = "";

    public NoveltyCore(String[] channelNames) {
        if (channelNames.length != C) throw new IllegalArgumentException("need " + C + " channel names");
        names = channelNames.clone();
    }

    public synchronized int phase() {
        return phase;
    }

    public synchronized void stop() {
        phase = PH_IDLE;
        open = null;
    }

    public synchronized void clearEvents() {
        events.clear();
        open = null;
    }

    public synchronized void startLearn(int seconds) {
        phase = PH_LEARN;
        learnTarget = Math.max(seconds, T + 20);
        base.clear();
        ringN = 0;
        ringPos = 0;
        histN = 0;
        aboveRun = 0;
        belowRun = 0;
        open = null;
    }

    /** Feed one raw vector. Returns the 192-float window for the model when one is ready, else null. */
    public synchronized float[] push(float[] raw, long tMs) {
        seconds++;
        latest = raw.clone();
        if (phase == PH_IDLE || phase == PH_CALIB) return null;
        if (phase == PH_LEARN) {
            base.add(raw.clone());
            if (base.size() >= learnTarget) finishStats();
            return null;
        }
        // READY
        float[] x = standardise(raw);
        pendingStd = x;
        pendingMs = tMs;
        pendingMh = mahalanobis(x);
        System.arraycopy(x, 0, ring[ringPos], 0, C);
        ringPos = (ringPos + 1) % T;
        if (ringN < T) ringN++;
        if (ringN >= T && aeAvailable) {
            float[] w = new float[D];
            for (int t = 0; t < T; t++) {
                System.arraycopy(ring[(ringPos + t) % T], 0, w, t * C, C);
            }
            return w;
        }
        finishInternal(Double.NaN, null, tMs);
        return null;
    }

    /** Call once for every non-null window returned by [push]. [recon] may be null if the model failed. */
    public synchronized void finishStep(float[] recon, float[] window) {
        if (phase != PH_READY || pendingStd == null) return;
        if (recon == null || window == null) {
            finishInternal(Double.NaN, null, pendingMs);
            return;
        }
        double[] chSum = new double[C];
        double score = aeScore(window, recon, chSum);
        double z = (score - aeMed) / (aeScale + 1e-9);
        double[] contrib = new double[C];
        for (int c = 0; c < C; c++) contrib[c] = (chSum[c] - chMed[c]) / (chScale[c] + 1e-6);
        finishInternal(z, contrib, pendingMs);
    }

    // ---------------------------------------------------------------- calibration with the model
    /** Windows (from the baseline) the model should reconstruct so its normal error level can be measured. */
    public synchronized List<float[]> calibrationWindows() {
        List<float[]> out = new ArrayList<float[]>();
        List<float[]> stdVecs = new ArrayList<float[]>();
        for (float[] r : base) stdVecs.add(standardise(r));
        for (int end = T - 1; end < stdVecs.size(); end += 2) {
            float[] w = new float[D];
            for (int t = 0; t < T; t++) System.arraycopy(stdVecs.get(end - T + 1 + t), 0, w, t * C, C);
            out.add(w);
        }
        return out;
    }

    /** [recons] same length/order as [calibrationWindows]; pass null if no model is available. */
    public synchronized void finishCalibration(List<float[]> windows, List<float[]> recons) {
        if (recons == null || windows == null || recons.size() != windows.size() || windows.size() < 8) {
            aeAvailable = false;
        } else {
            int n = windows.size();
            double[] sc = new double[n];
            double[][] ch = new double[C][n];
            for (int i = 0; i < n; i++) {
                double[] chSum = new double[C];
                sc[i] = aeScore(windows.get(i), recons.get(i), chSum);
                for (int c = 0; c < C; c++) ch[c][i] = chSum[c];
            }
            aeMed = median(sc);
            aeScale = upperScale(sc, aeMed);
            for (int c = 0; c < C; c++) {
                chMed[c] = median(ch[c]);
                chScale[c] = upperScale(ch[c], chMed[c]);
            }
            aeAvailable = true;
        }
        phase = PH_READY;
    }

    private double aeScore(float[] w, float[] r, double[] chSum) {
        double[] e = new double[D];
        for (int i = 0; i < D; i++) {
            double d = w[i] - r[i];
            e[i] = d * d;
            chSum[i % C] += e[i];
        }
        Arrays.sort(e);
        double s = 0;
        for (int i = D - TOPK; i < D; i++) s += e[i];
        return s / TOPK;
    }

    // ---------------------------------------------------------------- baseline statistics
    private void finishStats() {
        int n = base.size();
        for (int c = 0; c < C; c++) {
            double m = 0;
            for (float[] v : base) m += v[c];
            m /= n;
            double s = 0;
            for (float[] v : base) s += (v[c] - m) * (v[c] - m);
            s = Math.sqrt(s / Math.max(1, n - 1));
            mean[c] = m;
            std[c] = s;
            active[c] = s > 1e-6 && s > 1e-4 * (Math.abs(m) + 1e-3);
        }
        nActive = 0;
        int[] idx = new int[C];
        for (int c = 0; c < C; c++) if (active[c]) idx[nActive++] = c;
        covInv = null;
        if (nActive >= 1) {
            double[][] cov = new double[nActive][nActive];
            for (float[] v : base) {
                double[] z = new double[nActive];
                for (int i = 0; i < nActive; i++) z[i] = (v[idx[i]] - mean[idx[i]]) / std[idx[i]];
                for (int i = 0; i < nActive; i++) for (int j = 0; j < nActive; j++) cov[i][j] += z[i] * z[j];
            }
            for (int i = 0; i < nActive; i++) {
                for (int j = 0; j < nActive; j++) cov[i][j] /= Math.max(1, n - 1);
                cov[i][i] += 0.05;
            }
            covInv = invert(cov);
        }
        double[] d = new double[n];
        for (int i = 0; i < n; i++) d[i] = Math.sqrt(mahalanobis(standardise(base.get(i))));
        mhMed = median(d);
        mhScale = upperScale(d, mhMed);
        phase = PH_CALIB;
    }

    private float[] standardise(float[] raw) {
        float[] x = new float[C];
        for (int c = 0; c < C; c++) {
            if (!active[c]) continue;
            double z = (raw[c] - mean[c]) / std[c];
            if (z > 8) z = 8;
            if (z < -8) z = -8;
            x[c] = (float) z;
        }
        return x;
    }

    private double mahalanobis(float[] x) {
        if (covInv == null || nActive == 0) return 0.0;
        double[] v = new double[nActive];
        int k = 0;
        for (int c = 0; c < C; c++) if (active[c]) v[k++] = x[c];
        double s = 0;
        for (int i = 0; i < nActive; i++) {
            double row = 0;
            for (int j = 0; j < nActive; j++) row += covInv[i][j] * v[j];
            s += v[i] * row;
        }
        return Math.max(s, 0.0);
    }

    // ---------------------------------------------------------------- per-step bookkeeping + events
    private void finishInternal(double ae, double[] contrib, long tMs) {
        double mz = (Math.sqrt(pendingMh) - mhMed) / mhScale;
        if (mz < -5) mz = -5;
        mhZ = mz;
        aeZ = Double.isNaN(ae) ? 0.0 : Math.max(ae, -5.0);
        // top contributing channels
        String topNow = "";
        if (contrib != null) {
            Integer[] order = new Integer[C];
            for (int i = 0; i < C; i++) order[i] = i;
            final double[] cc = contrib;
            Arrays.sort(order, (a, b) -> Double.compare(cc[b], cc[a]));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 3; i++) {
                if (cc[order[i]] > 2.0) {
                    if (sb.length() > 0) sb.append(", ");
                    sb.append(names[order[i]]);
                }
            }
            topNow = sb.toString();
        }
        if (topNow.isEmpty()) {
            int best = -1;
            double bv = 1.5;
            for (int c = 0; c < C; c++) {
                if (active[c] && Math.abs(pendingStd[c]) > bv) {
                    bv = Math.abs(pendingStd[c]);
                    best = c;
                }
            }
            if (best >= 0) topNow = names[best];
        }
        top = topNow;
        if (histN < HIST) {
            aeHist[histN] = (float) aeZ;
            mhHist[histN] = (float) mhZ;
            histN++;
        } else {
            System.arraycopy(aeHist, 1, aeHist, 0, HIST - 1);
            System.arraycopy(mhHist, 1, mhHist, 0, HIST - 1);
            aeHist[HIST - 1] = (float) aeZ;
            mhHist[HIST - 1] = (float) mhZ;
        }

        double z = Math.max(aeZ, mhZ);
        if (z >= OPEN_Z) {
            aboveRun++;
            belowRun = 0;
        } else if (z < CLOSE_Z) {
            belowRun++;
            aboveRun = 0;
        } else {
            belowRun = 0;
        }
        if (open == null && aboveRun >= OPEN_RUN) {
            open = new NoveltyEvent();
            open.id = nextId++;
            open.startMs = tMs - 2000;
            open.open = true;
            peakAe = 0;
            peakMh = 0;
            peakChannels = "";
            events.add(0, open);
            while (events.size() > 30) events.remove(events.size() - 1);
        }
        if (open != null) {
            if (aeZ > peakAe) peakAe = aeZ;
            if (mhZ > peakMh) peakMh = mhZ;
            if (z >= Math.max(open.peakZ, OPEN_Z) && !top.isEmpty()) peakChannels = top;
            open.peakZ = (float) Math.max(peakAe, peakMh);
            open.durSec = (tMs - open.startMs) / 1000f;
            boolean m = peakAe >= OPEN_Z;
            boolean s = peakMh >= OPEN_Z;
            open.kind = m && s ? "BOTH" : (m ? "MODEL" : "STATS");
            open.channels = peakChannels;
            if (belowRun >= 5) {
                open.open = false;
                open = null;
            }
        }
    }

    public synchronized NoveltySnapshot snapshot() {
        NoveltySnapshot s = new NoveltySnapshot();
        s.phase = phase;
        s.learnProgress = phase == PH_LEARN ? Math.min(1f, base.size() / (float) Math.max(1, learnTarget)) : 1f;
        s.aeAvailable = aeAvailable;
        s.aeZ = (float) aeZ;
        s.mahaZ = (float) mhZ;
        s.aeHist = Arrays.copyOf(aeHist, histN);
        s.mahaHist = Arrays.copyOf(mhHist, histN);
        s.names = names.clone();
        s.latest = latest.clone();
        s.active = active.clone();
        s.topChannels = top;
        List<NoveltyEvent> copy = new ArrayList<NoveltyEvent>();
        for (NoveltyEvent e : events) copy.add(e);
        s.events = copy;
        s.seconds = seconds;
        return s;
    }

    // ---------------------------------------------------------------- numerics
    private static double median(double[] a) {
        double[] c = a.clone();
        Arrays.sort(c);
        int n = c.length;
        return n == 0 ? 0.0 : (n % 2 == 1 ? c[n / 2] : 0.5 * (c[n / 2 - 1] + c[n / 2]));
    }

    /** Upper-tail spread: distance from the median to the 84th percentile (one "sigma" for skewed data). */
    private static double upperScale(double[] a, double med) {
        double[] c = a.clone();
        Arrays.sort(c);
        int n = c.length;
        if (n == 0) return 1.0;
        double q84 = c[Math.min(n - 1, (int) Math.floor(0.84 * n))];
        double q97 = c[Math.min(n - 1, (int) Math.floor(0.975 * n))];
        double s = Math.max(q84 - med, 0.5 * (q97 - med) / 2.0);
        return Math.max(s, 1e-6);
    }

    private static double[][] invert(double[][] a) {
        int n = a.length;
        double[][] m = new double[n][2 * n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) m[i][j] = a[i][j];
            m[i][n + i] = 1.0;
        }
        for (int col = 0; col < n; col++) {
            int piv = col;
            for (int r = col + 1; r < n; r++) if (Math.abs(m[r][col]) > Math.abs(m[piv][col])) piv = r;
            double[] t = m[col];
            m[col] = m[piv];
            m[piv] = t;
            double d = m[col][col];
            if (Math.abs(d) < 1e-12) d = 1e-12;
            for (int j = 0; j < 2 * n; j++) m[col][j] /= d;
            for (int r = 0; r < n; r++) {
                if (r == col) continue;
                double f = m[r][col];
                if (f == 0) continue;
                for (int j = 0; j < 2 * n; j++) m[r][j] -= f * m[col][j];
            }
        }
        double[][] inv = new double[n][n];
        for (int i = 0; i < n; i++) for (int j = 0; j < n; j++) inv[i][j] = m[i][n + j];
        return inv;
    }
}
