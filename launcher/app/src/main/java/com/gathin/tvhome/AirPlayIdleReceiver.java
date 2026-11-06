package com.gathin.tvhome;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** The one alarm AirPlayControl sets while the receiver is on: see AirPlayControl.idleCheck. */
public final class AirPlayIdleReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        final PendingResult result = goAsync();
        AirPlayControl.idleCheck(context.getApplicationContext(), result::finish);
    }
}
