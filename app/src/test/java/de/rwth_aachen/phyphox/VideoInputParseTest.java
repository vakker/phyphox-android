package de.rwth_aachen.phyphox;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import de.rwth_aachen.phyphox.camera.video.VideoInput;

//The video input of this build (<video> in <input>): its attributes and outputs on the real parser
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class VideoInputParseTest {

    private Experiment activity; //Set up once per test, as the simulated camera can be added only once

    private PhyphoxExperiment load(String video) {
        return load(video, "<value label=\"Frame\"><input>frame</input></value>");
    }

    private PhyphoxExperiment load(String video, String elements) {
        if (activity == null)
            activity = CorpusTestEnvironment.fullyEquippedActivity();
        String xml = "<phyphox version=\"1.21\" locale=\"en\">"
                + "<title>Video</title><category>Test</category><description>A video</description>"
                + "<data-containers><container size=\"0\">t</container><container size=\"0\">exposure</container>"
                + "<container size=\"0\">frame</container></data-containers>"
                + "<input>" + video + "</input>"
                + "<views><view label=\"Values\">" + elements + "</view></views>"
                + "<export><set name=\"Frames\"><data name=\"t\">t</data></set></export>"
                + "</phyphox>";
        return CorpusTestEnvironment.load(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)), activity);
    }

    @Test
    public void theDefaultsAreAFixedFocusFullHdVideo() {
        PhyphoxExperiment experiment = load("<video><output component=\"t\">t</output></video>");
        assertTrue("failed to load: " + experiment.message, experiment.loaded);
        assertNotNull(experiment.videoInput);
        VideoInput.Settings settings = experiment.videoInput.settings();
        assertFalse(settings.front);
        assertEquals(1920, settings.width);
        assertEquals(1080, settings.height);
        assertEquals(30, settings.rate);
        assertEquals(16000000, settings.bitrate);
        assertEquals("infinity", 0, settings.focus, 0);
        assertFalse(settings.stabilization);
        assertEquals(60, settings.segment, 0);
    }

    @Test
    public void theAttributesSetTheRecording() {
        PhyphoxExperiment experiment = load("<video width=\"1280\" height=\"720\" rate=\"60\" bitrate=\"8000000\" focus=\"auto\""
                + " stabilization=\"true\" segment=\"30\"><output component=\"t\">t</output>"
                + "<output component=\"exposure\">exposure</output><output component=\"frame\">frame</output></video>");
        assertTrue("failed to load: " + experiment.message, experiment.loaded);
        VideoInput.Settings settings = experiment.videoInput.settings();
        assertEquals(1280, settings.width);
        assertEquals(720, settings.height);
        assertEquals(60, settings.rate);
        assertEquals(8000000, settings.bitrate);
        assertTrue("auto focus", Double.isNaN(settings.focus));
        assertTrue(settings.stabilization);
        assertEquals(30, settings.segment, 0);

        experiment = load("<video focus=\"2.5\"><output component=\"t\">t</output></video>");
        assertEquals("diopters", 2.5, experiment.videoInput.settings().focus, 0);
    }

    @Test
    public void thePreviewIsAViewElement() {
        PhyphoxExperiment experiment = load("<video><output component=\"t\">t</output></video>",
                "<video-gui label=\"Camera\" maxHeight=\"0.4\"/>");
        assertTrue("failed to load: " + experiment.message, experiment.loaded);
        assertTrue(experiment.experimentViews.get(0).elements.get(0) instanceof de.rwth_aachen.phyphox.ExperimentView.VideoGuiElement);
    }

    @Test
    public void aVideoWithoutSizeIsRefused() {
        PhyphoxExperiment experiment = load("<video width=\"0\"><output component=\"t\">t</output></video>");
        assertFalse(experiment.loaded);
    }
}
