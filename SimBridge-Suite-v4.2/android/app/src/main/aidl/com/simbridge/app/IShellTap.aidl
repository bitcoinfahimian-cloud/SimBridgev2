package com.simbridge.app;

import android.os.ParcelFileDescriptor;

interface IShellTap {
    String getVersion();
    int getUid();
    ParcelFileDescriptor[] openCallAudio(int methodCode, int sampleRate);
    void setLocalMicMute(boolean muted);
    void closeCallAudio();
    String probeCallAudio(int methodCode, int durationMs);
    void exitShell();
}
