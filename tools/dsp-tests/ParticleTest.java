import com.nscb.spiritscan.senses.*;
import java.util.*;

public class ParticleTest {
    static final int W = 1500, H = 1000;
    static Random rnd = new Random(77);
    static int[] hotX, hotY; static double[] hotAmp;

    static class Truth { int frame; String kind; double x, y; }

    static void paint(short[] f, int x, int y, double amp) { if (x < 1 || y < 1 || x >= W - 1 || y >= H - 1) return; int i = y * W + x; f[i] = (short) Math.min(4000, (f[i] & 0xFFFF) + Math.round(amp)); }

    static Truth inject(short[] f, String kind, int frame) {
        Truth t = new Truth(); t.frame = frame; t.kind = kind;
        int cx = 40 + rnd.nextInt(W - 80), cy = 40 + rnd.nextInt(H - 80);
        t.x = cx; t.y = cy;
        switch (kind) {
            case "TRACK": { double ang = rnd.nextDouble() * Math.PI, len = 8 + rnd.nextDouble() * 12; double a = 40 + rnd.nextDouble() * 80;
                for (double s = -len / 2; s <= len / 2; s += 0.5) { int x = (int) Math.round(cx + s * Math.cos(ang)), y = (int) Math.round(cy + s * Math.sin(ang)); paint(f, x, y, a); } break; }
            case "WORM": { double x = cx, y = cy, ang = rnd.nextDouble() * 6.28; double a = 30 + rnd.nextDouble() * 70; int n = 6 + rnd.nextInt(8);
                for (int i = 0; i < n; i++) { paint(f, (int) Math.round(x), (int) Math.round(y), a); ang += (rnd.nextDouble() - 0.5) * 2.4; x += Math.cos(ang) * 1.1; y += Math.sin(ang) * 1.1; } break; }
            case "SPOT": { double a = 28 + rnd.nextDouble() * 60; paint(f, cx, cy, a); if (rnd.nextBoolean()) paint(f, cx + 1, cy, a * 0.7); break; }
            case "BLOB": { double r = 3 + rnd.nextDouble(); double a = 40 + rnd.nextDouble() * 100; for (int dy = -4; dy <= 4; dy++) for (int dx = -4; dx <= 4; dx++) if (dx * dx + dy * dy <= r * r) paint(f, cx + dx, cy + dy, a * (1 - 0.5 * Math.hypot(dx, dy) / r)); break; }
        }
        return t;
    }

    static short[] frame(boolean leak) {
        short[] f = new short[W * H];
        double black = 64 + (leak ? 30 : 0);
        for (int i = 0; i < f.length; i++) f[i] = (short) Math.round(black + rnd.nextGaussian() * 3.0);
        for (int k = 0; k < hotX.length; k++) paint(f, hotX[k], hotY[k], hotAmp[k] + rnd.nextGaussian() * 6);
        return f;
    }

    static int poisson(double lam) { double L = Math.exp(-lam), p = 1; int k = 0; do { k++; p *= rnd.nextDouble(); } while (p > L); return k - 1; }

    public static void main(String[] a) {
        hotX = new int[200]; hotY = new int[200]; hotAmp = new double[200];
        for (int i = 0; i < 200; i++) { hotX[i] = 5 + rnd.nextInt(W - 10); hotY[i] = 5 + rnd.nextInt(H - 10); hotAmp[i] = 40 + rnd.nextDouble() * 80; }
        ParticleEngine eng = new ParticleEngine();
        String[] kinds = {"TRACK", "WORM", "SPOT", "BLOB"};
        double[] weights = {0.2, 0.3, 0.4, 0.1};
        List<Truth> truth = new ArrayList<>();
        long t0 = 1_000_000;
        int frames = 900; double lam = 0.12;   // 7 events/min at 1 frame/s
        long start = System.nanoTime();
        for (int fr = 1; fr <= frames; fr++) {
            short[] f = frame(false);
            if (fr > 10) {
                int n = poisson(lam);
                for (int i = 0; i < n; i++) { double u = rnd.nextDouble(); int k = 0; double c = weights[0]; while (u > c && k < 3) { k++; c += weights[k]; } truth.add(inject(f, kinds[k], fr)); }
            }
            eng.process(f, W, H, W, 1.0, t0 + fr * 1000L);
        }
        double msPerFrame = (System.nanoTime() - start) / 1e6 / frames;
        List<ParticleEngine.Event> ev = eng.allEvents();
        // match
        Map<String, int[]> conf = new TreeMap<>();
        int matched = 0; boolean[] used = new boolean[ev.size()];
        for (Truth t : truth) {
            int best = -1; double bd = 6;
            for (int i = 0; i < ev.size(); i++) { ParticleEngine.Event e = ev.get(i); if (used[i] || e.frame != t.frame) continue; double d = Math.hypot(e.x - t.x, e.y - t.y); if (d < bd) { bd = d; best = i; } }
            conf.computeIfAbsent(t.kind, k -> new int[6]);
            int[] row = conf.get(t.kind);
            row[0]++;
            if (best >= 0) { used[best] = true; matched++; row[1]++; String k = ev.get(best).kind; int col = k.equals("TRACK") ? 2 : k.equals("WORM") ? 3 : k.equals("SPOT") ? 4 : k.equals("BLOB") ? 5 : 1; if (col >= 2) row[col]++; }
        }
        int falseEv = 0, falseSingles = 0; for (int i = 0; i < ev.size(); i++) if (!used[i]) { if (ev.get(i).kind.equals("SINGLE")) falseSingles++; else falseEv++; }
        System.out.printf("%d frames of %dx%d, %.0f ms/frame on this CPU; injected %d events; hot pixels 200%n", frames, W, H, msPerFrame, truth.size());
        System.out.println("truth    injected  found  ->TRACK ->WORM ->SPOT ->BLOB");
        for (Map.Entry<String, int[]> e : conf.entrySet()) { int[] r = e.getValue(); System.out.printf("%-8s %7d %6d  %6d %6d %6d %6d%n", e.getKey(), r[0], r[1], r[2], r[3], r[4], r[5]); }
        System.out.printf("false particle-like events with no injected truth: %d ; unmatched single pixels: %d (hot pixels masked after calibration: %d)%n", falseEv, falseSingles, eng.snapshot(t0 + frames * 1000L).hotMasked);
        for (int i = 0; i < ev.size(); i++) if (!used[i] && !ev.get(i).kind.equals("SINGLE")) { ParticleEngine.Event e = ev.get(i); StringBuilder near = new StringBuilder(); for (Truth t : truth) if (t.frame == e.frame) near.append(String.format(" [truth %s at %.0f,%.0f]", t.kind, t.x, t.y)); System.out.printf("   unmatched %s n=%d at (%.0f,%.0f) frame %d charge %.0f%s%n", e.kind, e.n, e.x, e.y, e.frame, e.charge, near); }
        ParticleEngine.Snapshot s = eng.snapshot(t0 + frames * 1000L);
        System.out.printf("rate last 5 min %.2f/min, baseline %.2f/min (injected %.1f/min), elevated=%b p=%.3f%n", s.rate5PerMin, s.baselinePerMin, lam * 60, s.elevated, s.pElevated);
    }
}
