package com.nscb.spiritscan.senses;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Particle detector for a COVERED phone camera (the DECO / CRAYFIS / CREDO idea).
 * An ionising particle crossing the CMOS sensor liberates charge in a few pixels. With the lens taped over, long exposures
 * and noise reduction off, those hits stand out of the dark noise as small bright clusters. This engine
 *  - learns the dark level and noise of every frame (median / MAD of a sub-sampled grid),
 *  - learns hot pixels from repetition (a real particle does not hit the same pixel three times),
 *  - finds seeds above 6 sigma and grows them through neighbours above 3 sigma,
 *  - classifies clusters by shape: SPOT (1-3 px), WORM (curly, electron/gamma-like), TRACK (straight, muon-like), BLOB (large, heavy),
 *  - keeps a Poisson rate, compares the last 5 min with the long-term rate, and flags bursts and light leaks.
 * Single bright pixels are counted separately and never raise the rate (too easily noise or hot pixels).
 * Rates are low (minutes to hours for statistics) unless a radioactive source is nearby.
 */
public final class ParticleEngine {
    public static final int CAL_FRAMES = 10;
    public static final int THUMB = 16;
    private static final int LOG_CAP = 3000;
    private static final int[] LUT = Palette.inferno256();

    public static final class Event {
        public long tMs;
        public long frame;
        public String kind = "";
        public int n;
        public float charge;
        public float length;
        public float elong;
        public float peak;
        public float x;
        public float y;
        public int[] thumb;
    }

    public static final class Snapshot {
        public boolean calibrated;
        public int frames;
        public int width;
        public int height;
        public double medianDN;
        public double sigmaDN;
        public int hotMasked;
        public boolean lightLeak;
        public boolean noisyFrame;
        public String status = "";
        public long singles;
        public long spots;
        public long worms;
        public long tracks;
        public long blobs;
        public long particleLike;
        public double rate5PerMin;
        public double baselinePerMin;
        public double pElevated;
        public boolean elevated;
        public boolean burst;
        public double elapsedMin;
        public double exposureSec;
        public List<Event> recent = new ArrayList<Event>();
    }

    private int w;
    private int h;
    private byte[] hits;
    private int frames;
    private int calSeen;
    private double sumMed;
    private double sumSig;
    private double refMedian = -1;
    private double refSigma = 1;
    private double medianDN;
    private double sigmaDN = 1;
    private boolean lightLeak;
    private boolean noisyFrame;
    private String status = "waiting for frames";
    private long singles;
    private long spots;
    private long worms;
    private long tracks;
    private long blobs;
    private long startMs = -1;
    private long calEndMs = -1;
    private double exposureSec;
    private final List<Long> stamps = new ArrayList<Long>();
    private final List<Event> recent = new ArrayList<Event>();
    private final List<Event> log = new ArrayList<Event>();
    private final int[] hist = new int[65536];
    private final int[] hist2 = new int[65536];
    private int hotCount;

    public synchronized void reset() {
        frames = 0;
        calSeen = 0;
        sumMed = 0;
        sumSig = 0;
        refMedian = -1;
        singles = 0;
        spots = 0;
        worms = 0;
        tracks = 0;
        blobs = 0;
        startMs = -1;
        calEndMs = -1;
        stamps.clear();
        recent.clear();
        log.clear();
        if (hits != null) Arrays.fill(hits, (byte) 0);
        hotCount = 0;
        lightLeak = false;
        status = "waiting for frames";
    }

    /** All logged events (position, shape, frame) for analysis and tests. */
    public synchronized List<Event> allEvents() {
        return new ArrayList<Event>(log);
    }

