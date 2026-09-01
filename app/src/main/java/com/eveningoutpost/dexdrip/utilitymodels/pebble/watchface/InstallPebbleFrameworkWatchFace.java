
package com.eveningoutpost.dexdrip.utilitymodels.pebble.watchface;

import com.eveningoutpost.dexdrip.R;

import java.io.InputStream;

/**
 * Created by jamorham on 22/04/2016.
 */
public class InstallPebbleFrameworkWatchFace extends InstallPebbleWatchFace {

    private static String TAG = "InstallPebbleFrameworkatchFace";


    @Override
    protected String getTag() {
        return TAG;
    }


    protected InputStream openRawResource() {
               // jstevensog latest clay enabled and enhanced watchface for all Pebble platforms
        return getResources().openRawResource(R.raw.xdrip_pebble_fw);
    }

    protected String getOutputFilename() {
        return "xDrip-Pebble-E.pbw";
    }


}
