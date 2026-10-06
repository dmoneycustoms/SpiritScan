package com.nscb.spiritscan.dsp;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Voice-signature detector for a 16 kHz mono stream. Pure DSP, no trained model.
 *
 * Per 32 ms frame (10 ms hop):
 *  - band-limited real cepstrum -> Cepstral Peak Prominence (harmonic structure) and F0 (70..400 Hz)
 *  - liftered cepstral envelope -> up to three formant peaks (F1/F2/F3) and a plausibility score
 *  - F0 continuity run-length (speech holds pitch; noise does not)
 *  - 1.3 s envelope modulation (syllable rate 2.5..7.5 Hz) as a soft feature
 * A voice-like EVENT needs sustained voiced frames with continuous pitch inside a 0.4 s window.
 * It reports structure, not meaning; noise can occasionally satisfy it, which is why NullTest measures the false-alarm rate.
 */
public final class VoiceSignature {

    public static final class Result {
        public boolean active;
        public float score;
        public float levelDb;
        public float snrDb;
        public float cppDb;
        public float f0;
        public float f1;
        public float f2;
        public boolean voicedNow;
        public int voicedRun;
        public float modRatioDb;
        public int events60;
        public long eventsTotal;
        public float secSinceEvent;
        public float lastF0;
        public float lastF1;
        public float lastF2;
        public float lastDur;
    }

    public static final int FS = 16000;
    private static final int WIN = 1024;
    private static final int NFFT = 2048;
    private static final int HOP = 160;
    private static final int HALF = NFFT / 2;
    private static final double DF = (double) FS / NFFT;
    private static final int LQ = 34;
    private static final int Q_MIN = 40;
    private static final int Q_MAX = 228;
    private static final int K_LO = (int) Math.round(60.0 / ((double) FS / NFFT));
    private static final int K_HI = (int) Math.round(4000.0 / ((double) FS / NFFT));
    private static final int WIN_FR = 40;
    private static final int ENV_N = 128;
    /** Calibrated on synthetic data: noise p99 = 0.55, max 0.76; clean vowels p50 1.1..1.8 (this scale is ln-power based, not clinical dB). */
    private static final double CPP_T = 0.95;
    private static final int PROM_BINS = (int) Math.round(312.0 / ((double) FS / NFFT));

    private final Fft fft = new Fft(NFFT);
    private final double[] win = new double[WIN];
    private final double[] ring = new double[WIN];
    private int wpos;
    private long total;
    private int sinceHop;
    private double dcX;
    private double dcY;

    private final double[] re = new double[NFFT];
    private final double[] im = new double[NFFT];
    private final double[] pw = new double[HALF + 1];
    private final double[] logS = new double[HALF + 1];
    private final double[] cep = new double[NFFT];
    private final double[] env = new double[HALF + 1];
    private final double[][] cosTab = new double[LQ + 1][HALF + 1];

    // per-frame history (last WIN_FR frames)
    private final boolean[] vFlag = new boolean[WIN_FR];
    private final double[] hF0 = new double[WIN_FR];
    private final double[] hF1 = new double[WIN_FR];
    private final double[] hF2 = new double[WIN_FR];
    private final double[] hCpp = new double[WIN_FR];
    private final double[] hF = new double[WIN_FR];
    private int hPos;
    private int hCount;

    private final double[] envSeries = new double[ENV_N];
    private int envPos;
    private int envCount;
    private double modRatioDb;

    private double noiseDb = -80.0;
    private boolean noiseInit;
    private double levelDb = -120.0;

    private double cppNow;
    private double f0Now;
    private double f1Now;
    private double f2Now;
    private boolean voicedNow;
    private int run;
    private double prevF0;
    private double scoreSm;

    private long frameNo;
    private long lastEventFrame = -100000;
    private long eventsTotal;
    private final List<Long> eventFrames = new ArrayList<Long>();
    private double lastF0;
    private double lastF1;
    private double lastF2;
    private double lastDur;

