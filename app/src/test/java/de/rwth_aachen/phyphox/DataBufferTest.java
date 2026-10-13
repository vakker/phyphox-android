package de.rwth_aachen.phyphox;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

//DataBuffer on its ChunkedDoubles: a buffer with a fixed size keeps its newest values whether they
//come one by one or as arrays, with and without a graph's float copy attached, and the readers see
//the same values.
public class DataBufferTest {

    private static void assertValues(String message, List<Double> expected, DataBuffer buffer) {
        Double[] actual = buffer.getArray();
        assertEquals(message + ": size", expected.size(), actual.length);
        assertEquals(message + ": filled size", expected.size(), buffer.getFilledSize());
        for (int i = 0; i < expected.size(); i++)
            assertEquals(message + ": value " + i, expected.get(i), actual[i], 0.0);
        ChunkedDoubles.Snapshot snapshot = buffer.getSnapshot();
        assertEquals(message + ": snapshot size", expected.size(), snapshot.size());
        int i = 0;
        for (Iterator<Double> it = buffer.getIterator(); it.hasNext(); i++)
            assertEquals(message + ": iterated value " + i, expected.get(i), it.next(), 0.0);
        assertEquals(message + ": iterated count", expected.size(), i);
    }

    private static void appendToModel(List<Double> model, int size, double value) {
        model.add(value);
        if (size > 0 && model.size() > size)
            model.remove(0);
    }

    @Test
    public void appendsKeepTheNewestValuesOfAFixedSize() {
        Random random = new Random(42);
        for (int size : new int[]{0, 1, 5, 64, 1000, ChunkedDoubles.CHUNK + 3, 5000}) {
            for (boolean graphed : new boolean[]{false, true}) {
                String message = "size " + size + (graphed ? ", graphed" : "");
                DataBuffer buffer = new DataBuffer("b", "", size, null);
                List<Double> model = new ArrayList<>();
                double next = 0;
                for (int step = 0; step < 3000; step++) {
                    if (graphed && step % 50 == 0)
                        buffer.getFloatBuffer(); //Attaches the float copy, which sends small arrays through append(double)
                    if (random.nextBoolean()) {
                        buffer.append(next);
                        appendToModel(model, size, next++);
                    } else {
                        int count = random.nextInt(random.nextBoolean() ? 4 : 2 * Math.max(size, 10));
                        Double[] values = new Double[count];
                        for (int i = 0; i < count; i++) {
                            values[i] = next;
                            appendToModel(model, size, next++);
                        }
                        buffer.append(values, count);
                    }
                    if (!model.isEmpty())
                        assertEquals(message + ": last value at step " + step, model.get(model.size() - 1), buffer.value, 0.0);
                }
                assertValues(message, model, buffer);
                if (graphed) {
                    FloatBufferRepresentation floats = buffer.getFloatBuffer();
                    assertEquals(message + ": float copy size", model.size(), floats.size);
                    for (int i = 0; i < model.size(); i++)
                        assertEquals(message + ": float " + i, model.get(i).floatValue(), floats.data.get(floats.offset + i), 0.0f);
                }
            }
        }
    }

    //Upstream copied the remainder with subList(count, size), which fails while the buffer is not yet full
    @Test
    public void anArrayThatOverflowsAPartlyFilledBufferKeepsTheNewestValues() {
        DataBuffer buffer = new DataBuffer("b", "", 10, null);
        List<Double> model = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            buffer.append(i);
            appendToModel(model, 10, i);
        }
        buffer.append(new Double[]{100.0, 101.0, 102.0, 103.0, 104.0}, 5);
        for (double v = 100; v < 105; v++)
            appendToModel(model, 10, v);
        assertValues("overflow", model, buffer);
    }

    @Test
    public void aCopyHasTheValuesAndTheLastOne() {
        DataBuffer buffer = new DataBuffer("b", "group", 3, null);
        buffer.isStatic = true;
        for (int i = 0; i < 5; i++)
            buffer.append(i);
        DataBuffer copy = buffer.copy();
        List<Double> expected = Arrays.asList(2.0, 3.0, 4.0);
        assertValues("copy", expected, copy);
        assertEquals(4.0, copy.value, 0.0);
        assertEquals(3, copy.size);
        assertTrue(copy.isStatic);

        copy.append(5);
        assertValues("original after the copy changed", expected, buffer);

        DataBuffer empty = new DataBuffer("e", "", 0, null).copy();
        assertEquals(0, empty.getFilledSize());
        assertTrue(Double.isNaN(empty.value));
    }

    @Test
    public void minAndMaxSkipNaNAndInfinity() {
        DataBuffer buffer = new DataBuffer("b", "", 0, null);
        assertTrue(Double.isNaN(buffer.getMin()));
        buffer.append(new Double[]{Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}, 3);
        assertTrue(Double.isNaN(buffer.getMin()));
        assertTrue(Double.isNaN(buffer.getMax()));
        buffer.append(new Double[]{3.0, -2.0, Double.NaN, 7.5, Double.NEGATIVE_INFINITY}, 5);
        assertEquals(-2.0, buffer.getMin(), 0.0);
        assertEquals(7.5, buffer.getMax(), 0.0);
        buffer.append(-4.0);
        assertEquals(-4.0, buffer.getMin(), 0.0);
        assertEquals(7.5, buffer.getMax(), 0.0);
    }

    @Test
    public void aMissingArrayEntryIsStoredAsNaN() {
        DataBuffer buffer = new DataBuffer("b", "", 0, null);
        buffer.append(new Double[]{1.0, null, 3.0}, 3);
        Double[] values = buffer.getArray();
        assertEquals(1.0, values[0], 0.0);
        assertTrue(Double.isNaN(values[1]));
        assertEquals(3.0, values[2], 0.0);
        buffer.append(new Double[]{null}, 1);
        assertTrue(Double.isNaN(buffer.value));
    }

    @Test
    public void theShortArrayScalesToTheShortRange() {
        DataBuffer buffer = new DataBuffer("b", "", 0, null);
        buffer.append(new Double[]{0.0, 0.5, -1.0, 1.0}, 4);
        short[] shorts = buffer.getShortArray();
        assertEquals(4, shorts.length);
        assertEquals(0, shorts[0]);
        assertEquals((short) (0.5 * Short.MAX_VALUE), shorts[1]);
        assertEquals(-Short.MAX_VALUE, shorts[2]);
        assertEquals(Short.MAX_VALUE, shorts[3]);
    }
}
