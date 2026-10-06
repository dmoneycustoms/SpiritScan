package com.nscb.spiritscan.dsp;

import java.util.List;

/** Immutable-by-convention copy of everything the Spirit Box HUD card needs for one redraw. */
public final class ScanSnapshot {
    public int bands;
    public int histRows;
    public float fMinHz;
    public float fMaxHz;
    public int[] waterfall;
    public float[] excess;
    public float levelDb;
    public boolean wideReady;
    public boolean voiceReady;
    public String wideSource = "";
    public String voiceSource = "";
    public List<BandScanner.Anomaly> anomalies;
    public VoiceSignature.Result voice;

    /** 0 = no baseline, 1 = collecting, 2 = ready */
    public int nullState;
    public float nullProgress;
    public float baseVoicePerMin;
    public float baseBandPerMin;
    public int voiceEvents60;
    public int bandEvents60;
    public float voiceZ;
    public float bandZ;
    public boolean voiceAbove;
    public boolean bandAbove;
}
