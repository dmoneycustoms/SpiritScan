package com.nscb.spiritscan.air;

/** One AIR-engine result. The packed pixel field is meant for the GPU shader. */
public final class AirFrame {
    public int w;
    public int h;
    /**
     * Packed ARGB, A = 255.
     * R: temporal micro-variation, signed, 128 = none, +-120 = +-12 sigma (Eulerian-style band-pass 0.3..3 Hz)
     * G: air-flow (background-oriented schlieren) significance, 0..255 = 0..12.75 sigma
     * B: periodic-pulse strength, 0..255
     */
    public int[] pixels;
    public int gw;
    public int gh;
    public int block;
    public float[] bosDx;
    public float[] bosDy;
    public float[] bosSig;
    public PulseSource[] pulses = new PulseSource[0];
    /** 0..1: how much coherent, statistically significant air motion is visible. Saturating, never a blob count. */
    public float airIndex;
    public float fps;
    public int validBlocks;
    public int sigBlocks;
    public boolean warm;
    public String note = "";
}
