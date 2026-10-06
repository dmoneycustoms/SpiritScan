import com.nscb.spiritscan.dsp.*;
import java.util.*;

public class Test1 {
    static Random rnd = new Random(42);

    static short[] noise(int n, double amp) {
        short[] s = new short[n];
        for (int i = 0; i < n; i++) s[i] = (short) Math.max(-32768, Math.min(32767, rnd.nextGaussian() * amp * 32768.0));
        return s;
    }

    static void addTone(short[] s, double fs, double f, double amp, int from, int to) {
        for (int i = from; i < to && i < s.length; i++) {
            s[i] += (short) (Math.sin(2 * Math.PI * f * i / fs) * amp * 32768.0);
        }
    }

    // vowel: impulse train -> 2nd-order resonators, syllabic AM
    static short[] vowel(int n, double fs, double f0, double[] F, double[] bw, double amp, double amHz, double noiseAmp) {
        double[] x = new double[n];
        double period = fs / f0;
        double nextPulse = 0;
        for (int i = 0; i < n; i++) {
            double fi = f0 * (1.0 + 0.01 * Math.sin(2 * Math.PI * 5.0 * i / fs)); // vibrato
            period = fs / fi;
            if (i >= nextPulse) { x[i] = 1.0; nextPulse += period; }
        }
        // glottal-ish lowpass (two one-poles)
        for (int pass = 0; pass < 2; pass++) {
            double y = 0;
            for (int i = 0; i < n; i++) { y = 0.9 * y + 0.1 * x[i]; x[i] = y; }
        }
        double[] cur = x;
        for (int r = 0; r < F.length; r++) {
            double rr = Math.exp(-Math.PI * bw[r] / fs);
            double th = 2 * Math.PI * F[r] / fs;
            double a1 = -2 * rr * Math.cos(th), a2 = rr * rr;
            double g = 1 - rr;
            double y1 = 0, y2 = 0;
            double[] o = new double[n];
            for (int i = 0; i < n; i++) {
                double y = g * cur[i] - a1 * y1 - a2 * y2;
                o[i] = y; y2 = y1; y1 = y;
            }
            cur = o;
        }
        double mx = 0;
        for (double v : cur) mx = Math.max(mx, Math.abs(v));
        short[] s = new short[n];
        for (int i = 0; i < n; i++) {
            double am = 0.5 + 0.5 * Math.sin(2 * Math.PI * amHz * i / fs - 1.0);
            am = Math.pow(am, 0.7);
            double v = cur[i] / mx * amp * am + rnd.nextGaussian() * noiseAmp;
            s[i] = (short) Math.max(-32768, Math.min(32767, v * 32768.0));
        }
        return s;
    }

    static void feed16(VoiceSignature v, short[] s) {
        int pos = 0;
        while (pos < s.length) {
            int n = Math.min(512, s.length - pos);
            short[] chunk = Arrays.copyOfRange(s, pos, pos + n);
            v.push(chunk, n);
            pos += n;
        }
    }

    static void voiceCase(String name, short[] s) {
        VoiceSignature v = new VoiceSignature();
        feed16(v, s);
        VoiceSignature.Result r = v.result();
        System.out.printf("%-34s events=%d  lastF0=%.0f F1=%.0f F2=%.0f dur=%.2fs score=%.2f frames=%d%n",
            name, r.eventsTotal, r.lastF0, r.lastF1, r.lastF2, r.lastDur, r.score, v.frameCount());
    }

    public static void main(String[] a) {
        int fs = 16000;
        System.out.println("=== VOICE SIGNATURE ===");
        voiceCase("white noise 30s", noise(fs * 30, 0.05));
        // brown-ish noise
        short[] br = noise(fs * 30, 0.05);
        double y = 0; for (int i = 0; i < br.length; i++) { y = 0.97 * y + br[i] * 0.2; br[i] = (short) y; }
        voiceCase("brown noise 30s", br);
        // sweeping hop tone + noise
        short[] hop = noise(fs * 30, 0.03);
        for (int i = 0; i < hop.length; i++) {
            double f = 180 + (i / 160) % 3920;
            hop[i] += (short) (Math.sin(2 * Math.PI * f * i / fs) * 0.1 * 32768);
        }
        voiceCase("hop tone sweep + noise", hop);
        voiceCase("vowel /a/ 120Hz clean", vowel(fs * 10, fs, 120, new double[]{700, 1200, 2600}, new double[]{80, 90, 120}, 0.3, 4.0, 0.0005));
        voiceCase("vowel /a/ 120Hz + noise -26dB", vowel(fs * 10, fs, 120, new double[]{700, 1200, 2600}, new double[]{80, 90, 120}, 0.3, 4.0, 0.015));
        voiceCase("vowel /i/ 210Hz", vowel(fs * 10, fs, 210, new double[]{300, 2300, 3000}, new double[]{60, 100, 120}, 0.3, 3.5, 0.001));
        voiceCase("vowel /u/ 100Hz", vowel(fs * 10, fs, 100, new double[]{320, 800, 2500}, new double[]{60, 80, 120}, 0.3, 4.5, 0.001));
        voiceCase("vowel buried in noise (-6dB)", vowel(fs * 10, fs, 120, new double[]{700, 1200, 2600}, new double[]{80, 90, 120}, 0.1, 4.0, 0.08));

        System.out.println("=== BAND SCANNER (48k) ===");
        int fw = 48000;
        BandScanner bs = new BandScanner(fw, 4096, 1024, 96, 120, 20.0, 23000.0);
        int dur = fw * 40;
        short[] w = noise(dur, 0.004);
        addTone(w, fw, 15000, 0.004, fw * 10, fw * 14);       // audible-ish high tone
        addTone(w, fw, 21000, 0.004, fw * 16, fw * 19);       // ultrasonic
        addTone(w, fw, 120, 0.01, fw * 22, fw * 26);          // mains 2nd harmonic of 60
        addTone(w, fw, 2300, 0.01, fw * 28, fw * 31);         // unexplained mid tone
        for (int i = fw * 34; i < fw * 34 + 400; i++) w[i] += (short) (rnd.nextGaussian() * 0.3 * 32768); // click
        Map<String, String> seen = new LinkedHashMap<>();
        int pos = 0;
        long t0 = System.nanoTime();
        while (pos < w.length) {
            int n = Math.min(1920, w.length - pos);
            bs.push(Arrays.copyOfRange(w, pos, pos + n), n);
            pos += n;
            for (BandScanner.Anomaly an : bs.anomalies()) {
                String key = an.id + " " + an.kind + " " + an.cause;
                seen.put(key, String.format("t=%.1fs center=%.0fHz lo=%.0f hi=%.0f ex=%.0fdB age=%.2fs", pos / (double) fw, an.centerHz, an.loHz, an.hiHz, an.excessDb, an.ageSec));
            }
        }
        double ms = (System.nanoTime() - t0) / 1e6;
        for (Map.Entry<String, String> e : seen.entrySet()) System.out.println(e.getKey() + "  | " + e.getValue());
        System.out.printf("noise-only region false alarms checked above; processing %.0f ms for 40 s audio (%.1fx realtime)%n", ms, 40000.0 / ms);
        int[] wf = new int[96 * 120];
        bs.fillWaterfall(wf);
        System.out.println("waterfall ok, confirmedUnexplained=" + bs.confirmedCount());
    }
}
