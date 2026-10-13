package de.rwth_aachen.phyphox;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import de.rwth_aachen.phyphox.camera.video.VideoInput;

//A video's files (VideoInput) in the run folder go into the CSV export and a saved state, and a state
//loaded from the collection exports them again
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class VideoExportTest {

    private static final String XML = "<phyphox version=\"1.21\" locale=\"en\">"
            + "<title>Video</title><category>Test</category><description>A video</description>"
            + "<data-containers><container size=\"0\">t</container></data-containers>"
            + "<input><video><output component=\"t\">t</output></video></input>"
            + "<views><view label=\"Values\"><value label=\"t\"><input>t</input></value></view></views>"
            + "<export><set name=\"Frames\"><data name=\"t\">t</data></set></export>"
            + "</phyphox>";

    private final Context context = ApplicationProvider.getApplicationContext();
    private Experiment activity;

    @After
    public void cleanUp() {
        RunFolder.deleteTree(RunFolder.root(context));
    }

    private PhyphoxExperiment load() {
        if (activity == null)
            activity = CorpusTestEnvironment.fullyEquippedActivity();
        PhyphoxExperiment experiment = CorpusTestEnvironment.load(new ByteArrayInputStream(XML.getBytes(StandardCharsets.UTF_8)), activity);
        assertTrue("failed to load: " + experiment.message, experiment.loaded);
        experiment.source = XML.getBytes(StandardCharsets.UTF_8);
        return experiment;
    }

    private static byte[] bytes(int n, int seed) {
        byte[] b = new byte[n];
        for (int i = 0; i < n; i++)
            b[i] = (byte) (i * 31 + seed);
        return b;
    }

    private static void write(File file, byte[] content) throws IOException {
        File parent = file.getParentFile();
        if (parent != null)
            parent.mkdirs();
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(content);
        }
    }

    private static byte[] read(ZipFile zip, String name) throws IOException {
        ZipEntry entry = zip.getEntry(name);
        assertNotNull("missing " + name, entry);
        try (InputStream in = zip.getInputStream(entry)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) != -1)
                out.write(buffer, 0, n);
            return out.toByteArray();
        }
    }

    @Test
    public void theVideoGoesIntoTheExportAndTheSavedState() throws Exception {
        PhyphoxExperiment experiment = load();
        experiment.openRunFolder(context);
        File dir = experiment.runFolder().dir;
        byte[] first = bytes(300000, 1);
        byte[] second = bytes(1234, 2);
        write(new File(dir, VideoInput.MEDIA + "/video-000.mp4"), first);
        write(new File(dir, VideoInput.MEDIA + "/video-001.mp4"), second);
        write(new File(dir, VideoInput.CAMERA_CSV), "\"property\",\"value\"\n\"fx\",\"1\"\n".getBytes(StandardCharsets.UTF_8));

        Map<String, File> media = experiment.mediaFiles();
        assertEquals("[media/video-000.mp4, media/video-001.mp4, meta/camera.csv]", media.keySet().toString());

        File export = experiment.exporter.exportDirect(experiment.exporter.exportFormats[1], activity.getCacheDir(), false, "video", activity);
        try (ZipFile zip = new ZipFile(export)) {
            assertArrayEquals(first, read(zip, "media/video-000.mp4"));
            assertArrayEquals(second, read(zip, "media/video-001.mp4"));
            assertTrue(new String(read(zip, "meta/camera.csv"), StandardCharsets.UTF_8).contains("\"fx\""));
            assertNotNull("the data stays", zip.getEntry("Frames.csv"));
            ZipEntry stored = zip.getEntry("media/video-000.mp4");
            assertTrue("not compressed: " + stored.getCompressedSize(), stored.getCompressedSize() >= first.length);
        }

        File state = new File(context.getFilesDir(), "video" + SavedState.DIRECTORY_SUFFIX);
        SavedState.writeDirectory(experiment, "Video", context, state);
        assertArrayEquals(first, java.nio.file.Files.readAllBytes(new File(state, "media/video-000.mp4").toPath()));
        assertTrue(new File(state, VideoInput.CAMERA_CSV).isFile());
        assertEquals(null, SavedState.validate(state));

        //Loaded from the collection, without a run folder of its own
        PhyphoxExperiment loaded = load();
        loaded.stateFolder = state.getPath();
        assertEquals(SavedState.restore(loaded, state), null);
        File again = loaded.exporter.exportDirect(loaded.exporter.exportFormats[1], activity.getCacheDir(), false, "again", activity);
        try (ZipFile zip = new ZipFile(again)) {
            assertArrayEquals(second, read(zip, "media/video-001.mp4"));
        }
        RunFolder.deleteTree(state);
    }

    @Test
    public void withoutAVideoNothingIsAdded() throws Exception {
        PhyphoxExperiment experiment = load();
        experiment.openRunFolder(context);
        assertTrue(experiment.mediaFiles().isEmpty());
        File export = experiment.exporter.exportDirect(experiment.exporter.exportFormats[1], activity.getCacheDir(), false, "none", activity);
        try (ZipFile zip = new ZipFile(export)) {
            assertFalse(zip.stream().anyMatch(e -> e.getName().startsWith("media/")));
        }
    }
}
