package com.nscb.spiritscan.senses;

/** Shared colour ramps for the sensing engines. */
public final class Palette {
    private Palette() {
    }

    private static final double[][] C = {
        {0.00021894, 0.00165100, -0.01948090},
        {0.10651341, 0.56395644, 3.93271230},
        {11.602494, -3.9728539, -15.942394},
        {-41.703995, 17.436398, 44.354145},
        {77.162935, -33.402359, -81.80731},
        {-71.319428, 32.626064, 73.20952},
        {25.13113, -12.242669, -23.070327}
    };

    /** 256-entry inferno-like ramp (ARGB). */
    public static int[] inferno256() {
        int[] lut = new int[256];
        for (int i = 0; i < 256; i++) {
            double t = i / 255.0;
            int[] rgb = new int[3];
            for (int ch = 0; ch < 3; ch++) {
                double v = 0;
                for (int d = 6; d >= 0; d--) v = v * t + C[d][ch];
                rgb[ch] = (int) Math.round(Math.max(0.0, Math.min(1.0, v)) * 255.0);
            }
            lut[i] = 0xFF000000 | (rgb[0] << 16) | (rgb[1] << 8) | rgb[2];
        }
        return lut;
    }

    /** Diverging blue-white-red ramp for signed values; index 0..255 with 128 = zero (ARGB). */
    public static int[] diverging256() {
        int[] lut = new int[256];
        for (int i = 0; i < 256; i++) {
            double t = (i - 128) / 128.0;
            double a = Math.min(1.0, Math.abs(t));
            int r;
            int g;
            int b;
            if (t >= 0) {
                r = 255;
                g = (int) Math.round(255 * (1 - 0.75 * a));
                b = (int) Math.round(255 * (1 - a));
            } else {
                r = (int) Math.round(255 * (1 - a));
                g = (int) Math.round(255 * (1 - 0.45 * a));
                b = 255;
            }
            double dark = 0.15 + 0.85 * a;
            lut[i] = 0xFF000000 | ((int) (r * dark) << 16) | ((int) (g * dark) << 8) | (int) (b * dark);
        }
        return lut;
    }
}
