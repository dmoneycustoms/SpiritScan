# DSP test harness (not part of the Android build)
Synthetic-signal tests for the Java engines in `dsp/` and `air/`. Needs a JDK:

    javac -d out app/src/main/java/com/nscb/spiritscan/dsp/*.java app/src/main/java/com/nscb/spiritscan/air/*.java
    javac -cp out -d out tools/dsp-tests/*.java
    java -cp out Dbg2      # voice-signature statistics on noise vs synthetic vowels
    java -cp out Fa        # false alarms: 50 min of noise
    java -cp out Fa2       # band scanner false alarms: 20 min of noise
    java -cp out Test1     # band scanner: tones, ultrasonic, mains, click
    java -cp out AirTest   # AIR engine: noise, drift, pulses, air shimmer, flicker, tremor

v9.0 tests (also need senses/*.java on the class path): SonarTest, SonarDbg, DoaTest, DoaNoise, MapTest, CoincTest, ParTest.
v9.1 tests: ParticleTest (minutes of simulated sensor frames, slow), ParticleRate (rate statistics), HarnessTest.
