package de.rwth_aachen.phyphox;

import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.location.GnssClock;
import android.location.GnssMeasurement;
import android.location.GnssMeasurementRequest;
import android.location.GnssMeasurementsEvent;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.location.LocationProvider;
import android.location.OnNmeaMessageListener;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.RequiresApi;
import androidx.core.location.GnssStatusCompat;
import androidx.core.location.LocationManagerCompat;

import java.io.Serializable;
import java.util.Vector;
import java.util.concurrent.locks.Lock;

public class GpsInput implements Serializable {
    public DataBuffer dataLat; //Data-buffer for latitude
    public DataBuffer dataLon; //Data-buffer for longitude
    public DataBuffer dataZ; //Data-buffer for height
    public DataBuffer dataZWGS84; //Data-buffer for height above WGS84 ellipsoid
    public DataBuffer dataV; //Data-buffer for velocity
    public DataBuffer dataDir; //Data-buffer for direction
    public DataBuffer dataT; //Data-buffer for time

    public DataBuffer dataAccuracy; //Data-buffer for horizontal accuracy
    public DataBuffer dataZAccuracy; //Data-buffer for height accuracy
    public DataBuffer dataStatus; //Data-buffer for status codes (note filled parallel to the other buffers)
    public DataBuffer dataSatellites; //Data-buffer for status codes (note filled parallel to the other buffers)

    //Summary of the latest GnssStatus report, appended with each fix. Counts are -1 and C/N0 NaN before the first report.
    public DataBuffer dataSatellitesInView; //Satellites tracked by the GNSS engine
    public DataBuffer dataSatellitesUsed; //Satellites used in the fix
    public DataBuffer dataCn0Mean; //Mean carrier-to-noise density (dB-Hz) of the satellites used in the fix
    public DataBuffer dataCn0Max; //Highest C/N0 (dB-Hz) among the satellites used in the fix
    public DataBuffer dataCn0Min; //Lowest C/N0 (dB-Hz) among the satellites used in the fix
    //Per-satellite table: one row per tracked satellite per status report, on its own time base (not aligned with the fixes)
    public DataBuffer dataSvT; //Experiment time of the status report
    public DataBuffer dataSvId; //Satellite id within its constellation
    public DataBuffer dataSvConstellation; //GnssStatus constellation code: 1 GPS, 2 SBAS, 3 GLONASS, 4 QZSS, 5 BeiDou, 6 Galileo, 7 NavIC
    public DataBuffer dataSvCn0; //C/N0 (dB-Hz)
    public DataBuffer dataSvUsed; //1 if the satellite was used in the fix, 0 otherwise
    public DataBuffer dataSvElevation; //Elevation (degrees)
    public DataBuffer dataSvAzimuth; //Azimuth (degrees)
    public DataBuffer dataSvCarrierFrequency; //Carrier frequency (Hz), NaN if the device does not report it

