package de.rwth_aachen.phyphox;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

//This build starts a sensor without preferUncalibrated in its uncalibrated version (upstream: calibrated); an
//experiment that asks for the calibrated one gets it (SensorPreferUncalibratedTest).
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class SensorUncalibratedDefaultTest {

    private static final String EXPERIMENT =
            "<phyphox version=\"1.21\">"
                    + "<title>Uncalibrated by default</title>"
                    + "<category>Test</category>"
                    + "<description>The three sensors with an uncalibrated version, without the attribute.</description>"
                    + "<data-containers>"
                    + "<container size=\"0\">ax</container>"
                    + "<container size=\"0\">gx</container>"
                    + "<container size=\"0\">mx</container>"
                    + "<container size=\"0\">cx</container>"
                    + "</data-containers>"
                    + "<input>"
                    + "<sensor type=\"accelerometer\"><output component=\"x\">ax</output></sensor>"
                    + "<sensor type=\"gyroscope\"><output component=\"x\">gx</output></sensor>"
                    + "<sensor type=\"magnetic_field\"><output component=\"x\">mx</output></sensor>"
                    + "<sensor type=\"magnetic_field\" preferUncalibrated=\"false\"><output component=\"x\">cx</output></sensor>"
                    + "</input>"
                    + "<views><view label=\"View\"><value label=\"v\"><input>ax</input></value></view></views>"
                    + "<export><set name=\"All\"><data name=\"ax\">ax</data></set></export>"
                    + "</phyphox>";

    @Test
    public void aSensorWithoutTheAttributeStartsUncalibrated() {
        PhyphoxExperiment experiment = CorpusTestEnvironment.load(
                new ByteArrayInputStream(EXPERIMENT.getBytes(StandardCharsets.UTF_8)), CorpusTestEnvironment.fullyEquippedActivity());
        assertTrue("Test experiment failed to load: " + experiment.message, experiment.loaded);
        assertEquals(4, experiment.inputSensors.size());
        for (int i = 0; i < 3; i++)
            assertFalse(experiment.inputSensors.get(i).sensorName + " without the attribute must start uncalibrated", experiment.inputSensors.get(i).calibrated);
        assertTrue("preferUncalibrated=\"false\" must start calibrated", experiment.inputSensors.get(3).calibrated);
    }
}
