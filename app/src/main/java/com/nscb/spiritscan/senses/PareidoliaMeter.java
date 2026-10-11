package com.nscb.spiritscan.senses;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Pareidolia meter. Looks for FACE-LIKE constellations (two similar blobs side by side with a third below their midpoint)
 * and FIGURE-LIKE stacks (a small blob above a larger one) among the blobs the camera decoder reports, and, crucially, tells
 * you how often random blobs of the same count form the same pattern by pure chance. With 5 random blobs a "face" is not rare.
 */
public final class PareidoliaMeter {
    public static final class Hit {
        public int kind;      // 0 = face-like, 1 = figure-like
        public int[] idx;
    }

    private final double w;
    private final double h;
    private final double[] chanceFace = new double[16];
    private final double[] chanceFigure = new double[16];

    public PareidoliaMeter(double frameW, double frameH) {
        w = frameW;
        h = frameH;
        Random r = new Random(5150);
        for (int n = 0; n < 16; n++) {
            if (n < 3) continue;
            int trials = n <= 8 ? 6000 : 2500;
            int f = 0;
            int g = 0;
            double[] x = new double[n];
            double[] y = new double[n];
            double[] rad = new double[n];
            for (int t = 0; t < trials; t++) {
                for (int i = 0; i < n; i++) {
                    x[i] = r.nextDouble() * w;
                    y[i] = r.nextDouble() * h;
                    rad[i] = 4.0 + r.nextDouble() * 8.0;
                }
                List<Hit> hits = scan(x, y, rad, n);
                boolean face = false;
                boolean fig = false;
                for (Hit hh : hits) {
                    if (hh.kind == 0) face = true;
                    else fig = true;
                }
                if (face) f++;
                if (fig) g++;
            }
            chanceFace[n] = (double) f / trials;
            chanceFigure[n] = (double) g / trials;
        }
    }

    /** probability that n random blobs contain at least one face-like triplet (0 if n < 3) */
    public double chanceFace(int n) {
        return n < 3 ? 0.0 : chanceFace[Math.min(n, 15)];
    }

    public double chanceFigure(int n) {
        return n < 2 ? 0.0 : (n < 3 ? 0.0 : chanceFigure[Math.min(n, 15)]);
    }

    /** positions and radii in frame pixels */
    public List<Hit> scan(double[] x, double[] y, double[] r, int n) {
        List<Hit> out = new ArrayList<Hit>();
        for (int a = 0; a < n; a++) {
            for (int b = a + 1; b < n; b++) {
                double dx = Math.abs(x[a] - x[b]);
                double dy = Math.abs(y[a] - y[b]);
                if (dx < 10.0 || dx > 60.0 || dy > 0.2 * dx) continue;
                double rr = r[a] / r[b];
                if (rr > 2.0 || rr < 0.5) continue;
                double mx = 0.5 * (x[a] + x[b]);
                double my = 0.5 * (y[a] + y[b]);
                double er = 0.5 * (r[a] + r[b]);
                for (int c = 0; c < n; c++) {
                    if (c == a || c == b) continue;
                    if (Math.abs(x[c] - mx) > 0.3 * dx) continue;
                    double below = y[c] - my;
                    if (below < 0.8 * dx || below > 1.8 * dx) continue;
                    if (r[c] < 0.5 * er || r[c] > 3.0 * er) continue;
                    Hit hit = new Hit();
                    hit.kind = 0;
                    hit.idx = new int[]{a, b, c};
                    out.add(hit);
                }
            }
        }
        for (int a = 0; a < n; a++) {
            for (int b = 0; b < n; b++) {
                if (a == b) continue;
                // a = head (above, smaller), b = body (below, larger)
                if (r[b] < 1.3 * r[a]) continue;
                if (Math.abs(x[a] - x[b]) > 0.35 * (r[a] + r[b])) continue;
                double gap = y[b] - y[a];
                double head = 2.0 * r[a];
                if (gap < 1.2 * head || gap > 3.0 * head) continue;
                Hit hit = new Hit();
                hit.kind = 1;
                hit.idx = new int[]{a, b};
                out.add(hit);
            }
        }
        return out;
    }

    /** Running tally of how many frames showed a face-like pattern versus how many chance predicts. */
    public static final class Tally {
        public long frames;
        public long faceFrames;
        public double expected;
        public double variance;

        public synchronized void add(int nBlobs, boolean face, double chance) {
            if (nBlobs < 3) return;
            frames++;
            if (face) faceFrames++;
            expected += chance;
            variance += chance * (1.0 - chance);
        }

        public synchronized double z() {
            return variance > 1e-9 ? (faceFrames - expected) / Math.sqrt(variance) : 0.0;
        }
    }
}