    //More of each fix, aligned with t. NaN where the device or Android version does not report it.
    public DataBuffer dataMeanCn0; //The chipset's mean C/N0 (dB-Hz) from the fix extras
    public DataBuffer dataMaxCn0; //The chipset's highest C/N0 (dB-Hz) from the fix extras
    public DataBuffer dataUtc; //UTC time of the fix (seconds since 1970)
    public DataBuffer dataVAccuracy; //Speed accuracy (m/s, API 26)
    public DataBuffer dataDirAccuracy; //Bearing accuracy (degrees, API 26)
    public DataBuffer dataZMsl; //Altitude above mean sea level as Android computes it (m, API 34), next to z from phyphox's geoid
    public DataBuffer dataZMslAccuracy; //Accuracy of zMsl (m, API 34)
    public DataBuffer dataTUncertainty; //Uncertainty of the fix's timestamp (s, API 29)
    public DataBuffer dataMock; //1 if the fix came from a mock provider, 0 otherwise
    //More of each satellite, aligned with svT
    public DataBuffer dataSvEphemeris; //1 if the receiver has the satellite's ephemeris, 0 otherwise
    public DataBuffer dataSvAlmanac; //1 if the receiver has the satellite's almanac, 0 otherwise
    public DataBuffer dataSvBasebandCn0; //C/N0 at the baseband (dB-Hz, API 30)
    //One value per start of the GNSS engine
    public DataBuffer dataTtff; //Time to first fix (s)
    //NMEA tables (API 24), each on its own time base, one row per sentence. See NmeaParser.
    public DataBuffer dataGgaT; //Experiment time at which the GGA sentence arrived
    public DataBuffer dataGgaTime; //UTC time of day in the sentence (s since midnight)
    public DataBuffer dataGgaQuality; //Fix quality: 0 no fix, 1 GNSS, 2 DGNSS, 4 RTK fixed, 5 RTK float, 6 dead reckoning
    public DataBuffer dataGgaSatellites; //Satellites in use
    public DataBuffer dataGgaHdop; //Horizontal dilution of precision
    public DataBuffer dataGgaAltitude; //Altitude above mean sea level as the receiver computes it (m)
    public DataBuffer dataGgaGeoidSeparation; //Geoid above the WGS84 ellipsoid (m)
    public DataBuffer dataGsaT; //Experiment time at which the GSA sentence arrived
    public DataBuffer dataGsaMode; //1 no fix, 2 2D, 3 3D
    public DataBuffer dataGsaPdop; //Position dilution of precision
    public DataBuffer dataGsaHdop; //Horizontal dilution of precision
    public DataBuffer dataGsaVdop; //Vertical dilution of precision
    public DataBuffer dataGsaSystem; //NMEA 4.1 system id (1 GPS, 2 GLONASS, 3 Galileo, 4 BeiDou, 5 QZSS, 6 NavIC), NaN before 4.1
    //Raw measurements (API 24), one clock row per measurement event and one measurement row per tracked signal of the event.
    //Values a device does not report are NaN. Nanosecond values beyond 2^53 lose their last digits as doubles:
    //clockFullBiasNanos does, so the receiver's GPS time is also given exactly as clockGpsWeek and clockGpsTow.
    public DataBuffer dataClockT; //Experiment time of the event, from its elapsed realtime (API 29) like the fixes, else its arrival
    public DataBuffer dataClockTimeNanos; //The receiver's hardware clock (ns)
    public DataBuffer dataClockTimeUncertainty; //(ns)
    public DataBuffer dataClockLeapSecond; //(s)
    public DataBuffer dataClockFullBiasNanos; //Hardware clock minus GPS time (ns), rounded to a few hundred ns
    public DataBuffer dataClockBiasNanos; //Sub-nanosecond part of the bias (ns)
    public DataBuffer dataClockBiasUncertainty; //(ns)
    public DataBuffer dataClockDrift; //(ns/s)
    public DataBuffer dataClockDriftUncertainty; //(ns/s)
    public DataBuffer dataClockDiscontinuity; //Hardware clock discontinuity count
    public DataBuffer dataClockGpsWeek; //GPS week of the receiver's GPS time, TimeNanos - (FullBiasNanos + BiasNanos)
    public DataBuffer dataClockGpsTow; //GPS time of week of it (s)
    public DataBuffer dataMeasT; //Experiment time of the event, as clockT
    public DataBuffer dataMeasSvid;
    public DataBuffer dataMeasConstellation; //GnssStatus constellation code
    public DataBuffer dataMeasCarrierFrequency; //(Hz)
    public DataBuffer dataMeasCodeType; //Character code of the RINEX code type letter (67 C, 73 I, 81 Q, 88 X, ...), API 29
    public DataBuffer dataMeasTimeOffset; //Measurement time minus the clock's time (ns)
    public DataBuffer dataMeasState; //Synchronization state bits
    public DataBuffer dataMeasReceivedSvTime; //(ns)
    public DataBuffer dataMeasReceivedSvTimeUncertainty; //(ns)
    public DataBuffer dataMeasCn0; //(dB-Hz)
    public DataBuffer dataMeasBasebandCn0; //(dB-Hz, API 30)
    public DataBuffer dataMeasPseudorangeRate; //(m/s)
    public DataBuffer dataMeasPseudorangeRateUncertainty; //(m/s)
    public DataBuffer dataMeasAdrState; //Accumulated delta range state bits
    public DataBuffer dataMeasAdr; //Accumulated delta range, the carrier phase (m)
    public DataBuffer dataMeasAdrUncertainty; //(m)
    public DataBuffer dataMeasMultipath; //0 unknown, 1 detected, 2 not detected
    public DataBuffer dataMeasAgc; //Automatic gain control level (dB, API 26)
    public DataBuffer dataMeasStatus; //Each status the measurements report: 0 not supported, 1 ready, 2 location disabled, 3 not allowed

    transient private LocationManager locationManager; //Hold the sensor manager
    transient private Object nmeaListener; //An OnNmeaMessageListener (API 24) while registered
    transient private Object measurementsCallback; //A GnssMeasurementsEvent.Callback (API 24) while registered

    private static final long WEEK_NANOS = 604800L * 1000000000L;

    //Component names of the raw measurement outputs, in the order of the location mapping from index 50
    static final String[] RAW_COMPONENTS = {
            "clockT", "clockTimeNanos", "clockTimeUncertainty", "clockLeapSecond", "clockFullBiasNanos", "clockBiasNanos",
            "clockBiasUncertainty", "clockDrift", "clockDriftUncertainty", "clockDiscontinuity", "clockGpsWeek", "clockGpsTow",
            "measT", "measSvid", "measConstellation", "measCarrierFrequency", "measCodeType", "measTimeOffset", "measState",
            "measReceivedSvTime", "measReceivedSvTimeUncertainty", "measCn0", "measBasebandCn0", "measPseudorangeRate",
            "measPseudorangeRateUncertainty", "measAdrState", "measAdr", "measAdrUncertainty", "measMultipath", "measAgc",
            "measStatus"};

