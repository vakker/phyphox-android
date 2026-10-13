package de.rwth_aachen.phyphox;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.nio.FloatBuffer;

//The graphs' float buffers outside the Java heap: direct, of the size asked for, small or large
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class OffHeapFloatsTest {

    @Test
    public void aBufferHoldsWhatIsPutIntoIt() {
        for (int floats : new int[]{1, OffHeapFloats.MIN_FLOATS - 1, OffHeapFloats.MIN_FLOATS, 5 * OffHeapFloats.MIN_FLOATS + 3}) {
            FloatBuffer buffer = OffHeapFloats.allocate(floats);
            assertTrue("direct, for OpenGL", buffer.isDirect());
            assertEquals(floats, buffer.capacity());
            for (int i = 0; i < floats; i++)
                buffer.put(i, i * 0.5f);
            for (int i = 0; i < floats; i++)
                assertEquals(floats + " floats, value " + i, i * 0.5f, buffer.get(i), 0f);
        }
    }
}
