package de.rwth_aachen.phyphox;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;

//The folder of a measurement on disk, filesDir/runs/<id>, laid out as a saved state (SavedState):
//experiment.phyphox and res/ when it is opened, then at every checkpoint data/ with one file per
//container, data/index.csv, meta/time.csv and meta/state.csv. A container with more than SPILL_AFTER
//values that was not cleared since the last checkpoint spills its full chunks into its data file, so
//a long run keeps them off the Java heap (ChunkedDoubles, ChunkFile); the checkpoint brings such a
//file up to the last value, and writes the other containers whole if they changed.
//The folder goes when the experiment is closed. One left behind by a process that died becomes a
//saved state of the collection on the next start (recover), with the values of its last checkpoint.
public class RunFolder {
    private static final String TAG = "RunFolder";
    static final String DIRECTORY = "runs";
    static final int SPILL_AFTER = 64 * ChunkedDoubles.CHUNK; //Values, 512 kB
    static final long CHECKPOINT_INTERVAL = 2000; //ms

    final File dir;
    private final PhyphoxExperiment experiment;
    private final List<DataBuffer> buffers;
    private final String[] files;
    private final ChunkFile[] chunkFiles;
    private final int[] clearsAtCheckpoint;
    private final int[] changesAtCheckpoint;
    private final boolean[] written;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "RunFolder");
        thread.setDaemon(true);
        return thread;
    });
    private Future<?> pending;
    private long lastCheckpoint = 0;
    private volatile boolean closed = false;

    private RunFolder(PhyphoxExperiment experiment, File dir) {
        this.experiment = experiment;
        this.dir = dir;
        buffers = new ArrayList<>(experiment.dataBuffers);
        files = SavedState.dataFileNames(buffers);
        chunkFiles = new ChunkFile[buffers.size()];
        clearsAtCheckpoint = new int[buffers.size()];
        changesAtCheckpoint = new int[buffers.size()];
        written = new boolean[buffers.size()];
        for (int i = 0; i < buffers.size(); i++)
            clearsAtCheckpoint[i] = buffers.get(i).clearCount();
    }

    static File root(Context ctx) {
        return new File(ctx.getFilesDir(), DIRECTORY);
    }

    //A new folder with the experiment's source; nothing is written to it before the first checkpoint
    static RunFolder open(PhyphoxExperiment experiment, Context ctx) throws IOException {
        File dir = new File(root(ctx), UUID.randomUUID().toString().replaceAll("-", ""));
        if (!dir.mkdirs())
            throw new IOException("Could not create " + dir);
        try {
            SavedState.writeSource(experiment, ctx, (path, content) -> {
                File file = new File(dir, path);
                File parent = file.getParentFile();
                if (parent != null && !parent.isDirectory() && !parent.mkdirs())
                    throw new IOException("Could not create " + path + ".");
                try (FileOutputStream out = new FileOutputStream(file)) {
                    content.writeTo(out);
                }
            });
        } catch (IOException e) {
            deleteTree(dir);
            throw e;
        }
        return new RunFolder(experiment, dir);
    }

    //A checkpoint if the last one is CHECKPOINT_INTERVAL ago and done. Called by the analysis loop.
    void checkpointIfDue() {
        long now = System.currentTimeMillis();
        if (now - lastCheckpoint < CHECKPOINT_INTERVAL || (pending != null && !pending.isDone()))
            return;
        checkpoint();
    }

    //A checkpoint on the folder's own thread
    synchronized Future<?> checkpoint() {
        lastCheckpoint = System.currentTimeMillis();
        try {
            pending = executor.submit(() -> {
                if (closed)
                    return;
                try {
                    write();
                } catch (IOException | RuntimeException e) {
                    Log.e(TAG, "Checkpoint failed: " + e.getMessage());
                }
            });
        } catch (RejectedExecutionException e) {
            return null;
        }
        return pending;
    }

    //Deletes the folder once a running checkpoint is done. The containers keep reading the chunks
    //they mapped, as a deleted file stays valid for its mappings.
    synchronized void delete() {
        closed = true;
        try {
            executor.execute(() -> {
                for (ChunkFile file : chunkFiles)
                    if (file != null)
                        file.close();
                deleteTree(dir);
            });
        } catch (RejectedExecutionException ignored) {
        }
        executor.shutdown();
    }

    //Waits until the folder is deleted (for the tests)
    void awaitClosed() throws InterruptedException {
        executor.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS);
    }

    private ChunkFile chunkFile(int i) {
        if (chunkFiles[i] == null)
            chunkFiles[i] = new ChunkFile(new File(dir, "data/" + files[i]));
        return chunkFiles[i];
    }

    private void write() throws IOException {
        int n = buffers.size();
        int[] counts = new int[n];
        ChunkedDoubles.Snapshot[] whole = new ChunkedDoubles.Snapshot[n]; //Containers written as a whole
        String time;
        experiment.dataLock.lock();
        try {
            for (int i = 0; i < n; i++) {
                DataBuffer buffer = buffers.get(i);
                int clears = buffer.clearCount();
                if (buffer.size == 0 && buffer.spillFile() == null && clears == clearsAtCheckpoint[i]
                        && buffer.getFilledSize() > SPILL_AFTER) {
                    ChunkFile file = chunkFile(i);
                    file.restart(); //The file a checkpoint wrote whole before goes
                    buffer.spillTo(file);
                }
                clearsAtCheckpoint[i] = clears;
                counts[i] = buffer.getFilledSize();
                if (buffer.spillFile() != null && buffer.writeTail()) {
                    written[i] = true;
                    continue;
                }
                if (!written[i] || buffer.changeCount() != changesAtCheckpoint[i]) {
                    whole[i] = buffer.getSnapshot();
                    changesAtCheckpoint[i] = buffer.changeCount();
                }
            }
            time = DataExport.timeCsv(experiment.experimentTimeReference, ',', '.');
        } finally {
            experiment.dataLock.unlock();
        }

        for (int i = 0; i < n; i++) {
            if (whole[i] == null)
                continue;
            if (chunkFiles[i] != null && chunkFiles[i] != buffers.get(i).spillFile()) {
                //The container stopped spilling; the file it spilled into is replaced below
                chunkFiles[i].close();
                chunkFiles[i] = null;
            }
            final ChunkedDoubles.Snapshot data = whole[i];
            replace("data/" + files[i], out -> SavedState.writeValues(data, out));
            written[i] = true;
        }

        StringBuilder index = new StringBuilder("\"container\",\"file\",\"count\"\n");
        for (int i = 0; i < n; i++)
            index.append(SavedState.csvString(buffers.get(i).name)).append(',').append(SavedState.csvString(files[i])).append(',').append(counts[i]).append('\n');
        replace(SavedState.TIME_CSV, out -> out.write(time.getBytes(StandardCharsets.UTF_8)));
        String title = experiment.stateTitle != null && !experiment.stateTitle.isEmpty() ? experiment.stateTitle : experiment.title;
        String state = SavedState.stateCsv(title, lastCheckpoint);
        replace(SavedState.STATE_CSV, out -> out.write(state.getBytes(StandardCharsets.UTF_8)));
        //The index last: once it is there, every file it names holds at least its count
        replace(SavedState.INDEX_CSV, out -> out.write(index.toString().getBytes(StandardCharsets.UTF_8)));
    }

    //Writes a file next to its place and renames it there, so a reader (or a recovery after a crash)
    //sees the old or the new one, and a mapping of the old one stays valid
    private void replace(String path, SavedState.Content content) throws IOException {
        File file = new File(dir, path);
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs())
            throw new IOException("Could not create " + parent);
        File temporary = new File(file.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(temporary)) {
            content.writeTo(out);
        }
        if (!temporary.renameTo(file))
            throw new IOException("Could not replace " + file);
    }

    //Every folder in runs/ belongs to a process that is gone, as long as this runs before an experiment
    //of this process opens one (App.onCreate). A folder with values becomes a saved state of the
    //collection, titled with the suffix; one without is deleted; one that does not come out as a valid
    //state is left as <id>.broken. Returns the titles of the recovered states.
    static List<String> recover(Context ctx, String titleSuffix) {
        List<String> titles = new ArrayList<>();
        File[] dirs = root(ctx).listFiles();
        if (dirs == null)
            return titles;
        for (File dir : dirs) {
            if (!dir.isDirectory() || dir.getName().endsWith(".broken"))
                continue;
            try {
                if (!finish(dir, ctx)) {
                    deleteTree(dir);
                    continue;
                }
                String error = SavedState.validate(dir);
                if (error != null) {
                    Log.e(TAG, "Run folder " + dir.getName() + " is no valid state: " + error);
                    if (!dir.renameTo(new File(dir.getPath() + ".broken")))
                        deleteTree(dir);
                    continue;
                }
                String title = SavedState.readTitle(dir);
                String recoveredTitle = (title == null ? "" : title + " ") + titleSuffix;
                SavedState.rename(dir, recoveredTitle);
                File target = new File(ctx.getFilesDir(), dir.getName() + SavedState.DIRECTORY_SUFFIX);
                if (!dir.renameTo(target))
                    throw new IOException("Could not move " + dir + " to " + target);
                titles.add(recoveredTitle);
            } catch (IOException | RuntimeException e) {
                Log.e(TAG, "Could not recover " + dir.getName() + ": " + e.getMessage());
            }
        }
        return titles;
    }

    //Brings a folder from its last checkpoint to a saved state: the data files cut to their counts
    //(a spilled file is longer, or shorter if it started over after the checkpoint), the index
    //rewritten to match, meta/device.csv added. False if there is no checkpoint or no value.
    private static boolean finish(File dir, Context ctx) throws IOException {
        File indexCsv = new File(dir, SavedState.INDEX_CSV);
        if (!indexCsv.isFile() || !new File(dir, SavedState.EXPERIMENT_FILE).isFile())
            return false;
        List<String[]> rows = SavedState.readCsv(indexCsv);
        StringBuilder index = new StringBuilder("\"container\",\"file\",\"count\"\n");
        long total = 0;
        for (int i = 1; i < rows.size(); i++) {
            String[] row = rows.get(i);
            if (row.length < 3)
                continue;
            File data = new File(new File(dir, "data"), row[1]);
            long count = Long.parseLong(row[2].trim());
            if (!data.isFile()) {
                count = 0;
                if (!data.createNewFile())
                    throw new IOException("Could not create " + data);
            }
            try (RandomAccessFile access = new RandomAccessFile(data, "rw")) {
                count = Math.min(count, access.length() / 8);
                access.setLength(count * 8);
            }
            total += count;
            index.append(SavedState.csvString(row[0])).append(',').append(SavedState.csvString(row[1])).append(',').append(count).append('\n');
        }
        if (total == 0)
            return false;
        writeFile(indexCsv, index.toString());
        writeFile(new File(dir, SavedState.DEVICE_CSV), DataExport.deviceCsv(',', ctx));
        //Files of an interrupted replace
        File[] data = new File(dir, "data").listFiles();
        if (data != null)
            for (File file : data)
                if (file.getName().endsWith(".tmp") && !file.delete())
                    Log.w(TAG, "Could not delete " + file);
        return true;
    }

    private static void writeFile(File file, String content) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs())
            throw new IOException("Could not create " + parent);
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(content.getBytes(StandardCharsets.UTF_8));
        }
    }

    static void deleteTree(File file) {
        File[] children = file.listFiles();
        if (children != null)
            for (File child : children)
                deleteTree(child);
        if (!file.delete() && file.exists())
            Log.w(TAG, "Could not delete " + file);
    }
}
