package com.nscb.spiritscan.dsp;

import java.util.List;

/** What the PASSIVE card needs for one redraw. */
public final class NoveltySnapshot {
    /** 0 idle, 1 learning baseline, 2 calibrating model, 3 ready */
    public int phase;
    public float learnProgress;
    public boolean aeAvailable;
    public float aeZ;
    public float mahaZ;
    public float[] aeHist;
    public float[] mahaHist;
    public String[] names;
    public float[] latest;
    public boolean[] active;
    public String topChannels = "";
    public List<NoveltyEvent> events;
    public int seconds;
}
