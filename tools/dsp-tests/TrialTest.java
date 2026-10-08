import com.nscb.spiritscan.dsp.*;
import java.util.*;
public class TrialTest {
    public static void main(String[] a) {
        boolean[] o = TrialStats.randomOrder(8, 42);
        System.out.println("order(8,seed42): " + Arrays.toString(o) + " live=" + count(o));
        System.out.println("order(7,seed1) live=" + count(TrialStats.randomOrder(7, 1)));
        // clear effect: LIVE blocks ~4/min, SHAM ~0.3/min
        double[] r = {4.2, 0.3, 3.8, 0.2, 0.4, 4.5, 0.1, 4.0};
        boolean[] l = {true, false, true, false, false, true, false, true};
        TrialStats.Result x = TrialStats.permutationTest(r, l);
        System.out.printf("clear effect: live=%.2f sham=%.2f diff=%.2f p=%.4f exact=%b (min possible p for 4/4 = 2/70=%.4f)%n", x.liveMean, x.shamMean, x.diff, x.pValue, x.exact, 2.0 / 70);
        // null: both conditions same distribution -> p should be uniform-ish; check false positive rate at p<0.05
        Random rn = new Random(3); int fp = 0; int trials = 2000;
        for (int t = 0; t < trials; t++) {
            double[] rr = new double[8]; for (int i = 0; i < 8; i++) rr[i] = poisson(rn, 1.0 * 3) / 3.0;
            if (TrialStats.permutationTest(rr, TrialStats.randomOrder(8, t)).pValue < 0.05) fp++;
        }
        System.out.printf("null simulation: p<0.05 in %.1f%% of %d trials (should be <= 5%%)%n", 100.0 * fp / trials, trials);
    }
    static int count(boolean[] b) { int c = 0; for (boolean v : b) if (v) c++; return c; }
    static int poisson(Random r, double lam) { double L = Math.exp(-lam), p = 1; int k = 0; do { k++; p *= r.nextDouble(); } while (p > L); return k - 1; }
}
