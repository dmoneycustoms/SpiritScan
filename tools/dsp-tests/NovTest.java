import com.nscb.spiritscan.dsp.*;
import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;

public class NovTest {
    static float[] W1, b1, W2, b2, W3, b3, W4, b4;
    static float[] rd(ByteBuffer bb, int n) { float[] a = new float[n]; for (int i = 0; i < n; i++) a[i] = bb.getFloat(); return a; }
    static void load() throws Exception {
        ByteBuffer bb = ByteBuffer.wrap(Files.readAllBytes(Paths.get("/home/claude/onnxwork/weights.bin"))).order(ByteOrder.LITTLE_ENDIAN);
        W1 = rd(bb, 192 * 48); b1 = rd(bb, 48); W2 = rd(bb, 48 * 10); b2 = rd(bb, 10);
        W3 = rd(bb, 10 * 48); b3 = rd(bb, 48); W4 = rd(bb, 48 * 192); b4 = rd(bb, 192);
    }
    static float[] dense(float[] x, float[] W, float[] b, int in, int out, boolean tanh) {
        float[] y = new float[out];
        for (int j = 0; j < out; j++) { double s = b[j]; for (int i = 0; i < in; i++) s += x[i] * W[i * out + j]; y[j] = tanh ? (float) Math.tanh(s) : (float) s; }
        return y;
    }
    static float[] ae(float[] x) {
        return dense(dense(dense(dense(x, W1, b1, 192, 48, true), W2, b2, 48, 10, false), W3, b3, 10, 48, true), W4, b4, 48, 192, false);
    }

    static Random rnd = new Random(7);
    static final double[] MEAN = {48, 0.0, 0.1, 0.0, 0.05, 0.2, 0.01, 0.05, 0.1, 5, 0.02, 0.0};
    static final double[] SCALE = {0.4, 1, 0.05, 1, 0.02, 0.1, 0.005, 0.02, 0.05, 2, 0.02, 0.01};
    static double[] state = new double[12]; static double[] shared = new double[3];
    static double[][] mix = new double[12][3];
    static void init() {
        for (int c = 0; c < 12; c++) { double n = 0; for (int k = 0; k < 3; k++) { mix[c][k] = rnd.nextGaussian(); n += mix[c][k] * mix[c][k]; } for (int k = 0; k < 3; k++) mix[c][k] /= Math.sqrt(n); }
    }
    static float[] sample(int t, String scenario, double tNow) {
        for (int k = 0; k < 3; k++) shared[k] = 0.9 * shared[k] + Math.sqrt(1 - 0.81) * rnd.nextGaussian();
        float[] x = new float[12];
        for (int c = 0; c < 12; c++) {
            if (c == 10 || c == 11) { x[c] = 0f; continue; } // two channels not running (AIR / decode off)
            state[c] = 0.5 * state[c] + Math.sqrt(1 - 0.25) * rnd.nextGaussian();
            double v = Math.sqrt(0.5) * (mix[c][0] * shared[0] + mix[c][1] * shared[1] + mix[c][2] * shared[2]) + Math.sqrt(0.5) * state[c];
            x[c] = (float) (MEAN[c] + SCALE[c] * v);
        }
        if (scenario.equals("step") && tNow >= 0 && tNow < 8) x[3] += 4.5 * SCALE[3];
        if (scenario.equals("burst") && tNow >= 0 && tNow < 30) { double w = 3.0 * Math.sin(2 * Math.PI * 0.2 * tNow); x[1] += w * SCALE[1]; x[8] += w * SCALE[8]; }
        if (scenario.equals("spike") && tNow >= 0 && tNow < 1) x[0] += 12 * SCALE[0];
        if (scenario.equals("drift") && tNow >= 0) x[0] += (tNow / 600.0) * 3 * SCALE[0];
        return x;
    }

    static NoveltyCore calibrated(boolean useAe) {
        String[] names = {"mag", "zMag", "resid", "audioZ", "speechRes", "camScore", "camMad", "specRes", "voice", "bandMax", "decode", "air"};
        NoveltyCore core = new NoveltyCore(names);
        core.startLearn(150);
        for (int i = 0; i < 150; i++) core.push(sample(i, "none", -1), i * 1000L);
        List<float[]> wins = core.calibrationWindows();
        List<float[]> rec = new ArrayList<>();
        for (float[] w : wins) rec.add(ae(w));
        core.finishCalibration(wins, useAe ? rec : null);
        return core;
    }

    static int run(NoveltyCore core, String scenario, int seconds, int onsetSec, boolean print) {
        long t0 = 200_000L;
        for (int i = 0; i < seconds; i++) {
            float[] raw = sample(i, scenario, i - onsetSec);
            float[] w = core.push(raw, t0 + i * 1000L);
            if (w != null) core.finishStep(ae(w), w);
        }
        NoveltySnapshot s = core.snapshot();
        if (print) for (NoveltyEvent e : s.events) System.out.printf("    event kind=%s peak=%.1f dur=%.0fs start=%ds channels=[%s]%n", e.kind, e.peakZ, e.durSec, (e.startMs - t0) / 1000, e.channels);
        return s.events.size();
    }

    public static void main(String[] a) throws Exception {
        load(); init();
        System.out.println("=== false events: 2 hours of normal data after a 150 s baseline (x3 seeds) ===");
        for (int seed = 0; seed < 3; seed++) { rnd = new Random(100 + seed); init(); state = new double[12]; shared = new double[3];
            NoveltyCore c = calibrated(true); int n = run(c, "none", 7200, 0, false); System.out.println("seed " + seed + " -> events in 2 h: " + n); }
        System.out.println("=== injected disturbances (200 s normal, onset at 60 s) ===");
        for (String sc : new String[]{"step", "burst", "spike", "drift"}) {
            rnd = new Random(5); init(); state = new double[12]; shared = new double[3];
            NoveltyCore c = calibrated(true);
            System.out.println(sc + ":"); int n = run(c, sc, sc.equals("drift") ? 900 : 200, 60, true); if (n == 0) System.out.println("    (no event)");
        }
        System.out.println("=== model unavailable (stats only) ===");
        rnd = new Random(5); init(); state = new double[12]; shared = new double[3];
        NoveltyCore c = calibrated(false); System.out.println("aeAvailable=" + c.snapshot().aeAvailable); run(c, "step", 200, 60, true);
    }
}
