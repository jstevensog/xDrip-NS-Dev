package com.eveningoutpost.dexdrip.utilitymodels.pebble;

import static com.eveningoutpost.dexdrip.models.JoH.tolerantParseDouble;
import static com.eveningoutpost.dexdrip.models.JoH.tolerantParseInt;

import android.content.BroadcastReceiver;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.os.PowerManager;
import android.preference.PreferenceManager;

import com.eveningoutpost.dexdrip.BestGlucose;
import com.eveningoutpost.dexdrip.Home;
import com.eveningoutpost.dexdrip.models.ActiveBgAlert;
import com.eveningoutpost.dexdrip.models.BgReading;
import com.eveningoutpost.dexdrip.models.JoH;
import com.eveningoutpost.dexdrip.models.UserError.Log;
import com.eveningoutpost.dexdrip.utilitymodels.BgGraphBuilder;
import com.eveningoutpost.dexdrip.utilitymodels.BgSparklineBuilder;
import com.eveningoutpost.dexdrip.utilitymodels.Constants;
import com.eveningoutpost.dexdrip.utilitymodels.Pref;
import com.eveningoutpost.dexdrip.utilitymodels.SimpleImageEncoder;
import com.getpebble.android.kit.PebbleKit;
import com.getpebble.android.kit.util.PebbleDictionary;
import com.getpebble.android.kit.util.PebbleTuple;

import java.io.BufferedOutputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Date;
import java.util.Dictionary;
import java.util.List;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;


/**
 * Created by THE NIGHTSCOUT PROJECT CONTRIBUTORS (and adapted to fit the needs of this project)
 * <p/>
 * Changed by Andy (created from PebbleSync from PebbleTrend branch)
 * Later cut and pasted from xDrip-Experimental directly from the Sept 2016 beta
 * Primarily the work of John Stevens (jstevensog)
 */
public class PebbleFramework extends PebbleDisplayAbstract {

    private final static String TAG = PebbleFramework.class.getSimpleName();

   /*
    public static final int ICON_KEY = 0;
    public static final int BG_KEY = 1;
    public static final int RECORD_TIME_KEY = 2;
    public static final int PHONE_TIME_KEY = 3;
    public static final int BG_DELTA_KEY = 4;
    public static final int UPLOADER_BATTERY_KEY = 5;
    public static final int NAME_KEY = 6;
    public static final int TREND_BEGIN_KEY = 7;
    public static final int TREND_DATA_KEY = 8;
    public static final int TREND_END_KEY = 9;
    public static final int MESSAGE_KEY = 10;
    public static final int VIBE_KEY = 11;

    private static final int NO_BLUETOOTH_KEY = 111;
    private static final int COLLECT_HEALTH_KEY = 112;

    public static final int SYNC_KEY = 1000;
    public static final int PLATFORM_KEY = 1001;
    public static final int VERSION_KEY = 1002;
    */
    private static final int CHUNK_SIZE = 100;
    public static final boolean d = true;

    private static byte last_collect_health_key_byte = 0x1A;
    private static byte last_bluetooth_key_byte = 0x1A;
    private static boolean messageInTransit = false;
    private static boolean transactionFailed = false;
    private static boolean transactionOk = false;
    private static boolean done = false;
    private static boolean sendingData = false;
    private static int lastTrendPeriod = -1;
    private static int current_size = 0;
    private static int image_size = 0;
    private static byte[] chunk;
    private static ByteBuffer buff = null;
    public static int retries = 0;
    private static final boolean debugPNG = false;
    private static boolean didTrend = false;
    private static final ReentrantLock lock = new ReentrantLock();

    private static long pebble_platform = -1;
    private static String pebble_app_version = "";
    private static long pebble_sync_value = 0;
    private static long pebble_trend_size = 0;
    private static boolean sentInitialSync = false;

    private boolean no_signal = false;
    private BgGraphBuilder bgGraphBuilder;
    private BgReading mBgReading;
    private static short sendStep = 5;
    private final PebbleDictionary dictionary = new PebbleDictionary();

    protected boolean heartBeat = false;

    PebbleFramework() {

    }

