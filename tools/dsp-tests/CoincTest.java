import com.nscb.spiritscan.senses.*;
import java.util.*;

public class CoincTest {
    static boolean[][] gen(Random r, int n, int secs, double rate, int dur, boolean sticky) {
        boolean[][] a = new boolean[n][secs];
        for (int c = 0; c < n; c++) {
            int left = 0;
            double rt = rate;
            for (int t = 0; t < secs; t++) {
                if (left == 0 && r.nextDouble() < rt) left = sticky && c == n - 1 ? 900 : dur;
                if (left > 0) { a[c][t] = true; left--; }
            }
        }
        return a;
    }
    static CoincidenceEngine feed(boolean[][] a, int secs) {
        CoincidenceEngine e = new CoincidenceEngine(new String[]{"MAG", "AUDIO", "SONAR", "CAM", "NOV", "MAP"});
        boolean[] act = new boolean[a.length];
        for (int t = 0; t < secs; t++) { for (int c = 0; c < a.length; c++) act[c] = a[c][t]; e.tick(act, t * 1000L); }
        e.evaluateNow();
        return e;
    }
    public static void main(String[] x) {
        CoincidenceEngine.EVAL_EVERY = 1_000_000;
        int sims = 200, secs = 3600;
        int lt05 = 0, lt01 = 0, p3lt05 = 0, p3lt01 = 0; double meanP = 0;
        Random r = new Random(1);
        for (int s = 0; s < sims; s++) {
            CoincidenceEngine.Snapshot sn = feed(gen(r, 6, secs, 1.0 / 90, 4, false), secs).snapshot();
            if (sn.p2 < 0.05) lt05++; if (sn.p2 < 0.01) lt01++; meanP += sn.p2; if (sn.p3 < 0.05) p3lt05++; if (sn.p3 < 0.01) p3lt01++;
        }
        System.out.printf("NULL (6 independent bursty channels, 1 h, %d sims): p2<0.05 in %.1f%%, p2<0.01 in %.1f%%; p3<0.05 in %.1f%%, p3<0.01 in %.1f%%%n", sims, 100.0 * lt05 / sims, 100.0 * lt01 / sims, 100.0 * p3lt05 / sims, 100.0 * p3lt01 / sims);
        lt05 = 0; lt01 = 0;
        for (int s = 0; s < sims; s++) {
            CoincidenceEngine.Snapshot sn = feed(gen(r, 6, secs, 1.0 / 90, 4, true), secs).snapshot();
            if (sn.p2 < 0.05) lt05++; if (sn.p2 < 0.01) lt01++;
        }
        System.out.printf("NULL with one sticky (15 min bursts) channel: p2<0.05 in %.1f%%, p2<0.01 in %.1f%%%n", 100.0 * lt05 / sims, 100.0 * lt01 / sims);
        for (int planted : new int[]{4, 8, 14}) {
            int det = 0, det05 = 0; double meanP2 = 0, p3m = 0;
            for (int s = 0; s < 60; s++) {
                boolean[][] a = gen(r, 6, secs, 1.0 / 90, 4, false);
                for (int i = 0; i < planted; i++) { int t0 = 100 + r.nextInt(secs - 200); for (int c = 0; c < 3; c++) for (int d = 0; d < 3; d++) a[c][t0 + d + r.nextInt(2)] = true; }
                CoincidenceEngine.Snapshot sn = feed(a, secs).snapshot();
                if (sn.p3 < 0.01) det++; if (sn.p3 < 0.05) det05++; meanP2 += sn.p2; p3m += sn.p3;
            }
            System.out.printf("PLANTED %2d genuine 3-channel coincidences in 1 h: p3<0.01 in %d/60, p3<0.05 in %d/60 (mean p3=%.3f)%n", planted, det, det05, p3m / 60);
        }
        long t0 = System.nanoTime(); feed(gen(r, 6, 3600, 1.0 / 90, 4, false), 3600); System.out.printf("one evaluation of 1 h history x %d surrogates: %.0f ms%n", CoincidenceEngine.SURROGATES, (System.nanoTime() - t0) / 1e6);
    }
}
