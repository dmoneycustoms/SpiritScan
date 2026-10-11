import com.nscb.spiritscan.senses.*;
import java.util.*;
public class DoaNoise {
    public static void main(String[] a) {
        Random rnd = new Random(2);
        for (double lvl : new double[]{0.001, 0.01, 0.05}) {
            DoaEngine e = new DoaEngine(0.14);
            int frames = 48000 * 60;
            short[] buf = new short[2 * 1920];
            int done = 0;
            while (done < frames) {
                for (int i = 0; i < 1920; i++) {
                    // independent noise per channel with slow level wobble (room noise), no coherent source
                    double w = 1.0 + 0.3 * Math.sin(2 * Math.PI * 0.2 * (done + i) / 48000.0);
                    buf[2 * i] = (short) Math.round(rnd.nextGaussian() * lvl * w * 32768);
                    buf[2 * i + 1] = (short) Math.round(rnd.nextGaussian() * lvl * w * 32768);
                }
                e.push(buf, 1920); done += 1920;
            }
            DoaEngine.Snapshot s = e.snapshot();
            System.out.printf("independent noise level %.3f for 60 s -> accepted direction updates: %d (of ~1400 blocks)%n", lvl, s.updates);
        }
    }
}
