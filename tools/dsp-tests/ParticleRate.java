import com.nscb.spiritscan.senses.*;
import java.util.*;

public class ParticleRate {
    static final int W = 160, H = 120;
    static Random rnd = new Random(77);
    static int[] hotX, hotY; static double[] hotAmp;

    static class Truth { int frame; String kind; double x, y; }

    static void paint(short[] f, int x, int y, double amp) { if (x < 1 || y < 1 || x >= W - 1 || y >= H - 1) return; int i = y * W + x; f[i] = (short) Math.min(4000, (f[i] & 0xFFFF) + Math.round(amp)); }

    static Truth inject(short[] f, String kind, int frame) {
        Truth t = new Truth(); t.frame = frame; t.kind = kind;
        int cx = 20 + rnd.nextInt(W - 40), cy = 20 + rnd.nextInt(H - 40);
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

    static int[] simulate(long seed, int frames, double lam, double lamBurst, int burstFrames, boolean checkEvery, int leakFrom, int leakTo, int[] leakEvents) {
        rnd = new Random(seed);
        hotX = new int[30]; hotY = new int[30]; hotAmp = new double[30];
        for (int i = 0; i < 30; i++) { hotX[i] = 5 + rnd.nextInt(W - 10); hotY[i] = 5 + rnd.nextInt(H - 10); hotAmp[i] = 40 + rnd.nextDouble() * 80; }
        ParticleEngine eng = new ParticleEngine();
        String[] kinds = {"TRACK", "WORM", "SPOT", "BLOB"};
        long t0 = 5_000_000; int falseFlags = 0; int checks = 0; boolean leakSeen = false; int eventsDuringLeak = 0; long before = 0;
        for (int fr = 1; fr <= frames; fr++) {
            boolean leak = fr >= leakFrom && fr < leakTo;
            short[] f = frame(leak);
            double l = fr > frames - burstFrames ? lamBurst : lam;
            if (fr > 10 && !leak) { int n = poisson(l); for (int i = 0; i < n; i++) inject(f, kinds[rnd.nextInt(4)], fr); }
            eng.process(f, W, H, W, 1.0, t0 + fr * 1000L);
            if (fr == leakFrom - 1) before = eng.snapshot(t0 + fr * 1000L).particleLike;
            if (leak) { ParticleEngine.Snapshot s = eng.snapshot(t0 + fr * 1000L); if (s.lightLeak) leakSeen = true; eventsDuringLeak = (int) (s.particleLike - before); }
            if (checkEvery && fr > 1500 && fr % 60 == 0 && fr <= frames - burstFrames) { checks++; if (eng.snapshot(t0 + fr * 1000L).elevated) falseFlags++; }
        }
        ParticleEngine.Snapshot s = eng.snapshot(t0 + frames * 1000L);
        if (leakEvents != null) { leakEvents[0] = leakSeen ? 1 : 0; leakEvents[1] = eventsDuringLeak; }
        return new int[]{s.elevated ? 1 : 0, falseFlags, checks, (int) s.particleLike};
    }

    public static void main(String[] a) {
        int nullRuns = 12; int ff = 0, ck = 0, endFlag = 0;
        for (int r = 0; r < nullRuns; r++) { int[] o = simulate(100 + r, 2700, 0.05, 0.05, 0, true, 1 << 30, 1 << 30, null); ff += o[1]; ck += o[2]; endFlag += o[0]; }
        System.out.printf("NULL: constant 3 events/min for 45 min, %d runs: elevated flagged in %d of %d checks (%.2f%%), at end in %d of %d runs%n", nullRuns, ff, ck, 100.0 * ff / ck, endFlag, nullRuns);
        for (double mult : new double[]{2, 3, 6}) {
            int det = 0; int runs = 10;
            for (int r = 0; r < runs; r++) { int[] o = simulate(500 + r, 2700, 0.05, 0.05 * mult, 300, false, 1 << 30, 1 << 30, null); det += o[0]; }
            System.out.printf("RATE x%.0f for the last 5 min (after 40 min at 3/min): flagged ELEVATED in %d of %d runs%n", mult, det, runs);
        }
        int[] le = new int[2]; simulate(9, 700, 0.05, 0.05, 0, false, 300, 330, le);
        System.out.printf("LIGHT LEAK (+30 DN for 30 s): flagged=%b, particle events counted during the leak=%d%n", le[0] == 1, le[1]);
    }
}
