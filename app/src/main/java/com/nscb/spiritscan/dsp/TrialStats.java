package com.nscb.spiritscan.dsp;

import java.util.Random;

/**
 * Statistics for the sham-controlled trial. Exact permutation test on block rates: with n blocks of which k were LIVE,
 * every possible assignment of LIVE/SHAM labels is enumerated (n <= 20) and the p-value is the fraction of assignments
 * whose absolute mean difference is at least the observed one. No distributional assumptions.
 */
public final class TrialStats {

    public static final class Result {
        public double liveMean;
        public double shamMean;
        public double diff;
        public double pValue;
        public int nLive;
        public int nSham;
        public boolean exact;
    }

    private TrialStats() {
    }

    /** Balanced random LIVE/SHAM order: half LIVE, half SHAM (extra block, if odd, is decided by the seed). */
    public static boolean[] randomOrder(int blocks, long seed) {
        Random r = new Random(seed);
        boolean[] live = new boolean[blocks];
        int nLive = blocks / 2 + ((blocks % 2 == 1 && r.nextBoolean()) ? 1 : 0);
        for (int i = 0; i < nLive; i++) live[i] = true;
        for (int i = blocks - 1; i > 0; i--) {
            int j = r.nextInt(i + 1);
            boolean t = live[i];
            live[i] = live[j];
            live[j] = t;
        }
        return live;
    }

    public static Result permutationTest(double[] rates, boolean[] live) {
        int n = rates.length;
        Result res = new Result();
        double sl = 0, ss = 0;
        for (int i = 0; i < n; i++) {
            if (live[i]) {
                sl += rates[i];
                res.nLive++;
            } else {
                ss += rates[i];
                res.nSham++;
            }
        }
        if (res.nLive == 0 || res.nSham == 0) {
            res.pValue = 1.0;
            return res;
        }
        res.liveMean = sl / res.nLive;
        res.shamMean = ss / res.nSham;
        res.diff = res.liveMean - res.shamMean;
        double total = sl + ss;
        int k = res.nLive;
        double obs = Math.abs(res.diff);
        long count = 0;
        long all = 0;
        if (n <= 20) {
            res.exact = true;
            for (int mask = 0; mask < (1 << n); mask++) {
                if (Integer.bitCount(mask) != k) continue;
                double s = 0;
                for (int i = 0; i < n; i++) if ((mask & (1 << i)) != 0) s += rates[i];
                double d = Math.abs(s / k - (total - s) / (n - k));
                all++;
                if (d >= obs - 1e-12) count++;
            }
        } else {
            Random r = new Random(12345);
            int[] idx = new int[n];
            for (int i = 0; i < n; i++) idx[i] = i;
            for (int it = 0; it < 20000; it++) {
                for (int i = n - 1; i > 0; i--) {
                    int j = r.nextInt(i + 1);
                    int t = idx[i];
                    idx[i] = idx[j];
                    idx[j] = t;
                }
                double s = 0;
                for (int i = 0; i < k; i++) s += rates[idx[i]];
                double d = Math.abs(s / k - (total - s) / (n - k));
                all++;
                if (d >= obs - 1e-12) count++;
            }
        }
        res.pValue = (double) count / (double) all;
        return res;
    }
}