    //Their buffers in the same order
    DataBuffer[] rawBuffers() {
        return new DataBuffer[]{
                dataClockT, dataClockTimeNanos, dataClockTimeUncertainty, dataClockLeapSecond, dataClockFullBiasNanos, dataClockBiasNanos,
                dataClockBiasUncertainty, dataClockDrift, dataClockDriftUncertainty, dataClockDiscontinuity, dataClockGpsWeek, dataClockGpsTow,
                dataMeasT, dataMeasSvid, dataMeasConstellation, dataMeasCarrierFrequency, dataMeasCodeType, dataMeasTimeOffset, dataMeasState,
                dataMeasReceivedSvTime, dataMeasReceivedSvTimeUncertainty, dataMeasCn0, dataMeasBasebandCn0, dataMeasPseudorangeRate,
                dataMeasPseudorangeRateUncertainty, dataMeasAdrState, dataMeasAdr, dataMeasAdrUncertainty, dataMeasMultipath, dataMeasAgc,
                dataMeasStatus};
    }
    transient private GnssStatusCompat lastGnssStatus; //Latest status report, summarized when the next fix arrives. Fixes and reports both arrive on the main thread.

    private ExperimentTimeReference experimentTimeReference; //the start time of the measurement. This allows for timestamps relative to the beginning of a measurement
    public double lastSatBasedLocation;

    private Lock dataLock;
    private int lastStatus = 0;
    private GpsGeoid geoid;

    public enum ValueFormat {
        FLOAT, DEGREE_MINUTES, DEGREE_MINUTES_SECONDS, ASCII_
    }
    public boolean forceGNSS = false;
    //Interval between raw measurement events requested from API 33 (ms), from the measurementInterval attribute of
    //<location>. 0 leaves it to Android. The chipset may not go faster than its own rate.
    public int measurementIntervalMillis = 0;
    //Interval between GNSS fixes asked of the GPS provider (ms), from the fixInterval attribute of <location>. 0, as
    //upstream asks, may leave the chipset at its default of one fix per second.
    public int fixIntervalMillis = 0;

    //The constructor
    protected GpsInput(Vector<DataOutput> buffers, Lock lock, ExperimentTimeReference experimentTimeReference) {
        this.dataLock = lock;
        this.experimentTimeReference = experimentTimeReference;

        //Store the buffer references if any
        if (buffers == null)
            return;

        buffers.setSize(50 + RAW_COMPONENTS.length);
        if (buffers.get(0) != null)
            this.dataLat = buffers.get(0).buffer;
        if (buffers.get(1) != null)
            this.dataLon = buffers.get(1).buffer;
        if (buffers.get(2) != null)
            this.dataZ = buffers.get(2).buffer;
        if (buffers.get(3) != null)
            this.dataZWGS84 = buffers.get(3).buffer;
        if (buffers.get(4) != null)
            this.dataV = buffers.get(4).buffer;
        if (buffers.get(5) != null)
            this.dataDir = buffers.get(5).buffer;
        if (buffers.get(6) != null)
            this.dataT = buffers.get(6).buffer;
        if (buffers.get(7) != null)
            this.dataAccuracy = buffers.get(7).buffer;
        if (buffers.get(8) != null)
            this.dataZAccuracy = buffers.get(8).buffer;
        if (buffers.get(9) != null)
            this.dataStatus = buffers.get(9).buffer;
        if (buffers.get(10) != null)
            this.dataSatellites = buffers.get(10).buffer;
        if (buffers.get(11) != null)
            this.dataSatellitesInView = buffers.get(11).buffer;
        if (buffers.get(12) != null)
            this.dataSatellitesUsed = buffers.get(12).buffer;
        if (buffers.get(13) != null)
            this.dataCn0Mean = buffers.get(13).buffer;
        if (buffers.get(14) != null)
            this.dataCn0Max = buffers.get(14).buffer;
        if (buffers.get(15) != null)
            this.dataCn0Min = buffers.get(15).buffer;
        if (buffers.get(16) != null)
            this.dataSvT = buffers.get(16).buffer;
        if (buffers.get(17) != null)
            this.dataSvId = buffers.get(17).buffer;
        if (buffers.get(18) != null)
            this.dataSvConstellation = buffers.get(18).buffer;
        if (buffers.get(19) != null)
            this.dataSvCn0 = buffers.get(19).buffer;
        if (buffers.get(20) != null)
            this.dataSvUsed = buffers.get(20).buffer;
        if (buffers.get(21) != null)
            this.dataSvElevation = buffers.get(21).buffer;
        if (buffers.get(22) != null)
            this.dataSvAzimuth = buffers.get(22).buffer;
        if (buffers.get(23) != null)
            this.dataSvCarrierFrequency = buffers.get(23).buffer;
        if (buffers.get(24) != null)
            this.dataMeanCn0 = buffers.get(24).buffer;
        if (buffers.get(25) != null)
            this.dataMaxCn0 = buffers.get(25).buffer;
        if (buffers.get(26) != null)
            this.dataUtc = buffers.get(26).buffer;
        if (buffers.get(27) != null)
            this.dataVAccuracy = buffers.get(27).buffer;
        if (buffers.get(28) != null)
            this.dataDirAccuracy = buffers.get(28).buffer;
        if (buffers.get(29) != null)
            this.dataZMsl = buffers.get(29).buffer;
        if (buffers.get(30) != null)
            this.dataZMslAccuracy = buffers.get(30).buffer;
        if (buffers.get(31) != null)
            this.dataTUncertainty = buffers.get(31).buffer;
        if (buffers.get(32) != null)
            this.dataMock = buffers.get(32).buffer;
        if (buffers.get(33) != null)
            this.dataSvEphemeris = buffers.get(33).buffer;
        if (buffers.get(34) != null)
            this.dataSvAlmanac = buffers.get(34).buffer;
        if (buffers.get(35) != null)
            this.dataSvBasebandCn0 = buffers.get(35).buffer;
        if (buffers.get(36) != null)
            this.dataTtff = buffers.get(36).buffer;
        if (buffers.get(37) != null)
            this.dataGgaT = buffers.get(37).buffer;
        if (buffers.get(38) != null)
            this.dataGgaTime = buffers.get(38).buffer;
        if (buffers.get(39) != null)
            this.dataGgaQuality = buffers.get(39).buffer;
        if (buffers.get(40) != null)
            this.dataGgaSatellites = buffers.get(40).buffer;
        if (buffers.get(41) != null)
            this.dataGgaHdop = buffers.get(41).buffer;
        if (buffers.get(42) != null)
            this.dataGgaAltitude = buffers.get(42).buffer;
        if (buffers.get(43) != null)
            this.dataGgaGeoidSeparation = buffers.get(43).buffer;
        if (buffers.get(44) != null)
            this.dataGsaT = buffers.get(44).buffer;
        if (buffers.get(45) != null)
            this.dataGsaMode = buffers.get(45).buffer;
        if (buffers.get(46) != null)
            this.dataGsaPdop = buffers.get(46).buffer;
        if (buffers.get(47) != null)
            this.dataGsaHdop = buffers.get(47).buffer;
        if (buffers.get(48) != null)
            this.dataGsaVdop = buffers.get(48).buffer;
        if (buffers.get(49) != null)
            this.dataGsaSystem = buffers.get(49).buffer;
        DataBuffer[] raw = new DataBuffer[RAW_COMPONENTS.length];
        for (int i = 0; i < raw.length; i++)
            raw[i] = buffers.get(50 + i) != null ? buffers.get(50 + i).buffer : null;
        dataClockT = raw[0];
        dataClockTimeNanos = raw[1];
        dataClockTimeUncertainty = raw[2];
        dataClockLeapSecond = raw[3];
        dataClockFullBiasNanos = raw[4];
        dataClockBiasNanos = raw[5];
        dataClockBiasUncertainty = raw[6];
        dataClockDrift = raw[7];
        dataClockDriftUncertainty = raw[8];
        dataClockDiscontinuity = raw[9];
        dataClockGpsWeek = raw[10];
        dataClockGpsTow = raw[11];
        dataMeasT = raw[12];
        dataMeasSvid = raw[13];
        dataMeasConstellation = raw[14];
        dataMeasCarrierFrequency = raw[15];
        dataMeasCodeType = raw[16];
        dataMeasTimeOffset = raw[17];
        dataMeasState = raw[18];
        dataMeasReceivedSvTime = raw[19];
        dataMeasReceivedSvTimeUncertainty = raw[20];
        dataMeasCn0 = raw[21];
        dataMeasBasebandCn0 = raw[22];
        dataMeasPseudorangeRate = raw[23];
        dataMeasPseudorangeRateUncertainty = raw[24];
        dataMeasAdrState = raw[25];
        dataMeasAdr = raw[26];
        dataMeasAdrUncertainty = raw[27];
        dataMeasMultipath = raw[28];
        dataMeasAgc = raw[29];
        dataMeasStatus = raw[30];
    }

