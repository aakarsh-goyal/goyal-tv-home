package com.gathin.tvhome;

import android.app.Application;

/** Registers the volume pill's receiver once per process; everything else is created on demand. */
public final class TvApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        VolumeHud.install(this);
    }
}
