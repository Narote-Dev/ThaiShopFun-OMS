package com.thaishopfun.oms.pii;

import org.junit.platform.launcher.LauncherSession;
import org.junit.platform.launcher.LauncherSessionListener;

/** Install before test discovery initializes any static loggers or Spring contexts. */
public class PiiLogGateSession implements LauncherSessionListener {
  @Override
  public void launcherSessionOpened(LauncherSession session) {
    PiiLogGate.install();
  }
}