    @Override
    public void startDeviceCommand() {
        if (JoH.ratelimitmilli("pebble-trend", 250)) {
            // intent received, which means new data is available
            transactionFailed = false;
            transactionOk = false;
            sendStep = 5;
            messageInTransit = false;
            done = true;
            sendingData = false;

            // if we have a trend time, check if > 1 min ago and send
            BgReading reading = BgReading.last();
            long readingts = reading.timestamp / 1000;
            Log.d(TAG, "Timestamps: " + readingts + " vs " + last_seen_timestamp);
            if ((360) > readingts- last_seen_timestamp && readingts- last_seen_timestamp  > (50)) {
                // send value to watch since we are in the window

                PebbleDictionary dict = new PebbleDictionary();
                sendBgl(dict, reading);
                sendDelta(dict);
                sendDataToPebble(dict);
            } else {
                sendData();
            }
        } else {
            Log.d(TAG, "SendData ratelimited!");
        }
    }


    @Override
    public void receiveNack(int transactionId) {
        Log.i(TAG, "receiveNack: Got an Nack for transactionId " + transactionId + ". Waiting and retrying.");

        if (retries < 3) {
            transactionFailed = true;
            transactionOk = false;
            messageInTransit = false;
            retries++;
            sendData();
        } else {
            Log.i(TAG, "recieveNAck: exceeded retries.  Giving Up");
            transactionFailed = false;
            transactionOk = false;
            messageInTransit = false;
            sendStep = 4;
            retries = 0;
            done = true;
        }
    }


    @Override
    public void receiveAck(int transactionId) {

        if (d) Log.i(TAG, "receiveAck: Got an Ack for transactionId " + transactionId);
        messageInTransit = false;
        transactionOk = true;
        transactionFailed = false;
        retries = 0;

        if (!done && sendingData)
            sendData();
    }


    @Override
    public void receiveData(int transactionId, PebbleDictionary data) {
        Log.d(TAG, "receiveData: transactionId is " + String.valueOf(transactionId));
        this.pebbleWatchSync.lastTransactionId = transactionId;
        Log.d(TAG, "Received Query. data: " + data.size() + ".");
        PebbleKit.sendAckToPebble(this.context, transactionId);
        evaluateDataFromPebble(data);
        transactionFailed = false;
        transactionOk = false;
        messageInTransit = false;
        sendStep = 5;
        sendData();
    }

    private PebbleDictionary sendDelta(PebbleDictionary dict) {
        char value = 0;
        char mask = 0;
        if (use_best_glucose) {
            value = (char) dg.delta_mgdl;
        } else {
            String deltastring = this.bgGraphBuilder.unitizedDeltaString(false, true);
            if (deltastring.contains("?")) {
                mask = 0x20;
            } else {
                float bgfloat = Float.parseFloat(deltastring);
                value = (char) bgfloat;
            }
        }
        if (getBooleanValue("pebble_show_delta_units")) {
            mask |= 0x40;
        }
        if (!Pref.getString("units", "mgdl").equals("mgdl")) {
            mask |= 0x80;
        }
        short result = (short) (mask << 8 | value);
        Log.d(TAG, "Bgl delta: " + Integer.toHexString((int) result));
        dict.addUint16(FRAMEWORK_BGL_DELTA, result);
        return dict;
    }

    private PebbleDictionary sendBgl(PebbleDictionary dict, BgReading reading) {
        boolean ismmol = !Pref.getString("units", "mgdl").equals("mgdl");
        short value  = (short) Math.round(reading.getDg_mgdl());
        if (ismmol) value |= 0x8000;
        int ts = (int) (reading.timestamp / 1000);
        buff = ByteBuffer.allocate(6);
        buff.putInt(0, ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN ? Integer.reverseBytes(ts) : ts);
        buff.putShort(4, ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN ? Short.reverseBytes(value) : value);
        dict.addBytes(FRAMEWORK_BGL_VALUE, buff.array());
        Log.d(TAG, "Sending BGL value");
        return dict;
    }

