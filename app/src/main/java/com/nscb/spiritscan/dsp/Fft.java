package com.nscb.spiritscan.dsp;

/** In-place iterative radix-2 complex FFT (double precision). Size must be a power of two. */
public final class Fft {
    private final int n;
    private final double[] cosT;
    private final double[] sinT;
    private final int[] rev;

    public Fft(int n) {
        if (n < 2 || (n & (n - 1)) != 0) throw new IllegalArgumentException("n must be a power of two");
        this.n = n;
        cosT = new double[n / 2];
        sinT = new double[n / 2];
        for (int i = 0; i < n / 2; i++) {
            double a = -2.0 * Math.PI * i / n;
            cosT[i] = Math.cos(a);
            sinT[i] = Math.sin(a);
        }
        rev = new int[n];
        int bits = Integer.numberOfTrailingZeros(n);
        for (int i = 0; i < n; i++) {
            rev[i] = Integer.reverse(i) >>> (32 - bits);
        }
    }

    public int size() {
        return n;
    }

    public void forward(double[] re, double[] im) {
        for (int i = 0; i < n; i++) {
            int j = rev[i];
            if (j > i) {
                double t = re[i]; re[i] = re[j]; re[j] = t;
                t = im[i]; im[i] = im[j]; im[j] = t;
            }
        }
        for (int len = 2; len <= n; len <<= 1) {
            int half = len >> 1;
            int step = n / len;
            for (int i = 0; i < n; i += len) {
                int k = 0;
                for (int j = i; j < i + half; j++) {
                    double wr = cosT[k];
                    double wi = sinT[k];
                    double xr = re[j + half] * wr - im[j + half] * wi;
                    double xi = re[j + half] * wi + im[j + half] * wr;
                    re[j + half] = re[j] - xr;
                    im[j + half] = im[j] - xi;
                    re[j] += xr;
                    im[j] += xi;
                    k += step;
                }
            }
        }
    }
}