    public VoiceSignature() {
        for (int i = 0; i < WIN; i++) win[i] = 0.5 - 0.5 * Math.cos(2.0 * Math.PI * i / WIN);
        for (int q = 1; q <= LQ; q++) {
            for (int k = 0; k <= HALF; k++) {
                cosTab[q][k] = Math.cos(2.0 * Math.PI * k * q / NFFT);
            }
        }
    }

    public synchronized void reset() {
        Arrays.fill(vFlag, false);
        hPos = 0;
        hCount = 0;
        envCount = 0;
        run = 0;
        scoreSm = 0;
        eventFrames.clear();
    }

    public synchronized void push(short[] pcm, int n) {
        for (int i = 0; i < n; i++) {
            double x = pcm[i] / 32768.0;
            double y = x - dcX + 0.995 * dcY;
            dcX = x;
            dcY = y;
            ring[wpos] = y;
            wpos++;
            if (wpos == WIN) wpos = 0;
            total++;
            sinceHop++;
            if (sinceHop >= HOP && total >= WIN) {
                sinceHop = 0;
                frame();
            }
        }
    }

    private void frame() {
        frameNo++;
        double e = 0;
        for (int i = 0; i < WIN; i++) {
            double v = ring[(wpos + i) % WIN];
            e += v * v;
            re[i] = v * win[i];
            im[i] = 0;
        }
        for (int i = WIN; i < NFFT; i++) {
            re[i] = 0;
            im[i] = 0;
        }
        levelDb = 10.0 * Math.log10(e / WIN + 1e-14);
        if (!noiseInit) {
            noiseDb = levelDb;
            noiseInit = true;
        } else {
            noiseDb += (levelDb - noiseDb) * (levelDb < noiseDb ? 0.1 : 0.002);
        }

        fft.forward(re, im);
        double kmean = 0;
        int cnt = 0;
        for (int k = 0; k <= HALF; k++) {
            pw[k] = re[k] * re[k] + im[k] * im[k];
            logS[k] = Math.log(pw[k] + 1e-12);
        }
        for (int k = K_LO; k <= K_HI; k++) {
            kmean += logS[k];
            cnt++;
        }
        kmean /= cnt;

        // envelope feature: 300..3400 Hz amplitude
        double eb = 0;
        int kb0 = (int) Math.round(300.0 / DF);
        int kb1 = (int) Math.round(3400.0 / DF);
        for (int k = kb0; k <= kb1; k++) eb += pw[k];
        envSeries[envPos] = Math.sqrt(eb);
        envPos = (envPos + 1) % ENV_N;
        if (envCount < ENV_N) envCount++;
        if (frameNo % 10 == 0) computeModulation();

        boolean voiced = false;
        double cpp = 0;
        double f0 = 0;
        double f1 = 0;
        double f2 = 0;
        double fScore = 0;
        if (levelDb > -85.0) {
            // symmetric band-limited log spectrum -> real cepstrum
            for (int k = 0; k < NFFT; k++) {
                int kk = k <= HALF ? k : NFFT - k;
                re[k] = (kk >= K_LO && kk <= K_HI) ? logS[kk] : kmean;
                im[k] = 0;
            }
            fft.forward(re, im);
            for (int q = 0; q < NFFT; q++) cep[q] = re[q] / NFFT;

            // linear regression over the pitch quefrency range
            double sx = 0, sy = 0, sxx = 0, sxy = 0;
            int m = Q_MAX - Q_MIN + 1;
            for (int q = Q_MIN; q <= Q_MAX; q++) {
                sx += q;
                sy += cep[q];
                sxx += (double) q * q;
                sxy += q * cep[q];
            }
            double slope = (m * sxy - sx * sy) / (m * sxx - sx * sx);
            double icpt = (sy - slope * sx) / m;
            double bestV = -1e9;
            int bestQ = Q_MIN;
            for (int q = Q_MIN; q <= Q_MAX; q++) {
                double v = cep[q] - (icpt + slope * q);
                if (v > bestV) {
                    bestV = v;
                    bestQ = q;
                }
            }
            cpp = bestV * 4.342944819;
            double qf = bestQ;
            if (bestQ > Q_MIN && bestQ < Q_MAX) {
                double a = cep[bestQ - 1];
                double b = cep[bestQ];
                double c = cep[bestQ + 1];
                double den = a - 2.0 * b + c;
                if (Math.abs(den) > 1e-12) qf = bestQ + 0.5 * (a - c) / den;
            }
            f0 = FS / qf;

            // liftered envelope + formants
            for (int k = 0; k <= HALF; k++) {
                double s = cep[0];
                for (int q = 1; q <= LQ; q++) s += 2.0 * cep[q] * cosTab[q][k];
                env[k] = s;
            }
            double[] fm = pickFormants();
            f1 = fm[0];
            f2 = fm[1];
            fScore = fm[3];

            voiced = cpp >= CPP_T && f0 >= 70.0 && f0 <= 400.0 && fScore >= 0.5;
        }

        if (voiced) {
            if (run > 0 && prevF0 > 0 && Math.abs(Math.log(f0 / prevF0)) < 0.10) run++;
            else run = 1;
            prevF0 = f0;
        } else {
            run = 0;
            prevF0 = 0;
        }
        cppNow = cpp;
        f0Now = f0;
        f1Now = f1;
        f2Now = f2;
        voicedNow = voiced;

        vFlag[hPos] = voiced;
        hF0[hPos] = f0;
        hF1[hPos] = f1;
        hF2[hPos] = f2;
        hCpp[hPos] = cpp;
        hF[hPos] = fScore;
        hPos = (hPos + 1) % WIN_FR;
        if (hCount < WIN_FR) hCount++;

        windowDecision();
    }

