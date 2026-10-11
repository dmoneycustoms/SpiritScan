package com.nscb.spiritscan.senses;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Magnetic floor map by dead reckoning. Steps come from the accelerometer, heading from the gyroscope about gravity
 * (the magnetometer is the thing being measured, so it is NOT used for heading), and every step deposits the average
 * field magnitude |B| in a 0.5 m cell. After a second pass the engine can tell three things apart:
 *  - STATIC structure (wiring, rebar, speakers): same value every pass, listed as pins,
 *  - CHANGED: a cell whose field differs from the first pass by much more than noise plus the local spatial gradient,
 *  - nothing: everything else.
 * Dead reckoning drifts (gyro bias, step length): keep walks short (a couple of minutes, a room or two) and RESET between rooms.
 * Hold the phone the same way as you walk; the map is relative to where you started, facing the way you walk.
 */
public final class MagMapEngine {
    public static final int G = 64;
    public static final double CELL = 0.5;
    private static final double REVISIT_GAP_S = 20.0;
    private static final double MIN_STEP_GAP_S = 0.30;

    public static final class Pin {
        public float x;
        public float y;
        public float dev;
        public String kind = "";
        public int cx;
        public int cy;
    }

    public static final class Snapshot {
        public int g = G;
        public float cell = (float) CELL;
        public float[] mean = new float[G * G];
        public byte[] state = new byte[G * G];
        public float x;
        public float y;
        public float headingDeg;
        public int steps;
        public float sigma;
        public float median;
        public int visited;
        public int changedCells;
        public boolean walking;
        public List<Pin> pins = new ArrayList<Pin>();
        public String note = "";
    }

    private double stepLen = 0.70;
    private double x;
    private double y;
    private double heading;
    private double gx;
    private double gy;
    private double gz = 9.81;
    private boolean gInit;
    private double gyroBias;
    private double aVs;
    private double peak;
    private boolean above;
    private double lastStepT = -10;
    private double peakAvg = 1.5;
    private double lastT = -1;
    private int steps;
    private double magSum;
    private int magCnt;
    private double lastStepAvg = Double.NaN;
    private final List<Double> stepDiffs = new ArrayList<Double>();
    private double sigma = 0.3;

    private final double[] m0 = new double[G * G];
    private final int[] n0 = new int[G * G];
    private final double[] tLast = new double[G * G];
    private final double[] tFirst = new double[G * G];
    private final byte[] state = new byte[G * G];
    private final int[] chgRun = new int[G * G];
    private double accVar;
    private double accMean;

    public MagMapEngine() {
        reset();
    }

    public synchronized void reset() {
        x = 0;
        y = 0;
        heading = 0;
        steps = 0;
        magSum = 0;
        magCnt = 0;
        lastStepAvg = Double.NaN;
        stepDiffs.clear();
        sigma = 0.3;
        Arrays.fill(m0, 0);
        Arrays.fill(n0, 0);
        Arrays.fill(tLast, -1e9);
        Arrays.fill(tFirst, 1e18);
        Arrays.fill(state, (byte) 0);
        Arrays.fill(chgRun, 0);
        lastStepT = -10;
        above = false;
        peak = 0;
    }

    public synchronized void setStepLength(double meters) {
        stepLen = Math.max(0.4, Math.min(1.0, meters));
    }

    /** Sensor sample. Times in seconds, acc in m/s^2, gyro in rad/s, magnitude in microtesla. */
    public synchronized void push(double t, double ax, double ay, double az, double wx, double wy, double wz, double magUt) {
        if (lastT < 0) {
            lastT = t;
            return;
        }
        double dt = t - lastT;
        lastT = t;
        if (dt <= 0 || dt > 0.5) return;
        if (!gInit) {
            gx = ax;
            gy = ay;
            gz = az;
            gInit = true;
        }
        double ag = 1.0 - Math.exp(-dt / 0.6);
        gx += ag * (ax - gx);
        gy += ag * (ay - gy);
        gz += ag * (az - gz);
        double gm = Math.sqrt(gx * gx + gy * gy + gz * gz) + 1e-9;
        double ux = gx / gm;
        double uy = gy / gm;
        double uz = gz / gm;
        double wUp = wx * ux + wy * uy + wz * uz;

        double aV = (ax * ux + ay * uy + az * uz) - gm;
        double as = 1.0 - Math.exp(-dt / 0.04);
        aVs += as * (aV - aVs);
        double av2 = 1.0 - Math.exp(-dt / 2.0);
        accMean += av2 * (aV - accMean);
        accVar += av2 * ((aV - accMean) * (aV - accMean) - accVar);
        boolean still = accVar < 0.02 && (t - lastStepT) > 2.0;
        if (still && Math.abs(wUp) < 0.05) gyroBias += 0.01 * (wUp - gyroBias);
        heading += (wUp - gyroBias) * dt;

        magSum += magUt;
        magCnt++;

        double thr = Math.max(0.7, 0.45 * peakAvg);
        if (aVs > thr) {
            above = true;
            if (aVs > peak) peak = aVs;
        } else if (above && aVs < 0.5 * thr) {
            above = false;
            if ((t - lastStepT) >= MIN_STEP_GAP_S) {
                peakAvg += 0.2 * (peak - peakAvg);
                onStep(t);
                lastStepT = t;
            }
            peak = 0;
        }
    }

