package de.rwth_aachen.phyphox;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

//ChunkedDoubles against a plain list: appending, removing from the front, clearing and copying across
//chunk boundaries, and snapshots that keep their values while the buffer goes on.
public class ChunkedDoublesTest {

    private static final int CHUNK = ChunkedDoubles.CHUNK;

    private static void assertSame(String message, List<Double> expected, ChunkedDoubles actual) {
        assertEquals(message + ": size", expected.size(), actual.size());
        for (int i = 0; i < expected.size(); i++)
            assertEquals(message + ": value " + i, expected.get(i), actual.get(i), 0.0);
    }

    private static void assertSame(String message, List<Double> expected, ChunkedDoubles.Snapshot actual) {
        assertEquals(message + ": size", expected.size(), actual.size());
        for (int i = 0; i < expected.size(); i++)
            assertEquals(message + ": value " + i, expected.get(i), actual.get(i), 0.0);
        int i = 0;
        for (double v : actual)
            assertEquals(message + ": iterated value " + i, expected.get(i++), v, 0.0);
        assertEquals(message + ": iterated count", expected.size(), i);
    }

    private static void removeFirst(List<Double> list, int n) {
        list.subList(0, Math.min(n, list.size())).clear();
    }

    @Test
    public void randomOperationsMatchAList() {
        Random random = new Random(20261013);
        ChunkedDoubles store = new ChunkedDoubles();
        List<Double> model = new ArrayList<>();
        List<ChunkedDoubles.Snapshot> snapshots = new ArrayList<>();
        List<List<Double>> snapshotValues = new ArrayList<>();
        double next = 0;
        //Mostly appending with a few small removals, now and then a large one or a clear, so the size
        //wanders up to a few chunks
        for (int step = 0; step < 200000; step++) {
            int op = random.nextInt(10000);
            if (op < 9800) {
                int n = op < 9000 ? 0 : random.nextInt(op < 9797 ? 3 : 3 * CHUNK);
                store.removeFirst(n);
                removeFirst(model, n);
                store.add(next);
                model.add(next);
                next++;
            } else if (op < 9801) {
                store.clear();
                model.clear();
            } else if (op < 9805) {
                snapshots.add(store.snapshot());
                snapshotValues.add(new ArrayList<>(model));
            } else {
                store.add(next);
                model.add(next);
                next++;
            }
            if (step % 997 == 0)
                assertSame("step " + step, model, store);
        }
        assertSame("end", model, store);
        for (int s = 0; s < snapshots.size(); s++)
            assertSame("snapshot " + s, snapshotValues.get(s), snapshots.get(s));
    }

    //What a buffer with a fixed size does: remove the first value, append one
    @Test
    public void aSlidingWindowKeepsTheNewestValues() {
        for (int window : new int[]{1, 2, 15, 16, 17, 100, CHUNK - 1, CHUNK, CHUNK + 1, 3 * CHUNK + 5}) {
            ChunkedDoubles store = new ChunkedDoubles();
            List<Double> model = new ArrayList<>();
            ChunkedDoubles.Snapshot early = null;
            List<Double> earlyValues = null;
            for (int i = 0; i < 5 * window + 3 * CHUNK; i++) {
                if (store.size() + 1 > window) {
                    store.removeFirst(1);
                    model.remove(0);
                }
                store.add(i);
                model.add((double) i);
                if (i == window + 7) {
                    early = store.snapshot();
                    earlyValues = new ArrayList<>(model);
                }
            }
            assertSame("window " + window, model, store);
            assertSame("window " + window + ", early snapshot", earlyValues, early);
        }
    }

    @Test
    public void aSnapshotKeepsItsValuesThroughGrowthRemovalAndClear() {
        ChunkedDoubles store = new ChunkedDoubles();
        List<Double> model = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            store.add(i);
            model.add((double) i);
        }
        store.removeFirst(5);
        removeFirst(model, 5);
        ChunkedDoubles.Snapshot snapshot = store.snapshot();
        List<Double> expected = new ArrayList<>(model);

        for (int i = 0; i < 3 * CHUNK; i++)
            store.add(-i);
        store.removeFirst(CHUNK + 3);
        store.clear();
        for (int i = 0; i < 40; i++)
            store.add(1000 + i);

        assertSame("snapshot", expected, snapshot);
    }

    @Test
    public void aCopyIsIndependent() {
        for (int n : new int[]{0, 1, 16, 17, CHUNK, CHUNK + 1, 2 * CHUNK + 100}) {
            ChunkedDoubles store = new ChunkedDoubles();
            List<Double> model = new ArrayList<>();
            for (int i = 0; i < n + 7; i++) {
                store.add(i);
                model.add((double) i);
            }
            store.removeFirst(7);
            removeFirst(model, 7);

            ChunkedDoubles copy = store.copy();
            List<Double> copyModel = new ArrayList<>(model);
            assertSame("copy of " + n, copyModel, copy);

            for (int i = 0; i < CHUNK + 50; i++) {
                store.add(i + 0.5);
                model.add(i + 0.5);
                copy.add(-i);
                copyModel.add((double) -i);
            }
            copy.removeFirst(3);
            removeFirst(copyModel, 3);
            assertSame("original of " + n, model, store);
            assertSame("copy of " + n + " after changes", copyModel, copy);
        }
    }

    @Test
    public void readingOutsideTheValuesThrows() {
        ChunkedDoubles store = new ChunkedDoubles();
        store.add(1);
        store.add(2);
        store.removeFirst(1);
        assertEquals(2.0, store.get(0), 0.0);
        assertEquals(2.0, store.getLast(), 0.0);
        assertThrows(IndexOutOfBoundsException.class, () -> store.get(1));
        assertThrows(IndexOutOfBoundsException.class, () -> store.get(-1));
        assertThrows(IndexOutOfBoundsException.class, () -> store.snapshot().get(1));
        store.removeFirst(5);
        assertEquals(0, store.size());
        assertThrows(IndexOutOfBoundsException.class, store::getLast);
    }
}