    /** returns {F1, F2, F3, plausibility}. Frequencies in Hz or 0. */
    private double[] pickFormants() {
        int kA = (int) Math.round(200.0 / DF);
        int kB = (int) Math.round(3800.0 / DF);
        double[] pf = new double[8];
        double[] pv = new double[8];
        int np = 0;
        for (int k = kA; k <= kB && np < 8; k++) {
            if (env[k] >= env[k - 1] && env[k] > env[k + 1]) {
                int l0 = Math.max(0, k - PROM_BINS);
                int r1 = Math.min(HALF, k + PROM_BINS);
                double lmin = env[k];
                for (int j = l0; j <= k; j++) lmin = Math.min(lmin, env[j]);
                double rmin = env[k];
                for (int j = k; j <= r1; j++) rmin = Math.min(rmin, env[j]);
                double prom = env[k] - Math.max(lmin, rmin);
                if (prom >= 0.15) {
                    pf[np] = k * DF;
                    pv[np] = env[k];
                    np++;
                }
            }
        }
        // merge peaks closer than 250 Hz (keep stronger)
        List<double[]> keep = new ArrayList<double[]>();
        for (int i = 0; i < np; i++) {
            if (!keep.isEmpty()) {
                double[] last = keep.get(keep.size() - 1);
                if (pf[i] - last[0] < 250.0) {
                    if (pv[i] > last[1]) {
                        last[0] = pf[i];
                        last[1] = pv[i];
                    }
                    continue;
                }
            }
            keep.add(new double[]{pf[i], pv[i]});
        }
        double f1 = keep.size() > 0 ? keep.get(0)[0] : 0.0;
        double f2 = keep.size() > 1 ? keep.get(1)[0] : 0.0;
        double f3 = keep.size() > 2 ? keep.get(2)[0] : 0.0;
        double plaus = 0.0;
        if (f1 >= 200.0 && f1 <= 1000.0) {
            if (f2 >= f1 + 250.0 && f2 <= 2900.0) plaus = 1.0;
            else plaus = 0.5;
        }
        return new double[]{f1, f2, f3, plaus};
    }

