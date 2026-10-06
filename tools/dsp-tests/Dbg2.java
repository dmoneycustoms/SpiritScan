import com.nscb.spiritscan.dsp.*;
import java.util.*;
public class Dbg2 {
    static void stats(String name, short[] s) {
        VoiceSignature v = new VoiceSignature();
        List<Double> cpps = new ArrayList<>(); List<Double> f1s = new ArrayList<>();
        int pos = 0; int voiced = 0, fr = 0;
        while (pos < s.length) {
            int n = Math.min(160, s.length - pos);
            v.push(Arrays.copyOfRange(s, pos, pos + n), n); pos += n;
            VoiceSignature.Result r = v.result();
            if (pos > 16000 && r.levelDb > -85) { cpps.add((double) r.cppDb); fr++; if (r.voicedNow) voiced++; }
        }
        Collections.sort(cpps);
        VoiceSignature.Result r = v.result();
        System.out.printf("%-28s cpp p50=%.2f p90=%.2f p99=%.2f max=%.2f  voicedFrac=%.3f events=%d F0=%.0f F1=%.0f F2=%.0f%n", name,
            cpps.get(cpps.size()/2), cpps.get((int)(cpps.size()*0.9)), cpps.get((int)(cpps.size()*0.99)), cpps.get(cpps.size()-1),
            voiced/(double)fr, r.eventsTotal, r.lastF0, r.lastF1, r.lastF2);
    }
    public static void main(String[] a) {
        int fs = 16000;
        stats("white noise", Test1.noise(fs*30, 0.05));
        short[] br = Test1.noise(fs*30, 0.05); double y=0; for (int i=0;i<br.length;i++){ y=0.97*y+br[i]*0.2; br[i]=(short)y; }
        stats("brown noise", br);
        short[] pk = Test1.noise(fs*30, 0.05); y=0; for (int i=0;i<pk.length;i++){ y=0.7*y+pk[i]*0.5; pk[i]=(short)y; }
        stats("pinkish noise", pk);
        short[] hop = Test1.noise(fs*30, 0.03);
        for (int i=0;i<hop.length;i++){ double f=180+(i/160)%3920; hop[i]+=(short)(Math.sin(2*Math.PI*f*i/fs)*0.1*32768);} 
        stats("hop sweep+noise", hop);
        stats("vowel a 120 clean", Test1.vowel(fs*10, fs, 120, new double[]{700,1200,2600}, new double[]{80,90,120}, 0.3, 4.0, 0.0005));
        stats("vowel a 120 +noise", Test1.vowel(fs*10, fs, 120, new double[]{700,1200,2600}, new double[]{80,90,120}, 0.3, 4.0, 0.015));
        stats("vowel i 210", Test1.vowel(fs*10, fs, 210, new double[]{300,2300,3000}, new double[]{60,100,120}, 0.3, 3.5, 0.001));
        stats("vowel u 100", Test1.vowel(fs*10, fs, 100, new double[]{320,800,2500}, new double[]{60,80,120}, 0.3, 4.5, 0.001));
        stats("vowel a buried -6dB", Test1.vowel(fs*10, fs, 120, new double[]{700,1200,2600}, new double[]{80,90,120}, 0.1, 4.0, 0.08));
    }
}
