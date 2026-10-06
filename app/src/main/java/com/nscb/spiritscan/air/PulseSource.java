package com.nscb.spiritscan.air;

/** A localized periodic brightness pulse found by the AIR engine. Positions are normalised 0..1 in upright map space. */
public final class PulseSource {
    public int id;
    public float freqHz;
    public float cx;
    public float cy;
    public float halfW;
    public float halfH;
    public float pmrDb;
    /** peak amplitude in thousandths of full-scale luma */
    public float ampMilli;
    public int cells;
    public String cause = "";
    public boolean explained;
    public float ageSec;
    public boolean confirmed;
}
