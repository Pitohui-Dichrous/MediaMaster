package io.mediamaster.app.player;

import static org.junit.Assert.*;

import org.junit.Test;

public class SeekEnginePolicyTest {
  @Test
  public void oneSlowSeekDoesNotChangeEngine() {
    SeekEnginePolicy p = new SeekEnginePolicy();
    p.recordExoSeek(2500);
    assertFalse(p.chooseVlc(true));
  }

  @Test
  public void repeatedSlowFramesAllowOnlyOneForegroundTrial() {
    SeekEnginePolicy p = new SeekEnginePolicy();
    for (int i = 0; i < 3; i++) p.recordExoSeek(700);
    assertFalse(p.chooseVlc(false));
    assertTrue(p.chooseVlc(true));
    assertFalse(p.chooseVlc(true));
  }

  @Test
  public void fastSamplesDampenIsolatedSlowSamples() {
    SeekEnginePolicy p = new SeekEnginePolicy();
    for (int i = 0; i < 12; i++) p.recordExoSeek(100);
    for (int i = 0; i < 3; i++) p.recordExoSeek(500);
    assertFalse(p.chooseVlc(true));
  }

  @Test
  public void unknownOrCanceledMeasurementsAreNotSlowEvidence() {
    SeekEnginePolicy p = new SeekEnginePolicy();
    p.recordExoSeek(-1);
    p.recordExoSeek(10000);
    assertEquals(0, p.samples);
    assertFalse(p.chooseVlc(true));
  }

  @Test
  public void errorFallbackIsRememberedOnlyAfterVlcRenders() {
    SeekEnginePolicy p = new SeekEnginePolicy();
    p.exoFailed = true;
    assertFalse(p.chooseVlc(true));
    p.vlcReady = true;
    assertTrue(p.chooseVlc(false));
    p.vlcFailed = true;
    assertFalse(p.chooseVlc(true));
  }
}