    /**
     * @param pix      16-bit samples (RAW) or 8-bit luma widened to short
     * @param stridePx row stride in samples
     */
    public synchronized void process(short[] pix, int width, int height, int stridePx, double expSec, long tMs) {
        if (width != w || height != h || hits == null) {
            w = width;
            h = height;
            hits = new byte[w * h];
            reset();
        }
        if (startMs < 0) startMs = tMs;
        frames++;
        exposureSec = expSec;

        // ---- dark level and noise from a sub-sampled grid ----------------------------------------
        Arrays.fill(hist, 0);
        int cnt = 0;
        for (int y = 0; y < h; y += 7) {
            int row = y * stridePx;
            for (int x = 0; x < w; x += 7) {
                hist[pix[row + x] & 0xFFFF]++;
                cnt++;
            }
        }
        int acc = 0;
        int med = 0;
        for (int v = 0; v < 65536; v++) {
            acc += hist[v];
            if (acc * 2 >= cnt) {
                med = v;
                break;
            }
        }
        Arrays.fill(hist2, 0);
        for (int v = 0; v < 65536; v++) {
            if (hist[v] > 0) hist2[Math.abs(v - med)] += hist[v];
        }
        acc = 0;
        int mad = 0;
        for (int v = 0; v < 65536; v++) {
            acc += hist2[v];
            if (acc * 2 >= cnt) {
                mad = v;
                break;
            }
        }
        double sigma = Math.max(0.6, 1.4826 * mad);
        medianDN = med;
        sigmaDN = sigma;

        boolean calibrating = calSeen < CAL_FRAMES;
        if (calibrating) {
            calSeen++;
            sumMed += med;
            sumSig += sigma;
            if (calSeen == CAL_FRAMES) {
                refMedian = sumMed / CAL_FRAMES;
                refSigma = sumSig / CAL_FRAMES;
                calEndMs = tMs;
            }
        } else {
            lightLeak = med > refMedian + Math.max(6.0 * refSigma, 3.0);
        }

        double hi = med + Math.max(6.0 * sigma, 5.0);
        double lo = med + Math.max(3.0 * sigma, 2.5);
        int hiI = (int) Math.ceil(hi);
        int loI = (int) Math.ceil(lo);

        // ---- seeds -----------------------------------------------------------------------------
        int[] seeds = new int[4096];
        int ns = 0;
        boolean overflow = false;
        for (int y = 0; y < h; y++) {
            int row = y * stridePx;
            int hrow = y * w;
            for (int x = 0; x < w; x++) {
                if ((pix[row + x] & 0xFFFF) > hiI) {
                    if (ns == seeds.length) {
                        if (ns >= 30000) {
                            overflow = true;
                            break;
                        }
                        seeds = Arrays.copyOf(seeds, ns * 2);
                    }
                    seeds[ns++] = hrow + x;
                }
            }
            if (overflow) break;
        }
        noisyFrame = overflow;
        if (overflow) {
            status = "too many bright pixels: light leak or sensor noise burst, frame skipped";
            return;
        }
        if (lightLeak) {
            status = "LIGHT LEAK: the dark level rose; cover the lens completely";
            return;
        }

        // ---- clusters --------------------------------------------------------------------------
        Set<Integer> seen = new HashSet<Integer>();
        int[] qx = new int[800];
        int[] qy = new int[800];
        List<Long> newStamps = new ArrayList<Long>();
        for (int si = 0; si < ns; si++) {
            int idx = seeds[si];
            int sy = idx / w;
            int sx = idx - sy * w;
            if (seen.contains(idx)) continue;
            int hv = hits[idx] & 0xFF;
            if (hv >= 3) {
                seen.add(idx);
                continue;
            }
            // grow
            int qn = 0;
            int head = 0;
            qx[qn] = sx;
            qy[qn] = sy;
            qn++;
            seen.add(idx);
            while (head < qn && qn < qx.length) {
                int cx = qx[head];
                int cy = qy[head];
                head++;
                // 5x5 reach: a one-pixel gap inside a track or worm must not split it into two events
                for (int dy = -2; dy <= 2; dy++) {
                    for (int dx = -2; dx <= 2; dx++) {
                        if (dx == 0 && dy == 0) continue;
                        int nx = cx + dx;
                        int ny = cy + dy;
                        if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue;
                        int nidx = ny * w + nx;
                        if (seen.contains(nidx)) continue;
                        if ((hits[nidx] & 0xFF) >= 3) continue;
                        if ((pix[ny * stridePx + nx] & 0xFFFF) > loI) {
                            seen.add(nidx);
                            if (qn < qx.length) {
                                qx[qn] = nx;
                                qy[qn] = ny;
                                qn++;
                            }
                        }
                    }
                }
            }
            // hot-pixel bookkeeping: every pixel above the seed threshold counts one hit
            for (int i = 0; i < qn; i++) {
                int pidx = qy[i] * w + qx[i];
                if ((pix[qy[i] * stridePx + qx[i]] & 0xFFFF) > hiI) {
                    int v = hits[pidx] & 0xFF;
                    if (v < 250) hits[pidx] = (byte) (v + 1);
                    if (v + 1 == 3) hotCount++;
                }
            }
            if (calibrating) continue;
            Event e = describe(pix, stridePx, qx, qy, qn, med, tMs);
            if (e == null) continue;
            newStamps.add(tMs);
            if (e.kind.equals("SPOT")) spots++;
            else if (e.kind.equals("WORM")) worms++;
            else if (e.kind.equals("TRACK")) tracks++;
            else if (e.kind.equals("BLOB")) blobs++;
            else singles++;
            if (!e.kind.equals("SINGLE")) {
                stamps.add(tMs);
                recent.add(0, e);
                while (recent.size() > 12) recent.remove(recent.size() - 1);
            }
            log.add(e);
            while (log.size() > LOG_CAP) log.remove(0);
        }
        status = calibrating ? "learning the dark level and hot pixels " + calSeen + "/" + CAL_FRAMES
            : "listening: " + w + "x" + h + ", exposure " + String.format("%.2f", expSec) + " s";
    }

