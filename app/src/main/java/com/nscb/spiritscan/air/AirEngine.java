package com.nscb.spiritscan.air;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * AIR engine: makes the invisible visible from nothing but the phone camera.
 *
 *  1. MICRO-VARIATION (Eulerian-style): per-pixel temporal band-pass (0.3..3 Hz), 3x3 spatial smoothing,
 *     common-mode removal, normalised by the expected noise -> a signed sigma map. Brightness changes far below what the
 *     eye can see become visible.
 *  2. AIR FLOW (background-oriented schlieren): sub-pixel Lucas-Kanade displacement of 6x6 px blocks against the learned
 *     background. Air with different temperature/density bends light; a textured background shifts by hundredths of a pixel.
 *     The global (hand) shift is removed with a median, then each block gets a statistical significance from the
 *     structure tensor. Only textured background works: blank walls cannot be measured.
 *  3. PULSES: per 8x8-px cell, 4.3 s of brightness is regressed against the global brightness (kills whole-room flicker),
 *     then scanned with a Goertzel bank (0.6 .. ~13 Hz). A narrowband peak that persists, groups with neighbours and
 *     survives the explanations (light flicker, hand tremor on edges, screens) is reported as a pulse source.
 *
 * It measures optical effects. It does not measure temperature in degrees, air quality, or anything paranormal.
 */
public final class AirEngine {

    private static final int BS = 6;
    private static final int CELL = 8;
    private static final int RING = 128;
    private static final int EVERY = 8;
    private static final int NB = 32;
    private static final double FMIN = 0.6;
    private static final double FC_FAST = 3.0;
    private static final double FC_SLOW = 0.3;
    private static final double PMR_DB_MIN = 13.0;

    private int w;
    private int h;
    private int n;
    private int gw;
    private int gh;
    private int cw;
    private int ch;

    // band-pass state
    private float[] lpF = new float[0];
    private float[] lpS = new float[0];
    private float[] bp = new float[0];
    private float[] bpT = new float[0];
    private boolean haveLp;
    private int warm;

    // BOS state
    private float[] rEx = new float[0];
    private float[] rEy = new float[0];
    private float[] sdx = new float[0];
    private float[] sdy = new float[0];
    private float[] bDx = new float[0];
    private float[] bDy = new float[0];
    private float[] bSdx = new float[0];
    private float[] bSdy = new float[0];
    private boolean[] bValid = new boolean[0];
    private float[] bSig = new float[0];

    // pulse state
    private float[] cellRing = new float[0];
    private float[] gRing = new float[RING];
    private double[] tRing = new double[RING];
    private double[] mxRing = new double[RING];
    private double[] myRing = new double[RING];
    private int ringPos;
    private int ringCount;
    private double tAbs;
    private int frameNo;
    private int[] candBin = new int[0];
    private int[] runLen = new int[0];
    private float[] cellPmr = new float[0];
    private float[] cellAmp = new float[0];
    private float[] cellFreq = new float[0];
    private float[] cellLuma = new float[0];
    private float[] cellEdge = new float[0];
    private final double[] hann = new double[RING];
    private double hannSum;

    private final List<Track> tracks = new ArrayList<Track>();
    private int nextId = 1;
    private PulseSource[] lastPulses = new PulseSource[0];
    private double fpsEma = 30.0;
    private final double[] m4 = new double[16];
    private final double[] r4 = new double[4];
    private final double[] f4 = new double[4];
    private final double[] inv = new double[16];
    private final double[] inv8 = new double[32];

    private static final class Track {
        int id;
        float cx;
        float cy;
        float freq;
        int age;
        int missed;
        boolean matched;
    }

    public AirEngine() {
        double s = 0;
        for (int i = 0; i < RING; i++) {
            hann[i] = 0.5 - 0.5 * Math.cos(2.0 * Math.PI * i / (RING - 1));
            s += hann[i];
        }
        hannSum = s;
    }

