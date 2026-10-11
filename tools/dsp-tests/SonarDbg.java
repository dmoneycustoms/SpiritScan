import com.nscb.spiritscan.senses.*;
import java.util.*;
public class SonarDbg {
    public static void main(String[] a) {
        double ppm = Double.parseDouble(a[0]);
        double clutterAmp = Double.parseDouble(a[1]);
        SonarEngine eng = new SonarEngine();
        Random rnd = new Random(11);
        int FS = 48000; int total = 40 * FS; short[] buf = new short[4096]; int pos = 0;
        for (int n = 0; n < total; n++) {
            double clk = n * (1.0 + ppm * 1e-6) + 333;
            double v = 0.1 * SonarTest.tx(clk - 12.7);
            if (clutterAmp > 0) { v += clutterAmp * SonarTest.tx(clk - 12.7 - 2.0 * 0.9 / 343.0 * FS); v += 0.75 * clutterAmp * SonarTest.tx(clk - 12.7 - 2.0 * 1.7 / 343.0 * FS); }
            v += rnd.nextGaussian() * 0.0002;
            buf[pos++] = (short) Math.max(-32768, Math.min(32767, Math.round(v * 32768.0)));
            if (pos == buf.length) { eng.push(buf, pos); pos = 0; }
        }
        SonarEngine.Snapshot s = eng.snapshot();
        StringBuilder sb = new StringBuilder("dopDb bins1..8: ");
        for (int b = 1; b <= 8; b++) sb.append(String.format("%.1f(%.2fHz) ", s.dopDb[b], s.dopHz[b]));
        System.out.println("ppm=" + ppm + " clutter=" + clutterAmp + " | " + sb);
        sb = new StringBuilder("staticDb bins0..8: ");
        for (int b = 0; b <= 8; b++) sb.append(String.format("%.0f ", s.staticDb[b]));
        System.out.println(sb);
    }
}
