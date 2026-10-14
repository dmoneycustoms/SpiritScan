import com.nscb.spiritscan.senses.*;
import java.util.*;
public class HarnessTest {
    public static void main(String[] a) {
        String[] labels = {"weak", "medium", "strong"};
        int[] w = HarnessStats.schedule(3, 2, 6, 11);
        System.out.println("schedule: " + Arrays.toString(w));
        double[] effect = {0.8, 2.5, 6.0};
        Random rnd = new Random(1);
        int nulls = 3000, fp05 = 0; double aucSum = 0;
        for (int r = 0; r < nulls; r++) {
            int[] sched = HarnessStats.schedule(3, 2, 6, r);
            double[] sc = new double[sched.length]; for (int i = 0; i < sc.length; i++) sc[i] = rnd.nextGaussian();
            HarnessStats.Report rep = HarnessStats.analyze(labels, sched, sc, 3.0);
            if (rep.pValue < 0.05) fp05++; aucSum += rep.auc;
        }
        System.out.printf("NULL (stimulus has no effect), %d sessions: p<0.05 in %.1f%% (expect <=5%%), mean AUC %.2f%n", nulls, 100.0 * fp05 / nulls, aucSum / nulls);
        int sessions = 500, limStrong = 0, limMed = 0, limNone = 0, fixedFalse = 0;
        for (int r = 0; r < sessions; r++) {
            int[] sched = HarnessStats.schedule(3, 2, 6, 100000 + r);
            double[] sc = new double[sched.length]; for (int i = 0; i < sc.length; i++) sc[i] = rnd.nextGaussian() + (sched[i] >= 0 ? effect[sched[i]] : 0);
            HarnessStats.Report rep = HarnessStats.analyze(labels, sched, sc, 3.0);
            if (rep.limitIndex == 2) limStrong++; else if (rep.limitIndex == 1) limMed++; else if (rep.limitIndex == 0) limMed += 0; else limNone++;
            fixedFalse += rep.shamFalseFixed;
        }
        System.out.printf("REAL EFFECT (0.8 / 2.5 / 6.0 sigma), %d sessions: limit = strong only %d, medium or better %d, none %d; sham false alarms at threshold 3.0: %.3f per sham window%n", sessions, limStrong, limMed, limNone, fixedFalse / (sessions * 6.0));
        int[] sched = HarnessStats.schedule(3, 2, 6, 7);
        double[] sc = new double[sched.length]; Random r2 = new Random(5); for (int i = 0; i < sc.length; i++) sc[i] = r2.nextGaussian() + (sched[i] >= 0 ? effect[sched[i]] : 0);
        HarnessStats.Report rep = HarnessStats.analyze(labels, sched, sc, 3.0);
        for (HarnessStats.LevelResult l : rep.levels) System.out.printf("  %s: %d/%d empirical hits, %d/%d at fixed threshold, median %.1f%n", l.label, l.hitsEmpirical, l.n, l.hitsFixed, l.n, l.median);
        System.out.println("  " + rep.verdict);
    }
}
