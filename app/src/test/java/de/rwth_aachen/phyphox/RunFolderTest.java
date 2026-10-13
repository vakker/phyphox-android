package de.rwth_aachen.phyphox;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.List;

//The folder of a measurement on disk (RunFolder): its checkpoint is a valid saved state with the
//values of that moment, a long container spills into its data file, one an analysis keeps clearing
//does not, the folder goes when the experiment is closed, and a folder left behind by a dead process
//comes back as a saved state of the collection.
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class RunFolderTest {

    private static final String XML = "<phyphox version=\"1.21\" locale=\"en\">"
            + "<title>Run folder</title><category>Test</category><description>Containers on disk</description>"
            + "<data-containers><container size=\"0\">long</container><container size=\"0\">short</container>"
            + "<container size=\"10\">window</container><container size=\"0\">cleared</container></data-containers>"
            + "<views><view label=\"Values\"><value label=\"Long\"><input>long</input></value></view></views>"
            + "<export><set name=\"Values\"><data name=\"long\">long</data></set></export>"
            + "</phyphox>";

    private final Context context = ApplicationProvider.getApplicationContext();
    private Experiment activity; //Set up once per test, as the simulated camera can be added only once

    @After
    public void cleanUp() {
        RunFolder.deleteTree(RunFolder.root(context));
    }

    private PhyphoxExperiment load() {
        if (activity == null)
            activity = CorpusTestEnvironment.fullyEquippedActivity();
        PhyphoxExperiment experiment = CorpusTestEnvironment.load(
                new ByteArrayInputStream(XML.getBytes(StandardCharsets.UTF_8)), activity);
        assertTrue("experiment failed to load: " + experiment.message, experiment.loaded);
        experiment.source = XML.getBytes(StandardCharsets.UTF_8); //Kept by the stream opener, which this load skips
        return experiment;
    }

    private static void append(PhyphoxExperiment experiment, String buffer, double first, int count) {
        DataBuffer b = experiment.getBuffer(buffer);
        for (int i = 0; i < count; i++)
            b.append(first + i);
    }

    private static void assertValues(String message, DataBuffer expected, DataBuffer actual, int count) {
        assertEquals(message + ": count", count, actual.getFilledSize());
        ChunkedDoubles.Snapshot e = expected.getSnapshot();
        ChunkedDoubles.Snapshot a = actual.getSnapshot();
        for (int i = 0; i < count; i++)
            assertEquals(message + ": value " + i, e.get(i), a.get(i), 0.0);
    }

    private File onlyRunFolder() {
        File[] dirs = RunFolder.root(context).listFiles();
        assertNotNull(dirs);
        assertEquals("one run folder", 1, dirs.length);
        return dirs[0];
    }

    @Test
    public void aCheckpointIsASavedStateWithTheValuesOfThatMoment() throws Exception {
        PhyphoxExperiment experiment = load();
        experiment.openRunFolder(context);
        File dir = onlyRunFolder();
        assertTrue(new File(dir, SavedState.EXPERIMENT_FILE).isFile());

        int longCount = RunFolder.SPILL_AFTER + 3 * ChunkedDoubles.CHUNK + 5;
        append(experiment, "long", 0, longCount);
        append(experiment, "short", 100, 7);
        append(experiment, "window", 0, 25);
        RunFolder folder = runFolder(experiment);
        assertEquals(0, experiment.getBuffer("long").chunksOnDisk());
        folder.checkpoint().get();
        assertNotNull("the long container spills at the checkpoint", experiment.getBuffer("long").spillFile());
        assertEquals("all but the last chunk on disk", (longCount - 1) / ChunkedDoubles.CHUNK, experiment.getBuffer("long").chunksOnDisk());
        assertNull(experiment.getBuffer("short").spillFile());
        assertNull("a container of fixed size does not spill", experiment.getBuffer("window").spillFile());
        assertNull("the folder is a valid state after a checkpoint", SavedState.validate(dir));

        //More values, then the next checkpoint
        append(experiment, "long", longCount, 2 * ChunkedDoubles.CHUNK + 1);
        append(experiment, "short", 107, 3);
        folder.checkpoint().get();
        assertNull("the folder is a valid state", SavedState.validate(dir));

        PhyphoxExperiment restored = load();
        assertNull(SavedState.restore(restored, dir));
        assertValues("long", experiment.getBuffer("long"), restored.getBuffer("long"), longCount + 2 * ChunkedDoubles.CHUNK + 1);
        assertValues("short", experiment.getBuffer("short"), restored.getBuffer("short"), 10);
        assertValues("window", experiment.getBuffer("window"), restored.getBuffer("window"), 10);
        assertEquals(0, restored.getBuffer("cleared").getFilledSize());
    }

    @Test
    public void aContainerClearedBetweenCheckpointsDoesNotSpill() throws Exception {
        PhyphoxExperiment experiment = load();
        experiment.openRunFolder(context);
        RunFolder folder = runFolder(experiment);
        DataBuffer cleared = experiment.getBuffer("cleared");
        append(experiment, "cleared", 0, RunFolder.SPILL_AFTER + 10);
        cleared.clear(false);
        append(experiment, "cleared", 0, RunFolder.SPILL_AFTER + 10);
        folder.checkpoint().get();
        assertNull("cleared since the last checkpoint", cleared.spillFile());
        folder.checkpoint().get();
        assertNotNull("not cleared since the last one", cleared.spillFile());
        cleared.clear(false);
        assertNull("a clear ends the spilling", cleared.spillFile());
        append(experiment, "cleared", 5, 20);
        folder.checkpoint().get();
        assertNull(SavedState.validate(onlyRunFolder()));
        PhyphoxExperiment restored = load();
        assertNull(SavedState.restore(restored, onlyRunFolder()));
        assertValues("cleared", cleared, restored.getBuffer("cleared"), 20);
    }

    @Test
    public void theFolderGoesWhenTheExperimentIsClosed() throws Exception {
        PhyphoxExperiment experiment = load();
        experiment.openRunFolder(context);
        File dir = onlyRunFolder();
        append(experiment, "long", 0, RunFolder.SPILL_AFTER + 1);
        DataBuffer buffer = experiment.getBuffer("long");
        runFolder(experiment).checkpoint().get();
        RunFolder folder = runFolder(experiment);
        experiment.closeRunFolder();
        folder.awaitClosed();
        assertFalse("folder deleted", dir.exists());
        //The values mapped from the deleted file are still there
        assertEquals(RunFolder.SPILL_AFTER + 1, buffer.getFilledSize());
        assertEquals(RunFolder.SPILL_AFTER, buffer.getSnapshot().get(RunFolder.SPILL_AFTER), 0.0);
        assertEquals(0, buffer.getSnapshot().get(0), 0.0);
        //A container that goes on and is cleared does not bring the folder back
        append(experiment, "long", 0, 3 * ChunkedDoubles.CHUNK);
        buffer.clear(false);
        append(experiment, "long", 0, 3 * ChunkedDoubles.CHUNK);
        assertFalse("folder stays deleted", dir.exists());
        assertEquals(3 * ChunkedDoubles.CHUNK, buffer.getFilledSize());
    }

    @Test
    public void aFolderLeftBehindBecomesASavedStateWithItsLastCheckpoint() throws Exception {
        PhyphoxExperiment experiment = load();
        experiment.openRunFolder(context);
        int count = RunFolder.SPILL_AFTER + 2 * ChunkedDoubles.CHUNK + 17;
        append(experiment, "long", 0, count);
        append(experiment, "short", 0, 4);
        runFolder(experiment).checkpoint().get();
        //Values after the last checkpoint are lost with the process
        append(experiment, "long", count, ChunkedDoubles.CHUNK + 3);
        File dir = onlyRunFolder();

        List<String> recovered = RunFolder.recover(context, "(recovered)");
        assertEquals(1, recovered.size());
        assertEquals("Run folder (recovered)", recovered.get(0));
        assertFalse("moved out of runs/", dir.exists());
        File state = new File(context.getFilesDir(), dir.getName() + SavedState.DIRECTORY_SUFFIX);
        assertTrue(state.isDirectory());
        assertNull(SavedState.validate(state));
        assertEquals("Run folder (recovered)", SavedState.readTitle(state));

        PhyphoxExperiment restored = load();
        assertNull(SavedState.restore(restored, state));
        assertValues("long", experiment.getBuffer("long"), restored.getBuffer("long"), count);
        assertEquals("the state's file is mapped", (count - 1) / ChunkedDoubles.CHUNK, restored.getBuffer("long").chunksOnDisk());
        assertValues("short", experiment.getBuffer("short"), restored.getBuffer("short"), 4);
        RunFolder.deleteTree(state);
    }

    //A saved state (here a recovered one) measured further spills too, its restored chunks included
    @Test
    public void aRestoredStateMeasuredFurtherSpillsAndComesBack() throws Exception {
        PhyphoxExperiment first = load();
        first.openRunFolder(context);
        int count = RunFolder.SPILL_AFTER + 2 * ChunkedDoubles.CHUNK + 9;
        append(first, "long", 0, count);
        runFolder(first).checkpoint().get();
        File firstDir = onlyRunFolder();
        assertEquals(1, RunFolder.recover(context, "(recovered)").size());
        File state = new File(context.getFilesDir(), firstDir.getName() + SavedState.DIRECTORY_SUFFIX);

        PhyphoxExperiment second = load();
        assertNull(SavedState.restore(second, state));
        DataBuffer restored = second.getBuffer("long");
        assertTrue("restored mapped", restored.chunksOnDisk() > 0);
        second.openRunFolder(context);
        append(second, "long", count, 3 * ChunkedDoubles.CHUNK);
        runFolder(second).checkpoint().get(); //Throws if the checkpoint failed
        assertNotNull("the restored container spills", restored.spillFile());
        append(second, "long", count + 3 * ChunkedDoubles.CHUNK, 5);
        runFolder(second).checkpoint().get();
        File secondDir = onlyRunFolder();
        assertFalse("no staging file left", new File(secondDir, "data/long.bin.spill").exists());
        assertNull(SavedState.validate(secondDir));

        assertEquals(1, RunFolder.recover(context, "(recovered)").size());
        File secondState = new File(context.getFilesDir(), secondDir.getName() + SavedState.DIRECTORY_SUFFIX);
        PhyphoxExperiment third = load();
        assertNull(SavedState.restore(third, secondState));
        assertValues("long", restored, third.getBuffer("long"), count + 3 * ChunkedDoubles.CHUNK + 5);
        RunFolder.deleteTree(state);
        RunFolder.deleteTree(secondState);
    }

    //A kill between the start of a spill and its first complete file leaves data/<file>.spill behind;
    //the recovery takes the file the index names, written whole at the checkpoint before
    @Test
    public void aLeftoverSpillFileIsIgnoredOnRecovery() throws Exception {
        PhyphoxExperiment experiment = load();
        experiment.openRunFolder(context);
        append(experiment, "long", 0, 1000);
        runFolder(experiment).checkpoint().get();
        File dir = onlyRunFolder();
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(new File(dir, "data/long.bin.spill"))) {
            out.write(new byte[3 * 8192]);
        }
        assertEquals(1, RunFolder.recover(context, "(recovered)").size());
        File state = new File(context.getFilesDir(), dir.getName() + SavedState.DIRECTORY_SUFFIX);
        assertFalse(new File(state, "data/long.bin.spill").exists());
        PhyphoxExperiment restored = load();
        assertNull(SavedState.restore(restored, state));
        assertValues("long", experiment.getBuffer("long"), restored.getBuffer("long"), 1000);
        RunFolder.deleteTree(state);
    }

    //A video file without its moov box (cut short by the crash) is set aside, a complete one stays
    @Test
    public void aVideoCutShortIsSetAsideOnRecovery() throws Exception {
        PhyphoxExperiment experiment = load();
        experiment.openRunFolder(context);
        append(experiment, "short", 0, 3);
        runFolder(experiment).checkpoint().get();
        File dir = onlyRunFolder();
        File media = new File(dir, "media");
        assertTrue(media.mkdirs());
        java.nio.ByteBuffer complete = java.nio.ByteBuffer.allocate(16 + 16 + 8);
        complete.putInt(16).putInt(0x66747970).putLong(0); //ftyp
        complete.putInt(16).putInt(0x6d646174).putLong(0); //mdat
        complete.putInt(8).putInt(0x6d6f6f76); //moov
        java.nio.ByteBuffer cut = java.nio.ByteBuffer.allocate(16 + 24);
        cut.putInt(16).putInt(0x66747970).putLong(0); //ftyp
        cut.putInt(0).putInt(0x6d646174).putLong(0).putLong(0); //mdat to the end, no moov
        java.nio.file.Files.write(new File(media, "video-000.mp4").toPath(), complete.array());
        java.nio.file.Files.write(new File(media, "video-001.mp4").toPath(), cut.array());

        assertEquals(1, RunFolder.recover(context, "(recovered)").size());
        File state = new File(context.getFilesDir(), dir.getName() + SavedState.DIRECTORY_SUFFIX);
        assertTrue(new File(state, "media/video-000.mp4").isFile());
        assertFalse(new File(state, "media/video-001.mp4").exists());
        assertTrue(new File(state, "media/video-001.mp4.partial").isFile());
        RunFolder.deleteTree(state);
    }

    @Test
    public void aFolderWithoutValuesIsDeletedOnRecovery() throws Exception {
        PhyphoxExperiment experiment = load();
        experiment.openRunFolder(context);
        File dir = onlyRunFolder();
        runFolder(experiment).checkpoint().get();
        assertTrue(RunFolder.recover(context, "(recovered)").isEmpty());
        assertFalse(dir.exists());

        //And one that never reached a checkpoint
        load().openRunFolder(context);
        assertTrue(RunFolder.recover(context, "(recovered)").isEmpty());
        File[] left = RunFolder.root(context).listFiles();
        assertTrue(left == null || left.length == 0);
    }

    private static RunFolder runFolder(PhyphoxExperiment experiment) {
        RunFolder folder = experiment.runFolder();
        assertNotNull("no run folder", folder);
        return folder;
    }
}
