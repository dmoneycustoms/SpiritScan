import com.nscb.spiritscan.dsp.*;
import java.util.*;
public class Fa2 {
    public static void main(String[] x) {
        int fw = 48000;
        for (int seed = 0; seed < 4; seed++) {
            Test1.rnd = new Random(seed + 11);
            BandScanner bs = new BandScanner(fw, 4096, 1024, 96, 120, 20.0, 23000.0);
            short[] w = Test1.noise(fw * 300, 0.004 + 0.002 * seed);
            if (seed >= 2) { double y = 0; for (int i = 0; i < w.length; i++) { y = 0.9 * y + w[i] * 0.3; w[i] = (short) y; } }
            int pos = 0;
            while (pos < w.length) { int n = Math.min(1920, w.length - pos); bs.push(Arrays.copyOfRange(w, pos, pos + n), n); pos += n; }
            System.out.println("5 min noise seed " + seed + " -> confirmed unexplained anomalies = " + bs.confirmedCount());
        }
    }
}
