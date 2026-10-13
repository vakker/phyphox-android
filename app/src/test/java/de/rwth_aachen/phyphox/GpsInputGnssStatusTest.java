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

//The location input's GnssStatus outputs, an extension of this build: a summary of the satellites and
//signals used in the fix is appended with every fix, and the per-satellite table gets one row per tracked
//signal per status report, on its own time base.
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class GpsInputGnssStatusTest {

    private static final String[] COMPONENTS = {"t", "lat", "lon", "satellites",
            "satellitesInView", "satellitesUsed", "cn0Mean", "cn0Max", "cn0Min",
            "svT", "svId", "svConstellation", "svCn0", "svUsed", "svElevation", "svAzimuth", "svCarrierFrequency",
            "meanCn0", "maxCn0", "utc", "vAccuracy", "dirAccuracy", "zMsl", "zMslAccuracy", "tUncertainty", "mock",
            "svEphemeris", "svAlmanac", "svBasebandCn0", "ttff",
            "ggaT", "ggaTime", "ggaQuality", "ggaSatellites", "ggaHdop", "ggaAltitude", "ggaGeoidSeparation",
            "gsaT", "gsaMode", "gsaPdop", "gsaHdop", "gsaVdop", "gsaSystem",
            "signalsInView", "signalsUsed"};

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
        assertArrayEquals(new double[]{3}, values(experiment, "signalsInView"), 0);
        assertArrayEquals(new double[]{2}, values(experiment, "signalsUsed"), 0);
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
        assertArrayEquals(new double[]{-1}, values(experiment, "signalsInView"), 0);
        assertArrayEquals(new double[]{-1}, values(experiment, "signalsUsed"), 0);
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
        assertArrayEquals(new double[]{1}, values(experiment, "signalsInView"), 0);
        assertArrayEquals(new double[]{0}, values(experiment, "signalsUsed"), 0);
        assertTrue(Double.isNaN(values(experiment, "cn0Mean")[0]));
        assertTrue(Double.isNaN(values(experiment, "cn0Max")[0]));
        assertTrue(Double.isNaN(values(experiment, "cn0Min")[0]));
        assertArrayEquals(new double[]{25}, values(experiment, "svCn0"), 1e-6);
        assertArrayEquals(new double[]{0}, values(experiment, "svUsed"), 0);
    }

    @Test
    public void fixCarriesItsExtrasAndTheNewerLocationFields() {
        Experiment activity = CorpusTestEnvironment.fullyEquippedActivity();
        PhyphoxExperiment experiment = loadAndStart(activity);

        Location fix = gpsFix(50.78, 6.07);
        fix.setTime(1760000000123L);
        fix.getExtras().putInt("meanCn0", 33);
        fix.getExtras().putInt("maxCn0", 41);
        fix.setSpeedAccuracyMetersPerSecond(0.5f);
        fix.setBearingAccuracyDegrees(2f);
        fix.setMslAltitudeMeters(123.4);
        fix.setMslAltitudeAccuracyMeters(5f);
        fix.setElapsedRealtimeUncertaintyNanos(2.5e6);
        fix.setMock(true);
        Shadows.shadowOf(locationManager(activity)).simulateLocation(fix);
        ShadowLooper.idleMainLooper();

        assertArrayEquals(new double[]{33}, values(experiment, "meanCn0"), 0);
        assertArrayEquals(new double[]{41}, values(experiment, "maxCn0"), 0);
        assertArrayEquals(new double[]{1760000000.123}, values(experiment, "utc"), 1e-6);
        assertArrayEquals(new double[]{0.5}, values(experiment, "vAccuracy"), 1e-6);
        assertArrayEquals(new double[]{2}, values(experiment, "dirAccuracy"), 1e-6);
        assertArrayEquals(new double[]{123.4}, values(experiment, "zMsl"), 1e-9);
        assertArrayEquals(new double[]{5}, values(experiment, "zMslAccuracy"), 1e-6);
        assertArrayEquals(new double[]{0.0025}, values(experiment, "tUncertainty"), 1e-12);
        assertArrayEquals(new double[]{1}, values(experiment, "mock"), 0);
    }

    @Test
    public void fixWithoutThemGivesNaNAndNoMock() {
        Experiment activity = CorpusTestEnvironment.fullyEquippedActivity();
        PhyphoxExperiment experiment = loadAndStart(activity);

        Shadows.shadowOf(locationManager(activity)).simulateLocation(gpsFix(50.78, 6.07));
        ShadowLooper.idleMainLooper();

        for (String buffer : new String[]{"meanCn0", "maxCn0", "vAccuracy", "dirAccuracy", "zMsl", "zMslAccuracy", "tUncertainty"}) {
            double[] value = values(experiment, buffer);
            assertEquals(buffer + " must have one entry per fix", 1, value.length);
            assertTrue(buffer + " must be NaN when the fix lacks it", Double.isNaN(value[0]));
        }
        assertArrayEquals(new double[]{0}, values(experiment, "mock"), 0);
        assertEquals("the fix's own time must be kept", 1, values(experiment, "utc").length);
    }

    @Test
    public void tableMarksEphemerisAlmanacAndBasebandCn0() {
        Experiment activity = CorpusTestEnvironment.fullyEquippedActivity();
        PhyphoxExperiment experiment = loadAndStart(activity);

        GnssStatus.Builder report = new GnssStatus.Builder();
        report.addSatellite(GnssStatus.CONSTELLATION_GPS, 5, 40f, 45f, 180f, true, false, true, false, 0f, true, 37f);
        report.addSatellite(GnssStatus.CONSTELLATION_GALILEO, 12, 30f, 45f, 180f, false, true, false, false, 0f, false, 0f);
        Shadows.shadowOf(locationManager(activity)).simulateGnssStatus(report.build());
        ShadowLooper.idleMainLooper();

        assertArrayEquals(new double[]{1, 0}, values(experiment, "svEphemeris"), 0);
        assertArrayEquals(new double[]{0, 1}, values(experiment, "svAlmanac"), 0);
        double[] baseband = values(experiment, "svBasebandCn0");
        assertEquals(2, baseband.length);
        assertEquals(37, baseband[0], 1e-6);
        assertTrue("missing baseband C/N0 must be NaN", Double.isNaN(baseband[1]));
    }

    @Test
    public void firstFixGivesTheTimeToFirstFix() {
        Experiment activity = CorpusTestEnvironment.fullyEquippedActivity();
        PhyphoxExperiment experiment = loadAndStart(activity);

        Shadows.shadowOf(locationManager(activity)).simulateGnssStatusFirstFix(4200);
        ShadowLooper.idleMainLooper();

        assertArrayEquals(new double[]{4.2}, values(experiment, "ttff"), 1e-9);
    }

    //One GGA row and one GSA row per sentence, each table on its own time base; other sentences are not recorded
    @Test
    public void nmeaSentencesFillTheirTables() {
        Experiment activity = CorpusTestEnvironment.fullyEquippedActivity();
        PhyphoxExperiment experiment = loadAndStart(activity);

        long now = System.currentTimeMillis();
        Shadows.shadowOf(locationManager(activity)).simulateNmeaMessage("$GNGGA,123519.50,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,*72", now);
        Shadows.shadowOf(locationManager(activity)).simulateNmeaMessage("$GNGSA,A,3,04,05,09,12,24,,,,,,,,2.5,1.3,2.1,1*3A", now);
        Shadows.shadowOf(locationManager(activity)).simulateNmeaMessage("$GNGSA,A,3,65,66,,,,,,,,,,,2.5,1.3,2.1,2*37", now);
        Shadows.shadowOf(locationManager(activity)).simulateNmeaMessage("$GPRMC,123519,A,4807.038,N,01131.000,E,022.4,084.4,230394,003.1,W*6A", now);
        ShadowLooper.idleMainLooper();

        assertEquals(1, values(experiment, "ggaT").length);
        assertArrayEquals(new double[]{45319.5}, values(experiment, "ggaTime"), 1e-9);
        assertArrayEquals(new double[]{1}, values(experiment, "ggaQuality"), 0);
        assertArrayEquals(new double[]{8}, values(experiment, "ggaSatellites"), 0);
        assertArrayEquals(new double[]{0.9}, values(experiment, "ggaHdop"), 1e-9);
        assertArrayEquals(new double[]{545.4}, values(experiment, "ggaAltitude"), 1e-9);
        assertArrayEquals(new double[]{46.9}, values(experiment, "ggaGeoidSeparation"), 1e-9);

        assertEquals(2, values(experiment, "gsaT").length);
        assertArrayEquals(new double[]{3, 3}, values(experiment, "gsaMode"), 0);
        assertArrayEquals(new double[]{2.5, 2.5}, values(experiment, "gsaPdop"), 1e-9);
        assertArrayEquals(new double[]{1.3, 1.3}, values(experiment, "gsaHdop"), 1e-9);
        assertArrayEquals(new double[]{2.1, 2.1}, values(experiment, "gsaVdop"), 1e-9);
        assertArrayEquals(new double[]{1, 2}, values(experiment, "gsaSystem"), 0);
    }

    //A report lists every signal: a satellite on two bands is one satellite and two signals, and it counts as
    //used if one of its signals is. The same id in two constellations is two satellites.
    @Test
    public void aSatelliteOnTwoBandsCountsOnceAndItsSignalsTwice() {
        Experiment activity = CorpusTestEnvironment.fullyEquippedActivity();
        PhyphoxExperiment experiment = loadAndStart(activity);

        GnssStatus.Builder report = new GnssStatus.Builder();
        //One band after the other, as chipsets may list them, so the signals of a satellite are not neighbours
        satellite(report, GnssStatus.CONSTELLATION_GPS, 5, 40f, true, 1575420000f); //L1
        satellite(report, GnssStatus.CONSTELLATION_GALILEO, 12, 30f, true, 1575420000f); //E1
        satellite(report, GnssStatus.CONSTELLATION_BEIDOU, 5, 22f, false, 1561098000f); //Same id as the GPS one
        satellite(report, GnssStatus.CONSTELLATION_GPS, 5, 36f, true, 1176450000f); //L5
        satellite(report, GnssStatus.CONSTELLATION_GALILEO, 12, 26f, false, 1176450000f); //E5a, not used
        Shadows.shadowOf(locationManager(activity)).simulateGnssStatus(report.build());
        ShadowLooper.idleMainLooper();
        Shadows.shadowOf(locationManager(activity)).simulateLocation(gpsFix(50.78, 6.07));
        ShadowLooper.idleMainLooper();

        assertArrayEquals(new double[]{3}, values(experiment, "satellitesInView"), 0);
        assertArrayEquals(new double[]{2}, values(experiment, "satellitesUsed"), 0);
        assertArrayEquals(new double[]{5}, values(experiment, "signalsInView"), 0);
        assertArrayEquals(new double[]{3}, values(experiment, "signalsUsed"), 0);
        //C/N0 over the signals used
        assertArrayEquals(new double[]{(40 + 36 + 30) / 3.0}, values(experiment, "cn0Mean"), 1e-6);
        assertArrayEquals(new double[]{40}, values(experiment, "cn0Max"), 1e-6);
        assertArrayEquals(new double[]{30}, values(experiment, "cn0Min"), 1e-6);
        //The table keeps a row per signal
        assertArrayEquals(new double[]{5, 12, 5, 5, 12}, values(experiment, "svId"), 0);
        assertArrayEquals(new double[]{1, 1, 0, 1, 0}, values(experiment, "svUsed"), 0);
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
        assertNotNull(experiment.gpsIn.dataMeanCn0);
        assertNotNull(experiment.gpsIn.dataUtc);
        assertNotNull(experiment.gpsIn.dataZMsl);
        assertNotNull(experiment.gpsIn.dataMock);
        assertNotNull(experiment.gpsIn.dataSvBasebandCn0);
        assertNotNull(experiment.gpsIn.dataTtff);
        assertNotNull(experiment.gpsIn.dataSignalsInView);
        assertNotNull(experiment.gpsIn.dataSignalsUsed);
    }
}