    private void alloc(int nw, int nh) {
        w = nw;
        h = nh;
        n = nw * nh;
        gw = nw / BS;
        gh = nh / BS;
        cw = nw / CELL;
        ch = nh / CELL;
        lpF = new float[n];
        lpS = new float[n];
        bp = new float[n];
        bpT = new float[n];
        int nb = gw * gh;
        rEx = new float[nb];
        rEy = new float[nb];
        sdx = new float[nb];
        sdy = new float[nb];
        bDx = new float[nb];
        bDy = new float[nb];
        bSdx = new float[nb];
        bSdy = new float[nb];
        bValid = new boolean[nb];
        bSig = new float[nb];
        int nc = cw * ch;
        cellRing = new float[nc * RING];
        candBin = new int[nc];
        runLen = new int[nc];
        cellPmr = new float[nc];
        cellAmp = new float[nc];
        cellFreq = new float[nc];
        cellLuma = new float[nc];
        cellEdge = new float[nc];
        reset();
    }

    public void reset() {
        haveLp = false;
        warm = 0;
        ringPos = 0;
        ringCount = 0;
        frameNo = 0;
        tracks.clear();
        lastPulses = new PulseSource[0];
        Arrays.fill(rEx, 0f);
        Arrays.fill(rEy, 0f);
        Arrays.fill(bSig, 0f);
        Arrays.fill(runLen, 0);
    }

    /** Frame was gated (phone moving / too dark / relearning): do not ingest, restart the temporal state. */
    public void hold() {
        haveLp = false;
        warm = 0;
        ringCount = 0;
        ringPos = 0;
        Arrays.fill(runLen, 0);
        for (int i = 0; i < rEx.length; i++) {
            rEx[i] *= 0.7f;
            rEy[i] *= 0.7f;
            bSig[i] *= 0.7f;
        }
    }

