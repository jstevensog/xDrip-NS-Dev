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
import com.eveningoutpost.dexdrip.cgm.medtrum.SensorState;
import com.eveningoutpost.dexdrip.g5model.DexSessionKeeper;
import com.eveningoutpost.dexdrip.models.ActiveBgAlert;
import com.eveningoutpost.dexdrip.models.BgReading;
import com.eveningoutpost.dexdrip.models.JoH;
import com.eveningoutpost.dexdrip.models.Sensor;
import com.eveningoutpost.dexdrip.models.UserError.Log;
import com.eveningoutpost.dexdrip.services.Ob1G5CollectionService;
import com.eveningoutpost.dexdrip.utilitymodels.BgGraphBuilder;
import com.eveningoutpost.dexdrip.utilitymodels.BgSparklineBuilder;
import com.eveningoutpost.dexdrip.utilitymodels.Constants;
import com.eveningoutpost.dexdrip.utilitymodels.Pref;
import com.eveningoutpost.dexdrip.utilitymodels.SensorStatus;
import com.eveningoutpost.dexdrip.utilitymodels.SimpleImageEncoder;
import com.eveningoutpost.dexdrip.g5model.SensorDays;
import com.getpebble.android.kit.PebbleKit;
import com.getpebble.android.kit.util.PebbleDictionary;
import com.getpebble.android.kit.util.PebbleTuple;

