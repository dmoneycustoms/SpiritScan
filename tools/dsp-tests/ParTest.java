import com.nscb.spiritscan.senses.*;
import java.util.*;
public class ParTest {
    public static void main(String[] a) {
        long t0 = System.nanoTime();
        PareidoliaMeter m = new PareidoliaMeter(120, 160);
        System.out.printf("chance table built in %.0f ms%n", (System.nanoTime() - t0) / 1e6);
        StringBuilder sb = new StringBuilder("chance that n random blobs contain a face-like triplet: ");
        for (int n = 3; n <= 10; n++) sb.append(String.format("n=%d:%.1f%% ", n, 100 * m.chanceFace(n)));
        System.out.println(sb);
        sb = new StringBuilder("chance of a figure-like stack: ");
        for (int n = 3; n <= 10; n++) sb.append(String.format("n=%d:%.1f%% ", n, 100 * m.chanceFigure(n)));
        System.out.println(sb);
        double[] x = {40, 70, 55}, y = {60, 62, 95}, r = {6, 7, 8};
        List<PareidoliaMeter.Hit> hits = m.scan(x, y, r, 3);
        System.out.println("planted face -> hits: " + hits.size() + " kind=" + (hits.isEmpty() ? -1 : hits.get(0).kind));
        double[] x2 = {60, 60}, y2 = {50, 70}, r2 = {5, 12};
        hits = m.scan(x2, y2, r2, 2);
        System.out.println("planted figure -> hits: " + hits.size() + " kind=" + (hits.isEmpty() ? -1 : hits.get(0).kind));
        // live-style test: random blob fields vs predicted chance, z should be ~N(0,1)
        Random rnd = new Random(8);
        int lt = 0; int reps = 300;
        for (int rep = 0; rep < reps; rep++) {
            PareidoliaMeter.Tally t = new PareidoliaMeter.Tally();
            for (int f = 0; f < 400; f++) {
                int n = 3 + rnd.nextInt(6);
                double[] xx = new double[n], yy = new double[n], rr = new double[n];
                for (int i = 0; i < n; i++) { xx[i] = rnd.nextDouble() * 120; yy[i] = rnd.nextDouble() * 160; rr[i] = 4 + rnd.nextDouble() * 8; }
                boolean face = false; for (PareidoliaMeter.Hit h : m.scan(xx, yy, rr, n)) if (h.kind == 0) face = true;
                t.add(n, face, m.chanceFace(n));
            }
            if (Math.abs(t.z()) > 2.0) lt++;
        }
        System.out.printf("random blobs vs predicted chance: |z|>2 in %.1f%% of %d sessions (expect ~5%%)%n", 100.0 * lt / reps, reps);
    }
}
