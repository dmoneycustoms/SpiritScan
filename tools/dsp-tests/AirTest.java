import com.nscb.spiritscan.air.*;
import java.util.*;

public class AirTest {
    static final int W = 120, H = 160;
    static Random rnd = new Random(3);

    static double tex(double x, double y) {
        return 0.5 + 0.4 * (0.18 * Math.sin(0.55 * x) * Math.cos(0.43 * y) + 0.10 * Math.sin(0.9 * (x + 0.7 * y)) + 0.08 * Math.sin(1.7 * x - 0.3 * y));
    }

    interface Scene { void frame(double t, float[] xa); }

    static void run(String name, double seconds, Scene sc, double sigma, boolean verbose) {
        float[] mu = new float[W * H];
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) mu[y * W + x] = (float) tex(x, y);
        AirEngine eng = new AirEngine();
        float[] xa = new float[W * H];
        int frames = (int) (seconds * 30);
        double sumIdx = 0; int idxN = 0; int maxSig = 0; int pulseFrames = 0;
        Map<String, String> pul = new LinkedHashMap<>();
        AirFrame last = null;
        long t0 = System.nanoTime();
        for (int f = 0; f < frames; f++) {
            double t = f / 30.0;
            sc.frame(t, xa);
            for (int i = 0; i < xa.length; i++) xa[i] += (float) (rnd.nextGaussian() * sigma);
            last = eng.process(xa, mu, (float) sigma, W, H, 1.0 / 30.0);
            if (f > 150) { sumIdx += last.airIndex; idxN++; maxSig = Math.max(maxSig, last.sigBlocks); }
            if (last.pulses.length > 0) pulseFrames++;
            for (PulseSource p : last.pulses) pul.put(p.id + "", String.format("%.2fHz at (%.2f,%.2f) cells=%d pmr=%.1fdB amp=%.1f/1000 cause=%s", p.freqHz, p.cx, p.cy, p.cells, p.pmrDb, p.ampMilli, p.cause));
        }
        double ms = (System.nanoTime() - t0) / 1e6 / frames;
        System.out.printf("%-34s meanAirIdx=%.3f maxSigBlocks=%d validBlocks=%d pulseFrames=%d  (%.2f ms/frame)%n", name, sumIdx / Math.max(1, idxN), maxSig, last.validBlocks, pulseFrames, ms);
        for (Map.Entry<String, String> e : pul.entrySet()) System.out.println("    pulse #" + e.getKey() + " " + e.getValue());
    }

    static void fill(float[] xa, double dx, double dy) { for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) xa[y * W + x] = (float) tex(x - dx, y - dy); }

    public static void main(String[] a) {
        double sig = 0.01;
        run("A noise only 40s", 40, (t, xa) -> fill(xa, 0, 0), sig, false);
        run("A2 noise + 0.3px hand drift", 40, (t, xa) -> { double d = 0.3 * Math.sin(2 * Math.PI * 0.4 * t); fill(xa, d, -d * 0.7); }, sig, false);
        run("B pulse 1.5Hz 12/1000 in 3x3 cells", 20, (t, xa) -> {
            fill(xa, 0, 0);
            for (int y = 64; y < 88; y++) for (int x = 48; x < 72; x++) xa[y * W + x] += (float) (0.012 * Math.sin(2 * Math.PI * 1.5 * t));
        }, sig, true);
        run("B2 pulse 4Hz 6/1000 in 2x2 cells", 20, (t, xa) -> {
            fill(xa, 0, 0);
            for (int y = 96; y < 112; y++) for (int x = 24; x < 40; x++) xa[y * W + x] += (float) (0.006 * Math.sin(2 * Math.PI * 4.0 * t));
        }, sig, true);
        run("C air shimmer 0.12px 1.2Hz region", 20, (t, xa) -> {
            double dh = 0.12 * Math.sin(2 * Math.PI * 1.2 * t);
            for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) {
                boolean in = x >= 40 && x < 80 && y >= 50 && y < 100;
                xa[y * W + x] = (float) tex(x - (in ? dh : 0), y);
            }
        }, sig, false);
        run("C2 static 0.15px shift region", 20, (t, xa) -> {
            for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) {
                boolean in = x >= 40 && x < 80 && y >= 50 && y < 100;
                xa[y * W + x] = (float) tex(x - (in ? 0.15 : 0), y);
            }
        }, sig, false);
        run("D whole-room flicker 6% @7.3Hz", 30, (t, xa) -> { fill(xa, 0, 0); float g = (float) (1 + 0.06 * Math.sin(2 * Math.PI * 7.3 * t)); for (int i = 0; i < xa.length; i++) xa[i] *= g; }, sig, true);
        run("E hand tremor 0.5px @5Hz", 30, (t, xa) -> { double d = 0.5 * Math.sin(2 * Math.PI * 5.0 * t); fill(xa, d, 0); }, sig, true);
    }
}