    /**
     * @param xa    aligned, exposure-normalised luma of the current frame (0..1)
     * @param mu    learned background (same size)
     * @param sigma per-pixel temporal noise std (0..1 luma), already floored
     * @param dt    seconds since the previous frame
     */
    public AirFrame process(float[] xa, float[] mu, float sigma, int nw, int nh, double dt) {
        if (nw != w || nh != h) alloc(nw, nh);
        if (dt < 0.004) dt = 0.004;
        if (dt > 0.25) dt = 0.25;
        tAbs += dt;
        frameNo++;
        fpsEma = fpsEma * 0.95 + (1.0 / dt) * 0.05;

        // ---- 1. micro-variation -----------------------------------------------------------------
        double aF = 1.0 - Math.exp(-2.0 * Math.PI * FC_FAST * dt);
        double aS = 1.0 - Math.exp(-2.0 * Math.PI * FC_SLOW * dt);
        if (!haveLp) {
            System.arraycopy(xa, 0, lpF, 0, n);
            System.arraycopy(xa, 0, lpS, 0, n);
            haveLp = true;
            warm = 0;
        }
        float fF = (float) aF;
        float fS = (float) aS;
        for (int i = 0; i < n; i++) {
            float x = xa[i];
            lpF[i] += fF * (x - lpF[i]);
            lpS[i] += fS * (x - lpS[i]);
            bp[i] = lpF[i] - lpS[i];
        }
        warm++;
        // 3x3 box smoothing (separable)
        for (int y = 0; y < h; y++) {
            int r = y * w;
            for (int x = 0; x < w; x++) {
                int xm = x > 0 ? x - 1 : x;
                int xp = x < w - 1 ? x + 1 : x;
                bpT[r + x] = (bp[r + xm] + bp[r + x] + bp[r + xp]) * (1f / 3f);
            }
        }
        for (int y = 0; y < h; y++) {
            int ym = y > 0 ? y - 1 : y;
            int yp = y < h - 1 ? y + 1 : y;
            for (int x = 0; x < w; x++) {
                bp[y * w + x] = (bpT[ym * w + x] + bpT[y * w + x] + bpT[yp * w + x]) * (1f / 3f);
            }
        }
        double v1 = aF / (2.0 - aF);
        double v2 = aS / (2.0 - aS);
        double c12 = aF * aS / (aF + aS - aF * aS);
        double fac = Math.sqrt(Math.max(v1 + v2 - 2.0 * c12, 1e-6));
        float sBp = (float) (sigma * fac / 2.2);
        if (sBp < 1e-6f) sBp = 1e-6f;
        double cs = 0;
        int cc = 0;
        float lim = 3f * sBp;
        for (int i = 0; i < n; i += 2) {
            float v = bp[i];
            if (v > -lim && v < lim) {
                cs += v;
                cc++;
            }
        }
        float cm = cc > 0 ? (float) (cs / cc) : 0f;
        boolean warmOk = warm > 40;
        int[] pixels = new int[n];

        // ---- 2. air flow (BOS) --------------------------------------------------------------------
        int nb = gw * gh;
        int valid = 0;
        float[] vdx = new float[nb];
        float[] vdy = new float[nb];
        int nv = 0;
        for (int by = 0; by < gh; by++) {
            for (int bx = 0; bx < gw; bx++) {
                int bi = by * gw + bx;
                int y0 = Math.max(1, by * BS);
                int y1 = Math.min(h - 2, by * BS + BS - 1);
                int x0 = Math.max(1, bx * BS);
                int x1 = Math.min(w - 2, bx * BS + BS - 1);
                double mbar = 0;
                int cnt = 0;
                for (int y = y0; y <= y1; y++) {
                    for (int x = x0; x <= x1; x++) {
                        mbar += mu[y * w + x];
                        cnt++;
                    }
                }
                bValid[bi] = false;
                if (cnt < 16) continue;
                mbar /= cnt;
                // features f = [-ix, -iy, 1, (mu - mbar)], model It = f . [dx, dy, offset, gain]
                Arrays.fill(m4, 0.0);
                Arrays.fill(r4, 0.0);
                for (int y = y0; y <= y1; y++) {
                    for (int x = x0; x <= x1; x++) {
                        int i = y * w + x;
                        f4[0] = -(mu[i + 1] - mu[i - 1]) * 0.5;
                        f4[1] = -(mu[i + w] - mu[i - w]) * 0.5;
                        f4[2] = 0.1;
                        f4[3] = mu[i] - mbar;
                        double it = xa[i] - mu[i];
                        for (int r = 0; r < 4; r++) {
                            r4[r] += f4[r] * it;
                            for (int c = 0; c < 4; c++) m4[r * 4 + c] += f4[r] * f4[c];
                        }
                    }
                }
                if (!invert4(m4, inv)) continue;
                double dx = inv[0] * r4[0] + inv[1] * r4[1] + inv[2] * r4[2] + inv[3] * r4[3];
                double dy = inv[4] * r4[0] + inv[5] * r4[1] + inv[6] * r4[2] + inv[7] * r4[3];
                double vx = inv[0];
                double vy = inv[5];
                if (vx <= 0 || vy <= 0) continue;
                double sx = sigma * Math.sqrt(vx);
                double sy = sigma * Math.sqrt(vy);
                if (Math.max(sx, sy) <= 0.12 && Math.abs(dx) < 1.0 && Math.abs(dy) < 1.0) {
                    bValid[bi] = true;
                    bDx[bi] = (float) dx;
                    bDy[bi] = (float) dy;
                    bSdx[bi] = (float) sx;
                    bSdy[bi] = (float) sy;
                    vdx[nv] = (float) dx;
                    vdy[nv] = (float) dy;
                    nv++;
                    valid++;
                }
            }
        }
        float medX = 0f;
        float medY = 0f;
        boolean bosOk = nv >= 10 && warmOk;
        if (bosOk) {
            Arrays.sort(vdx, 0, nv);
            Arrays.sort(vdy, 0, nv);
            medX = vdx[nv / 2];
            medY = vdy[nv / 2];
        }
        int sigBlocks = 0;
        float ema = 0.4f;
        for (int bi = 0; bi < nb; bi++) {
            if (bosOk && bValid[bi]) {
                float rx = bDx[bi] - medX;
                float ry = bDy[bi] - medY;
                rEx[bi] += ema * (rx - rEx[bi]);
                rEy[bi] += ema * (ry - rEy[bi]);
                sdx[bi] = bSdx[bi];
                sdy[bi] = bSdy[bi];
                float zx = rEx[bi] / (0.5f * Math.max(sdx[bi], 1e-4f));
                float zy = rEy[bi] / (0.5f * Math.max(sdy[bi], 1e-4f));
                bSig[bi] = (float) Math.sqrt(zx * zx + zy * zy);
            } else {
                rEx[bi] *= 0.8f;
                rEy[bi] *= 0.8f;
                bSig[bi] *= 0.8f;
            }
        }
        int coherent = 0;
        for (int by = 0; by < gh; by++) {
            for (int bx = 0; bx < gw; bx++) {
                int bi = by * gw + bx;
                float mag = (float) Math.hypot(rEx[bi], rEy[bi]);
                if (bSig[bi] >= 3.5f && mag >= 0.02f) {
                    sigBlocks++;
                    boolean nbr = false;
                    if (bx > 0 && bSig[bi - 1] >= 3.0f) nbr = true;
                    if (bx < gw - 1 && bSig[bi + 1] >= 3.0f) nbr = true;
                    if (by > 0 && bSig[bi - gw] >= 3.0f) nbr = true;
                    if (by < gh - 1 && bSig[bi + gw] >= 3.0f) nbr = true;
                    if (nbr) coherent++;
                }
            }
        }
        float airIndex = (float) (coherent / (coherent + 8.0));
        if (!bosOk) airIndex = 0f;

        // ---- 3. pulses ----------------------------------------------------------------------------
        int nc = cw * ch;
        float[] cm8 = new float[nc];
        double gsum = 0;
        for (int cy = 0; cy < ch; cy++) {
            for (int cx = 0; cx < cw; cx++) {
                double s = 0;
                for (int y = cy * CELL; y < cy * CELL + CELL; y++) {
                    int r = y * w + cx * CELL;
                    for (int x = 0; x < CELL; x++) s += xa[r + x];
                }
                float m = (float) (s / (CELL * CELL));
                cm8[cy * cw + cx] = m;
                gsum += m;
            }
        }
        float gm = (float) (gsum / nc);
        for (int c = 0; c < nc; c++) cellRing[c * RING + ringPos] = cm8[c];
        gRing[ringPos] = gm;
        tRing[ringPos] = tAbs;
        mxRing[ringPos] = bosOk ? medX : 0.0;
        myRing[ringPos] = bosOk ? medY : 0.0;
        ringPos = (ringPos + 1) % RING;
        if (ringCount < RING) ringCount++;
        System.arraycopy(cm8, 0, cellLuma, 0, nc);
        if (ringCount >= RING && frameNo % EVERY == 0) analyzePulses(mu, sigma);

        // ---- pack pixels --------------------------------------------------------------------------
        for (int y = 0; y < h; y++) {
            float fy = (y + 0.5f) / BS - 0.5f;
            int y0 = (int) Math.floor(fy);
            float ty = fy - y0;
            int ya = Math.max(0, Math.min(gh - 1, y0));
            int yb = Math.max(0, Math.min(gh - 1, y0 + 1));
            int cyi = Math.min(ch - 1, y / CELL);
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                int r = 128;
                if (warmOk) {
                    float z = (bp[i] - cm) / sBp;
                    r = Math.round(128f + z * 10f);
                    if (r < 0) r = 0;
                    if (r > 255) r = 255;
                }
                int g = 0;
                if (bosOk) {
                    float fx = (x + 0.5f) / BS - 0.5f;
                    int x0 = (int) Math.floor(fx);
                    float tx = fx - x0;
                    int xa0 = Math.max(0, Math.min(gw - 1, x0));
                    int xb0 = Math.max(0, Math.min(gw - 1, x0 + 1));
                    float s00 = bSig[ya * gw + xa0];
                    float s10 = bSig[ya * gw + xb0];
                    float s01 = bSig[yb * gw + xa0];
                    float s11 = bSig[yb * gw + xb0];
                    float sv = (s00 * (1 - tx) + s10 * tx) * (1 - ty) + (s01 * (1 - tx) + s11 * tx) * ty;
                    g = Math.round(sv * 20f);
                    if (g > 255) g = 255;
                    if (g < 0) g = 0;
                }
                int cxi = Math.min(cw - 1, x / CELL);
                int ci = cyi * cw + cxi;
                int bch = 0;
                if (runLen[ci] >= 3 && cellPmr[ci] > 0f) {
                    bch = Math.round(cellPmr[ci] * 8f);
                    if (bch > 255) bch = 255;
                }
                pixels[i] = 0xFF000000 | (r << 16) | (g << 8) | bch;
            }
        }

