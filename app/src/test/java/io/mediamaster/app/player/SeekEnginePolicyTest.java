package io.mediamaster.app.player;

import static org.junit.Assert.*;

import org.junit.Test;

public class SeekEnginePolicyTest {
  private static final long NOW = 1_000_000;

  @Test
  public void newFilePrefersNativeOnEveryOpen() {
    SeekEnginePolicy p = new SeekEnginePolicy();
    assertTrue(p.chooseVlc(NOW));
    assertTrue(p.chooseVlc(NOW));
    assertTrue(p.chooseVlc(NOW + 1));
  }

  @Test
  public void confirmedFallbackUsesMedia3UntilCooldownExpires() {
    SeekEnginePolicy p = new SeekEnginePolicy();
    p.recordCompatibleFallback(NOW);
    assertFalse(p.chooseVlc(NOW));
    assertFalse(p.chooseVlc(NOW + SeekEnginePolicy.FAILURE_COOLDOWN_MS - 1));
    assertTrue(p.chooseVlc(NOW + SeekEnginePolicy.FAILURE_COOLDOWN_MS));
    assertTrue(p.chooseVlc(NOW + SeekEnginePolicy.FAILURE_COOLDOWN_MS + 1));
  }

  @Test
  public void restoredFailureDeadlineKeepsTheSameChoice() {
    SeekEnginePolicy p = new SeekEnginePolicy();
    p.recordCompatibleFallback(NOW);
    SeekEnginePolicy restored = new SeekEnginePolicy();
    restored.vlcFailureUntilMs = p.vlcFailureUntilMs;
    assertFalse(restored.chooseVlc(NOW + 10));
    assertTrue(restored.chooseVlc(restored.vlcFailureUntilMs));
  }

  @Test
  public void nativeSuccessClearsAConfirmedFailure() {
    SeekEnginePolicy p = new SeekEnginePolicy();
    p.recordCompatibleFallback(NOW);
    assertTrue(p.recordVlcSuccess());
    assertEquals(0, p.vlcFailureUntilMs);
    assertTrue(p.chooseVlc(NOW + 1));
    assertFalse("Further ready events do not require another history write", p.recordVlcSuccess());
  }

  @Test
  public void anEligibleNativeErrorDoesNotYetRememberFallback() {
    SeekEnginePolicy p = new SeekEnginePolicy();
    assertTrue(SeekEnginePolicy.shouldRememberVlcFailure(true, true, true));
    assertTrue("An error alone does not prove Media3 can render this file", p.chooseVlc(NOW));
    assertEquals(0, p.vlcFailureUntilMs);
  }

  @Test
  public void foregroundInitializationAndPromotedPageErrorsAreEligible() {
    assertTrue(SeekEnginePolicy.shouldRememberVlcFailure(true, false, true));
    assertTrue(SeekEnginePolicy.shouldRememberVlcFailure(false, true, true));
  }

  @Test
  public void offscreenDecoderFailureDoesNotCreateAFileRule() {
    assertFalse(SeekEnginePolicy.shouldRememberVlcFailure(false, false, true));
  }

  @Test
  public void warmupTimeoutOrUnreadableInputDoesNotCreateAFileRule() {
    assertFalse(SeekEnginePolicy.shouldRememberVlcFailure(true, true, false));
    assertFalse(SeekEnginePolicy.shouldRememberVlcFailure(false, false, false));
  }

  @Test
  public void softwareFallbackKeepsVlcAndExpiresAfterOneDay() {
    SeekEnginePolicy p = new SeekEnginePolicy();
    assertFalse(p.shouldAvoidSoftware(NOW));
    p.recordSoftwareFallback(NOW);
    assertTrue(p.shouldAvoidSoftware(NOW + SeekEnginePolicy.FAILURE_COOLDOWN_MS - 1));
    assertFalse(p.shouldAvoidSoftware(NOW + SeekEnginePolicy.FAILURE_COOLDOWN_MS));
    assertTrue("Software trouble does not blacklist the whole native engine", p.chooseVlc(NOW));
  }

  @Test
  public void nativeSuccessDoesNotEraseSoftwareAvoidance() {
    SeekEnginePolicy p = new SeekEnginePolicy();
    p.recordSoftwareFallback(NOW);
    p.recordCompatibleFallback(NOW);
    assertTrue(p.recordVlcSuccess());
    assertTrue(p.shouldAvoidSoftware(NOW + 1));
    assertTrue(p.chooseVlc(NOW + 1));
  }

  @Test
  public void softwareSuccessClearsOnlyItsOwnRule() {
    SeekEnginePolicy p = new SeekEnginePolicy();
    p.recordSoftwareFallback(NOW);
    p.recordCompatibleFallback(NOW);
    assertTrue(p.recordSoftwareSuccess());
    assertFalse(p.shouldAvoidSoftware(NOW + 1));
    assertFalse("Engine compatibility remains an independent decision", p.chooseVlc(NOW + 1));
    assertFalse(p.recordSoftwareSuccess());
  }

  @Test
  public void restoredSoftwareDeadlinePreservesTheCooldown() {
    SeekEnginePolicy p = new SeekEnginePolicy();
    p.recordSoftwareFallback(NOW);
    SeekEnginePolicy restored = new SeekEnginePolicy();
    restored.softwareAvoidUntilMs = p.softwareAvoidUntilMs;
    assertTrue(restored.shouldAvoidSoftware(NOW + 1));
    assertFalse(restored.shouldAvoidSoftware(restored.softwareAvoidUntilMs));
    assertTrue(restored.chooseVlc(NOW + 1));
  }
}
