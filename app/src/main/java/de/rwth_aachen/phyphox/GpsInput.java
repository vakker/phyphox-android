package de.rwth_aachen.phyphox;

import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.location.LocationProvider;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.core.location.GnssStatusCompat;
import androidx.core.location.LocationManagerCompat;

import java.io.Serializable;
import java.util.Arrays;
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
    //A report has one entry per signal, a satellite on one band: a multi-band receiver lists most satellites twice
    //or more, so the satellite counts take each satellite once and the signal counts take the entries.
    public DataBuffer dataSatellitesInView; //Satellites tracked by the GNSS engine
    public DataBuffer dataSatellitesUsed; //Satellites with a signal used in the fix
    public DataBuffer dataSignalsInView; //Signals tracked by the GNSS engine, the entries of the report
    public DataBuffer dataSignalsUsed; //Signals used in the fix
    public DataBuffer dataCn0Mean; //Mean carrier-to-noise density (dB-Hz) of the signals used in the fix
    public DataBuffer dataCn0Max; //Highest C/N0 (dB-Hz) among the signals used in the fix
    public DataBuffer dataCn0Min; //Lowest C/N0 (dB-Hz) among the signals used in the fix
    //Per-satellite table: one row per tracked signal per status report, on its own time base (not aligned with the fixes)
    public DataBuffer dataSvT; //Experiment time of the status report
    public DataBuffer dataSvId; //Satellite id within its constellation
    public DataBuffer dataSvConstellation; //GnssStatus constellation code: 1 GPS, 2 SBAS, 3 GLONASS, 4 QZSS, 5 BeiDou, 6 Galileo, 7 NavIC
    public DataBuffer dataSvCn0; //C/N0 (dB-Hz)
    public DataBuffer dataSvUsed; //1 if the signal was used in the fix, 0 otherwise
    public DataBuffer dataSvElevation; //Elevation (degrees)
    public DataBuffer dataSvAzimuth; //Azimuth (degrees)
    public DataBuffer dataSvCarrierFrequency; //Carrier frequency (Hz), NaN if the device does not report it; tells the signals of a satellite apart

    transient private LocationManager locationManager; //Hold the sensor manager
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

    //The constructor
    protected GpsInput(Vector<DataOutput> buffers, Lock lock, ExperimentTimeReference experimentTimeReference) {
        this.dataLock = lock;
        this.experimentTimeReference = experimentTimeReference;

        //Store the buffer references if any
        if (buffers == null)
            return;

        buffers.setSize(26);
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
            this.dataSignalsInView = buffers.get(24).buffer;
        if (buffers.get(25) != null)
            this.dataSignalsUsed = buffers.get(25).buffer;
    }

    //True if any output needs the GnssStatus reports, so the callback is only registered when an experiment uses it
    private boolean usesGnssStatus() {
        return usesGnssSummary() || usesSatelliteTable();
    }

    private boolean usesGnssSummary() {
        return dataSatellitesInView != null || dataSatellitesUsed != null || dataCn0Mean != null || dataCn0Max != null || dataCn0Min != null
                || dataSignalsInView != null || dataSignalsUsed != null;
    }

    private boolean usesSatelliteTable() {
        return dataSvT != null || dataSvId != null || dataSvConstellation != null || dataSvCn0 != null || dataSvUsed != null || dataSvElevation != null || dataSvAzimuth != null || dataSvCarrierFrequency != null;
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
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0, 0, locationListener);
            if (!forceGNSS && locationManager.getAllProviders().contains(LocationManager.NETWORK_PROVIDER))
                locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 0, 0, locationListener);
            //Satellite status reports arrive on the main thread like the fixes. The compat version falls back to GpsStatus before API 24.
            if (usesGnssStatus())
                LocationManagerCompat.registerGnssStatusCallback(locationManager, gnssStatusCallback, new Handler(Looper.getMainLooper()));
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
        lastGnssStatus = null;
    }

    private final GnssStatusCompat.Callback gnssStatusCallback = new GnssStatusCompat.Callback() {
        @Override
        public void onSatelliteStatusChanged(GnssStatusCompat status) {
            onGnssStatusChanged(status);
        }
    };

    //Called with every satellite status report, usually once per second while the GNSS engine runs.
    //Keep it for the summary of the next fix and append one row per signal to the satellite table.
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
        int satellitesInView = -1;
        int satellitesUsed = -1;
        double sum = 0;
        double max = Double.NaN;
        double min = Double.NaN;
        if (status != null) {
            inView = status.getSatelliteCount();
            used = 0;
            //The satellite of each signal (constellation and id) as a key, for all signals and for those used in the fix
            int[] satellites = new int[inView];
            int[] usedSatellites = new int[inView];
            for (int i = 0; i < inView; i++) {
                int satellite = (status.getConstellationType(i) << 16) | (status.getSvid(i) & 0xffff);
                satellites[i] = satellite;
                if (!status.usedInFix(i))
                    continue;
                double cn0 = status.getCn0DbHz(i);
                usedSatellites[used] = satellite;
                used++;
                sum += cn0;
                if (Double.isNaN(max) || cn0 > max)
                    max = cn0;
                if (Double.isNaN(min) || cn0 < min)
                    min = cn0;
            }
            satellitesInView = distinct(satellites, inView);
            satellitesUsed = distinct(usedSatellites, used);
        }
        if (dataSatellitesInView != null)
            dataSatellitesInView.append(satellitesInView);
        if (dataSatellitesUsed != null)
            dataSatellitesUsed.append(satellitesUsed);
        if (dataSignalsInView != null)
            dataSignalsInView.append(inView);
        if (dataSignalsUsed != null)
            dataSignalsUsed.append(used);
        if (dataCn0Mean != null)
            dataCn0Mean.append(used > 0 ? sum / used : Double.NaN);
        if (dataCn0Max != null)
            dataCn0Max.append(max);
        if (dataCn0Min != null)
            dataCn0Min.append(min);
    }

    //The number of different values among the first n of keys (sorts them)
    private static int distinct(int[] keys, int n) {
        Arrays.sort(keys, 0, n);
        int count = 0;
        for (int i = 0; i < n; i++)
            if (i == 0 || keys[i] != keys[i - 1])
                count++;
        return count;
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
        } finally {
            dataLock.unlock();
        }

    }
}
