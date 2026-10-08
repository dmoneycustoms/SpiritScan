import com.nscb.spiritscan.dsp.*;
import java.util.*;
public class NovSweep {
    public static void main(String[] a) throws Exception {
        NovTest.load();
        double[][] cfg = {{4, 2.5, 3}, {5, 3, 3}, {6, 3, 3}, {6, 3, 4}, {8, 4, 3}};
        for (int baseSec : new int[]{150, 300}) {
            System.out.println("--- baseline " + baseSec + " s ---");
            for (double[] c : cfg) {
                NoveltyCore.OPEN_Z = c[0]; NoveltyCore.CLOSE_Z = c[1]; NoveltyCore.OPEN_RUN = (int) c[2];
                int fa = 0; int det = 0; double pk = 0;
                for (int seed = 0; seed < 5; seed++) {
                    NovTest.rnd = new Random(300 + seed); NovTest.init(); NovTest.state = new double[12]; NovTest.shared = new double[3];
                    String[] names = {"mag","zMag","resid","audioZ","speechRes","camScore","camMad","specRes","voice","bandMax","decode","air"};
                    NoveltyCore core = new NoveltyCore(names); core.startLearn(baseSec);
                    for (int i = 0; i < baseSec; i++) core.push(NovTest.sample(i, "none", -1), i * 1000L);
                    List<float[]> w = core.calibrationWindows(); List<float[]> r = new ArrayList<>(); for (float[] x : w) r.add(NovTest.ae(x)); core.finishCalibration(w, r);
                    fa += NovTest.run(core, "none", 3600, 0, false);
                }
                // detection: step of 4.5 sigma for 8 s on one channel
                for (int seed = 0; seed < 5; seed++) {
                    NovTest.rnd = new Random(400 + seed); NovTest.init(); NovTest.state = new double[12]; NovTest.shared = new double[3];
                    String[] names = {"mag","zMag","resid","audioZ","speechRes","camScore","camMad","specRes","voice","bandMax","decode","air"};
                    NoveltyCore core = new NoveltyCore(names); core.startLearn(baseSec);
                    for (int i = 0; i < baseSec; i++) core.push(NovTest.sample(i, "none", -1), i * 1000L);
                    List<float[]> w = core.calibrationWindows(); List<float[]> r = new ArrayList<>(); for (float[] x : w) r.add(NovTest.ae(x)); core.finishCalibration(w, r);
                    int n = NovTest.run(core, "step", 200, 60, false); if (n > 0) det++;
                }
                System.out.printf("open z>=%.0f close z<%.1f run>=%.0f s : false events per hour (5 seeds avg) = %.1f ; 4.5-sigma 8 s step detected %d/5%n", c[0], c[1], c[2], fa / 5.0, det);
            }
        }
    }
}