import java.io.BufferedOutputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
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

    private static long pebble_trend_size = 0;

    private boolean no_signal = false;
    private BgGraphBuilder bgGraphBuilder;
    private static short sendStep = 5;
    private final PebbleDictionary dictionary = new PebbleDictionary();

    protected boolean heartBeat = false;

    private boolean use_png = false;
    private long time_period = 0;
    private boolean colour = false;
    private long png_height = 0;
    private long png_width = 0;
    private boolean png_highdef = false;
    private short high_line_store = 0;
    private short low_line_store = 0;

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

            // we likely have a new reading, use it
            dg = BestGlucose.getDisplayGlucose();
            bgReading = BgReading.last();

            BgReading reading = bgReading;
            if (reading != null) {
                long readingts = reading.timestamp / 1000;

                Log.d(TAG, "Timestamps: " + readingts + " vs " + last_seen_timestamp + " = " + (readingts - last_seen_timestamp ));
                Log.d(TAG, "DG: " + dg.timestamp + " val: " + dg.mgdl + " // " + " reading: " + bgReading.timestamp + " val: " + bgReading.getDg_mgdl());
                if (readingts - last_seen_timestamp > (30)) { // the watch will figure out if it needs more
                    // send value to watch since we are in the window

                    PebbleDictionary dict = new PebbleDictionary();

                    // bgl error reporting is done by sendBgl

                    sendBgl(dict, reading);
                    sendDelta(dict);
                    sendSlope(dict, reading);
                    sendVibe(dict);

                    sendHighLimit(dict, false);
                    sendLowLimit(dict, false);
                    sendMessage(dict);
                    // if we have png enabled, also send png
                    if (use_png) sendPng(dict, colour, png_highdef, png_width, png_height);

                    sendDataToPebble(dict);
                    last_seen_timestamp = readingts;
                }
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

    private PebbleDictionary sendMessage(PebbleDictionary dict) {
        long timeLeft = SensorDays.get().getRemainingSensorPeriodInMs();
        String message = null;
        if (getBgReading().equalsIgnoreCase(PreferenceManager.getDefaultSharedPreferences(this.context).getString("pebble_special_value", ""))) {
            message =  PreferenceManager.getDefaultSharedPreferences(this.context).getString("pebble_special_text", "BAZINGA!");
        } else if(timeLeft < (24*3600000)) { // less than a day left
            long hoursLeft = Math.toIntExact(timeLeft / 3600000);
            int minutesLeft = Math.toIntExact((timeLeft - (hoursLeft * 3600000)) / 60000);
            Log.d(TAG,"timeLeft="+timeLeft+", hoursLeft="+hoursLeft+ ", minutesLeft="+minutesLeft);
            if(hoursLeft > 0) {
                message = "End: " + hoursLeft + ":" + String.format("%02d", minutesLeft) + "h";
            } else if (minutesLeft > 0) {
                message = "End: " + minutesLeft + " min";
            } else {
                message = "Sensor exp.";
            }
        } else if(SensorDays.get().isValid() && (Ob1G5CollectionService.isG5WarmingUp() || (Ob1G5CollectionService.isPendingStart())) && !Ob1G5CollectionService.isCollecting()) {
            double timeleft = (SensorDays.get().getWarmupMs() - JoH.msSince(SensorDays.get().getStart())) / 60000.0;
            message = String.format("Wait %.1fm",  timeleft >= 0.0 ? timeleft : 0.0);
       } else {
            message = "";
        }
        if (message != null) {
            dict.addString(FRAMEWORK_MESSAGE, message);
        }
        return dict;
    }

    private PebbleDictionary sendSensorRemaining(PebbleDictionary dict) {
        long TimeLeft = (long) (SensorDays.get().getRemainingSensorPeriodInMs() / 1000.0);
        dict.addUint32(FRAMEWORK_SENSOR_TIME_LEFT, (int) TimeLeft);
        return dict;
    }
    private PebbleDictionary sendVibe(PebbleDictionary dict) {
        no_signal = ((new Date().getTime()) - Home.stale_data_millis() - this.bgReading.timestamp > 0);

        if (no_signal) {
            dict.addInt8(FRAMEWORK_VIBE, (byte) (getBooleanValue("pebble_vibrate_no_signal") ? 0x01 : 0x00));
        } else {
            // vibrate on alert
            if (getBooleanValue("pebble_vibe_alerts", false) && ActiveBgAlert.currentlyAlerting()) {
                dict.addInt8(FRAMEWORK_VIBE, (byte) 0x03);
            }
        }
        return dict;
    }
    private PebbleDictionary sendDelta(PebbleDictionary dict) {
        byte value = 0;
        byte mask = 0;
        if (use_best_glucose && BestGlucose.getDisplayGlucose() != null) {
            value = (byte) BestGlucose.getDisplayGlucose().delta_mgdl;
        } else {
            try {
                String deltastring = this.bgGraphBuilder.unitizedDeltaString(false, true);
                if (deltastring.contains("?")) {
                    mask = 0x20;
                } else {
                    float bgfloat = Float.parseFloat(deltastring);
                    value = (byte) bgfloat;
                }
            } catch (Exception e) {
                if (!SensorDays.get().isValid()) {
                    mask |= 0x08; // unknown state
                    value = 0;
                } else {
                    mask |= 0x10; // hide in warmup
                }
            }
        }
        if (getBooleanValue("pebble_show_delta_units")) {
            mask |= 0x40;
        }
        if (!Pref.getString("units", "mgdl").equals("mgdl")) {
            mask |= 0x80;
        }
        if (!getBooleanValue("pebble_show_delta")) {
            mask |= 0x10; // hide the delta information
        }
        buff = ByteBuffer.allocate(2);
        buff.put(0, value); // value is read as uint16, needs endianess conversion
        buff.put(1, mask);
        Log.d(TAG, "Bgl delta: mask: " + Integer.toHexString(mask & 0xFF) + " Value: " + Integer.toHexString(value & 0xFF));
        dict.addBytes(FRAMEWORK_BGL_DELTA, buff.array());
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

    final private int SPECIAL_VALUE_NONE = 0;
    final private int SPECIAL_VALUE_SENSOR_NOT_ACTIVE= 1;
    final private int SPECIAL_VALUE_MINIMALLY_EGV_AB = 2;
    final private int SPECIAL_VALUE_NP_ANTENNA = 3;
    final private int SPECIAL_VALUE_SENSOR_OUT_OF_CALIBRATION = 5;
    final private int SPECIAL_VALUE_DEVICE_ENDED = 6;

    final private int SPECIAL_VALUE_ABSOLUTE_AB = 9;
    final private int SPECIAL_VALUE_POWER_AB = 10;

    final private int SPECIAL_VALUE_NO_RF = 12;


    private PebbleDictionary sendSlope(PebbleDictionary dict, BgReading reading) {
        // Check for special cases in order of importance, if none detected show slope if requested by use
        Sensor sensor = Sensor.currentSensor();
        Log.d(TAG, "sensor: " + sensor + " reading: " + reading + " signal: " + no_signal);

        if (reading != null) no_signal = ((new Date().getTime()) - Home.stale_data_millis() - this.bgReading.timestamp > 0);
        else no_signal = true; // no readings possible
        if ( (sensor == null && !(Ob1G5CollectionService.isG5WarmingUp() || Ob1G5CollectionService.isPendingStart())) || (reading != null && (
                (int) reading.calculated_value == SPECIAL_VALUE_DEVICE_ENDED ||
                (int) reading.calculated_value == SPECIAL_VALUE_MINIMALLY_EGV_AB ||
                (int) reading.calculated_value == SPECIAL_VALUE_SENSOR_NOT_ACTIVE))
                ) { // traffic light
            dict.addUint8(FRAMEWORK_SLOPEVAL, (byte) 12);
        } else if ( (no_signal && !(Ob1G5CollectionService.isG5WarmingUp() || Ob1G5CollectionService.isPendingStart())) || (reading != null && (
                (int) reading.calculated_value == SPECIAL_VALUE_NO_RF ||
                (int) reading.calculated_value == SPECIAL_VALUE_NP_ANTENNA))
                ) { // broken antenna
            dict.addUint8(FRAMEWORK_SLOPEVAL, (byte) 10);
        } else if (  (reading != null && reading.calibration != null && !reading.calibration.isValid()) ||
                (reading != null && (int) reading.calculated_value == SPECIAL_VALUE_SENSOR_OUT_OF_CALIBRATION)
                ) { // blood drop
            dict.addUint8(FRAMEWORK_SLOPEVAL, (byte) 11);
        } else if (reading != null && ((int) reading.calculated_value == SPECIAL_VALUE_ABSOLUTE_AB || (int) reading.calculated_value == SPECIAL_VALUE_POWER_AB)) { // question marks
            dict.addUint8(FRAMEWORK_SLOPEVAL, (byte) 14);
        } else if (Ob1G5CollectionService.isG5WarmingUp() || Ob1G5CollectionService.isPendingStart()) { // hourglass
            // this should represent the warmup period of the sensor
            dict.addUint8(FRAMEWORK_SLOPEVAL, (byte) 13);
        } else if (!getBooleanValue("pebble_show_arrows")) {
            dict.addUint8(FRAMEWORK_SLOPEVAL, (byte) 0);
        } else {
            dict.addUint8(FRAMEWORK_SLOPEVAL, getSlopeOrdinalUint8());
        }
        return dict;
    }

    private PebbleDictionary sendHighLimit(PebbleDictionary dict, boolean force) {
        boolean highLine = getBooleanValue("pebble_high_line");
        SharedPreferences perfs = PreferenceManager.getDefaultSharedPreferences(context);
        short high_line = 0;
        // the hig/low line values are set as strings and can thus be in mmol/l
        if (!highLine) {
            high_line = 0;
        } else if (Double.parseDouble(perfs.getString("highValue", "170")) < 25) {
            high_line = (short) (tolerantParseDouble(perfs.getString("highValue", "10.0"), 10.0) / Constants.MGDL_TO_MMOLL);
        } else {
            high_line = (short) tolerantParseInt(perfs.getString("highValue", "170"), 170);
        }
        short high_limit_val = (short) Pref.getStringToInt("default_ymax", 250);
        buff = ByteBuffer.allocate(4);
        buff.putShort(0, ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN ? Short.reverseBytes((short) high_line) : (short) high_line);
        buff.putShort(2, ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN ? Short.reverseBytes((short) high_limit_val) : (short) high_limit_val);
        if ((!highLine && high_line_store != 0) || (highLine && high_line_store == 0) || high_line != high_line_store || force) {
            Log.d(TAG, "High values: " + high_line + " // " + high_limit_val + " // " + perfs.getString("highValue", "170"));
            high_line_store = high_line;
            dict.addBytes(FRAMEWORK_HIGHLIMIT, buff.array());
        }
        return dict;
    }

    private PebbleDictionary sendLowLimit(PebbleDictionary dict, boolean force) {
        boolean lowLine = getBooleanValue("pebble_low_line");
        SharedPreferences perfs = PreferenceManager.getDefaultSharedPreferences(context);
        short low_line = 0;
        if (!lowLine) {
            low_line = 0;
        } else if (Double.parseDouble(perfs.getString("lowValue", "70")) < 25) {
            low_line = (short) (tolerantParseDouble(perfs.getString("lowValue", "2.2"), 2.2) / Constants.MGDL_TO_MMOLL);
        } else {
            low_line = (short) tolerantParseInt(perfs.getString("lowValue", "70"), 70);
        }
        short low_limit_val = (short) Pref.getStringToInt("default_ymin", 40);

        buff = ByteBuffer.allocate(4);
        buff.putShort(0, ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN ? Short.reverseBytes((short) low_line) : (short) low_line);
        buff.putShort(2, ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN ? Short.reverseBytes((short) low_limit_val) : (short) low_limit_val);

        if ((!lowLine && low_line_store != 0) || (lowLine && low_line_store == 0) || low_line != low_line_store || force) {
            Log.d(TAG, "Low values: " + low_line + " // " + low_limit_val);
            low_line_store = low_line;
            dict.addBytes(FRAMEWORK_LOWLIMIT, buff.array());
        }
        return dict;
    }

    private PebbleDictionary sendBwp(PebbleDictionary dict) {
        if (((keyStore.getS("bwp_last_insulin") != null) && (JoH.msSince(keyStore.getL("bwp_last_insulin_timestamp")) < Constants.MINUTE_IN_MS * 11))
                && getBooleanValue("pebble_show_bwp")) {
            dict.addString(BG_DELTA_KEY, PEBBLE_BWP_SYMBOL + keyStore.getS("bwp_last_insulin")); // 😐
        }
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
                colour = (hb & 0x80000000) != 0;
                boolean time_series = (hb & 0x40000000) != 0;
                time_period = (hb & 0x30000000) >> 28;
                boolean high_limit = (hb & 0x08000000) != 0;
                boolean low_limit = (hb & 0x04000000) != 0;
                boolean small_dots = (hb & 0x02000000) != 0;
                boolean send_iob = (hb & 0x01000000) != 0;
                boolean send_pump_state = (hb & 0x00800000) != 0;
                boolean send_phone_battery = (hb & 0x00400000) != 0;
                boolean send_pump_battery = (hb & 0x00200000) != 0;
                boolean send_delta_value = (hb & 0x00100000) != 0;
                boolean send_slope_arrow = (hb & 0x00080000) != 0;
                boolean send_sensor_expiry = (hb & 0x00040000) != 0;
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
                        + " send_sensor_expiry=" + send_sensor_expiry
                );

                PebbleDictionary dict = new PebbleDictionary();

                if (send_sensor_expiry) sendSensorRemaining((dict));

                if (send_slope_arrow) sendSlope(dict, bgReading);

                if (high_limit) sendHighLimit(dict, true);

                if (low_limit) sendLowLimit(dict, true);

                if (send_phone_battery)  dict.addUint16(FRAMEWORK_PHONEBAT, (byte) getBatteryLevel());

                if (send_delta_value) sendDelta(dict);

                use_png = !time_series;

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
                    if (readings.size() > 1 && time_series) {
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
                    } else if (readings.size() == 1 || !time_series){
                        sendBgl(dict, readings.get(0));
                    }

                }

                if (!time_series && data.contains(FRAMEWORK_PNG_IMAGE)) {
                    // we use png method
                    long png_size = data.getUnsignedIntegerAsLong(FRAMEWORK_PNG_IMAGE);
                    png_highdef = (png_size & 0x80000000) != 0;
                    png_width = png_size & 0x000003FFF;
                    png_height = (png_size & 0x0FFFC000) >> 14;
                    sendPng(dict, colour, png_highdef, png_width, png_height);
                }

                sendMessage(dict);

                sendDataToPebble(dict);

            } else {
                Log.d(TAG, "receiveData: pebble_app_version not known");
            }
        } catch (NullPointerException e) {
            Log.e(TAG, "Got exception trying to parse data from pebble: " + e);
        }

    }

    private PebbleDictionary sendPng(PebbleDictionary dict, boolean colour, boolean high_bit, long width, long height) {
        if (width == 0 || height == 0) return dict;
        int png_depth = 16;
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
        Log.d(TAG, "Size: " + width + " x " + height);
        Bitmap bgTrend = new BgSparklineBuilder(this.context)
                .setBgGraphBuilder(this.bgGraphBuilder)
                .setStart(System.currentTimeMillis() - 60000 * 60 * trendPeriod)
                .setEnd(System.currentTimeMillis())
                //.setHeightPx(PebbleUtil.pebbleDisplayType == PebbleDisplayType.TrendClassic ? 63 : 84) // 84
                .setHeightPx((int) height)
                //.setWidthPx(PebbleUtil.pebbleDisplayType == PebbleDisplayType.TrendClassic ? 84 : 144) // 144
                .noLowLineFill(true)
                .setWidthPx((int)
                        width)
                .showHighLine(highLine)
                .showLowLine(lowLine)
                .setTinyDots(Pref.getBoolean("pebble_tiny_dots", false))
                .setSmallDots(!Pref.getBoolean("pebble_tiny_dots", false))
                .build();

        //encode the trend bitmap as a PNG
        if(high_bit && colour) {
            Log.d(TAG,"sendTrendToPebble: Pebble requested PNG8 depth");
            png_depth = 64;
        }
        final byte[] img = SimpleImageEncoder.encodeBitmapAsPNG(bgTrend, colour, !colour ? 2: png_depth, true);

        image_size = img.length;
        buff = ByteBuffer.wrap(img);
        bgTrend.recycle();
        Log.d(TAG, "Sending PNG: " + buff.array().length);
        dict.addBytes(FRAMEWORK_PNG_IMAGE, buff.array());
        return dict;
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
        return UUID.fromString("240ff2d2-a64a-11f1-9d00-c74dac4ca2e6");
    }
}
