package com.nscb.spiritscan.dsp;

import java.util.ArrayDeque;

/**
 * Spirit Box scan engine: a full-band acoustic scanner (48 kHz) + a voice-signature detector (16 kHz) + a NULL TEST.
 *
 * The NULL TEST is what keeps this honest. Any detector of "voice-like structure in noise" has a false-alarm rate.
 * Run the box in a quiet room for 30 s with nothing happening: that measures the baseline events/minute of THIS phone,
 * THIS room, THIS sweep. Afterwards the HUD only calls the event rate "above baseline" when it exceeds that rate by a
 * Poisson z-score of 3 or more.
 */
public final class ScanEngine {
    public static final int NULL_IDLE = 0;
    public static final int NULL_COLLECT = 1;
    public static final int NULL_READY = 2;
    private static final double NULL_SEC = 30.0;

    public final BandScanner wide = new BandScanner(48000, 4096, 1024, 96, 120, 20.0, 23000.0);
    public final VoiceSignature voice = new VoiceSignature();

    private volatile String wideSource = "";
    private volatile String voiceSource = "";

    private int nullState = NULL_IDLE;
    private long nullStartNs;
    private long nullVoice0;
    private long nullBand0;
    private double baseVoice = -1;
    private double baseBand = -1;

    private long lastBandConfirmed;
    private final ArrayDeque<Long> bandStamps = new ArrayDeque<Long>();

    public void setSources(String wideSrc, String voiceSrc) {
        wideSource = wideSrc == null ? "" : wideSrc;
        voiceSource = voiceSrc == null ? "" : voiceSrc;
    }

    public void pushWide(short[] pcm, int n) {
        wide.push(pcm, n);
    }

    public void pushVoice(short[] pcm, int n) {
        voice.push(pcm, n);
    }

    public void setOwnHz(double hz) {
        wide.setOwnHz(hz);
    }

    public void fastAdapt() {
        wide.fastAdapt();
    }

    public synchronized void startNull() {
        nullState = NULL_COLLECT;
        nullStartNs = System.nanoTime();
        nullVoice0 = voice.result().eventsTotal;
        nullBand0 = wide.confirmedCount();
    }

    public synchronized void clearNull() {
        nullState = NULL_IDLE;
        baseVoice = -1;
        baseBand = -1;
    }

    public synchronized ScanSnapshot snapshot() {
        long now = System.nanoTime();
        long bc = wide.confirmedCount();
        for (long i = lastBandConfirmed; i < bc; i++) bandStamps.addLast(now);
        lastBandConfirmed = bc;
        while (!bandStamps.isEmpty() && now - bandStamps.peekFirst() > 60_000_000_000L) bandStamps.pollFirst();

        ScanSnapshot s = new ScanSnapshot();
        s.bands = wide.bands;
        s.histRows = wide.histRows;
        s.fMinHz = wide.edgesHz[0];
        s.fMaxHz = wide.edgesHz[wide.bands];
        s.waterfall = new int[wide.bands * wide.histRows];
        wide.fillWaterfall(s.waterfall);
        s.excess = wide.excess();
        s.levelDb = wide.levelDb();
        s.wideReady = wide.ready();
        s.anomalies = wide.anomalies();
        s.voice = voice.result();
        s.voiceReady = s.voice.active;
        s.wideSource = wideSource;
        s.voiceSource = voiceSource;

        s.voiceEvents60 = s.voice.events60;
        s.bandEvents60 = bandStamps.size();

        if (nullState == NULL_COLLECT) {
            double el = (now - nullStartNs) / 1e9;
            s.nullProgress = (float) Math.min(1.0, el / NULL_SEC);
            if (el >= NULL_SEC) {
                double minutes = el / 60.0;
                baseVoice = (s.voice.eventsTotal - nullVoice0 + 0.5) / minutes;
                baseBand = (bc - nullBand0 + 0.5) / minutes;
                nullState = NULL_READY;
            }
        }
        s.nullState = nullState;
        if (nullState == NULL_READY) {
            s.nullProgress = 1f;
            s.baseVoicePerMin = (float) baseVoice;
            s.baseBandPerMin = (float) baseBand;
            s.voiceZ = (float) ((s.voiceEvents60 - baseVoice) / Math.sqrt(baseVoice + 1.0));
            s.bandZ = (float) ((s.bandEvents60 - baseBand) / Math.sqrt(baseBand + 1.0));
            s.voiceAbove = s.voiceZ >= 3f;
            s.bandAbove = s.bandZ >= 3f;
        }
        return s;
    }
}
