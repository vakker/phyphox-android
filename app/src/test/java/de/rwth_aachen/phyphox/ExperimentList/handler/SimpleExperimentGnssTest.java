package de.rwth_aachen.phyphox.ExperimentList.handler;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.CheckBox;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import de.rwth_aachen.phyphox.CorpusTestEnvironment;
import de.rwth_aachen.phyphox.DataBuffer;
import de.rwth_aachen.phyphox.Experiment;
import de.rwth_aachen.phyphox.GpsInput;
import de.rwth_aachen.phyphox.PhyphoxExperiment;
import de.rwth_aachen.phyphox.R;

//The GNSS options of "Add simple experiment", only in this build: they come with Location and load on the real parser
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class SimpleExperimentGnssTest {

    private static PhyphoxExperiment generateAndLoad(List<SimpleExperimentCreator.SensorType> sensors) throws Exception {
        Experiment activity = CorpusTestEnvironment.fullyEquippedActivity();
        ContextThemeWrapper themed = new ContextThemeWrapper(activity, R.style.Theme_Phyphox_DayNight);
        View layout = LayoutInflater.from(themed).inflate(R.layout.new_experiment, null);
        SimpleExperimentCreator creator = new SimpleExperimentCreator(activity, layout);
        File file = new File(activity.getCacheDir(), "simple-gnss.phyphox");
        try (FileOutputStream output = new FileOutputStream(file)) {
            creator.writeExperimentData(output, "GNSS", 0, SimpleExperimentCreator.withLocationForGnss(new ArrayList<>(sensors)));
        }
        PhyphoxExperiment experiment = CorpusTestEnvironment.load(file, activity);
        assertNotNull("the simple experiment failed to load its location input", experiment.gpsIn);
        return experiment;
    }

    //The dialog shows them under Location, and behaves so: an option ticks Location, clearing Location clears them
    @Test
    public void theDialogKeepsTheOptionsUnderLocation() {
        Experiment activity = CorpusTestEnvironment.fullyEquippedActivity();
        View layout = LayoutInflater.from(new ContextThemeWrapper(activity, R.style.Theme_Phyphox_DayNight)).inflate(R.layout.new_experiment, null);
        new SimpleExperimentCreator(activity, layout);
        CheckBox location = layout.findViewById(R.id.neLocation);
        CheckBox raw = layout.findViewById(R.id.neGnssRaw);
        CheckBox nmea = layout.findViewById(R.id.neGnssNmea);

        raw.setChecked(true);
        assertTrue("an option ticks Location", location.isChecked());
        nmea.setChecked(true);
        location.setChecked(false);
        assertFalse("clearing Location clears the raw option", raw.isChecked());
        assertFalse("clearing Location clears the NMEA option", nmea.isChecked());
        location.setChecked(true);
        assertFalse("ticking Location alone ticks no option", raw.isChecked());
    }

    @Test
    public void gnssOptionsBringLocationAlong() {
        List<SimpleExperimentCreator.SensorType> sensors = SimpleExperimentCreator.withLocationForGnss(new ArrayList<>(Arrays.asList(
                SimpleExperimentCreator.SensorType.ACCELEROMETER, SimpleExperimentCreator.SensorType.GNSS_RAW)));
        assertEquals(Arrays.asList(SimpleExperimentCreator.SensorType.ACCELEROMETER, SimpleExperimentCreator.SensorType.LOCATION,
                SimpleExperimentCreator.SensorType.GNSS_RAW), sensors);
    }

    @Test
    public void everyGnssOptionMapsItsOutputs() throws Exception {
        PhyphoxExperiment experiment = generateAndLoad(Arrays.asList(SimpleExperimentCreator.SensorType.ACCELEROMETER,
                SimpleExperimentCreator.SensorType.GNSS_SATELLITES, SimpleExperimentCreator.SensorType.GNSS_NMEA,
                SimpleExperimentCreator.SensorType.GNSS_RAW));
        GpsInput gps = experiment.gpsIn;
        assertNotNull("Location came along", gps);
        assertNotNull(gps.dataT);
        assertNotNull(gps.dataUtc);
        assertNotNull(gps.dataCn0Mean);
        assertNotNull(gps.dataSvCn0);
        assertNotNull(gps.dataSvBasebandCn0);
        assertNotNull(gps.dataTtff);
        assertNotNull(gps.dataGgaQuality);
        assertNotNull(gps.dataGsaPdop);
        DataBuffer[] raw = gps.rawBuffers();
        for (int i = 0; i < raw.length; i++)
            assertNotNull("raw component " + GpsInput.RAW_COMPONENTS[i] + " is not mapped", raw[i]);
        assertTrue("the accelerometer stays", experiment.inputSensors.size() > 0);
    }

    @Test
    public void locationAloneHasNoGnssDetails() throws Exception {
        PhyphoxExperiment experiment = generateAndLoad(Arrays.asList(SimpleExperimentCreator.SensorType.LOCATION));
        GpsInput gps = experiment.gpsIn;
        assertNotNull(gps.dataLat);
        assertEquals(null, gps.dataSvCn0);
        assertEquals(null, gps.dataGgaQuality);
        assertEquals(null, gps.dataClockT);
    }
}
