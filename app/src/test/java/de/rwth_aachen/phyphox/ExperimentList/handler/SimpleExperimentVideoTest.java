package de.rwth_aachen.phyphox.ExperimentList.handler;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;

import de.rwth_aachen.phyphox.CorpusTestEnvironment;
import de.rwth_aachen.phyphox.Experiment;
import de.rwth_aachen.phyphox.ExperimentView.ExpView;
import de.rwth_aachen.phyphox.ExperimentView.ExpViewElement;
import de.rwth_aachen.phyphox.ExperimentView.VideoGuiElement;
import de.rwth_aachen.phyphox.PhyphoxExperiment;
import de.rwth_aachen.phyphox.R;

//"Camera video" in Add simple experiment: the generated experiment records the back camera, shows its
//preview and exports one row per frame
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class SimpleExperimentVideoTest {

    @Test
    public void theCameraVideoOptionGivesAVideoWithPreviewAndFrames() throws Exception {
        Experiment activity = CorpusTestEnvironment.fullyEquippedActivity();
        ContextThemeWrapper themed = new ContextThemeWrapper(activity, R.style.Theme_Phyphox_DayNight);
        View layout = LayoutInflater.from(themed).inflate(R.layout.new_experiment, null);
        SimpleExperimentCreator creator = new SimpleExperimentCreator(activity, layout);
        File file = new File(activity.getCacheDir(), "simple-video.phyphox");
        try (FileOutputStream output = new FileOutputStream(file)) {
            creator.writeExperimentData(output, "Video", 0, new ArrayList<>(Arrays.asList(
                    SimpleExperimentCreator.SensorType.ACCELEROMETER, SimpleExperimentCreator.SensorType.CAMERA_VIDEO)));
        }
        PhyphoxExperiment experiment = CorpusTestEnvironment.load(file, activity);
        assertNotNull("no video input", experiment.videoInput);
        assertNotNull(experiment.getBuffer("vidT"));
        assertNotNull(experiment.getBuffer("vidFrame"));

        boolean preview = false;
        for (ExpView view : experiment.experimentViews)
            for (ExpViewElement element : view.elements)
                preview |= element instanceof VideoGuiElement;
        assertTrue("no preview", preview);

        String xml = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        int set = xml.indexOf("<set name=\"Video frames\">");
        assertTrue("no export set of the frames", set >= 0);
        String frames = xml.substring(set, xml.indexOf("</set>", set));
        assertEquals("a column per output", 7, frames.split("<data ").length - 1);
    }

    //The bundled logger (src/main/assets-local) is the same set of inputs, curated
    @Test
    public void theBundledCameraLoggerLoads() throws Exception {
        File asset = new File("src/main/assets-local/experiments/camera-logger.phyphox");
        if (!asset.isFile())
            asset = new File("app/src/main/assets-local/experiments/camera-logger.phyphox");
        assertTrue("bundled experiment missing: " + asset.getAbsolutePath(), asset.isFile());
        PhyphoxExperiment experiment = CorpusTestEnvironment.load(asset, CorpusTestEnvironment.fullyEquippedActivity());
        assertNotNull("no video input", experiment.videoInput);
        assertNotNull("no location input", experiment.gpsIn);
        assertNotNull("no raw measurements", experiment.gpsIn.dataMeasCn0);
        assertNotNull("no satellite table", experiment.gpsIn.dataSvCn0);
        assertTrue("no IMU", experiment.inputSensors.size() >= 4);
        assertTrue("the camera tab comes first", experiment.experimentViews.get(0).elements.get(0) instanceof VideoGuiElement);
    }
}
