import com.nscb.spiritscan.senses.*;
import java.util.*;

public class MapTest {
    static double bump(double x, double y, double x0, double y0, double a, double w) { double dx = x - x0, dy = y - y0; return a * Math.exp(-(dx * dx + dy * dy) / (2 * w * w)); }

    public static void main(String[] args) {
        run("changing source appears at 30 s (6,2); static source at (3,0.3)", 30.0, true, 0.72, 0.0015, 0.25, 4);
        run("nothing changes (false-alarm check)", 1e9, true, 0.72, 0.0015, 0.25, 4);
        run("nothing changes, noisier field + 10% step-length error", 1e9, true, 0.77, 0.0015, 0.5, 4);
        run("nothing changes, gyro bias 0.004 rad/s (cheap gyro)", 1e9, true, 0.72, 0.004, 0.25, 4);
    }

    static void run(String name, double tChange, boolean staticSrc, double trueStep, double bias, double magNoise, int loops) {
        Random rnd = new Random(21);
        MagMapEngine eng = new MagMapEngine();
        double[] segLen = {6, 4, 6, 4};
        double x = 0, y = 0, th = 0;
        double dt = 0.02; double v = trueStep * 1.5;
        double T = 26.0 * loops;
        double trueX = 0, trueY = 0;
        // time-based loop schedule: walk a side, turn left 90 deg in 1 s (moving slowly), repeat
        double tt = 0;
        for (double t = 0; t < T; t += dt) {
            double cyc = 0; for (double l : segLen) cyc += l / v + 1.0;
            double tm = t % cyc; double acc = 0; boolean turning = false; double w = 0;
            for (double l : segLen) { double d = l / v; if (tm < acc + d) { turning = false; break; } acc += d; if (tm < acc + 1.0) { turning = true; break; } acc += 1.0; }
            if (turning) w = (Math.PI / 2) / 1.0;
            th += w * dt;
            double speed = turning ? v * 0.4 : v;
            x += speed * Math.cos(th) * dt; y += speed * Math.sin(th) * dt;
            double bounce = 2.2 * Math.sin(2 * Math.PI * 1.5 * t);
            double B = 48.0 + (staticSrc ? bump(x, y, 3.0, 0.3, 12, 0.6) : 0) + (t >= tChange ? bump(x, y, 6.0, 2.0, 15, 0.5) : 0) + rnd.nextGaussian() * magNoise;
            eng.push(t, rnd.nextGaussian() * 0.2, rnd.nextGaussian() * 0.2, 9.81 + bounce + rnd.nextGaussian() * 0.2,
                     rnd.nextGaussian() * 0.02, rnd.nextGaussian() * 0.02, w + bias + rnd.nextGaussian() * 0.02, B);
            trueX = x; trueY = y;
        }
        MagMapEngine.Snapshot s = eng.snapshot();
        System.out.println("== " + name);
        System.out.printf("   steps=%d (expected ~%d)  est pos (%.1f, %.1f) true (%.1f, %.1f)  heading %.0f deg  sigma=%.2f uT  visited=%d%n",
            s.steps, (int) (T * 1.5), s.x, s.y, trueX, trueY, s.headingDeg, s.sigma, s.visited);
        System.out.printf("   changed cells: %d", s.changedCells);
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (int c = 0; c < s.g * s.g && shown < 8; c++) if (s.state[c] == 2) { sb.append(String.format(" (%.1f,%.1f)", ((c % s.g) - s.g / 2 + 0.5) * s.cell, ((c / s.g) - s.g / 2 + 0.5) * s.cell)); shown++; }
        System.out.println(sb);
        for (MagMapEngine.Pin p : s.pins) System.out.printf("   pin %s %+.1f uT at (%.1f, %.1f)%n", p.kind, p.dev, p.x, p.y);
    }
}
