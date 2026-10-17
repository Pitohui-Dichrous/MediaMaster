package io.mediamaster.app.media;

import android.content.IntentSender;

/** Launch intentSender; after consent retry writes, but refresh completed deletes. */
public final class MutationConsentException extends Exception {
  public final IntentSender intentSender;
  public final boolean operationCompletedByConsent;

  public MutationConsentException(IntentSender sender, boolean completedByConsent) {
    super(completedByConsent ? "请在系统提示中确认删除" : "请在系统提示中允许修改文件");
    intentSender = sender;
    operationCompletedByConsent = completedByConsent;
  }
}