    private void computeModulation() {
        if (envCount < ENV_N) return;
        double[] x = new double[ENV_N];
        double mean = 0;
        for (int i = 0; i < ENV_N; i++) {
            x[i] = envSeries[(envPos + i) % ENV_N];
            mean += x[i];
        }
        mean /= ENV_N;
        for (int i = 0; i < ENV_N; i++) {
            double w = 0.5 - 0.5 * Math.cos(2.0 * Math.PI * i / (ENV_N - 1));
            x[i] = (x[i] - mean) * w;
        }
        double fr = 100.0;
        int nb = 0;
        double[] p = new double[40];
        double peak = 0;
        for (double f = 0.8; f <= 12.0; f += 0.4) {
            double sr = 0, si = 0;
            for (int i = 0; i < ENV_N; i++) {
                double a = 2.0 * Math.PI * f * i / fr;
                sr += x[i] * Math.cos(a);
                si += x[i] * Math.sin(a);
            }
            double pp = sr * sr + si * si;
            p[nb++] = pp;
            if (f >= 2.5 && f <= 7.5 && pp > peak) peak = pp;
        }
        double[] sorted = Arrays.copyOf(p, nb);
        Arrays.sort(sorted);
        double med = sorted[nb / 2] + 1e-18;
        modRatioDb = 10.0 * Math.log10(peak / med + 1e-9);
    }

    private void windowDecision() {
        if (hCount < WIN_FR) return;
        int voiced = 0;
        int maxRun = 0;
        int r = 0;
        double sCpp = 0, sF = 0;
        List<Double> f0s = new ArrayList<Double>();
        List<Double> f1s = new ArrayList<Double>();
        List<Double> f2s = new ArrayList<Double>();
        for (int i = 0; i < WIN_FR; i++) {
            int idx = (hPos + i) % WIN_FR;
            if (vFlag[idx]) {
                voiced++;
                r++;
                if (r > maxRun) maxRun = r;
                f0s.add(hF0[idx]);
                if (hF1[idx] > 0) f1s.add(hF1[idx]);
                if (hF2[idx] > 0) f2s.add(hF2[idx]);
            } else {
                r = 0;
            }
            sCpp += hCpp[idx];
            sF += hF[idx];
        }
        double cppMean = sCpp / WIN_FR;
        double fMean = sF / WIN_FR;
        double modScore = Math.max(0.0, Math.min(1.0, (modRatioDb - 3.0) / 9.0));
        double raw = 0.45 * clamp((cppMean - 0.4) / 1.6)
                + 0.20 * fMean
                + 0.20 * clamp(maxRun / 20.0)
                + 0.15 * modScore;
        scoreSm = scoreSm * 0.9 + raw * 0.1;

        boolean fire = voiced >= 18 && maxRun >= 8 && (frameNo - lastEventFrame) > 100;
        if (fire) {
            lastEventFrame = frameNo;
            eventsTotal++;
            eventFrames.add(frameNo);
            lastF0 = median(f0s);
            lastF1 = median(f1s);
            lastF2 = median(f2s);
            lastDur = maxRun * HOP / (double) FS;
        }
        while (!eventFrames.isEmpty() && frameNo - eventFrames.get(0) > 6000) eventFrames.remove(0);
    }

    private static double clamp(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }

    private static double median(List<Double> v) {
        if (v.isEmpty()) return 0.0;
        List<Double> c = new ArrayList<Double>(v);
        java.util.Collections.sort(c);
        return c.get(c.size() / 2);
    }

    public synchronized Result result() {
        Result r = new Result();
        r.active = total >= WIN;
        r.score = (float) scoreSm;
        r.levelDb = (float) levelDb;
        r.snrDb = (float) (levelDb - noiseDb);
        r.cppDb = (float) cppNow;
        r.f0 = (float) f0Now;
        r.f1 = (float) f1Now;
        r.f2 = (float) f2Now;
        r.voicedNow = voicedNow;
        r.voicedRun = run;
        r.modRatioDb = (float) modRatioDb;
        r.events60 = eventFrames.size();
        r.eventsTotal = eventsTotal;
        r.secSinceEvent = lastEventFrame < 0 ? 999f : (float) ((frameNo - lastEventFrame) * HOP / (double) FS);
        r.lastF0 = (float) lastF0;
        r.lastF1 = (float) lastF1;
        r.lastF2 = (float) lastF2;
        r.lastDur = (float) lastDur;
        return r;
    }

    public synchronized long frameCount() {
        return frameNo;
    }
}