    private void evaluateDataFromPebble(PebbleDictionary data) {

        try {
            if (data.size() > 0 && data.contains(FRAMEWORK_HEARTBEAT)) {
                // set heartbeat protocol
                this.heartBeat = true;
                // heartbeat message struct
                long hb = data.getUnsignedIntegerAsLong(FRAMEWORK_HEARTBEAT);
                Log.d(TAG, "Heartbeat received: " + Long.toHexString(hb));
                // old fashioned decoding of bitmasks
                boolean colour = (hb & 0x80000000) != 0;
                boolean time_series = (hb & 0x40000000) != 0;
                long time_period = (hb & 0x30000000) >> 28;
                boolean high_limit = (hb & 0x08000000) != 0;
                boolean low_limit = (hb & 0x04000000) != 0;
                boolean small_dots = (hb & 0x02000000) != 0;
                boolean send_iob = (hb & 0x01000000) != 0;
                boolean send_pump_state = (hb & 0x00800000) != 0;
                boolean send_pump_battery = (hb & 0x00200000) != 0;
                boolean send_delta_value = (hb & 0x00100000) != 0;
                boolean send_slope_arrow = (hb & 0x00080000) != 0;
                boolean send_phone_battery = (hb & 0x00400000) != 0;
                Log.d(TAG, "Framework heartbeat: Colour=" + colour
                        + " time_series=" + time_series
                        + " time_period=" + time_period
                        + " high_limit=" + high_limit
                        + " low_limit=" + low_limit
                        + " small_dots=" + small_dots
                        + " send_iob=" + send_iob
                        + " send_pump_state=" + send_pump_state
                        + " send_pump_battery=" + send_pump_battery
                        + " send_delta_value=" + send_delta_value
                        + " send_slope_arrow=" + send_slope_arrow
                        + " send_phone_battery=" + send_phone_battery
                );

                PebbleDictionary dict = new PebbleDictionary();


                if (send_slope_arrow) {
                    if (!getBooleanValue("pebble_show_arrows") || no_signal) {
                        dict.addUint8(FRAMEWORK_SLOPEVAL, (byte) 0);
                    } else {
                        dict.addUint8(FRAMEWORK_SLOPEVAL, getSlopeOrdinalUint8());
                    }
                }
                SharedPreferences perfs = PreferenceManager.getDefaultSharedPreferences(context);
                if (high_limit) {
                    short high_line = 0;
                    // the hig/low line values are set as strings and can thus be in mmol/l
                    if (Double.parseDouble(perfs.getString("highValue", "170")) < 25) {
                        high_line = (short) (tolerantParseDouble(perfs.getString("highValue", "10.0"), 10.0) / Constants.MGDL_TO_MMOLL);
                    } else {
                        high_line = (short) tolerantParseInt(perfs.getString("highValue", "170"), 170);
                    }
                    short high_limit_val = (short) Pref.getStringToInt("default_ymax", 250);
                    Log.d(TAG, "High values: " + high_line + " // " + high_limit_val + " // " + perfs.getString("highValue", "170"));
                    buff = ByteBuffer.allocate(4);
                    buff.putShort(0, ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN ? Short.reverseBytes((short) high_line) : (short) high_line);
                    buff.putShort(2, ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN ? Short.reverseBytes((short) high_limit_val) : (short) high_limit_val);

                    dict.addBytes(FRAMEWORK_HIGHLIMIT, buff.array());
                }
                if (low_limit) {
                    short low_line = 0;
                    if (Double.parseDouble(perfs.getString("lowValue", "70")) < 25) {
                        low_line = (short) (tolerantParseDouble(perfs.getString("lowValue", "2.2"), 2.2) / Constants.MGDL_TO_MMOLL);
                    } else {
                        low_line = (short) tolerantParseInt(perfs.getString("lowValue", "70"), 70);
                    }
                    short low_limit_val = (short) Pref.getStringToInt("default_ymin", 40);
                    Log.d(TAG, "Low values: " + low_line + " // " + low_limit_val);

                    buff = ByteBuffer.allocate(4);
                    buff.putShort(0, ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN ? Short.reverseBytes((short) low_line) : (short) low_line);
                    buff.putShort(2, ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN ? Short.reverseBytes((short) low_limit_val) : (short) low_limit_val);
                    dict.addBytes(FRAMEWORK_LOWLIMIT, buff.array());

                }
                if (send_phone_battery) {
                    dict.addUint16(FRAMEWORK_PHONEBAT, (byte) getBatteryLevel());
                }

                if (send_delta_value) {
                    sendDelta(dict);
                }

                if (data.contains(FRAMEWORK_BGL_VALUE)) {
                    long timestamp = data.getUnsignedIntegerAsLong(FRAMEWORK_BGL_VALUE);
                    String trendPeriodString = PreferenceManager.getDefaultSharedPreferences(this.context).getString("pebble_trend_period", "3");
                    int trendPeriod = Integer.parseInt(trendPeriodString);
                    Log.d(TAG, "Trend period: " + trendPeriod + " - " + lastTrendPeriod + " Since: " + timestamp);

                    long end = System.currentTimeMillis() + (60000 * 5);
                    long start = timestamp == 0 ? end - (60000 * 60 * trendPeriod) - (60000 * 10) : (timestamp * 1000) - (4 * 60000);
                    lastTrendPeriod = trendPeriod;
                    List<BgReading> readings = BgReading.latestForGraph(200, start, end);
                    last_seen_timestamp = readings.get(0).timestamp / 1000;

                    Log.d(TAG, "Trend size: " + readings.size());
                    boolean ismmol = !Pref.getString("units", "mgdl").equals("mgdl");
                    // strip known (timestamp is a known value)
                    if (readings.get(readings.size()-1).timestamp / 1000 == timestamp) readings.remove(readings.size()-1); // check if oldest is the timestamp
                    if (readings.size() > 1) {
                        // convert to uint16
                        buff = ByteBuffer.allocate(4 + 2 + readings.size() * 2);
                        int ts = (int) (readings.get(0).timestamp / 1000);
                        buff.putInt(0, ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN ? Integer.reverseBytes(ts) : ts);
                        buff.putShort(4, ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN ? Short.reverseBytes((short) readings.size()) : (short) readings.size());
                        for (int i = 0; i < readings.size(); i++) {
                            short value = (short) Math.round(readings.get(readings.size() - 1 - i).getDg_mgdl());
                            Log.d(TAG, "Trend data: " + readings.get(readings.size() - 1 - i).getDg_mgdl() + " value: " + value + " - Time: " + readings.get(readings.size() - 1 - i).timestamp / 1000);
                            if (ismmol) value |= 0x8000;
                            buff.putShort(6 + i * 2, ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN ? Short.reverseBytes(value) : value); // convert endianess if need be
                        }
                        dict.addBytes(FRAMEWORK_BGL_SERIES, buff.array());
                        Log.d(TAG, "Sending bgl series");
                    } else if (readings.size() == 1){
                        sendBgl(dict, readings.get(0));
                    }

                }

                sendDataToPebble(dict);

            } else if (data.size() > 0) {
                pebble_sync_value = data.getUnsignedIntegerAsLong(SYNC_KEY);
                pebble_platform = data.getUnsignedIntegerAsLong(PLATFORM_KEY);
                pebble_app_version = data.getString(VERSION_KEY);
                pebble_trend_size = data.getUnsignedIntegerAsLong(TREND_SIZE);
                Log.d(TAG, "receiveData: pebble_sync_value=" + pebble_sync_value + ", pebble_platform=" + pebble_platform + ", pebble_app_version=" + pebble_app_version + ", pebble_trend_size=" +pebble_trend_size);

                switch ((int) pebble_platform) {
                    case 0:
                        if (PebbleUtil.pebbleDisplayType != PebbleDisplayType.TrendClassic) {
                            PebbleUtil.pebbleDisplayType = PebbleDisplayType.TrendClassic;
                            //JoH.static_toast_short("Switching to Pebble Classic Trend");
                            Log.d(TAG, "Changing to Classic Trend due to platform id");
                        }
                        break;
                }

            } else {
                Log.d(TAG, "receiveData: pebble_app_version not known");
            }
        } catch (NullPointerException e) {
            Log.e(TAG, "Got exception trying to parse data from pebble: " + e);
        }

    }



