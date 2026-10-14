package de.rwth_aachen.phyphox;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import de.rwth_aachen.phyphox.helper.DataExportUtility;

//Exports are written to the cache for the app they are shared with, and used to stay there for good:
//the ones older than an hour go (only exported files, not other files or folders of the cache), and
//an export the remote server sent goes right after.
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class ExportCacheTest {

    private static final String EXPERIMENT = "<phyphox version=\"1.20\">"
            + "<title>Export cache test</title><category>Test</category><description>Exports</description>"
            + "<data-containers><container size=\"0\">v</container></data-containers>"
            + "<views><view label=\"View\"><value label=\"v\"><input>v</input></value></view></views>"
            + "<export><set name=\"Values\"><data name=\"v\">v</data></set></export>"
            + "</phyphox>";

    private final Context context = ApplicationProvider.getApplicationContext();

    @After
    public void cleanUp() {
        delete(context.getCacheDir());
    }

    private static void delete(File file) {
        File[] children = file.listFiles();
        if (children != null)
            for (File child : children)
                delete(child);
        file.delete();
    }

    private File file(String name, long ageMillis) throws IOException {
        File file = new File(context.getCacheDir(), name);
        File parent = file.getParentFile();
        assertTrue(parent.isDirectory() || parent.mkdirs());
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(new byte[]{1, 2, 3});
        }
        assertTrue(file.setLastModified(System.currentTimeMillis() - ageMillis));
        return file;
    }

    @Test
    public void exportsOlderThanAnHourGo() throws Exception {
        long old = DataExportUtility.EXPORT_KEEP_MILLIS + 60_000;
        File oldZip = file("Run 2026-10-14 08-21-43.zip", old);
        File oldXlsx = file("Run 2026-10-14 08-21-50.xlsx", old);
        File oldCsv = file("Run 2026-10-14 08-22-00.csv", old);
        File oldPng = file("Run 2026-10-14 08-23-00.png", old);
        File recent = file("Run 2026-10-14 10-41-12.zip", 5 * 60_000);
        File other = file("something.bin", old);
        File inFolder = file("video/video-000.mp4", old);
        File zipInFolder = file("video/old.zip", old);

        DataExportUtility.deleteOldExports(context);

        assertFalse(oldZip.exists());
        assertFalse(oldXlsx.exists());
        assertFalse(oldCsv.exists());
        assertFalse(oldPng.exists());
        assertTrue("an export of the last hour stays for the app it was shared with", recent.exists());
        assertTrue("not an export", other.exists());
        assertTrue("folders of the cache are left alone", inFolder.exists());
        assertTrue(zipInFolder.exists());
    }

    @Test
    public void theRemoteServerDeletesWhatItSent() throws Exception {
        Experiment activity = CorpusTestEnvironment.fullyEquippedActivity();
        PhyphoxExperiment experiment = CorpusTestEnvironment.load(
                new ByteArrayInputStream(EXPERIMENT.getBytes(StandardCharsets.UTF_8)), activity);
        assertTrue("experiment failed to load: " + experiment.message, experiment.loaded);
        experiment.getBuffer("v").append(1.5);
        RemoteServer server = new RemoteServer(experiment, activity);
        assertTrue("RemoteServer did not start", server.start());
        try {
            for (int format = 0; format < experiment.exporter.exportFormats.length; format++) {
                HttpURLConnection connection = (HttpURLConnection) new URL(
                        "http://127.0.0.1:" + RemoteServer.httpServerPort + "/export?format=" + format).openConnection();
                connection.setRequestProperty("Connection", "close"); //a pooled connection could outlive its server
                assertEquals(200, connection.getResponseCode());
                long length = 0;
                try (InputStream in = connection.getInputStream()) {
                    byte[] buffer = new byte[4096];
                    int n;
                    while ((n = in.read(buffer)) != -1)
                        length += n;
                }
                connection.disconnect();
                assertTrue("format " + format + " sent something", length > 0);
                File[] left = activity.getCacheDir().listFiles(File::isFile);
                assertEquals("format " + format + " left its file in the cache", 0, left == null ? 0 : left.length);
            }
        } finally {
            server.stop();
        }
    }
}
