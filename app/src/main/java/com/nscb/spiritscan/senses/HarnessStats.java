package com.nscb.spiritscan.senses;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * Scoring for the ground-truth harness. Each window yields one score (the strongest response of a channel during the window).
 * Windows are either SHAM (no stimulus) or a stimulus at one of several levels, ordered weakest first.
 *  - fixed hit: score >= the channel's own detection threshold
 *  - empirical hit: score > the highest sham score (a threshold with zero false alarms on this very session)
 *  - AUC of stimulus windows vs sham windows with an exact permutation p-value (one-sided)
 *  - detection limit: the weakest level from which every stronger level is detected (empirically) in at least half its windows
 */
public final class HarnessStats {

    public static final class LevelResult {
        public String label = "";
        public int n;
        public int hitsFixed;
        public int hitsEmpirical;
        public double median;
        public double max;
    }

    public static final class Report {
        public List<LevelResult> levels = new ArrayList<LevelResult>();
        public int shamN;
        public int shamFalseFixed;
        public double shamMedian;
        public double shamMax;
        public double auc;
        public double pValue;
        public boolean exact;
        public int limitIndex = -1;
        public String verdict = "";
    }

    private HarnessStats() {
    }

    /** Balanced random window order: every level appears [repsPerLevel] times, plus [shams] shams; shuffled with the seed. */
    public static int[] schedule(int levels, int repsPerLevel, int shams, long seed) {
        int n = levels * repsPerLevel + shams;
        int[] w = new int[n];
        int k = 0;
        for (int l = 0; l < levels; l++) for (int r = 0; r < repsPerLevel; r++) w[k++] = l;
        for (int s = 0; s < shams; s++) w[k++] = -1;
        Random rnd = new Random(seed);
        for (int attempt = 0; attempt < 50; attempt++) {
            for (int i = n - 1; i > 0; i--) {
                int j = rnd.nextInt(i + 1);
                int t = w[i];
                w[i] = w[j];
                w[j] = t;
            }
            // avoid long runs of the same window type (a stimulus fatigue / expectation confound)
            boolean ok = true;
            for (int i = 2; i < n; i++) {
                boolean a = w[i] < 0;
                if (a == (w[i - 1] < 0) && a == (w[i - 2] < 0)) {
                    ok = false;
                    break;
                }
            }
            if (ok) break;
        }
        return w;
    }

    private static double median(List<Double> v) {
        if (v.isEmpty()) return Double.NaN;
        List<Double> c = new ArrayList<Double>(v);
        java.util.Collections.sort(c);
        int n = c.size();
        return n % 2 == 1 ? c.get(n / 2) : 0.5 * (c.get(n / 2 - 1) + c.get(n / 2));
    }

    private static double auc(double[] stim, double[] sham) {
        if (stim.length == 0 || sham.length == 0) return 0.5;
        double u = 0;
        for (double a : stim) {
            for (double b : sham) {
                if (a > b) u += 1.0;
                else if (a == b) u += 0.5;
            }
        }
        return u / ((double) stim.length * sham.length);
    }

    public static Report analyze(String[] labels, int[] windowLevel, double[] score, double fixedThreshold) {
        Report r = new Report();
        int nl = labels.length;
        List<List<Double>> per = new ArrayList<List<Double>>();
        for (int l = 0; l < nl; l++) per.add(new ArrayList<Double>());
        List<Double> sham = new ArrayList<Double>();
        List<Double> stimAll = new ArrayList<Double>();
        for (int i = 0; i < windowLevel.length; i++) {
            if (windowLevel[i] < 0) sham.add(score[i]);
            else {
                per.get(windowLevel[i]).add(score[i]);
                stimAll.add(score[i]);
            }
        }
        r.shamN = sham.size();
        r.shamMedian = median(sham);
        double smax = Double.NEGATIVE_INFINITY;
        for (double s : sham) {
            smax = Math.max(smax, s);
            if (s >= fixedThreshold) r.shamFalseFixed++;
        }
        r.shamMax = sham.isEmpty() ? Double.NaN : smax;
        for (int l = 0; l < nl; l++) {
            LevelResult lr = new LevelResult();
            lr.label = labels[l];
            lr.n = per.get(l).size();
            lr.median = median(per.get(l));
            double mx = Double.NEGATIVE_INFINITY;
            for (double s : per.get(l)) {
                mx = Math.max(mx, s);
                if (s >= fixedThreshold) lr.hitsFixed++;
                if (!sham.isEmpty() && s > smax) lr.hitsEmpirical++;
            }
            lr.max = mx;
            r.levels.add(lr);
        }
        double[] st = new double[stimAll.size()];
        for (int i = 0; i < st.length; i++) st[i] = stimAll.get(i);
        double[] sh = new double[sham.size()];
        for (int i = 0; i < sh.length; i++) sh[i] = sham.get(i);
        r.auc = auc(st, sh);
        int n = st.length + sh.length;
        if (st.length > 0 && sh.length > 0) {
            double[] all = new double[n];
            System.arraycopy(st, 0, all, 0, st.length);
            System.arraycopy(sh, 0, all, st.length, sh.length);
            int k = st.length;
            long ge = 0;
            long total = 0;
            if (n <= 20) {
                r.exact = true;
                for (int mask = 0; mask < (1 << n); mask++) {
                    if (Integer.bitCount(mask) != k) continue;
                    double[] a = new double[k];
                    double[] b = new double[n - k];
                    int ia = 0;
                    int ib = 0;
                    for (int i = 0; i < n; i++) {
                        if ((mask & (1 << i)) != 0) a[ia++] = all[i];
                        else b[ib++] = all[i];
                    }
                    total++;
                    if (auc(a, b) >= r.auc - 1e-12) ge++;
                }
            } else {
                Random rnd = new Random(4242);
                double[] a = new double[k];
                double[] b = new double[n - k];
                double[] cp = all.clone();
                for (int it = 0; it < 20000; it++) {
                    for (int i = n - 1; i > 0; i--) {
                        int j = rnd.nextInt(i + 1);
                        double t = cp[i];
                        cp[i] = cp[j];
                        cp[j] = t;
                    }
                    System.arraycopy(cp, 0, a, 0, k);
                    System.arraycopy(cp, k, b, 0, n - k);
                    total++;
                    if (auc(a, b) >= r.auc - 1e-12) ge++;
                }
            }
            r.pValue = (double) ge / (double) total;
        } else {
            r.pValue = 1.0;
        }
        // detection limit: weakest level from which all stronger levels are detected in at least half of their windows
        int lim = -1;
        for (int l = nl - 1; l >= 0; l--) {
            LevelResult lr = r.levels.get(l);
            if (lr.n > 0 && lr.hitsEmpirical * 2 >= lr.n) lim = l;
            else break;
        }
        r.limitIndex = lim;
        StringBuilder sb = new StringBuilder();
        if (r.shamN < 4) sb.append("Too few sham windows for a reliable false-alarm estimate. ");
        if (lim >= 0) {
            sb.append("Reliably detected from \"").append(labels[lim]).append("\" upward");
            sb.append(lim == 0 ? " (the weakest level tested: the true limit may be lower). " : ". ");
        } else {
            sb.append("No level was reliably detected above the sham windows. ");
        }
        sb.append(String.format("Sham false alarms (at the channel's own threshold): %d of %d. AUC %.2f, p = %.3f.",
            r.shamFalseFixed, r.shamN, r.auc, r.pValue));
        r.verdict = sb.toString();
        return r;
    }
}
