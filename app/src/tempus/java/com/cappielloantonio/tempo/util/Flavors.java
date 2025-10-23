package com.cappielloantonio.tempo.util;

import android.content.Context;

import androidx.core.content.ContextCompat;

import com.google.android.gms.cast.framework.CastContext;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GoogleApiAvailability;

public class Flavors {
    @SuppressWarnings("deprecation")
    public static void initializeCastContext(Context context) {
        if (GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS)
            CastContext.getSharedInstance(context, ContextCompat.getMainExecutor(context));
    }
}
