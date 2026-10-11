package com.nscb.spiritscan.senses;

import com.nscb.spiritscan.dsp.Fft;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Sound camera: direction of arrival of a sound from a two-microphone phone, by GCC-PHAT.
 *
 * Per 43 ms block the two channels are cross-correlated with phase transform weighting over 300 Hz..8 kHz (PHAT keeps only
 * phase, which is what carries delay and is robust to room colouration). The peak inside the physically possible lag
 * range (microphone spacing / speed of sound) is refined by parabolic interpolation; angle = asin(c * tau / d).
 * Positive angle = toward channel 0. The estimate is only accepted when the block is clearly above the noise floor and the
 * correlation peak is sharp. Coarse by nature: with ~14 cm spacing expect +-10..20 degrees, and only the angle in the plane
 * of the two microphones (the phone's long axis for a top/bottom pair). Many phones deliver a duplicated mono signal as
 * "stereo": that is detected and reported instead of producing nonsense angles.
 */
public final class DoaEngine {
    public static final int FS = 48000;
    public static final int BLOCK = 2048;
    public static final int NFFT = 4096;
    public static final int HIST_BINS = 37;
    private static final double C = 343.0;

    public static final class Snapshot {
        public boolean stereoOk = true;
        public boolean valid;
        public float angleDeg;
        public float confidence;
        public float levelDb;
        public float tauUs;
        public float biasUs;
        public boolean calibrating;
        public float baselineM;
        public float[] hist = new float[HIST_BINS];
        public long updates;
        public String note = "";
    }

    private final double baseline;
    private final int maxLag;
    private final Fft fft = new Fft(NFFT);
    private final double[] win = new double[BLOCK];
    private final double[] l = new double[BLOCK];
    private final double[] r = new double[BLOCK];
    private int pos;
    private final double[] ar = new double[NFFT];
    private final double[] ai = new double[NFFT];
    private final double[] gr = new double[NFFT];
    private final double[] gi = new double[NFFT];
    private final double[] cc = new double[NFFT];

    private double noiseDb = -70.0;
    private boolean noiseInit;
    private double levelDb = -120.0;
    private final double[] hist = new double[HIST_BINS];
    private double angle;
    private double conf;
    private double tauUs;
    private long lastValidBlock = -1000;
    private long block;
    private long updates;
    private double biasS;
    private boolean calibrating;
    private long calUntil;
    private final List<Double> calTaus = new ArrayList<Double>();
    private int dupHits;
    private int activeBlocks;
    private boolean stereoOk = true;
    private String note = "";

    public DoaEngine(double baselineMeters) {
        baseline = baselineMeters;
        maxLag = (int) Math.ceil(baselineMeters / C * FS * 1.15) + 1;
        for (int i = 0; i < BLOCK; i++) win[i] = 0.5 - 0.5 * Math.cos(2.0 * Math.PI * i / (BLOCK - 1));
    }

    public synchronized void startCalibration() {
        calibrating = true;
        calTaus.clear();
        calUntil = block + 70;
    }

    public synchronized void clearCalibration() {
        biasS = 0;
        calibrating = false;
    }

    /** Interleaved L,R int16 samples; [frames] = samples per channel. */
    public synchronized void push(short[] stereo, int frames) {
        for (int i = 0; i < frames; i++) {
            l[pos] = stereo[2 * i] / 32768.0;
            r[pos] = stereo[2 * i + 1] / 32768.0;
            pos++;
            if (pos == BLOCK) {
                pos = 0;
                process();
            }
        }
    }

    private void process() {
        block++;
        double e = 0;
        double cross = 0;
        double el = 0;
        double er = 0;
        for (int i = 0; i < BLOCK; i++) {
            e += l[i] * l[i] + r[i] * r[i];
            cross += l[i] * r[i];
            el += l[i] * l[i];
            er += r[i] * r[i];
        }
        levelDb = 10.0 * Math.log10(e / (2.0 * BLOCK) + 1e-14);
        if (!noiseInit) {
            noiseDb = levelDb;
            noiseInit = true;
        } else {
            noiseDb += (levelDb - noiseDb) * (levelDb < noiseDb ? 0.2 : 0.004);
        }
        for (int i = 0; i < HIST_BINS; i++) hist[i] *= 0.985;
        boolean active = levelDb > noiseDb + 8.0 && levelDb > -62.0;
        if (!active) {
            decayState();
            return;
        }
        double corr0 = cross / Math.sqrt(el * er + 1e-18);
        activeBlocks++;
        for (int i = 0; i < NFFT; i++) {
            ar[i] = i < BLOCK ? l[i] * win[i] : 0;
            ai[i] = 0;
            gr[i] = i < BLOCK ? r[i] * win[i] : 0;
            gi[i] = 0;
        }
        fft.forward(ar, ai);
        fft.forward(gr, gi);
        int kLo = (int) Math.round(300.0 * NFFT / FS);
        int kHi = (int) Math.round(8000.0 * NFFT / FS);
        double[] hr = new double[NFFT];
        double[] hi = new double[NFFT];
        for (int k = kLo; k <= kHi; k++) {
            double pr = ar[k] * gr[k] + ai[k] * gi[k];
            double pi = ai[k] * gr[k] - ar[k] * gi[k];
            double m = Math.hypot(pr, pi) + 1e-12;
            pr /= m;
            pi /= m;
            hr[k] = pr;
            hi[k] = -pi;
            hr[NFFT - k] = pr;
            hi[NFFT - k] = pi;
        }
        // hi currently holds conj for the positive side; build full Hermitian spectrum, then forward-FFT of conj gives conj(IFFT)*N
        fft.forward(hr, hi);
        for (int i = 0; i < NFFT; i++) cc[i] = hr[i] / NFFT;
        int bestLag = 0;
        double best = -1e9;
        double sumAbs = 0;
        int cnt = 0;
        for (int lag = -maxLag; lag <= maxLag; lag++) {
            double v = cc[(lag + NFFT) % NFFT];
            sumAbs += Math.abs(v);
            cnt++;
            if (v > best) {
                best = v;
                bestLag = lag;
            }
        }
        double mean = sumAbs / cnt + 1e-12;
        double sharp = best / mean;
        double frac = bestLag;
        if (bestLag > -maxLag && bestLag < maxLag) {
            double a = cc[(bestLag - 1 + NFFT) % NFFT];
            double b = cc[(bestLag + NFFT) % NFFT];
            double c = cc[(bestLag + 1 + NFFT) % NFFT];
            double den = a - 2.0 * b + c;
            if (Math.abs(den) > 1e-15) frac = bestLag + 0.5 * (a - c) / den;
        }
        // duplicated mono detection
        if (corr0 > 0.9995 && Math.abs(frac) < 0.3) dupHits++;
        else dupHits = Math.max(0, dupHits - 1);
        stereoOk = !(activeBlocks > 20 && dupHits > 15);
        // corr peak at lag m means R delayed by -m relative to L
        double tau = -frac / FS - biasS;
        if (calibrating && sharp >= 4.0) calTaus.add(-frac / FS);
        if (calibrating && block >= calUntil) {
            if (calTaus.size() >= 10) {
                Collections.sort(calTaus);
                biasS = calTaus.get(calTaus.size() / 2);
                note = "calibrated: this direction is now 0 degrees";
            } else {
                note = "calibration failed: make a clear sound straight ahead";
            }
            calibrating = false;
        }
        if (sharp >= 4.0 && stereoOk) {
            double s = C * tau / baseline;
            s = Math.max(-1.0, Math.min(1.0, s));
            angle = Math.toDegrees(Math.asin(s));
            conf = Math.min(1.0, (sharp - 4.0) / 12.0);
            tauUs = tau * 1e6;
            lastValidBlock = block;
            updates++;
            int hb = (int) Math.round((angle + 90.0) / 5.0);
            hb = Math.max(0, Math.min(HIST_BINS - 1, hb));
            hist[hb] += conf;
            note = "";
        } else if (!stereoOk) {
            note = "this phone delivers duplicated mono: no direction available";
        }
    }

    private void decayState() {
    }

    public synchronized Snapshot snapshot() {
        Snapshot s = new Snapshot();
        s.stereoOk = stereoOk;
        s.valid = (block - lastValidBlock) < 35 && updates > 0;
        s.angleDeg = (float) angle;
        s.confidence = (float) conf;
        s.levelDb = (float) levelDb;
        s.tauUs = (float) tauUs;
        s.biasUs = (float) (biasS * 1e6);
        s.calibrating = calibrating;
        s.baselineM = (float) baseline;
        for (int i = 0; i < HIST_BINS; i++) s.hist[i] = (float) hist[i];
        s.updates = updates;
        s.note = note;
        return s;
    }
}
