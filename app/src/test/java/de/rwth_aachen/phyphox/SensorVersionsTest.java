package de.rwth_aachen.phyphox;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowSystemClock;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

//meta/sensors.csv (SensorVersions): from which experiment time on each sensor input delivered which Android sensor.
//The simulated device has the magnetometer in both versions and the gyroscope only calibrated.
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class SensorVersionsTest {

    private static final String EXPERIMENT =
            "<phyphox version=\"1.20\">"
                    + "<title>Sensor versions</title>"
                    + "<category>Test</category>"
                    + "<description>A sensor in two versions, one in one version, one without versions.</description>"
                    + "<data-containers>"
                    + "<container size=\"0\">mx</container>"
                    + "<container size=\"0\">gx</container>"
                    + "<container size=\"0\">p</container>"
                    + "</data-containers>"
                    + "<input>"
                    + "<sensor type=\"magnetic_field\"><output component=\"x\">mx</output></sensor>"
                    + "<sensor type=\"gyroscope\"><output component=\"x\">gx</output></sensor>"
                    + "<sensor type=\"pressure\"><output component=\"x\">p</output></sensor>"
                    + "</input>"
                    + "<views><view label=\"View\"><value label=\"v\"><input>mx</input></value></view></views>"
                    + "<export><set name=\"All\"><data name=\"mx\">mx</data></set></export>"
                    + "</phyphox>";

    private PhyphoxExperiment load(Experiment activity) {
        PhyphoxExperiment experiment = CorpusTestEnvironment.load(
                new ByteArrayInputStream(EXPERIMENT.getBytes(StandardCharsets.UTF_8)), activity);
        assertTrue("Test experiment failed to load: " + experiment.message, experiment.loaded);
        experiment.source = EXPERIMENT.getBytes(StandardCharsets.UTF_8); //As the list's loader keeps it, for a saved state
        return experiment;
    }

    //A start, two seconds of measuring and a stop
    private static void run(PhyphoxExperiment experiment) throws Exception {
        experiment.startAllIO();
        ShadowSystemClock.advanceBy(Duration.ofSeconds(2));
        experiment.stopAllIO();
    }

    private static void assertRow(SensorVersions.Row row, double time, String sensor, String calibrated) {
        assertEquals(time, row.time, 1e-6);
        assertEquals(sensor, row.sensor);
        assertEquals(sensor + " calibrated", calibrated, row.calibrated);
    }

    private static PhyphoxExperiment started(PhyphoxExperiment experiment) throws Exception {
        experiment.inputSensors.get(0).calibrated = true; //magnetometer
        experiment.inputSensors.get(1).calibrated = false; //gyroscope, whose uncalibrated version the device lacks
        run(experiment);
        return experiment;
    }

    @Test
    public void aSwitchBetweenStartsAddsARowFromThatStart() throws Exception {
        PhyphoxExperiment experiment = started(load(CorpusTestEnvironment.fullyEquippedActivity()));

        List<SensorVersions.Row> rows = experiment.sensorVersions.getRows();
        assertEquals(3, rows.size());
        assertRow(rows.get(0), 0, "magnetic_field", "true");
        assertRow(rows.get(1), 0, "gyroscope", "true"); //Asked uncalibrated, delivered calibrated
        assertRow(rows.get(2), 0, "pressure", ""); //Android has one version
        assertEquals("simulated sensor type 2", rows.get(0).name);

        //The menu's switch between two starts, then a start without one
        experiment.inputSensors.get(0).calibrated = false;
        run(experiment);
        run(experiment);

        rows = experiment.sensorVersions.getRows();
        assertEquals("Only the switched sensor gets a row, from the start after the switch", 4, rows.size());
        assertRow(rows.get(3), 2, "magnetic_field", "false");
        assertEquals("android.sensor.magnetic_field_uncalibrated", rows.get(3).type);
        assertEquals("simulated sensor type 14", rows.get(3).name);
    }

    @Test
    public void afterAClearTheNextStartRecordsEveryInputAgain() throws Exception {
        Experiment activity = CorpusTestEnvironment.fullyEquippedActivity();
        PhyphoxExperiment experiment = started(load(activity));

        //The menu's "Clear data"
        activity.experiment = experiment;
        activity.clearData(new ArrayList<>());
        assertTrue(experiment.sensorVersions.isEmpty());

        run(experiment);
        List<SensorVersions.Row> rows = experiment.sensorVersions.getRows();
        assertEquals(3, rows.size());
        assertRow(rows.get(0), 0, "magnetic_field", "true");
    }

    @Test
    public void theExportsCarryTheRecordOnceTheSensorsHaveStarted() throws Exception {
        Experiment activity = CorpusTestEnvironment.fullyEquippedActivity();
        PhyphoxExperiment experiment = load(activity);

        File unstarted = experiment.exporter.exportDirect(experiment.exporter.exportFormats[1], activity.getCacheDir(), false, "unstarted", activity);
        try (ZipFile zip = new ZipFile(unstarted)) {
            assertNull("No start, no record", zip.getEntry(SensorVersions.CSV));
        }

        started(experiment);
        File csv = experiment.exporter.exportDirect(experiment.exporter.exportFormats[1], activity.getCacheDir(), false, "csv", activity);
        try (ZipFile zip = new ZipFile(csv)) {
            assertEquals("\"experiment time\",\"sensor\",\"calibrated\",\"android type\",\"name\",\"vendor\"\n"
                            + "0.0E0,\"magnetic_field\",\"true\",\"android.sensor.magnetic_field\",\"simulated sensor type 2\",\"\"\n"
                            + "0.0E0,\"gyroscope\",\"true\",\"android.sensor.gyroscope\",\"simulated sensor type 4\",\"\"\n"
                            + "0.0E0,\"pressure\",\"\",\"android.sensor.pressure\",\"simulated sensor type 6\",\"\"\n",
                    text(zip, SensorVersions.CSV));
        }

        File xlsx = experiment.exporter.exportDirect(experiment.exporter.exportFormats[0], activity.getCacheDir(), false, "xlsx", activity);
        try (ZipFile zip = new ZipFile(xlsx)) {
            assertTrue(text(zip, "xl/workbook.xml"), text(zip, "xl/workbook.xml").contains("name=\"Metadata Sensors\""));
        }
    }

    @Test
    public void aSavedStateKeepsTheRecord() throws Exception {
        Experiment activity = CorpusTestEnvironment.fullyEquippedActivity();
        PhyphoxExperiment experiment = started(load(activity));
        experiment.inputSensors.get(0).calibrated = false;
        run(experiment);

        File dir = new File(activity.getFilesDir(), "state" + SavedState.DIRECTORY_SUFFIX);
        SavedState.writeDirectory(experiment, "Sensor versions", activity, dir);
        assertTrue(new File(dir, SensorVersions.CSV).isFile());

        PhyphoxExperiment restored = load(activity);
        assertNull(SavedState.restore(restored, dir));
        List<SensorVersions.Row> expected = experiment.sensorVersions.getRows();
        List<SensorVersions.Row> rows = restored.sensorVersions.getRows();
        assertEquals(expected.size(), rows.size());
        for (int i = 0; i < rows.size(); i++) {
            assertEquals(expected.get(i).time, rows.get(i).time, 0);
            assertEquals(expected.get(i).sensor, rows.get(i).sensor);
            assertEquals(expected.get(i).calibrated, rows.get(i).calibrated);
            assertEquals(expected.get(i).type, rows.get(i).type);
            assertEquals(expected.get(i).name, rows.get(i).name);
            assertEquals(expected.get(i).vendor, rows.get(i).vendor);
        }
    }

    private static String text(ZipFile zip, String name) throws Exception {
        ZipEntry entry = zip.getEntry(name);
        assertTrue(name + " is missing", entry != null);
        try (InputStream in = zip.getInputStream(entry)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int n;
            while ((n = in.read(buffer)) != -1)
                out.write(buffer, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