    private boolean usesClockTable() {
        return dataClockT != null || dataClockTimeNanos != null || dataClockTimeUncertainty != null || dataClockLeapSecond != null
                || dataClockFullBiasNanos != null || dataClockBiasNanos != null || dataClockBiasUncertainty != null || dataClockDrift != null
                || dataClockDriftUncertainty != null || dataClockDiscontinuity != null || dataClockGpsWeek != null || dataClockGpsTow != null;
    }

    private boolean usesMeasurementTable() {
        return dataMeasT != null || dataMeasSvid != null || dataMeasConstellation != null || dataMeasCarrierFrequency != null
                || dataMeasCodeType != null || dataMeasTimeOffset != null || dataMeasState != null || dataMeasReceivedSvTime != null
                || dataMeasReceivedSvTimeUncertainty != null || dataMeasCn0 != null || dataMeasBasebandCn0 != null
                || dataMeasPseudorangeRate != null || dataMeasPseudorangeRateUncertainty != null || dataMeasAdrState != null
                || dataMeasAdr != null || dataMeasAdrUncertainty != null || dataMeasMultipath != null || dataMeasAgc != null;
    }

    private boolean usesMeasurements() {
        return usesClockTable() || usesMeasurementTable() || dataMeasStatus != null;
    }

    private boolean usesGga() {
        return dataGgaT != null || dataGgaTime != null || dataGgaQuality != null || dataGgaSatellites != null || dataGgaHdop != null || dataGgaAltitude != null || dataGgaGeoidSeparation != null;
    }

    private boolean usesGsa() {
        return dataGsaT != null || dataGsaMode != null || dataGsaPdop != null || dataGsaHdop != null || dataGsaVdop != null || dataGsaSystem != null;
    }

    //True if any output needs the GnssStatus reports, so the callback is only registered when an experiment uses it
    private boolean usesGnssStatus() {
        return usesGnssSummary() || usesSatelliteTable() || dataTtff != null;
    }

