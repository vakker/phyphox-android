package de.rwth_aachen.phyphox;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.location.GnssStatus;
import android.location.Location;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.SystemClock;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;

//The location input's GnssStatus outputs, an extension of this build: a summary of the satellites used
//in the fix is appended with every fix, and the per-satellite table gets one row per tracked satellite
//per status report, on its own time base.
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class GpsInputGnssStatusTest {

    private static final String[] COMPONENTS = {"t", "lat", "lon", "satellites",
            "satellitesInView", "satellitesUsed", "cn0Mean", "cn0Max", "cn0Min",
            "svT", "svId", "svConstellation", "svCn0", "svUsed", "svElevation", "svAzimuth", "svCarrierFrequency"};

    //A location experiment that maps every component to a container of the same name.
    private static String experimentXml() {
        StringBuilder containers = new StringBuilder();
        StringBuilder outputs = new StringBuilder();
        for (String component : COMPONENTS) {
            containers.append("<container size=\"0\">").append(component).append("</container>");
            outputs.append("<output component=\"").append(component).append("\">").append(component).append("</output>");
        }
        return "<phyphox version=\"1.21\" locale=\"en\">"
                + "<title>GNSS status</title><category>Test</category><description>GnssStatus outputs</description>"
                + "<data-containers>" + containers + "</data-containers>"
                + "<input><location>" + outputs + "</location></input>"
                + "<views><view label=\"Values\"><value label=\"Latitude\"><input>lat</input></value></view></views>"
                + "<export><set name=\"Position\"><data name=\"t\">t</data></set></export>"
                + "</phyphox>";
    }

    private static PhyphoxExperiment loadAndStart(Experiment activity) {
        PhyphoxExperiment experiment = CorpusTestEnvironment.load(
                new ByteArrayInputStream(experimentXml().getBytes(StandardCharsets.UTF_8)), activity);
        assertTrue("experiment failed to load: " + experiment.message, experiment.loaded);
        assertNotNull(experiment.gpsIn);
        Shadows.shadowOf(locationManager(activity)).setProviderEnabled(LocationManager.GPS_PROVIDER, true);
        experiment.experimentTimeReference.registerEvent(ExperimentTimeReference.TimeMappingEvent.START);
        experiment.gpsIn.start();
        return experiment;
    }

    private static LocationManager locationManager(Context context) {
        return (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
    }

    //The platform builder (API 30+); Robolectric's GnssStatusBuilder relies on a hidden constructor that SDK 35 no longer has
    private static GnssStatus.Builder satellite(GnssStatus.Builder builder, int constellation, int svid, float cn0, boolean usedInFix, Float carrierFrequencyHz) {
        return builder.addSatellite(constellation, svid, cn0, 45f, 180f, true, true, usedInFix,
                carrierFrequencyHz != null, carrierFrequencyHz != null ? carrierFrequencyHz : 0f, false, 0f);
    }

    private static Location gpsFix(double latitude, double longitude) {
        Location fix = new Location(LocationManager.GPS_PROVIDER);
        fix.setLatitude(latitude);
        fix.setLongitude(longitude);
        fix.setAccuracy(3f);
        fix.setTime(System.currentTimeMillis());
        fix.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
        Bundle extras = new Bundle();
        extras.putInt("satellites", 2);
        fix.setExtras(extras);
        return fix;
    }

    private static double[] values(PhyphoxExperiment experiment, String buffer) {
        Double[] boxed = experiment.getBuffer(buffer).getArray();
        double[] result = new double[boxed.length];
        for (int i = 0; i < boxed.length; i++)
            result[i] = boxed[i];
        return result;
    }

    @Test
    public void fixSummarizesTheSatellitesUsedAndTheTableListsEveryOne() {
        Experiment activity = CorpusTestEnvironment.fullyEquippedActivity();
        PhyphoxExperiment experiment = loadAndStart(activity);

        //Three tracked satellites, two of them used in the fix. Only the GPS one reports a carrier frequency.
        GnssStatus.Builder report = new GnssStatus.Builder();
        satellite(report, GnssStatus.CONSTELLATION_GPS, 5, 40f, true, 1575420000f);
        satellite(report, GnssStatus.CONSTELLATION_GALILEO, 12, 30f, true, null);
        satellite(report, GnssStatus.CONSTELLATION_GLONASS, 3, 20f, false, null);
        Shadows.shadowOf(locationManager(activity)).simulateGnssStatus(report.build());
        ShadowLooper.idleMainLooper();
        Shadows.shadowOf(locationManager(activity)).simulateLocation(gpsFix(50.78, 6.07));
        ShadowLooper.idleMainLooper();

        assertArrayEquals(new double[]{50.78}, values(experiment, "lat"), 1e-9);
        assertArrayEquals(new double[]{2}, values(experiment, "satellites"), 0);
        assertArrayEquals(new double[]{3}, values(experiment, "satellitesInView"), 0);
        assertArrayEquals(new double[]{2}, values(experiment, "satellitesUsed"), 0);
        assertArrayEquals(new double[]{35}, values(experiment, "cn0Mean"), 1e-6);
        assertArrayEquals(new double[]{40}, values(experiment, "cn0Max"), 1e-6);
        assertArrayEquals(new double[]{30}, values(experiment, "cn0Min"), 1e-6);

        assertArrayEquals(new double[]{5, 12, 3}, values(experiment, "svId"), 0);
        assertArrayEquals(new double[]{GnssStatus.CONSTELLATION_GPS, GnssStatus.CONSTELLATION_GALILEO, GnssStatus.CONSTELLATION_GLONASS}, values(experiment, "svConstellation"), 0);
        assertArrayEquals(new double[]{40, 30, 20}, values(experiment, "svCn0"), 1e-6);
        assertArrayEquals(new double[]{1, 1, 0}, values(experiment, "svUsed"), 0);
        assertArrayEquals(new double[]{45, 45, 45}, values(experiment, "svElevation"), 1e-6);
        assertArrayEquals(new double[]{180, 180, 180}, values(experiment, "svAzimuth"), 1e-6);
        double[] carrierFrequency = values(experiment, "svCarrierFrequency");
        assertEquals(3, carrierFrequency.length);
        assertEquals(1575420000, carrierFrequency[0], 200); //float precision at 1.5 GHz is 128 Hz
        assertTrue("missing carrier frequency must be NaN", Double.isNaN(carrierFrequency[1]));
        assertTrue("missing carrier frequency must be NaN", Double.isNaN(carrierFrequency[2]));
        //The rows of one report share its time
        double[] svT = values(experiment, "svT");
        assertEquals(3, svT.length);
        assertEquals(svT[0], svT[1], 0);
        assertEquals(svT[0], svT[2], 0);
        assertTrue("report time must be experiment time", svT[0] >= 0);
    }

    @Test
    public void fixBeforeAnyReportMarksTheSummaryUnknown() {
        Experiment activity = CorpusTestEnvironment.fullyEquippedActivity();
        PhyphoxExperiment experiment = loadAndStart(activity);

        Shadows.shadowOf(locationManager(activity)).simulateLocation(gpsFix(50.78, 6.07));
        ShadowLooper.idleMainLooper();

        assertArrayEquals(new double[]{50.78}, values(experiment, "lat"), 1e-9);
        assertArrayEquals(new double[]{-1}, values(experiment, "satellitesInView"), 0);
        assertArrayEquals(new double[]{-1}, values(experiment, "satellitesUsed"), 0);
        for (String buffer : new String[]{"cn0Mean", "cn0Max", "cn0Min"}) {
            double[] cn0 = values(experiment, buffer);
            assertEquals(buffer + " must have one entry per fix", 1, cn0.length);
            assertTrue(buffer + " must be NaN without a report", Double.isNaN(cn0[0]));
        }
        assertEquals("no report, no table rows", 0, values(experiment, "svT").length);
    }

    @Test
    public void reportWithoutUsedSatellitesGivesCountsButNoCn0() {
        Experiment activity = CorpusTestEnvironment.fullyEquippedActivity();
        PhyphoxExperiment experiment = loadAndStart(activity);

        Shadows.shadowOf(locationManager(activity)).simulateGnssStatus(
                satellite(new GnssStatus.Builder(), GnssStatus.CONSTELLATION_BEIDOU, 7, 25f, false, null).build());
        ShadowLooper.idleMainLooper();
        Shadows.shadowOf(locationManager(activity)).simulateLocation(gpsFix(50.78, 6.07));
        ShadowLooper.idleMainLooper();

        assertArrayEquals(new double[]{1}, values(experiment, "satellitesInView"), 0);
        assertArrayEquals(new double[]{0}, values(experiment, "satellitesUsed"), 0);
        assertTrue(Double.isNaN(values(experiment, "cn0Mean")[0]));
        assertTrue(Double.isNaN(values(experiment, "cn0Max")[0]));
        assertTrue(Double.isNaN(values(experiment, "cn0Min")[0]));
        assertArrayEquals(new double[]{25}, values(experiment, "svCn0"), 1e-6);
        assertArrayEquals(new double[]{0}, values(experiment, "svUsed"), 0);
    }

    //The bundled logger experiment (src/main/assets-local) uses every new component; it has to load on the real parser.
    @Test
    public void bundledLoggerExperimentLoads() throws Exception {
        File asset = new File("src/main/assets-local/experiments/gnss-logger.phyphox");
        if (!asset.isFile())
            asset = new File("app/src/main/assets-local/experiments/gnss-logger.phyphox");
        assertTrue("bundled experiment missing: " + asset.getAbsolutePath(), asset.isFile());

        PhyphoxExperiment experiment = CorpusTestEnvironment.load(asset, CorpusTestEnvironment.fullyEquippedActivity());
        assertTrue("gnss-logger.phyphox failed to load: " + experiment.message, experiment.loaded);
        assertNotNull(experiment.gpsIn.dataCn0Mean);
        assertNotNull(experiment.gpsIn.dataSvCn0);
        assertNotNull(experiment.gpsIn.dataSvCarrierFrequency);
    }
}