    private String lastBfReadingSent;

    public PebbleDictionary buildDictionary() {
        TimeZone tz = TimeZone.getDefault();
        Date now = new Date();
        int offsetFromUTC = tz.getOffset(now.getTime());

       // if (this.dictionary == null) {
       //     this.dictionary = new PebbleDictionary();
       // }

        if (use_best_glucose ? (this.dg != null) : (this.bgReading != null)) {
            boolean no_signal;

            final String slopeOrdinal = getSlopeOrdinal();
            final String bgReadingS = getBgReading();

            if (use_best_glucose)
            {
                Log.v(TAG, "buildDictionary: slopeOrdinal-" + slopeOrdinal + " bgReading-" + bgReadingS + //
                        " now-" + (int) now.getTime() / 1000 + " bgTime-" + (int) (dg.timestamp / 1000) + //
                        " phoneTime-" + (int) (new Date().getTime() / 1000) + " getBgDelta-" + getBgDelta());
                no_signal = (dg.mssince > Home.stale_data_millis());
        } else {
                Log.v(TAG, "buildDictionary: slopeOrdinal-" + slopeOrdinal + " bgReading-" + bgReadingS + //
                        " now-" + (int) now.getTime() / 1000 + " bgTime-" + (int) (this.bgReading.timestamp / 1000) + //
                        " phoneTime-" + (int) (new Date().getTime() / 1000) + " getBgDelta-" + getBgDelta());
                no_signal = ((new Date().getTime()) - Home.stale_data_millis() - this.bgReading.timestamp > 0);
        }

            if (!getBooleanValue("pebble_show_arrows") || no_signal) {
                this.dictionary.addString(ICON_KEY, "0");
            } else {
                this.dictionary.addString(ICON_KEY, slopeOrdinal);
            }

            if (no_signal) {
                // We display last reading, even if none was sent for some time.
                if (this.lastBfReadingSent != null) {
                    this.dictionary.addString(BG_KEY, this.lastBfReadingSent);
                    this.dictionary.addInt8(VIBE_KEY, (byte) (getBooleanValue("pebble_vibrate_no_signal") ? 0x01 : 0x00)); // not sure what this does exactly
                } else {
                    this.dictionary.addString(BG_KEY, "?RF");
                    this.dictionary.addInt8(VIBE_KEY, (byte) (getBooleanValue("pebble_vibrate_no_signal") ? 0x01 : 0x00));
                }
            } else {
                this.dictionary.addString(BG_KEY, bgReadingS);
                if (getBooleanValue("pebble_vibe_alerts", false) && ActiveBgAlert.currentlyAlerting()) {
                    dictionary.addInt8(VIBE_KEY, (byte) 0x03);
                } else {
                    this.dictionary.addInt8(VIBE_KEY, (byte) 0x00);
                }
                this.lastBfReadingSent = bgReadingS;
                }

            if (use_best_glucose) {
                this.dictionary.addUint32(RECORD_TIME_KEY, (int) (((dg.timestamp + offsetFromUTC) / 1000)));
            } else {
                this.dictionary.addUint32(RECORD_TIME_KEY, (int) (((this.bgReading.timestamp + offsetFromUTC) / 1000)));
            }

            if (getBooleanValue("pebble_show_delta")) {
                if (no_signal) {
                    this.dictionary.addString(BG_DELTA_KEY, "No Signal");
                } else {
                    this.dictionary.addString(BG_DELTA_KEY, getBgDelta());
                    if (((keyStore.getS("bwp_last_insulin") != null) && (JoH.msSince(keyStore.getL("bwp_last_insulin_timestamp")) < Constants.MINUTE_IN_MS * 11))
                            && getBooleanValue("pebble_show_bwp")) {
                        this.dictionary.addString(BG_DELTA_KEY, PEBBLE_BWP_SYMBOL + keyStore.getS("bwp_last_insulin")); // 😐
                    }

                }
            } else {
                this.dictionary.addString(BG_DELTA_KEY, "");
            }

            String msg = PreferenceManager.getDefaultSharedPreferences(this.context).getString("pebble_special_value", "");

            byte bluetooth_key_byte = (byte) (getBooleanValue("pebble_vibrate_no_bluetooth") ? 0x01 : 0x00);
            this.dictionary.addInt8(NO_BLUETOOTH_KEY, bluetooth_key_byte);

            byte collect_health_key_byte = (byte) (getBooleanValue("use_pebble_health") ? 0x01 : 0x00);
            if ((collect_health_key_byte != last_collect_health_key_byte) || JoH.ratelimit("collect_health_key_byte", 3)) {
                this.dictionary.addInt8(COLLECT_HEALTH_KEY, collect_health_key_byte);
                last_collect_health_key_byte = collect_health_key_byte;
            } else {
                this.dictionary.remove(COLLECT_HEALTH_KEY);
            }

            // TODO I think special message is only appropriate with flat trend
            if (bgReadingS.equalsIgnoreCase(msg)) {
                this.dictionary.addString(MESSAGE_KEY, PreferenceManager.getDefaultSharedPreferences(this.context).getString("pebble_special_text", "BAZINGA!"));
            } else {
                this.dictionary.addString(MESSAGE_KEY, "");
            }
        } else {
            Log.v(TAG, "buildDictionary: latest mBgReading is null, so sending default values");
            this.dictionary.addString(ICON_KEY, getSlopeOrdinal());
            this.dictionary.addString(BG_KEY, "?SN");
            this.dictionary.addUint32(RECORD_TIME_KEY, (int) ((new Date().getTime() + offsetFromUTC / 1000)));
            this.dictionary.addString(BG_DELTA_KEY, "No Sensor");
            this.dictionary.addString(MESSAGE_KEY, "");
        }

        this.dictionary.addUint32(PHONE_TIME_KEY, (int) ((new Date().getTime() + offsetFromUTC) / 1000));

        if (JoH.ratelimit("add_battery_status", 60)) {
            addBatteryStatusToDictionary(this.dictionary);
        } else {
            removeBatteryStatusFromDictionary(this.dictionary);
    }

        return this.dictionary;
    }

