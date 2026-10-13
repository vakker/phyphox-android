package de.rwth_aachen.phyphox.camera.video;

import static org.junit.Assert.assertEquals;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

//An encoded frame back to the sensor timestamp of its capture result, whether the encoder got the
//sensor's timestamps or the camera service moved them to the monotonic clock
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class VideoInputTimingTest {

    private static final long FRAME = 33333333;
    private static final long SLEPT = 5000000000L; //elapsedRealtimeNanos - nanoTime: the device slept 5 s since boot

    private static long[] sensorTimestamps() {
        long[] recent = new long[64];
        for (int i = 0; i < recent.length; i++)
            recent[i] = 1000000000000L + i * FRAME; //On the clock of elapsedRealtimeNanos
        return recent;
    }

    private static long match(long[] recent, long ptsNanos, long knownOffset) {
        for (long candidate : VideoInput.candidates(ptsNanos, knownOffset, SLEPT)) {
            int i = VideoInput.nearest(recent, recent.length, candidate, 2000000);
            if (i >= 0)
                return recent[i];
        }
        return -1;
    }

    @Test
    public void anEncoderOnTheSensorsClockMatchesDirectly() {
        long[] recent = sensorTimestamps();
        long pts = (recent[10] / 1000) * 1000; //Microseconds in the encoder
        assertEquals(recent[10], match(recent, pts, Long.MIN_VALUE));
    }

    @Test
    public void anEncoderOnTheMonotonicClockMatchesWithTheTimeSlept() {
        long[] recent = sensorTimestamps();
        long pts = ((recent[20] - SLEPT) / 1000) * 1000;
        assertEquals(recent[20], match(recent, pts, Long.MIN_VALUE));
        //Once known, the offset alone
        assertEquals(1, VideoInput.candidates(pts, SLEPT, SLEPT).length);
        assertEquals(recent[20], match(recent, pts, SLEPT));
    }

    @Test
    public void aFrameWithoutItsCaptureResultMatchesNothing() {
        long[] recent = sensorTimestamps();
        long pts = recent[63] + 10 * FRAME;
        assertEquals(-1, match(recent, pts, Long.MIN_VALUE));
        assertEquals("the nearest within the tolerance", 5, VideoInput.nearest(recent, recent.length, recent[5] + 1500000, 2000000));
    }
}