    private boolean usesGnssSummary() {
        return dataSatellitesInView != null || dataSatellitesUsed != null || dataCn0Mean != null || dataCn0Max != null || dataCn0Min != null;
    }

    private boolean usesSatelliteTable() {
        return dataSvT != null || dataSvId != null || dataSvConstellation != null || dataSvCn0 != null || dataSvUsed != null || dataSvElevation != null || dataSvAzimuth != null || dataSvCarrierFrequency != null
                || dataSvEphemeris != null || dataSvAlmanac != null || dataSvBasebandCn0 != null;
    }

    public void attachLocationManager(LocationManager locationManager) {
        this.locationManager = locationManager;
    }

    public void prepare(Resources res) {
        geoid = new GpsGeoid(res.openRawResource(R.raw.egm84_30));
    }

    //Check if GPS hardware is available
    public static boolean isAvailable(Context context) {
        return (context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_LOCATION_GPS));
    }

    //Start the data aquisition by registering a listener for this location manager.
    public void start() {
        lastSatBasedLocation = -100.0; //We did not yet have a GNSS-based location
        lastGnssStatus = null; //A new run must not summarize a report from the previous one
        if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER))
            this.lastStatus = 0;
        else
            this.lastStatus = -1;

        try {
            if (locationManager.getAllProviders().contains(LocationManager.GPS_PROVIDER))
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, fixIntervalMillis, 0, locationListener);
            if (!forceGNSS && locationManager.getAllProviders().contains(LocationManager.NETWORK_PROVIDER))
                locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 0, 0, locationListener);
            //Satellite status reports arrive on the main thread like the fixes. The compat version falls back to GpsStatus before API 24.
            if (usesGnssStatus())
                LocationManagerCompat.registerGnssStatusCallback(locationManager, gnssStatusCallback, new Handler(Looper.getMainLooper()));
            //NMEA sentences, on the main thread too. Not before API 24, where the outputs stay empty.
            if ((usesGga() || usesGsa()) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                OnNmeaMessageListener listener = (message, timestamp) -> onNmeaMessage(message);
                nmeaListener = listener;
                locationManager.addNmeaListener(listener, new Handler(Looper.getMainLooper()));
            }
            if (usesMeasurements() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
                registerMeasurements();
        } catch (SecurityException e) {
            Log.e("gps start", "Security exception when requesting location updates: " + e.getMessage());
        } catch (IllegalArgumentException e) {
            Log.e("gps start", "Illegal argument despite existing provider: " + e.getMessage());
        }
        onStatusUpdate(0);
    }

    //Stop the data aquisition by unregistering the listener for this location manager
    public void stop() {
        locationManager.removeUpdates(locationListener);
        if (usesGnssStatus())
            LocationManagerCompat.unregisterGnssStatusCallback(locationManager, gnssStatusCallback);
        if (nmeaListener != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
            locationManager.removeNmeaListener((OnNmeaMessageListener) nmeaListener);
        nmeaListener = null;
        //Unregistered the way registerMeasurements registered it
        if (measurementsCallback != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            locationManager.unregisterGnssMeasurementsCallback((GnssMeasurementsEvent.Callback) measurementsCallback);
        else if (measurementsCallback != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
            LocationManagerCompat.unregisterGnssMeasurementsCallback(locationManager, (GnssMeasurementsEvent.Callback) measurementsCallback);
        measurementsCallback = null;
        lastGnssStatus = null;
    }

    //Raw measurements on the main thread. From API 31 with full tracking: every constellation and band without duty
    //cycling, for continuous carrier phase at the cost of power.
    @RequiresApi(Build.VERSION_CODES.N)
    private void registerMeasurements() {
        GnssMeasurementsEvent.Callback callback = new GnssMeasurementsEvent.Callback() {
            @Override
            public void onGnssMeasurementsReceived(GnssMeasurementsEvent event) {
                onGnssMeasurements(event);
            }

            @Override
            public void onStatusChanged(int status) {
                onGnssMeasurementsStatus(status);
            }
        };
        Handler main = new Handler(Looper.getMainLooper());
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                GnssMeasurementRequest.Builder request = new GnssMeasurementRequest.Builder().setFullTracking(true);
                if (measurementIntervalMillis > 0 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                    request.setIntervalMillis(measurementIntervalMillis);
                locationManager.registerGnssMeasurementsCallback(request.build(), main::post, callback);
            } else
                LocationManagerCompat.registerGnssMeasurementsCallback(locationManager, callback, main);
            measurementsCallback = callback;
        } catch (SecurityException e) {
            Log.e("gps start", "Security exception when requesting raw measurements: " + e.getMessage());
        }
    }

    public void onGnssMeasurementsStatus(int status) {
        if (dataMeasStatus == null)
            return;
        dataLock.lock();
        try {
            dataMeasStatus.append(status);
        } finally {
            dataLock.unlock();
        }
    }

    //One clock row per event and one measurement row per signal, all at the event's time
    @RequiresApi(Build.VERSION_CODES.N)
    public void onGnssMeasurements(GnssMeasurementsEvent event) {
        GnssClock clock = event.getClock();
        double t;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && clock.hasElapsedRealtimeNanos())
            t = experimentTimeReference.getExperimentTimeFromEvent(clock.getElapsedRealtimeNanos());
        else
            t = experimentTimeReference.getExperimentTime();

        double gpsWeek = Double.NaN;
        double gpsTow = Double.NaN;
        if (clock.hasFullBiasNanos()) {
            long gpsNanos = clock.getTimeNanos() - clock.getFullBiasNanos(); //Exact in 64 bit, the bias below is sub-nanosecond
            long week = Math.floorDiv(gpsNanos, WEEK_NANOS);
            gpsWeek = week;
            gpsTow = ((gpsNanos - week * WEEK_NANOS) - (clock.hasBiasNanos() ? clock.getBiasNanos() : 0)) * 1e-9;
        }

        dataLock.lock();
        try {
            if (dataClockT != null)
                dataClockT.append(t);
            if (dataClockTimeNanos != null)
                dataClockTimeNanos.append(clock.getTimeNanos());
            if (dataClockTimeUncertainty != null)
                dataClockTimeUncertainty.append(clock.hasTimeUncertaintyNanos() ? clock.getTimeUncertaintyNanos() : Double.NaN);
            if (dataClockLeapSecond != null)
                dataClockLeapSecond.append(clock.hasLeapSecond() ? clock.getLeapSecond() : Double.NaN);
            if (dataClockFullBiasNanos != null)
                dataClockFullBiasNanos.append(clock.hasFullBiasNanos() ? clock.getFullBiasNanos() : Double.NaN);
            if (dataClockBiasNanos != null)
                dataClockBiasNanos.append(clock.hasBiasNanos() ? clock.getBiasNanos() : Double.NaN);
            if (dataClockBiasUncertainty != null)
                dataClockBiasUncertainty.append(clock.hasBiasUncertaintyNanos() ? clock.getBiasUncertaintyNanos() : Double.NaN);
            if (dataClockDrift != null)
                dataClockDrift.append(clock.hasDriftNanosPerSecond() ? clock.getDriftNanosPerSecond() : Double.NaN);
            if (dataClockDriftUncertainty != null)
                dataClockDriftUncertainty.append(clock.hasDriftUncertaintyNanosPerSecond() ? clock.getDriftUncertaintyNanosPerSecond() : Double.NaN);
            if (dataClockDiscontinuity != null)
                dataClockDiscontinuity.append(clock.getHardwareClockDiscontinuityCount());
            if (dataClockGpsWeek != null)
                dataClockGpsWeek.append(gpsWeek);
            if (dataClockGpsTow != null)
                dataClockGpsTow.append(gpsTow);

            if (usesMeasurementTable()) {
                for (GnssMeasurement m : event.getMeasurements())
                    appendMeasurement(t, m);
            }
        } finally {
            dataLock.unlock();
        }
    }

    //Call with dataLock held
    @RequiresApi(Build.VERSION_CODES.N)
    private void appendMeasurement(double t, GnssMeasurement m) {
        if (dataMeasT != null)
            dataMeasT.append(t);
        if (dataMeasSvid != null)
            dataMeasSvid.append(m.getSvid());
        if (dataMeasConstellation != null)
            dataMeasConstellation.append(m.getConstellationType());
        if (dataMeasCarrierFrequency != null)
            dataMeasCarrierFrequency.append(m.hasCarrierFrequencyHz() ? m.getCarrierFrequencyHz() : Double.NaN);
        if (dataMeasCodeType != null) {
            String code = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && m.hasCodeType() ? m.getCodeType() : "";
            dataMeasCodeType.append(code.isEmpty() ? Double.NaN : code.charAt(0));
        }
        if (dataMeasTimeOffset != null)
            dataMeasTimeOffset.append(m.getTimeOffsetNanos());
        if (dataMeasState != null)
            dataMeasState.append(m.getState());
        if (dataMeasReceivedSvTime != null)
            dataMeasReceivedSvTime.append(m.getReceivedSvTimeNanos());
        if (dataMeasReceivedSvTimeUncertainty != null)
            dataMeasReceivedSvTimeUncertainty.append(m.getReceivedSvTimeUncertaintyNanos());
        if (dataMeasCn0 != null)
            dataMeasCn0.append(m.getCn0DbHz());
        if (dataMeasBasebandCn0 != null)
            dataMeasBasebandCn0.append(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && m.hasBasebandCn0DbHz() ? m.getBasebandCn0DbHz() : Double.NaN);
        if (dataMeasPseudorangeRate != null)
            dataMeasPseudorangeRate.append(m.getPseudorangeRateMetersPerSecond());
        if (dataMeasPseudorangeRateUncertainty != null)
            dataMeasPseudorangeRateUncertainty.append(m.getPseudorangeRateUncertaintyMetersPerSecond());
        if (dataMeasAdrState != null)
            dataMeasAdrState.append(m.getAccumulatedDeltaRangeState());
        if (dataMeasAdr != null)
            dataMeasAdr.append(m.getAccumulatedDeltaRangeMeters());
        if (dataMeasAdrUncertainty != null)
            dataMeasAdrUncertainty.append(m.getAccumulatedDeltaRangeUncertaintyMeters());
        if (dataMeasMultipath != null)
            dataMeasMultipath.append(m.getMultipathIndicator());
        if (dataMeasAgc != null)
            dataMeasAgc.append(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && m.hasAutomaticGainControlLevelDb() ? m.getAutomaticGainControlLevelDb() : Double.NaN);
    }

    //One row in the GGA or the GSA table per sentence of that type, at the time it arrived
    public void onNmeaMessage(String message) {
        NmeaParser.Gga gga = usesGga() ? NmeaParser.parseGga(message) : null;
        NmeaParser.Gsa gsa = gga == null && usesGsa() ? NmeaParser.parseGsa(message) : null;
        if (gga == null && gsa == null)
            return;
        double t = experimentTimeReference.getExperimentTime(); //Its own timestamp is the fix's UTC time, if the chipset fills it
        dataLock.lock();
        try {
            if (gga != null) {
                if (dataGgaT != null)
                    dataGgaT.append(t);
                if (dataGgaTime != null)
                    dataGgaTime.append(gga.time);
                if (dataGgaQuality != null)
                    dataGgaQuality.append(gga.quality);
                if (dataGgaSatellites != null)
                    dataGgaSatellites.append(gga.satellites);
                if (dataGgaHdop != null)
                    dataGgaHdop.append(gga.hdop);
                if (dataGgaAltitude != null)
                    dataGgaAltitude.append(gga.altitude);
                if (dataGgaGeoidSeparation != null)
                    dataGgaGeoidSeparation.append(gga.geoidSeparation);
            } else {
                if (dataGsaT != null)
                    dataGsaT.append(t);
                if (dataGsaMode != null)
                    dataGsaMode.append(gsa.mode);
                if (dataGsaPdop != null)
                    dataGsaPdop.append(gsa.pdop);
                if (dataGsaHdop != null)
                    dataGsaHdop.append(gsa.hdop);
                if (dataGsaVdop != null)
                    dataGsaVdop.append(gsa.vdop);
                if (dataGsaSystem != null)
                    dataGsaSystem.append(gsa.system);
            }
        } finally {
            dataLock.unlock();
        }
    }

    private final GnssStatusCompat.Callback gnssStatusCallback = new GnssStatusCompat.Callback() {
        @Override
        public void onSatelliteStatusChanged(GnssStatusCompat status) {
            onGnssStatusChanged(status);
        }

        @Override
        public void onFirstFix(int ttffMillis) {
            onGnssFirstFix(ttffMillis);
        }
    };

    //Called once the GNSS engine has its first fix after starting
    public void onGnssFirstFix(int ttffMillis) {
        if (dataTtff == null)
            return;
        dataLock.lock();
        try {
            dataTtff.append(ttffMillis / 1000.0);
        } finally {
            dataLock.unlock();
        }
    }

    //Called with every satellite status report, usually once per second while the GNSS engine runs.
    //Keep it for the summary of the next fix and append one row per satellite to the satellite table.
    public void onGnssStatusChanged(GnssStatusCompat status) {
        lastGnssStatus = status;
        if (!usesSatelliteTable())
            return;

        double t = experimentTimeReference.getExperimentTime(); //Status reports carry no timestamp of their own
        int count = status.getSatelliteCount();
        dataLock.lock();
        try {
            for (int i = 0; i < count; i++) {
                if (dataSvT != null)
                    dataSvT.append(t);
                if (dataSvId != null)
                    dataSvId.append(status.getSvid(i));
                if (dataSvConstellation != null)
                    dataSvConstellation.append(status.getConstellationType(i));
                if (dataSvCn0 != null)
                    dataSvCn0.append(status.getCn0DbHz(i));
                if (dataSvUsed != null)
                    dataSvUsed.append(status.usedInFix(i) ? 1 : 0);
                if (dataSvElevation != null)
                    dataSvElevation.append(status.getElevationDegrees(i));
                if (dataSvAzimuth != null)
                    dataSvAzimuth.append(status.getAzimuthDegrees(i));
                if (dataSvCarrierFrequency != null)
                    dataSvCarrierFrequency.append(status.hasCarrierFrequencyHz(i) ? status.getCarrierFrequencyHz(i) : Double.NaN);
                if (dataSvEphemeris != null)
                    dataSvEphemeris.append(status.hasEphemerisData(i) ? 1 : 0);
                if (dataSvAlmanac != null)
                    dataSvAlmanac.append(status.hasAlmanacData(i) ? 1 : 0);
                if (dataSvBasebandCn0 != null)
                    dataSvBasebandCn0.append(status.hasBasebandCn0DbHz(i) ? status.getBasebandCn0DbHz(i) : Double.NaN);
            }
        } finally {
            dataLock.unlock();
        }
    }

    //Summarize the latest status report for the fix being appended. Call with dataLock held.
    private void appendGnssSummary() {
        GnssStatusCompat status = lastGnssStatus;
        int inView = -1;
        int used = -1;
        double sum = 0;
        double max = Double.NaN;
        double min = Double.NaN;
        if (status != null) {
            inView = status.getSatelliteCount();
            used = 0;
            for (int i = 0; i < inView; i++) {
                if (!status.usedInFix(i))
                    continue;
                double cn0 = status.getCn0DbHz(i);
                used++;
                sum += cn0;
                if (Double.isNaN(max) || cn0 > max)
                    max = cn0;
                if (Double.isNaN(min) || cn0 < min)
                    min = cn0;
            }
        }
        if (dataSatellitesInView != null)
            dataSatellitesInView.append(inView);
        if (dataSatellitesUsed != null)
            dataSatellitesUsed.append(used);
        if (dataCn0Mean != null)
            dataCn0Mean.append(used > 0 ? sum / used : Double.NaN);
        if (dataCn0Max != null)
            dataCn0Max.append(max);
        if (dataCn0Min != null)
            dataCn0Min.append(min);
    }

    LocationListener locationListener = new LocationListener() {
        //Mandatory for LocationListener
        public void onProviderDisabled(String provider) {
            onStatusUpdate(0);
        }
        public void onProviderEnabled(String provider) {
            onStatusUpdate(0);
        }
        public void onStatusChanged(String provider, int status, Bundle extras) {
            onStatusUpdate(status);
        }

        public void onLocationChanged(Location location) {
            // Called when a new location is found by the network location provider.
            onSensorChanged(location);
        }
    };

    public void onStatusUpdate(int status) {
        if (dataStatus == null)
            return;

        dataLock.lock();
        try {
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER))
                if (status == LocationProvider.AVAILABLE)
                    dataStatus.append(1);
                else
                    dataStatus.append(0);
            else
                dataStatus.append(-1);
        } finally {
            dataLock.unlock();
        }
    }

    //This is called when we receive new data from a sensor. Append it to the right buffer
    public void onSensorChanged(Location event) {
        long inT = event.getElapsedRealtimeNanos();

        double newT = experimentTimeReference.getExperimentTimeFromEvent(inT);

        if (dataT != null && newT < dataT.value)
            return;

        if (event.getProvider().equals(LocationManager.GPS_PROVIDER))
            lastSatBasedLocation = newT;
        else { //To if we have a recent GNSS-based location, we ignore other providers for a while as they are not necessary and make data interpretation unnecessary complicated, especially for students who do not know about the different location providers
            if (newT - lastSatBasedLocation < 10)
                return;
        }

        //Append the data to available buffers
        dataLock.lock();
        try {
            if (dataT != null)
                dataT.append(newT);
            if (dataLat != null)
                dataLat.append(event.getLatitude());
            if (dataLon != null)
                dataLon.append(event.getLongitude());
            if (dataZWGS84 != null) {
                dataZWGS84.append(event.hasAltitude() ? event.getAltitude() : Double.NaN);
            }
            if (dataZ != null) {
                dataZ.append(event.hasAltitude() ? event.getAltitude() - geoid.height(event.getLatitude(), event.getLongitude()) : Double.NaN);
            }
            if (dataV != null)
                dataV.append(event.hasSpeed() ? event.getSpeed() : Double.NaN);
            if (dataDir != null)
                dataDir.append(event.hasBearing() ? event.getBearing() : Double.NaN);

            if (dataAccuracy != null)
                dataAccuracy.append(event.getAccuracy());
            if (dataZAccuracy != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    dataZAccuracy.append(event.getVerticalAccuracyMeters());
                } else
                    dataZAccuracy.append(0); //Older Android does not provide vertical accuracy
            }
            if (dataSatellites != null) {
                if (event.getExtras() != null)  //Not sure why this might happen, but there seem to be rare cases in which this leads to a crash
                    dataSatellites.append(event.getExtras().getInt("satellites", 0));
                else
                    dataSatellites.append(-1);
            }
            if (usesGnssSummary())
                appendGnssSummary();
            appendFixDetails(event);
        } finally {
            dataLock.unlock();
        }

    }

    //The extras of the fix, from the fix extras and newer Location fields. Call with dataLock held.
    private void appendFixDetails(Location event) {
        Bundle extras = event.getExtras();
        if (dataMeanCn0 != null)
            dataMeanCn0.append(extras != null && extras.containsKey("meanCn0") ? extras.getInt("meanCn0") : Double.NaN);
        if (dataMaxCn0 != null)
            dataMaxCn0.append(extras != null && extras.containsKey("maxCn0") ? extras.getInt("maxCn0") : Double.NaN);
        if (dataUtc != null)
            dataUtc.append(event.getTime() / 1000.0);
        if (dataVAccuracy != null)
            dataVAccuracy.append(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && event.hasSpeedAccuracy() ? event.getSpeedAccuracyMetersPerSecond() : Double.NaN);
        if (dataDirAccuracy != null)
            dataDirAccuracy.append(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && event.hasBearingAccuracy() ? event.getBearingAccuracyDegrees() : Double.NaN);
        if (dataZMsl != null)
            dataZMsl.append(Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && event.hasMslAltitude() ? event.getMslAltitudeMeters() : Double.NaN);
        if (dataZMslAccuracy != null)
            dataZMslAccuracy.append(Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && event.hasMslAltitudeAccuracy() ? event.getMslAltitudeAccuracyMeters() : Double.NaN);
        if (dataTUncertainty != null)
            dataTUncertainty.append(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && event.hasElapsedRealtimeUncertaintyNanos() ? event.getElapsedRealtimeUncertaintyNanos() * 1e-9 : Double.NaN);
        if (dataMock != null)
            dataMock.append((Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? event.isMock() : event.isFromMockProvider()) ? 1 : 0);
    }
}
