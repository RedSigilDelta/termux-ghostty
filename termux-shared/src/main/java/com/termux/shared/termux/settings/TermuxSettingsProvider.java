package com.termux.shared.termux.settings;

/** Settings that termux-shared needs from the host app. */
public interface TermuxSettingsProvider {

    boolean shouldAllowExternalApps();

    boolean shouldRunTermuxAmSocketServer();

    int getDeleteTMPDIRFilesOlderThanXDaysOnExit();

}