        AirFrame f = new AirFrame();
        f.w = w;
        f.h = h;
        f.pixels = pixels;
        f.gw = gw;
        f.gh = gh;
        f.block = BS;
        f.bosDx = rEx.clone();
        f.bosDy = rEy.clone();
        f.bosSig = bSig.clone();
        f.pulses = lastPulses;
        f.airIndex = airIndex;
        f.fps = (float) fpsEma;
        f.validBlocks = valid;
        f.sigBlocks = sigBlocks;
        f.warm = warmOk;
        if (!warmOk) f.note = "AIR warming up";
        else if (!bosOk) f.note = "Too little texture for air-flow measurement (blank wall?)";
        else if (airIndex > 0.3f) f.note = "Coherent air motion in " + coherent + " blocks";
        else f.note = "Air still (within noise)";
        return f;
    }

    private static double[][] inv3(double[][] m) {
        double a = m[0][0], b = m[0][1], c = m[0][2];
        double d = m[1][0], e = m[1][1], f = m[1][2];
        double g = m[2][0], h = m[2][1], i = m[2][2];
        double det = a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g);
        double[][] r = new double[3][3];
        if (Math.abs(det) < 1e-300) return r;
        double id = 1.0 / det;
        r[0][0] = (e * i - f * h) * id;
        r[0][1] = (c * h - b * i) * id;
        r[0][2] = (b * f - c * e) * id;
        r[1][0] = (f * g - d * i) * id;
        r[1][1] = (a * i - c * g) * id;
        r[1][2] = (c * d - a * f) * id;
        r[2][0] = (d * h - e * g) * id;
        r[2][1] = (b * g - a * h) * id;
        r[2][2] = (a * e - b * d) * id;
        return r;
    }

    /** Gauss-Jordan inverse of a 4x4 row-major matrix. Returns false if (near-)singular. */
    private static boolean invert4(double[] a, double[] out) {
        double[] m = new double[32];
        double maxDiag = 0;
        for (int r = 0; r < 4; r++) {
            for (int c = 0; c < 4; c++) m[r * 8 + c] = a[r * 4 + c];
            m[r * 8 + 4 + r] = 1.0;
            maxDiag = Math.max(maxDiag, Math.abs(a[r * 5]));
        }
        if (maxDiag <= 0) return false;
        for (int col = 0; col < 4; col++) {
            int piv = col;
            double best = Math.abs(m[col * 8 + col]);
            for (int r = col + 1; r < 4; r++) {
                double v = Math.abs(m[r * 8 + col]);
                if (v > best) {
                    best = v;
                    piv = r;
                }
            }
            if (best < 1e-12 * maxDiag) return false;
            if (piv != col) {
                for (int c = 0; c < 8; c++) {
                    double t = m[col * 8 + c];
                    m[col * 8 + c] = m[piv * 8 + c];
                    m[piv * 8 + c] = t;
                }
            }
            double d = m[col * 8 + col];
            for (int c = 0; c < 8; c++) m[col * 8 + c] /= d;
            for (int r = 0; r < 4; r++) {
                if (r == col) continue;
                double f = m[r * 8 + col];
                if (f == 0) continue;
                for (int c = 0; c < 8; c++) m[r * 8 + c] -= f * m[col * 8 + c];
            }
        }
        for (int r = 0; r < 4; r++) for (int c = 0; c < 4; c++) out[r * 4 + c] = m[r * 8 + 4 + c];
        return true;
    }

    private void analyzePulses(float[] mu, double sigma) {
        int nc = cw * ch;
        double t0 = tRing[ringPos];
        double t1 = tRing[(ringPos + RING - 1) % RING];
        double span = t1 - t0;
        if (span <= 0.5) return;
        double fs = (RING - 1) / span;
        double fMax = Math.min(0.45 * fs, 14.0);
        if (fMax <= FMIN * 2) return;
        double[] freq = new double[NB];
        double[] coef = new double[NB];
        for (int b = 0; b < NB; b++) {
            freq[b] = FMIN * Math.pow(fMax / FMIN, (double) b / (NB - 1));
            coef[b] = 2.0 * Math.cos(2.0 * Math.PI * freq[b] / fs);
        }
        // Regressors, chronological: (1) robust global brightness = median over cells of each cell's deviation,
        // so a few pulsing cells cannot contaminate it; (2,3) measured global image shift (hand motion).
        double[] cellMean = new double[nc];
        for (int c = 0; c < nc; c++) {
            double m = 0;
            for (int k = 0; k < RING; k++) m += cellRing[c * RING + k];
            cellMean[c] = m / RING;
        }
        double[][] reg = new double[3][RING];
        double[] tmp = new double[nc];
        for (int k = 0; k < RING; k++) {
            int idx = (ringPos + k) % RING;
            for (int c = 0; c < nc; c++) tmp[c] = cellRing[c * RING + idx] - cellMean[c];
            Arrays.sort(tmp);
            reg[0][k] = 0.5 * (tmp[nc / 2 - 1] + tmp[nc / 2]);
            reg[1][k] = mxRing[idx];
            reg[2][k] = myRing[idx];
        }
        for (int j = 0; j < 3; j++) {
            double m = 0;
            for (int k = 0; k < RING; k++) m += reg[j][k];
            m /= RING;
            for (int k = 0; k < RING; k++) reg[j][k] -= m;
        }
        double[][] gram = new double[3][3];
        double trace = 0;
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                double s = 0;
                for (int k = 0; k < RING; k++) s += reg[i][k] * reg[j][k];
                gram[i][j] = s;
            }
            trace += gram[i][i];
        }
        double ridge = 1e-12 + 1e-6 * trace;
        for (int i = 0; i < 3; i++) gram[i][i] += ridge;
        double[][] ginv = inv3(gram);
        double sigCell = sigma / CELL;
        double minAmp = Math.max(0.0006, 1.5 * sigCell);
        double[] x = new double[RING];
        double[] pb = new double[NB];
        double[] sorted = new double[NB];

        // cell edge-ness from the background gradient
        for (int cy = 0; cy < ch; cy++) {
            for (int cx = 0; cx < cw; cx++) {
                double s = 0;
                int cnt = 0;
                for (int y = Math.max(1, cy * CELL); y < Math.min(h - 1, cy * CELL + CELL); y++) {
                    for (int xx = Math.max(1, cx * CELL); xx < Math.min(w - 1, cx * CELL + CELL); xx++) {
                        int i = y * w + xx;
                        s += Math.abs(mu[i + 1] - mu[i - 1]) + Math.abs(mu[i + w] - mu[i - w]);
                        cnt++;
                    }
                }
                cellEdge[cy * cw + cx] = cnt > 0 ? (float) (s / cnt) : 0f;
            }
        }

        for (int c = 0; c < nc; c++) {
            double mean = 0;
            for (int k = 0; k < RING; k++) {
                x[k] = cellRing[c * RING + (ringPos + k) % RING];
                mean += x[k];
            }
            mean /= RING;
            double[] cv = new double[3];
            for (int k = 0; k < RING; k++) {
                x[k] -= mean;
                cv[0] += x[k] * reg[0][k];
                cv[1] += x[k] * reg[1][k];
                cv[2] += x[k] * reg[2][k];
            }
            double b0 = ginv[0][0] * cv[0] + ginv[0][1] * cv[1] + ginv[0][2] * cv[2];
            double b1 = ginv[1][0] * cv[0] + ginv[1][1] * cv[1] + ginv[1][2] * cv[2];
            double b2 = ginv[2][0] * cv[0] + ginv[2][1] * cv[1] + ginv[2][2] * cv[2];
            for (int k = 0; k < RING; k++) {
                x[k] = (x[k] - b0 * reg[0][k] - b1 * reg[1][k] - b2 * reg[2][k]) * hann[k];
            }
            int best = 0;
            double bestP = 0;
            for (int b = 0; b < NB; b++) {
                double s1 = 0, s2 = 0;
                double cf = coef[b];
                for (int k = 0; k < RING; k++) {
                    double s0 = x[k] + cf * s1 - s2;
                    s2 = s1;
                    s1 = s0;
                }
                double pwr = s1 * s1 + s2 * s2 - cf * s1 * s2;
                if (pwr < 0) pwr = 0;
                pb[b] = pwr;
                sorted[b] = pwr;
                if (pwr > bestP) {
                    bestP = pwr;
                    best = b;
                }
            }
            Arrays.sort(sorted);
            double med = 0.5 * (sorted[NB / 2 - 1] + sorted[NB / 2]) + 1e-18;
            double pmr = 10.0 * Math.log10(bestP / med + 1e-9);
            double amp = 2.0 * Math.sqrt(bestP) / hannSum;
            boolean cand = pmr >= PMR_DB_MIN && amp >= minAmp;
            if (cand) {
                if (runLen[c] > 0 && Math.abs(candBin[c] - best) <= 1) runLen[c]++;
                else runLen[c] = 1;
                candBin[c] = best;
                cellPmr[c] = (float) pmr;
                cellAmp[c] = (float) amp;
                cellFreq[c] = (float) freq[best];
            } else {
                runLen[c] = 0;
                cellPmr[c] = 0f;
            }
        }

        // group persistent cells (4-neighbourhood, same frequency bin +-1)
        boolean[] seen = new boolean[nc];
        List<PulseSource> found = new ArrayList<PulseSource>();
        int[] stack = new int[nc];
        for (int c0 = 0; c0 < nc; c0++) {
            if (seen[c0] || runLen[c0] < 3) continue;
            int sp = 0;
            stack[sp++] = c0;
            seen[c0] = true;
            int cells = 0;
            double sx = 0, sy = 0, sf = 0, sw = 0, sLuma = 0, sEdge = 0, pmrMax = 0, ampMax = 0;
            int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, maxX = -1, maxY = -1;
            int run = 0;
            while (sp > 0) {
                int c = stack[--sp];
                int cx = c % cw;
                int cy = c / cw;
                cells++;
                double wgt = cellAmp[c];
                sx += wgt * cx;
                sy += wgt * cy;
                sf += wgt * cellFreq[c];
                sw += wgt;
                sLuma += cellLuma[c];
                sEdge += cellEdge[c];
                pmrMax = Math.max(pmrMax, cellPmr[c]);
                ampMax = Math.max(ampMax, cellAmp[c]);
                run = Math.max(run, runLen[c]);
                minX = Math.min(minX, cx);
                maxX = Math.max(maxX, cx);
                minY = Math.min(minY, cy);
                maxY = Math.max(maxY, cy);
                int[] nbs = {cx > 0 ? c - 1 : -1, cx < cw - 1 ? c + 1 : -1, cy > 0 ? c - cw : -1, cy < ch - 1 ? c + cw : -1};
                for (int nb : nbs) {
                    if (nb < 0 || seen[nb] || runLen[nb] < 3) continue;
                    if (Math.abs(candBin[nb] - candBin[c]) > 1) continue;
                    seen[nb] = true;
                    stack[sp++] = nb;
                }
            }
            PulseSource p = new PulseSource();
            p.cells = cells;
            p.freqHz = (float) (sf / sw);
            p.cx = (float) ((sx / sw + 0.5) / cw);
            p.cy = (float) ((sy / sw + 0.5) / ch);
            p.halfW = (float) ((maxX - minX + 1) * 0.5 / cw);
            p.halfH = (float) ((maxY - minY + 1) * 0.5 / ch);
            p.pmrDb = (float) pmrMax;
            p.ampMilli = (float) (ampMax * 1000.0);
            double luma = sLuma / cells;
            double edge = sEdge / cells;
            if (luma > 0.88) {
                p.cause = "SCREEN/LAMP";
                p.explained = true;
            } else if (cells >= 14) {
                p.cause = "LIGHT FLICKER";
                p.explained = true;
            } else if (p.freqHz >= 3.0f && edge > 0.06) {
                p.cause = "HAND TREMOR/EDGE";
                p.explained = true;
            } else {
                p.cause = "UNEXPLAINED";
                p.explained = false;
            }
            p.ageSec = (float) (run * EVERY / Math.max(fpsEma, 1.0));
            found.add(p);
        }

        // track across analyses so ids and ages are stable
        for (Track t : tracks) t.matched = false;
        List<PulseSource> out = new ArrayList<PulseSource>();
        for (PulseSource p : found) {
            Track best = null;
            double bd = 0.15;
            for (Track t : tracks) {
                if (t.matched) continue;
                double d = Math.hypot(t.cx - p.cx, t.cy - p.cy);
                if (d < bd && Math.abs(t.freq - p.freqHz) / Math.max(p.freqHz, 0.1f) < 0.25) {
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
            best.age++;
            best.cx = p.cx;
            best.cy = p.cy;
            best.freq = p.freqHz;
            p.id = best.id;
            p.confirmed = best.age >= 3;
            if (p.confirmed) out.add(p);
        }
        for (int i = tracks.size() - 1; i >= 0; i--) {
            Track t = tracks.get(i);
            if (!t.matched) {
                t.missed++;
                if (t.missed > 3) tracks.remove(i);
            }
        }
        java.util.Collections.sort(out, new java.util.Comparator<PulseSource>() {
            @Override
            public int compare(PulseSource a, PulseSource b) {
                return Float.compare(b.pmrDb * (float) Math.sqrt(b.cells), a.pmrDb * (float) Math.sqrt(a.cells));
            }
        });
        int keep = Math.min(4, out.size());
        lastPulses = out.subList(0, keep).toArray(new PulseSource[0]);
    }
}
