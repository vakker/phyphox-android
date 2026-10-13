package de.rwth_aachen.phyphox;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

//ChunkedDoubles spilling its full chunks into a ChunkFile: the values stay those of a plain list
//through appends, clears, copies and snapshots, only the last chunk stays on the heap, and the file
//holds the values from the first one on as little-endian binary64.
public class ChunkFileTest {

    private static final int CHUNK = ChunkedDoubles.CHUNK;

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private static void assertSame(String message, List<Double> expected, ChunkedDoubles actual) {
        assertEquals(message + ": size", expected.size(), actual.size());
        for (int i = 0; i < expected.size(); i++)
            assertEquals(message + ": value " + i, expected.get(i), actual.get(i), 0.0);
    }

    private static void assertSame(String message, List<Double> expected, ChunkedDoubles.Snapshot actual) {
        assertEquals(message + ": size", expected.size(), actual.size());
        for (int i = 0; i < expected.size(); i++)
            assertEquals(message + ": value " + i, expected.get(i), actual.get(i), 0.0);
    }

    //All chunks but the last one live in the file
    private static void assertSpilled(String message, ChunkedDoubles store) {
        int expected = store.size() == 0 ? 0 : (store.size() - 1) / CHUNK;
        assertEquals(message + ": chunks in the file", expected, store.mappedChunks());
    }

    private static double[] readFile(File file, int count) throws IOException {
        try (RandomAccessFile access = new RandomAccessFile(file, "r")) {
            byte[] bytes = new byte[8 * count];
            access.readFully(bytes);
            ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            double[] values = new double[count];
            for (int i = 0; i < count; i++)
                values[i] = buffer.getDouble();
            return values;
        }
    }

    @Test
    public void randomOperationsMatchAListWhileSpilling() throws IOException {
        Random random = new Random(20261013);
        ChunkFile file = new ChunkFile(new File(folder.getRoot(), "data/x.bin"));
        ChunkedDoubles store = new ChunkedDoubles();
        store.spillTo(file);
        List<Double> model = new ArrayList<>();
        List<ChunkedDoubles.Snapshot> snapshots = new ArrayList<>();
        List<List<Double>> snapshotValues = new ArrayList<>();
        List<ChunkedDoubles> copies = new ArrayList<>();
        List<List<Double>> copyValues = new ArrayList<>();
        double next = 0;
        for (int step = 0; step < 300000; step++) {
            int op = random.nextInt(100000);
            if (op < 99980) {
                store.add(next);
                model.add(next);
                next++;
            } else if (op < 99983) {
                store.clear();
                model.clear();
                store.spillTo(file); //As the run folder does at its next checkpoint
            } else if (op < 99990) {
                snapshots.add(store.snapshot());
                snapshotValues.add(new ArrayList<>(model));
            } else {
                copies.add(store.copy());
                copyValues.add(new ArrayList<>(model));
            }
            if (step % 4999 == 0) {
                assertSame("step " + step, model, store);
                assertSpilled("step " + step, store);
            }
        }
        assertSame("end", model, store);
        assertSpilled("end", store);
        assertTrue("the run must have spilled", store.mappedChunks() > 10);
        for (int s = 0; s < snapshots.size(); s++)
            assertSame("snapshot " + s, snapshotValues.get(s), snapshots.get(s));
        //A copy goes on on its own, on the heap
        for (int c = 0; c < copies.size(); c++) {
            ChunkedDoubles copy = copies.get(c);
            for (int i = 0; i < CHUNK + 10; i++) {
                copy.add(-i);
                copyValues.get(c).add((double) -i);
            }
            assertSame("copy " + c, copyValues.get(c), copy);
        }
        assertSame("end, after the copies went on", model, store);

        //The file holds every value once the tail is written
        assertTrue(store.writeTail());
        double[] inFile = readFile(file.file(), model.size());
        for (int i = 0; i < model.size(); i++)
            assertEquals("file value " + i, model.get(i), inFile[i], 0.0);
    }

    @Test
    public void spillingFromAFilledStoreTakesItsFullChunks() {
        ChunkedDoubles store = new ChunkedDoubles();
        List<Double> model = new ArrayList<>();
        for (int i = 0; i < 5 * CHUNK + 17; i++) {
            store.add(i);
            model.add((double) i);
        }
        assertEquals(0, store.mappedChunks());
        store.spillTo(new ChunkFile(new File(folder.getRoot(), "a.bin")));
        assertSpilled("after spillTo", store);
        for (int i = 0; i < 3 * CHUNK; i++) {
            store.add(-i);
            model.add((double) -i);
        }
        assertSpilled("after more", store);
        assertSame("values", model, store);
    }

