import com.nscb.spiritscan.senses.*;
import java.util.*;

public class SonarTest {
    static final int FS = 48000, N = 1024, CHIRP = 512;
    static final double F0 = 18000, F1 = 20500, C = 343.0;
    static double tx(double u) {
        double um = u % N; if (um < 0) um += N;
        if (um >= CHIRP) return 0.0;
        double t = um / FS, tc = (double) CHIRP / FS, k = (F1 - F0) / tc;
        double w = 0.5 - 0.5 * Math.cos(2 * Math.PI * um / (CHIRP - 1));
        return w * Math.sin(2 * Math.PI * (F0 * t + 0.5 * k * t * t));
    }
    interface Scene { double[] reflectors(double t, double[] amp); } // returns ranges, fills amplitudes

    static Map<String, String> run(String name, double seconds, Scene sc, double ppm, double noise, int glitchAtSec, boolean print) {
        SonarEngine eng = new SonarEngine();
        Random rnd = new Random(11);
        int total = (int) (seconds * FS);
        int startOffset = 333;
        short[] buf = new short[4096];
        int pos = 0; double maxRangeErr = 0; int detFrames = 0, evalFrames = 0; String kinds = "";
        Map<String, Integer> kindCount = new TreeMap<>();
        double[] amp = new double[4];
        int falseFrames = 0;
        List<float[]> tracked = new ArrayList<>();
        int glitchSample = glitchAtSec > 0 ? glitchAtSec * FS : -1;
        double extraDelay = 0;
        for (int n = 0; n < total; n++) {
            double tt = (double) n / FS;
            if (n == glitchSample) extraDelay = 37.0; // transmitter loses 37 samples
            double clk = n * (1.0 + ppm * 1e-6) + startOffset - extraDelay;
            double v = 0.1 * tx(clk - 12.7);                         // direct path
            double[] r = sc.reflectors(tt, amp);
            for (int i = 0; i < r.length; i++) {
                if (r[i] <= 0) continue;
                double tau = 2.0 * r[i] / C * FS;
                v += amp[i] * tx(clk - 12.7 - tau);
            }
            v += rnd.nextGaussian() * noise;
            buf[pos++] = (short) Math.max(-32768, Math.min(32767, Math.round(v * 32768.0)));
            if (pos == buf.length) { eng.push(buf, pos); pos = 0;
                if (n > FS * 12 && n % (FS / 4) < 4096) {
                    SonarEngine.Snapshot s = eng.snapshot();
                    evalFrames++;
                    if (s.targets.size() > 0) { detFrames++;
                        for (SonarEngine.Target t : s.targets) { kindCount.merge(t.kind, 1, Integer::sum); tracked.add(new float[]{t.rangeM, t.db, t.velCms}); } }
                } }
        }
        SonarEngine.Snapshot s = eng.snapshot();
        System.out.printf("%-38s targets-in-%d/%d eval snapshots; kinds=%s; note='%s' directDb=%.1f%n", name, detFrames, evalFrames, kindCount, s.note, s.directDb);
        if (!tracked.isEmpty()) {
            double rs = 0, ds = 0, vs = 0; for (float[] t : tracked) { rs += t[0]; ds += t[1]; vs += t[2]; }
            System.out.printf("      mean range %.2f m, mean strength %.1f dB over floor, mean vel %.2f cm/s%n", rs / tracked.size(), ds / tracked.size(), vs / tracked.size());
        }
        return null;
    }

    public static void main(String[] a) {
        Scene quiet = (t, amp) -> { amp[0] = 0.002; amp[1] = 0.0015; return new double[]{0.9, 1.7, 0, 0}; };
        run("A quiet room, clutter only", 70, quiet, 0, 0.0002, -1, false);
        Scene breath = (t, amp) -> { amp[0] = 0.002; amp[1] = 0.0015; amp[2] = 0.0006; return new double[]{0.9, 1.7, t < 20 ? 0 : 2.0 + 0.004 * Math.sin(2 * Math.PI * 0.3 * t), 0}; };
        run("B breathing-like target at 2.0 m (from 20 s)", 70, breath, 0, 0.0002, -1, false);
        Scene breathSmall = (t, amp) -> { amp[0] = 0.002; amp[1] = 0.0015; amp[2] = 0.0003; return new double[]{0.9, 1.7, t < 20 ? 0 : 2.5 + 0.003 * Math.sin(2 * Math.PI * 0.25 * t), 0}; };
        run("B2 weaker breathing at 2.5 m, 3 mm", 70, breathSmall, 0, 0.0002, -1, false);
        Scene fan = (t, amp) -> { amp[0] = 0.002; amp[1] = 0.0015; amp[2] = 0.0006; return new double[]{0.9, 1.7, t < 20 ? 0 : 1.5 + 0.0025 * Math.sin(2 * Math.PI * 4.0 * t), 0}; };
        run("G fan-like 4 Hz flutter at 1.5 m", 70, fan, 0, 0.0002, -1, false);
        Scene walker = (t, amp) -> { amp[0] = 0.002; amp[1] = 0.0015; amp[2] = 0.0008; double r = t < 20 ? 0 : 1.0 + 0.5 * (t - 20); return new double[]{0.9, 1.7, r > 3.2 ? 0 : r, 0}; };
        run("C walker 1.0 -> 3.2 m at 0.5 m/s", 70, walker, 0, 0.0002, -1, false);
        run("D quiet + 20 ppm clock drift", 70, quiet, 20, 0.0002, -1, false);
        run("E quiet + audio glitch at 40 s", 90, quiet, 0, 0.0002, 40, false);
        run("F quiet, 3x noise", 70, quiet, 0, 0.0006, -1, false);
    }
}
