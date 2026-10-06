import com.nscb.spiritscan.dsp.*;
import java.util.*;
public class Fa {
    public static void main(String[] x) {
        int fs = 16000;
        for (int seed = 0; seed < 5; seed++) {
            Test1.rnd = new Random(seed * 7 + 1);
            short[] s = Test1.noise(fs * 600, 0.03);
            if (seed % 2 == 1) { double y = 0; for (int i = 0; i < s.length; i++) { y = 0.8 * y + s[i] * 0.4; s[i] = (short) y; } }
            VoiceSignature v = new VoiceSignature();
            int pos = 0;
            while (pos < s.length) { int n = Math.min(512, s.length - pos); v.push(Arrays.copyOfRange(s, pos, pos + n), n); pos += n; }
            System.out.println("seed " + seed + (seed % 2 == 1 ? " (colored)" : " (white)") + " events in 10 min = " + v.result().eventsTotal);
        }
    }
}