    private Event describe(short[] pix, int stride, int[] qx, int[] qy, int qn, int med, long tMs) {
        double sx = 0, sy = 0, charge = 0, peak = 0;
        for (int i = 0; i < qn; i++) {
            double v = (pix[qy[i] * stride + qx[i]] & 0xFFFF) - med;
            if (v < 0) v = 0;
            charge += v;
            if (v > peak) peak = v;
            sx += qx[i];
            sy += qy[i];
        }
        double mx = sx / qn;
        double my = sy / qn;
        double cxx = 1.0 / 12.0, cyy = 1.0 / 12.0, cxy = 0;
        for (int i = 0; i < qn; i++) {
            double dx = qx[i] - mx;
            double dy = qy[i] - my;
            cxx += dx * dx / qn;
            cyy += dy * dy / qn;
            cxy += dx * dy / qn;
        }
        double tr = cxx + cyy;
        double det = cxx * cyy - cxy * cxy;
        double disc = Math.sqrt(Math.max(0.0, tr * tr / 4.0 - det));
        double l1 = tr / 2.0 + disc;
        double l2 = Math.max(tr / 2.0 - disc, 1e-6);
        double elong = Math.sqrt(l1 / l2);
        double length = Math.sqrt(12.0 * Math.max(l1 - 1.0 / 12.0, 0.0));
        Event e = new Event();
        e.tMs = tMs;
        e.frame = frames;
        e.n = qn;
        e.charge = (float) charge;
        e.length = (float) length;
        e.elong = (float) elong;
        e.peak = (float) peak;
        e.x = (float) mx;
        e.y = (float) my;
        if (qn == 1) e.kind = "SINGLE";
        else if (qn <= 3) e.kind = "SPOT";
        else if (qn >= 6 && elong >= 3.5) e.kind = "TRACK";
        else if (qn >= 12 && elong < 2.0) e.kind = "BLOB";
        else e.kind = "WORM";
        // thumbnail 16x16 around the centroid
        int[] th = new int[THUMB * THUMB];
        int cx0 = (int) Math.round(mx) - THUMB / 2;
        int cy0 = (int) Math.round(my) - THUMB / 2;
        for (int j = 0; j < THUMB; j++) {
            for (int i = 0; i < THUMB; i++) {
                int xx = cx0 + i;
                int yy = cy0 + j;
                int v = 0;
                if (xx >= 0 && yy >= 0 && xx < w && yy < h) {
                    double d = (pix[yy * stride + xx] & 0xFFFF) - med;
                    v = (int) Math.max(0, Math.min(255, 255.0 * d / (peak + 1.0)));
                }
                th[j * THUMB + i] = LUT[v];
            }
        }
        e.thumb = th;
        return e;
    }

    private static double poissonUpperTail(int k, double mu) {
        if (k <= 0) return 1.0;
        double term = Math.exp(-mu);
        double cdf = term;
        for (int i = 1; i < k; i++) {
            term *= mu / i;
            cdf += term;
        }
        return Math.max(0.0, 1.0 - cdf);
    }

    public synchronized Snapshot snapshot(long nowMs) {
        Snapshot s = new Snapshot();
        s.calibrated = calSeen >= CAL_FRAMES;
        s.frames = frames;
        s.width = w;
        s.height = h;
        s.medianDN = medianDN;
        s.sigmaDN = sigmaDN;
        s.hotMasked = hotCount;
        s.lightLeak = lightLeak;
        s.noisyFrame = noisyFrame;
        s.status = status;
        s.singles = singles;
        s.spots = spots;
        s.worms = worms;
        s.tracks = tracks;
        s.blobs = blobs;
        s.particleLike = spots + worms + tracks + blobs;
        s.exposureSec = exposureSec;
        s.recent = new ArrayList<Event>(recent);
        double elapsed = calEndMs > 0 ? (nowMs - calEndMs) / 60000.0 : 0.0;
        s.elapsedMin = Math.max(0.0, elapsed);
        int k5 = 0;
        int kOld = 0;
        for (long t : stamps) {
            if (nowMs - t <= 5 * 60000L) k5++;
            else kOld++;
        }
        s.rate5PerMin = k5 / 5.0;
        double oldMin = Math.max(s.elapsedMin - 5.0, 0.0);
        if (oldMin >= 10.0) {
            s.baselinePerMin = (kOld + 0.5) / oldMin;
            double mu = s.baselinePerMin * 5.0;
            s.pElevated = poissonUpperTail(k5, mu);
            s.elevated = oldMin >= 15.0 && k5 >= mu + 4.0 && s.pElevated < 0.001;
        } else {
            s.baselinePerMin = Double.NaN;
            s.pElevated = 1.0;
        }
        int recentBurst = 0;
        for (long t : stamps) if (nowMs - t <= 3000L) recentBurst++;
        s.burst = recentBurst >= 3;
        return s;
    }
}
