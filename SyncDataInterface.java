package com.hairyharri.gameye.sync;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

public interface SyncDataInterface {
    void onReady();
    boolean onDataReceived(@NonNull String data);
    void onFinished(@Nullable String errorMsg);
}