    private void onStep(double t) {
        steps++;
        x += stepLen * Math.cos(heading);
        y += stepLen * Math.sin(heading);
        double avg = magCnt > 0 ? magSum / magCnt : Double.NaN;
        magSum = 0;
        magCnt = 0;
        if (Double.isNaN(avg)) return;
        if (!Double.isNaN(lastStepAvg)) {
            stepDiffs.add(Math.abs(avg - lastStepAvg));
            if (stepDiffs.size() > 200) stepDiffs.remove(0);
            if (stepDiffs.size() >= 10) {
                double[] d = new double[stepDiffs.size()];
                for (int i = 0; i < d.length; i++) d[i] = stepDiffs.get(i);
                Arrays.sort(d);
                sigma = Math.max(0.15, 1.4826 * d[d.length / 2] / Math.sqrt(2.0));
            }
        }
        lastStepAvg = avg;
        int ix = (int) Math.floor(x / CELL) + G / 2;
        int iy = (int) Math.floor(y / CELL) + G / 2;
        if (ix < 0 || iy < 0 || ix >= G || iy >= G) return;
        int c = iy * G + ix;
        // Reference = the OLD (>= 20 s) first-pass cells within +-1 m. Dead reckoning drifts, so the second pass rarely lands
        // on exactly the same cells; comparing against the range of the neighbourhood tolerates that.
        double lo = Double.POSITIVE_INFINITY;
        double hi = Double.NEGATIVE_INFINITY;
        int cnt = 0;
        for (int dy = -2; dy <= 2; dy++) {
            for (int dx = -2; dx <= 2; dx++) {
                int nx = ix + dx;
                int ny = iy + dy;
                if (nx < 0 || ny < 0 || nx >= G || ny >= G) continue;
                int nc = ny * G + nx;
                if (n0[nc] > 0 && state[nc] != 2 && (t - tFirst[nc]) >= REVISIT_GAP_S) {
                    lo = Math.min(lo, m0[nc]);
                    hi = Math.max(hi, m0[nc]);
                    cnt++;
                }
            }
        }
        if (cnt >= 3) {
            double tol = 3.0 * sigma + 0.5;
            boolean anomalous = avg > hi + tol || avg < lo - tol;
            if (anomalous) {
                chgRun[c]++;
                if (chgRun[c] >= 2) {
                    if (n0[c] == 0) {
                        n0[c] = 1;
                        m0[c] = avg;
                        tFirst[c] = t;
                    }
                    state[c] = 2;
                }
                tLast[c] = t;
                return;
            }
            chgRun[c] = 0;
        }
        if (n0[c] == 0) {
            m0[c] = avg;
            n0[c] = 1;
            tFirst[c] = t;
            tLast[c] = t;
            state[c] = 1;
        } else if (t - tLast[c] < REVISIT_GAP_S && state[c] != 2) {
            if (n0[c] < 4) {
                m0[c] = (m0[c] * n0[c] + avg) / (n0[c] + 1);
                n0[c]++;
            }
            tLast[c] = t;
        } else {
            tLast[c] = t;
        }
    }

    public synchronized Snapshot snapshot() {
        Snapshot s = new Snapshot();
        s.x = (float) x;
        s.y = (float) y;
        s.headingDeg = (float) Math.toDegrees(heading);
        s.steps = steps;
        s.sigma = (float) sigma;
        s.walking = (lastT - lastStepT) < 3.0;
        List<Double> vals = new ArrayList<Double>();
        for (int c = 0; c < G * G; c++) {
            s.mean[c] = (float) m0[c];
            s.state[c] = state[c];
            if (n0[c] > 0) {
                vals.add(m0[c]);
                s.visited++;
            }
            if (state[c] == 2) s.changedCells++;
        }
        if (vals.size() >= 25) {
            double[] v = new double[vals.size()];
            for (int i = 0; i < v.length; i++) v[i] = vals.get(i);
            Arrays.sort(v);
            double med = v[v.length / 2];
            s.median = (float) med;
            double thr = Math.max(2.0, 6.0 * sigma);
            List<Pin> cand = new ArrayList<Pin>();
            for (int iy = 0; iy < G; iy++) {
                for (int ix = 0; ix < G; ix++) {
                    int c = iy * G + ix;
                    if (n0[c] == 0) continue;
                    double dev = m0[c] - med;
                    if (Math.abs(dev) < thr) continue;
                    boolean localMax = true;
                    for (int dy = -1; dy <= 1 && localMax; dy++) {
                        for (int dx = -1; dx <= 1; dx++) {
                            if (dx == 0 && dy == 0) continue;
                            int nx = ix + dx;
                            int ny = iy + dy;
                            if (nx < 0 || ny < 0 || nx >= G || ny >= G) continue;
                            int nc = ny * G + nx;
                            if (n0[nc] > 0 && Math.abs(m0[nc] - med) > Math.abs(dev)) {
                                localMax = false;
                                break;
                            }
                        }
                    }
                    if (!localMax) continue;
                    Pin p = new Pin();
                    p.cx = ix;
                    p.cy = iy;
                    p.x = (float) ((ix - G / 2 + 0.5) * CELL);
                    p.y = (float) ((iy - G / 2 + 0.5) * CELL);
                    p.dev = (float) dev;
                    p.kind = state[c] == 2 ? "CHANGED" : "STATIC";
                    cand.add(p);
                }
            }
            Collections_sort(cand);
            for (int i = 0; i < Math.min(6, cand.size()); i++) s.pins.add(cand.get(i));
        }
        if (steps < 20) s.note = "walk a few metres, holding the phone steady and facing the way you walk";
        else if (s.visited < 25) s.note = "keep walking to build the map";
        else s.note = "walk a second pass to test for changes (after at least 20 s)";
        return s;
    }

    private static void Collections_sort(List<Pin> l) {
        java.util.Collections.sort(l, new java.util.Comparator<Pin>() {
            @Override
            public int compare(Pin a, Pin b) {
                return Float.compare(Math.abs(b.dev), Math.abs(a.dev));
            }
        });
    }
}
