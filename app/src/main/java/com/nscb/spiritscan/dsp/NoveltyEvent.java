package com.nscb.spiritscan.dsp;

/** One captured period of unusual multi-sensor behaviour. */
public final class NoveltyEvent {
    public long id;
    public long startMs;
    public float durSec;
    public float peakZ;
    /** "MODEL" (autoencoder), "STATS" (Mahalanobis) or "BOTH" */
    public String kind = "";
    /** channels that contributed most at the peak */
    public String channels = "";
    /** filled by the app: what live context (space weather, weather, quakes) could explain it */
    public String context = "";
    public boolean open;
}
