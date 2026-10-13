package de.rwth_aachen.phyphox;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.location.GnssClock;
import android.location.GnssMeasurement;
import android.location.GnssMeasurementsEvent;
import android.location.GnssStatus;
import android.location.LocationManager;
import android.os.SystemClock;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.ReflectionHelpers.ClassParameter;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

//The location input's raw measurement outputs, an extension of this build: one clock row per measurement event
//and one measurement row per signal, at the event's time, with the receiver's GPS time computed exactly
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class GpsInputGnssMeasurementsTest {

    //A location experiment that maps every raw component, and lat, to a container of the same name
    private static String experimentXml() {
        StringBuilder containers = new StringBuilder("<container size=\"0\">lat</container>");
        StringBuilder outputs = new StringBuilder("<output component=\"lat\">lat</output>");
        for (String component : GpsInput.RAW_COMPONENTS) {
            containers.append("<container size=\"0\">").append(component).append("</container>");
            outputs.append("<output component=\"").append(component).append("\">").append(component).append("</output>");
        }
        return "<phyphox version=\"1.21\" locale=\"en\">"
                + "<title>GNSS raw</title><category>Test</category><description>Raw measurement outputs</description>"
                + "<data-containers>" + containers + "</data-containers>"
                + "<input><location>" + outputs + "</location></input>"
                + "<views><view label=\"Values\"><value label=\"Latitude\"><input>lat</input></value></view></views>"
                + "<export><set name=\"Clock\"><data name=\"t\">clockT</data></set></export>"
                + "</phyphox>";
    }

    private static PhyphoxExperiment loadAndStart(Experiment activity) {
        PhyphoxExperiment experiment = CorpusTestEnvironment.load(
                new ByteArrayInputStream(experimentXml().getBytes(StandardCharsets.UTF_8)), activity);
        assertTrue("experiment failed to load: " + experiment.message, experiment.loaded);
        Shadows.shadowOf(locationManager(activity)).setProviderEnabled(LocationManager.GPS_PROVIDER, true);
        experiment.experimentTimeReference.registerEvent(ExperimentTimeReference.TimeMappingEvent.START);
        experiment.gpsIn.start();
        return experiment;
    }

    private static LocationManager locationManager(Context context) {
        return (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
    }

    //GnssClock and GnssMeasurement have hidden constructors and setters; the receiver fills them on a phone
    private static void set(Object target, String setter, Class<?> type, Object value) {
        ReflectionHelpers.callInstanceMethod(target, setter, ClassParameter.from(type, value));
    }

    private static GnssClock clock(long timeNanos, Long fullBiasNanos, double biasNanos, long elapsedRealtimeNanos) {
        GnssClock clock = ReflectionHelpers.callConstructor(GnssClock.class);
        set(clock, "setTimeNanos", long.class, timeNanos);
        if (fullBiasNanos != null)
            set(clock, "setFullBiasNanos", long.class, fullBiasNanos);
        set(clock, "setBiasNanos", double.class, biasNanos);
        set(clock, "setBiasUncertaintyNanos", double.class, 20.0);
        set(clock, "setDriftNanosPerSecond", double.class, 3.5);
        set(clock, "setDriftUncertaintyNanosPerSecond", double.class, 0.5);
        set(clock, "setTimeUncertaintyNanos", double.class, 0.0);
        set(clock, "setLeapSecond", int.class, 18);
        set(clock, "setHardwareClockDiscontinuityCount", int.class, 2);
        set(clock, "setElapsedRealtimeNanos", long.class, elapsedRealtimeNanos);
        return clock;
    }

    private static GnssMeasurement measurement(int constellation, int svid, float carrierFrequencyHz, String codeType, double cn0) {
        GnssMeasurement m = ReflectionHelpers.callConstructor(GnssMeasurement.class);
        set(m, "setConstellationType", int.class, constellation);
        set(m, "setSvid", int.class, svid);
        set(m, "setCarrierFrequencyHz", float.class, carrierFrequencyHz);
        if (codeType != null)
            set(m, "setCodeType", String.class, codeType);
        set(m, "setCn0DbHz", double.class, cn0);
        set(m, "setTimeOffsetNanos", double.class, 0.25);
        set(m, "setState", int.class, GnssMeasurement.STATE_CODE_LOCK | GnssMeasurement.STATE_TOW_DECODED);
        set(m, "setReceivedSvTimeNanos", long.class, 345678912345678L);
        set(m, "setReceivedSvTimeUncertaintyNanos", long.class, 12L);
        set(m, "setPseudorangeRateMetersPerSecond", double.class, -512.25);
        set(m, "setPseudorangeRateUncertaintyMetersPerSecond", double.class, 0.05);
        set(m, "setAccumulatedDeltaRangeState", int.class, GnssMeasurement.ADR_STATE_VALID);
        set(m, "setAccumulatedDeltaRangeMeters", double.class, 12345678.125);
        set(m, "setAccumulatedDeltaRangeUncertaintyMeters", double.class, 0.002);
        set(m, "setMultipathIndicator", int.class, GnssMeasurement.MULTIPATH_INDICATOR_NOT_DETECTED);
        return m;
    }

    private static double[] values(PhyphoxExperiment experiment, String buffer) {
        Double[] boxed = experiment.getBuffer(buffer).getArray();
        double[] result = new double[boxed.length];
        for (int i = 0; i < boxed.length; i++)
            result[i] = boxed[i];
        return result;
    }

    @Test
    public void eventGivesAClockRowAndAMeasurementRowPerSignal() {
        Experiment activity = CorpusTestEnvironment.fullyEquippedActivity();
        PhyphoxExperiment experiment = loadAndStart(activity);

        //GPS week 2386, time of week 123456.789012345678 s, plus a bias of 0.25 ns subtracted from it
        long gpsNanos = 2386L * 604800L * 1000000000L + 123456789012345L;
        long timeNanos = 98765432100000L;
        GnssClock clock = clock(timeNanos, timeNanos - gpsNanos, 0.25, SystemClock.elapsedRealtimeNanos());
        GnssMeasurementsEvent event = new GnssMeasurementsEvent.Builder()
                .setClock(clock)
                .setMeasurements(Arrays.asList(
                        measurement(GnssStatus.CONSTELLATION_GPS, 5, 1575.42e6f, "C", 41.5),
                        measurement(GnssStatus.CONSTELLATION_GPS, 5, 1176.45e6f, "Q", 38.0),
                        measurement(GnssStatus.CONSTELLATION_GALILEO, 12, 1575.42e6f, null, 35.0)))
                .build();
        Shadows.shadowOf(locationManager(activity)).simulateGnssMeasurementsEvent(event);
        ShadowLooper.idleMainLooper();

        double[] clockT = values(experiment, "clockT");
        assertEquals(1, clockT.length);
        assertTrue("event time must be experiment time", clockT[0] >= 0);
        assertArrayEquals(new double[]{timeNanos}, values(experiment, "clockTimeNanos"), 0);
        assertArrayEquals(new double[]{18}, values(experiment, "clockLeapSecond"), 0);
        assertArrayEquals(new double[]{0.25}, values(experiment, "clockBiasNanos"), 0);
        assertArrayEquals(new double[]{3.5}, values(experiment, "clockDrift"), 0);
        assertArrayEquals(new double[]{2}, values(experiment, "clockDiscontinuity"), 0);
        assertArrayEquals(new double[]{2386}, values(experiment, "clockGpsWeek"), 0);
        assertArrayEquals(new double[]{123456.789012345 - 0.25e-9}, values(experiment, "clockGpsTow"), 1e-10);

        assertArrayEquals(new double[]{clockT[0], clockT[0], clockT[0]}, values(experiment, "measT"), 0);
        assertArrayEquals(new double[]{5, 5, 12}, values(experiment, "measSvid"), 0);
        assertArrayEquals(new double[]{GnssStatus.CONSTELLATION_GPS, GnssStatus.CONSTELLATION_GPS, GnssStatus.CONSTELLATION_GALILEO}, values(experiment, "measConstellation"), 0);
        double[] frequency = values(experiment, "measCarrierFrequency");
        assertEquals(1575.42e6, frequency[0], 200); //float precision
        assertEquals(1176.45e6, frequency[1], 200);
        double[] code = values(experiment, "measCodeType");
        assertEquals('C', code[0], 0);
        assertEquals('Q', code[1], 0);
        assertTrue("a missing code type must be NaN", Double.isNaN(code[2]));
        assertArrayEquals(new double[]{41.5, 38, 35}, values(experiment, "measCn0"), 0);
        assertArrayEquals(new double[]{345678912345678.0, 345678912345678.0, 345678912345678.0}, values(experiment, "measReceivedSvTime"), 0);
        assertArrayEquals(new double[]{0.25, 0.25, 0.25}, values(experiment, "measTimeOffset"), 0);
        assertArrayEquals(new double[]{-512.25, -512.25, -512.25}, values(experiment, "measPseudorangeRate"), 0);
        assertArrayEquals(new double[]{12345678.125, 12345678.125, 12345678.125}, values(experiment, "measAdr"), 0);
        assertArrayEquals(new double[]{GnssMeasurement.ADR_STATE_VALID, GnssMeasurement.ADR_STATE_VALID, GnssMeasurement.ADR_STATE_VALID}, values(experiment, "measAdrState"), 0);
        assertArrayEquals(new double[]{2, 2, 2}, values(experiment, "measMultipath"), 0);
        for (double baseband : values(experiment, "measBasebandCn0"))
            assertTrue("no baseband C/N0 reported, so NaN", Double.isNaN(baseband));
        for (double agc : values(experiment, "measAgc"))
            assertTrue("no AGC reported, so NaN", Double.isNaN(agc));
    }

    //Without GPS time (no full bias yet) the week and time of week are unknown, the rest is still recorded
    @Test
    public void clockWithoutFullBiasHasNoGpsTime() {
        Experiment activity = CorpusTestEnvironment.fullyEquippedActivity();
        PhyphoxExperiment experiment = loadAndStart(activity);

        GnssMeasurementsEvent event = new GnssMeasurementsEvent.Builder()
                .setClock(clock(1000L, null, 0, SystemClock.elapsedRealtimeNanos()))
                .setMeasurements(Arrays.asList(measurement(GnssStatus.CONSTELLATION_GPS, 7, 1575.42e6f, "C", 30)))
                .build();
        Shadows.shadowOf(locationManager(activity)).simulateGnssMeasurementsEvent(event);
        ShadowLooper.idleMainLooper();

        assertTrue(Double.isNaN(values(experiment, "clockGpsWeek")[0]));
        assertTrue(Double.isNaN(values(experiment, "clockGpsTow")[0]));
        assertTrue(Double.isNaN(values(experiment, "clockFullBiasNanos")[0]));
        assertArrayEquals(new double[]{7}, values(experiment, "measSvid"), 0);
    }

    @Test
    public void statusChangesAreRecorded() {
        Experiment activity = CorpusTestEnvironment.fullyEquippedActivity();
        PhyphoxExperiment experiment = loadAndStart(activity);

        Shadows.shadowOf(locationManager(activity)).simulateGnssMeasurementsStatus(GnssMeasurementsEvent.Callback.STATUS_READY);
        ShadowLooper.idleMainLooper();

        assertArrayEquals(new double[]{GnssMeasurementsEvent.Callback.STATUS_READY}, values(experiment, "measStatus"), 0);
    }

    //Stopping unregisters: a later event adds nothing
    @Test
    public void nothingArrivesAfterStop() {
        Experiment activity = CorpusTestEnvironment.fullyEquippedActivity();
        PhyphoxExperiment experiment = loadAndStart(activity);
        experiment.gpsIn.stop();

        GnssMeasurementsEvent event = new GnssMeasurementsEvent.Builder()
                .setClock(clock(1000L, null, 0, SystemClock.elapsedRealtimeNanos()))
                .build();
        Shadows.shadowOf(locationManager(activity)).simulateGnssMeasurementsEvent(event);
        ShadowLooper.idleMainLooper();

        assertEquals(0, values(experiment, "clockT").length);
    }

    //The bundled raw logger experiment (src/main/assets-local) has to load on the real parser
    @Test
    public void bundledRawLoggerExperimentLoads() throws Exception {
        File asset = new File("src/main/assets-local/experiments/gnss-raw-logger.phyphox");
        if (!asset.isFile())
            asset = new File("app/src/main/assets-local/experiments/gnss-raw-logger.phyphox");
        assertTrue("bundled experiment missing: " + asset.getAbsolutePath(), asset.isFile());

        PhyphoxExperiment experiment = CorpusTestEnvironment.load(asset, CorpusTestEnvironment.fullyEquippedActivity());
        assertTrue("gnss-raw-logger.phyphox failed to load: " + experiment.message, experiment.loaded);
        for (DataBuffer buffer : experiment.gpsIn.rawBuffers())
            assertNotNull("the raw logger maps every raw component", buffer);
    }
}