    @Test
    public void aSnapshotKeepsItsValuesWhenTheFileStartsOver() throws IOException {
        ChunkFile file = new ChunkFile(new File(folder.getRoot(), "b.bin"));
        ChunkedDoubles store = new ChunkedDoubles();
        store.spillTo(file);
        List<Double> model = new ArrayList<>();
        for (int i = 0; i < 4 * CHUNK + 3; i++) {
            store.add(i);
            model.add((double) i);
        }
        ChunkedDoubles.Snapshot snapshot = store.snapshot();
        ChunkedDoubles copy = store.copy();

        store.clear(); //Deletes the file and begins a new one under the same name
        assertEquals("a clear ends the spilling", null, store.spillFile());
        store.spillTo(file);
        List<Double> after = new ArrayList<>();
        for (int i = 0; i < 2 * CHUNK + 5; i++) {
            store.add(1000 + i);
            after.add(1000.0 + i);
        }
        assertSame("snapshot", model, snapshot);
        assertSame("copy", model, copy);
        assertSame("store", after, store);
        assertSpilled("store", store);
        assertEquals(2, file.written());
        assertTrue(store.writeTail());
        double[] inFile = readFile(file.file(), after.size());
        for (int i = 0; i < after.size(); i++)
            assertEquals("file value " + i, after.get(i), inFile[i], 0.0);
    }

    @Test
    public void removingFromTheFrontEndsTheSpilling() {
        ChunkedDoubles store = new ChunkedDoubles();
        store.spillTo(new ChunkFile(new File(folder.getRoot(), "c.bin")));
        List<Double> model = new ArrayList<>();
        for (int i = 0; i < 3 * CHUNK; i++) {
            store.add(i);
            model.add((double) i);
        }
        int before = store.mappedChunks();
        store.removeFirst(CHUNK + 1);
        model.subList(0, CHUNK + 1).clear();
        for (int i = 0; i < 3 * CHUNK; i++) {
            store.add(-i);
            model.add((double) -i);
        }
        assertSame("values", model, store);
        assertEquals("no more chunks go to the file", before - 1, store.mappedChunks());
        assertFalse("the file no longer matches the store", store.writeTail());
    }

    @Test
    public void aFileThatCannotBeWrittenKeepsTheChunksInMemory() throws IOException {
        File notADirectory = folder.newFile("plain");
        try (FileOutputStream out = new FileOutputStream(notADirectory)) {
            out.write(1);
        }
        ChunkFile file = new ChunkFile(new File(notADirectory, "d.bin"));
        ChunkedDoubles store = new ChunkedDoubles();
        store.spillTo(file);
        List<Double> model = new ArrayList<>();
        for (int i = 0; i < 4 * CHUNK; i++) {
            store.add(i);
            model.add((double) i);
        }
        assertSame("values", model, store);
        assertEquals(0, store.mappedChunks());
        assertFalse(file.usable());
        assertFalse(store.writeTail());
    }

    //The data file of a saved state: a container without a fixed size maps its full chunks, one with a
    //fixed size keeps the newest values on the heap
    @Test
    public void aDataFileRestoresMappedOrItsNewestValues() throws IOException {
        int count = 4 * CHUNK + 300;
        File file = folder.newFile("state.bin");
        ByteBuffer bytes = ByteBuffer.allocate(8 * count).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < count; i++)
            bytes.putDouble(i * 0.25);
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(bytes.array());
        }

        DataBuffer unbounded = new DataBuffer("u", "", 0, null);
        unbounded.restoreFrom(file, count);
        assertEquals(count, unbounded.getFilledSize());
        assertEquals("full chunks but the last mapped", 4, unbounded.chunksOnDisk());
        assertEquals((count - 1) * 0.25, unbounded.value, 0.0);
        ChunkedDoubles.Snapshot snapshot = unbounded.getSnapshot();
        for (int i = 0; i < count; i++)
            assertEquals("value " + i, i * 0.25, snapshot.get(i), 0.0);
        //It goes on on the heap
        for (int i = 0; i < CHUNK + 5; i++)
            unbounded.append(-i);
        assertEquals(count + CHUNK + 5, unbounded.getFilledSize());
        assertEquals(-(CHUNK + 4), unbounded.value, 0.0);
        assertEquals(42 * 0.25, unbounded.getSnapshot().get(42), 0.0);

        DataBuffer window = new DataBuffer("w", "", 100, null);
        window.restoreFrom(file, count);
        assertEquals(100, window.getFilledSize());
        assertEquals(0, window.chunksOnDisk());
        for (int i = 0; i < 100; i++)
            assertEquals("newest value " + i, (count - 100 + i) * 0.25, window.getSnapshot().get(i), 0.0);

        DataBuffer empty = new DataBuffer("e", "", 0, null);
        empty.restoreFrom(file, 0);
        assertEquals(0, empty.getFilledSize());
        assertTrue(Double.isNaN(empty.value));
    }

    @Test
    public void aSpilledStoreSerializesItsValues() throws Exception {
        ChunkedDoubles store = new ChunkedDoubles();
        store.spillTo(new ChunkFile(new File(folder.getRoot(), "e.bin")));
        List<Double> model = new ArrayList<>();
        for (int i = 0; i < 2 * CHUNK + 9; i++) {
            store.add(i * 0.5);
            model.add(i * 0.5);
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(store);
        }
        ChunkedDoubles read;
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            read = (ChunkedDoubles) in.readObject();
        }
        assertSame("read back", model, read);
        assertEquals("read back onto the heap", 0, read.mappedChunks());
    }
}