    private synchronized void sendTrendToPebble(boolean clearTrend) {
        int png_depth;
        //create a sparkline bitmap to send to the pebble
        final Bitmap blankTrend;
        if (clearTrend) {
            blankTrend = Bitmap.createBitmap(1,1,Bitmap.Config.ARGB_8888);
            Log.d(TAG,"Attempting to blank trend");
        } else {
            blankTrend = null; didTrend=true;
        }

        if(pebble_trend_size == 0) {
            Log.d(TAG, "No pebble_trend-size, returning.");
            return;
        }

        Log.i(TAG, "sendTrendToPebble called: sendStep= " + sendStep + ", messageInTransit= " + messageInTransit + //
                ", transactionFailed= " + transactionFailed + ", sendStep= " + sendStep);
        if (!done && (sendStep == 1 && ((!messageInTransit && !transactionOk && !transactionFailed) || //
                (messageInTransit && !transactionOk && transactionFailed)))) {

            if (!messageInTransit && !transactionOk && !transactionFailed) {

                if (!clearTrend && (!doWeDisplayTrendData())) {
                    sendStep = 5;
                    transactionFailed = false;
                    transactionOk = false;
                    done = true;
                    current_size = 0;
                    buff = null;
                }

                boolean highLine = getBooleanValue("pebble_high_line");
                boolean lowLine = getBooleanValue("pebble_low_line");

                String trendPeriodString = PreferenceManager.getDefaultSharedPreferences(this.context).getString("pebble_trend_period", "3");
                Integer trendPeriod = Integer.parseInt(trendPeriodString);

                if ((trendPeriod != lastTrendPeriod) || (JoH.ratelimit("pebble-bggraphbuilder",60)))
                {
                    long end = System.currentTimeMillis() + (60000 * 5);
                    long start = end - (60000 * 60 * trendPeriod) - (60000 * 10);
                    this.bgGraphBuilder = new BgGraphBuilder(context, start, end, MAX_VALUES, true);
                    lastTrendPeriod = trendPeriod;
                }


                Log.d(TAG, "sendTrendToPebble: highLine is " + highLine + ", lowLine is " + lowLine + ",trendPeriod is " + trendPeriod);
                Bitmap bgTrend = new BgSparklineBuilder(this.context)
                        .setBgGraphBuilder(this.bgGraphBuilder)
                        .setStart(System.currentTimeMillis() - 60000 * 60 * trendPeriod)
                        .setEnd(System.currentTimeMillis())
                        //.setHeightPx(PebbleUtil.pebbleDisplayType == PebbleDisplayType.TrendClassic ? 63 : 84) // 84
                        .setHeightPx((int) (pebble_trend_size & 0xff))
                        //.setWidthPx(PebbleUtil.pebbleDisplayType == PebbleDisplayType.TrendClassic ? 84 : 144) // 144
                        .noLowLineFill(true)
                        .setWidthPx((int) ((pebble_trend_size & 0xff00) >> 8 ))
                        .showHighLine(highLine)
                        .showLowLine(lowLine)
                        .setTinyDots(Pref.getBoolean("pebble_tiny_dots", false))
                        .setSmallDots(!Pref.getBoolean("pebble_tiny_dots", false))
                        .build();

                //encode the trend bitmap as a PNG
                if((pebble_trend_size & 0x80000000) == 0x80000000) {
                    Log.d(TAG,"sendTrendToPebble: Pebble requested PNG8 depth");
                    png_depth = 64;
                } else {
                    Log.d(TAG, "sendTrendToPebble: Pebble did not request PNG8, creating PNG4 depth");
                    png_depth = 16;
                }
                final byte[] img = SimpleImageEncoder.encodeBitmapAsPNG(clearTrend ? blankTrend : bgTrend, true, PebbleUtil.pebbleDisplayType == PebbleDisplayType.TrendClassic ? 2: png_depth, true);

                if (debugPNG) {
                    try {
                        // save debug image output
                        BufferedOutputStream bos = new BufferedOutputStream(new FileOutputStream("/sdcard/download/xdrip-trend-debug.png"));
                        bos.write(img);
                        bos.flush();
                        bos.close();
                    } catch (FileNotFoundException e) {

                    } catch (IOException e) {
                }
                    // also save full colour
                    final byte[] img2 = SimpleImageEncoder.encodeBitmapAsPNG(bgTrend, true, 16, true);
                    try {
                        // save debug image output
                        BufferedOutputStream bos = new BufferedOutputStream(new FileOutputStream("/sdcard/download/xdrip-trend-debug-colour.png"));
                        bos.write(img2);
                        bos.flush();
                        bos.close();
                    } catch (FileNotFoundException e) {

                    } catch (IOException e) {

                    }
                }

                image_size = img.length;
                buff = ByteBuffer.wrap(img);
                bgTrend.recycle();
                //Prepare the TREND_BEGIN_KEY dictionary.  We expect the length of the image to always be less than 65535 bytes.
                if (buff != null) {
                    //if (this.dictionary == null) {
                    //    this.dictionary = new PebbleDictionary();
                    //}
                    this.dictionary.addInt16(TREND_BEGIN_KEY, (short) image_size);
                    Log.d(TAG, "sendTrendToPebble: Sending TREND_BEGIN_KEY to pebble, image size is " + image_size);
                } else {
                    Log.d(TAG, "sendTrendToPebble: Error converting stream to ByteBuffer, buff is null.");
                    sendStep = 4;
                    return;
                }
            }

            transactionFailed = false;
            transactionOk = false;
            messageInTransit = true;
            sendDataToPebble(this.dictionary);
        }

        if (sendStep == 1 && !done && !messageInTransit && transactionOk && !transactionFailed) {
            Log.i(TAG, "sendTrendToPebble: sendStep " + sendStep + " complete.");
            this.dictionary.remove(TREND_BEGIN_KEY);
            current_size = 0;
            sendStep = 2;
            transactionOk = false;
        }

        if (!done && ((sendStep == 2 && !messageInTransit) || sendStep == 3 && transactionFailed)) {
            if (!transactionFailed && !messageInTransit) {
                // send image chunks to Pebble.
                if (d) Log.d(TAG, "sendTrendToPebble: current_size is " + current_size + ", image_size is " + image_size);
                if (current_size < image_size) {
                    this.dictionary.remove(TREND_DATA_KEY);
                    if ((image_size <= (current_size + CHUNK_SIZE))) {
                        chunk = new byte[image_size - current_size];
                        if (d) Log.d(TAG, "sendTrendToPebble: sending chunk of size " + (image_size - current_size));
                        buff.get(chunk, 0, image_size - current_size);
                        sendStep = 3;
                    } else {
                        chunk = new byte[CHUNK_SIZE];
                        if (d) Log.d(TAG, "sendTrendToPebble: sending chunk of size " + CHUNK_SIZE);
                        buff.get(chunk, 0, CHUNK_SIZE);
                        current_size += CHUNK_SIZE;
                    }
                    this.dictionary.addBytes(TREND_DATA_KEY, chunk);
                }
            }
            Log.d(TAG, "sendTrendToPebble: Sending TREND_DATA_KEY to pebble, current_size is " + current_size);
            transactionFailed = false;
            transactionOk = false;
            messageInTransit = true;
            sendDataToPebble(this.dictionary);
        }

        if (sendStep == 3 && !done && !messageInTransit && transactionOk && !transactionFailed) {
            Log.i(TAG, "sendTrendToPebble: sendStep " + sendStep + " complete.");
            this.dictionary.remove(TREND_DATA_KEY);
            sendStep = 4;
            transactionOk = false;
            buff = null;
            //stream = null;
        }

        if (!done && (sendStep == 4 && ((!messageInTransit && !transactionOk && !transactionFailed) || //
                (messageInTransit && !transactionOk && transactionFailed)))) {
            if (!transactionFailed) {
                // prepare the TREND_END_KEY dictionary and send it.
                this.dictionary.addUint8(TREND_END_KEY, (byte) 0);
                Log.d(TAG, "sendTrendToPebble: Sending TREND_END_KEY to pebble.");
            }

            transactionFailed = false;
            transactionOk = false;
            messageInTransit = true;
            sendDataToPebble(this.dictionary);
        }

        if (sendStep == 4 && !done && transactionOk && !messageInTransit && !transactionFailed) {
            Log.i(TAG, "sendTrendToPebble: sendStep " + sendStep + " complete.");
            this.dictionary.remove(TREND_END_KEY);
            sendStep = 5;
            transactionFailed = false;
            transactionOk = false;
            done = true;
            current_size = 0;
            buff = null;
            if (clearTrend) didTrend=false; // cleared
        }
    }


