import com.nscb.spiritscan.senses.*;
import java.util.*;

public class DoaTest {
    static final int FS = 48000;
    static final double C = 343.0;

    static short[] scene(double angleDeg, double d, double seconds, double snrDb, long seed, double biasUs, boolean mono) {
        Random rnd = new Random(seed);
        int n = (int) (seconds * FS);
        int nf = 300;
        double[] f = new double[nf], ph = new double[nf], am = new double[nf];
        for (int i = 0; i < nf; i++) { f[i] = 300 + rnd.nextDouble() * 7700; ph[i] = rnd.nextDouble() * 6.2832; am[i] = 1.0 / Math.sqrt(nf); }
        double tau = d * Math.sin(Math.toRadians(angleDeg)) / C + biasUs * 1e-6; // R is later than L when angle > 0 (source toward L)
        double noiseSigma = Math.pow(10, -snrDb / 20.0) * 0.3 * 0.7;
        short[] out = new short[2 * n];
        for (int i = 0; i < n; i++) {
            double t = (double) i / FS;
            // sound only 40% of the time (bursts), so the noise floor can be learned
            boolean on = ((int) (t * 2.0)) % 2 == 0;
            double sl = 0, sr = 0;
            if (on) for (int k = 0; k < nf; k++) { sl += am[k] * Math.cos(2 * Math.PI * f[k] * t + ph[k]); sr += am[k] * Math.cos(2 * Math.PI * f[k] * (t - tau) + ph[k]); }
            sl *= 0.3; sr *= 0.3;
            double nl = rnd.nextGaussian() * noiseSigma + rnd.nextGaussian() * 0.0005, nr = rnd.nextGaussian() * noiseSigma + rnd.nextGaussian() * 0.0005;
            double L = sl + nl, R = mono ? L : sr + nr;
            out[2 * i] = (short) Math.max(-32768, Math.min(32767, Math.round(L * 32768)));
            out[2 * i + 1] = (short) Math.max(-32768, Math.min(32767, Math.round(R * 32768)));
        }
        return out;
    }

    static DoaEngine.Snapshot run(DoaEngine e, short[] st) {
        int pos = 0; int frames = st.length / 2;
        DoaEngine.Snapshot last = null;
        List<Double> est = new ArrayList<>();
        while (pos < frames) {
            int m = Math.min(1920, frames - pos);
            short[] chunk = Arrays.copyOfRange(st, 2 * pos, 2 * (pos + m));
            e.push(chunk, m); pos += m;
            DoaEngine.Snapshot s = e.snapshot();
            if (s.valid) est.add((double) s.angleDeg);
            last = s;
        }
        Collections.sort(est);
        last.angleDeg = est.isEmpty() ? Float.NaN : est.get(est.size() / 2).floatValue();
        return last;
    }

    public static void main(String[] a) {
        double d = 0.14;
        System.out.println("true angle -> median estimate (SNR 15 dB, d=14 cm)");
        for (double ang : new double[]{-70, -35, -10, 0, 20, 45, 70}) {
            DoaEngine e = new DoaEngine(d);
            DoaEngine.Snapshot s = run(e, scene(ang, d, 8, 15, 5, 0, false));
            System.out.printf("  %6.0f -> %6.1f  (updates=%d, stereoOk=%b)%n", ang, s.angleDeg, s.updates, s.stereoOk);
        }
        System.out.println("SNR sweep at 30 degrees:");
        for (double snr : new double[]{25, 10, 3, 0}) {
            DoaEngine e = new DoaEngine(d);
            DoaEngine.Snapshot s = run(e, scene(30, d, 8, snr, 7, 0, false));
            System.out.printf("  SNR %4.0f dB -> %6.1f  (updates=%d)%n", snr, s.angleDeg, s.updates);
        }
        System.out.println("duplicated mono:");
        { DoaEngine e = new DoaEngine(d); DoaEngine.Snapshot s = run(e, scene(30, d, 8, 15, 9, 0, true)); System.out.printf("  stereoOk=%b note='%s'%n", s.stereoOk, s.note); }
        System.out.println("noise only (no sound): ");
        { DoaEngine e = new DoaEngine(d); short[] st = scene(0, d, 8, 15, 3, 0, false); for (int i = 0; i < st.length; i++) st[i] = (short) (st[i] / 100); DoaEngine.Snapshot s = run(e, st); System.out.printf("  updates=%d%n", s.updates); }
        System.out.println("calibration with a 60 us hardware bias: broadside clap then source at 30 deg");
        { DoaEngine e = new DoaEngine(d); e.startCalibration(); run(e, scene(0, d, 4, 15, 12, 60, false));
          DoaEngine.Snapshot s0 = e.snapshot(); System.out.printf("  bias est = %.1f us (true 60)%n", s0.biasUs);
          DoaEngine.Snapshot s = run(e, scene(30, d, 8, 15, 13, 60, false)); System.out.printf("  30 deg source reads %.1f%n", s.angleDeg); }
    }
}