    private void clearDictionary() {
        synchronized (this.dictionary) {
            // might just be easier to instantiate a new dictionary
            final List<Integer> temp = new ArrayList<>();
            for (PebbleTuple aDictionary : this.dictionary) {
                temp.add(aDictionary.key);
            }
            for (Integer i : temp) {
                this.dictionary.remove(i);
            }
        }

  /*      this.dictionary.remove(ICON_KEY);
        this.dictionary.remove(BG_KEY);
        this.dictionary.remove(NAME_KEY);
        this.dictionary.remove(BG_DELTA_KEY);
        this.dictionary.remove(PHONE_TIME_KEY);
        this.dictionary.remove(RECORD_TIME_KEY);
        this.dictionary.remove(UPLOADER_BATTERY_KEY);
        this.dictionary.remove(VIBE_KEY);
    }

        this.dictionary.remove(COLLECT_HEALTH_KEY);
        this.dictionary.remove(NO_BLUETOOTH_KEY);

        */
    }


    public synchronized void sendData() {
        PowerManager.WakeLock wl = JoH.getWakeLock("pebble-trend-sendData",60000);
        try {
            if (lock.tryLock(60, TimeUnit.SECONDS)) {
                try {
                    if (d) Log.d(TAG, "Sendstep: " + sendStep);
                    if (sendStep == 5) {
                        sendStep = 0;
                        done = false;
                        clearDictionary();
                    }

                    if (d)
                        Log.i(TAG, "sendData: messageInTransit= " + messageInTransit + ", transactionFailed= " + transactionFailed + ", sendStep= " + sendStep);
                    if (sendStep == 0 && !messageInTransit && !transactionOk && !transactionFailed && !heartBeat) {

                        if (use_best_glucose) {
                            this.dg = BestGlucose.getDisplayGlucose();
                        } else {
                            this.bgReading = BgReading.last();
                        }

                        sendingData = true;
                        // Do not send anything, use heartbeat protocol
                        //buildDictionary();
                        //sendDownload();
                    }


                    if (sendStep == 0 && !messageInTransit && transactionOk && !transactionFailed) {
                        if (d) Log.i(TAG, "sendData: sendStep 0 complete, clearing dictionary");
                        clearDictionary();
                        transactionOk = false;
                        sendStep = 1;
                    }
                    if (sendStep > 0 && sendStep < 5) {
                        if (!doWeDisplayTrendData()) {
                            if (didTrend) {
                                sendTrendToPebble(true); // clear trend image
                            } else {
                                sendStep = 5;
                            }
                        } else {
                            sendTrendToPebble(false);
                        }
                    }

                    if (sendStep == 5) {
                        if (d)
                            Log.i(TAG, "sendData: finished sending.  sendStep = " + sendStep);
                        done = true;
                        transactionFailed = false;
                        transactionOk = false;
                        messageInTransit = false;
                        sendingData = false;
                    }

                } catch (Exception e) {
                    Log.wtf(TAG, "Got exception handling pebble: " + e);

                } finally {
                    lock.unlock();
                }
            } else {
                Log.w(TAG, "Could not acquire lock within timeout!");
            }
        } catch (InterruptedException e)
        {
            Log.w(TAG,"Got interrupted while waiting to acquire lock!");
        } finally {
            JoH.releaseWakeLock(wl);
        }
    }

    public String getBgDelta() {
        final boolean show_delta_units = getBooleanValue("pebble_show_delta_units");
        return (use_best_glucose) ? (show_delta_units ? dg.unitized_delta : dg.unitized_delta_no_units)
                : this.bgGraphBuilder.unitizedDeltaString(show_delta_units, true);
    }


    public String phoneBattery() {
        return String.valueOf(getBatteryLevel());
    }

    public String bgUnit() {
        return bgGraphBuilder.unit();
    }


    public void sendDownload() {
        Log.d(TAG,"send download called");
        if (this.dictionary != null && this.context != null) {
            Log.d(TAG, "sendDownload: Sending data to pebble");
            messageInTransit = true;
            transactionFailed = false;
            transactionOk = false;
            sendDataToPebble(this.dictionary);
        }
    }

    /*
    public int getBatteryLevel() {
     // is in abstract base class
    }
    */


    public boolean doWeDisplayTrendData() {
        return getBooleanValue("pebble_display_trend");
    }
    public UUID watchfaceUUID()
    {
        return UUID.fromString("51a6140e-92cc-420f-aef6-51b229666742");
    }
}
