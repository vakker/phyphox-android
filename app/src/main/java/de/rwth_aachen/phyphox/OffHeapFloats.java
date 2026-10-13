package de.rwth_aachen.phyphox;

import android.os.Build;
import android.os.SharedMemory;
import android.system.ErrnoException;
import android.util.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

//Float buffers for the graphs outside the Java heap. ART counts ByteBuffer.allocateDirect against the
//heap limit, so in a long measurement the graphs' copies of their data (4 bytes per value, up to twice
//that with the room to grow) took a large part of it. Shared memory (Android 8.1) is not part of the
//heap: the system can swap it out, and the mapping goes when the buffer is garbage collected. Below
//that, for small buffers, or if it fails, a direct buffer as before.
public final class OffHeapFloats {
    private static final String TAG = "OffHeapFloats";
    static final int MIN_FLOATS = 16384; //64 kB; smaller ones are not worth a mapping of their own

    private OffHeapFloats() {
    }

    public static FloatBuffer allocate(int floats) {
        if (floats >= MIN_FLOATS && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            try {
                SharedMemory memory = SharedMemory.create("phyphox graph", 4 * floats);
                try {
                    return memory.mapReadWrite().order(ByteOrder.nativeOrder()).asFloatBuffer();
                } finally {
                    memory.close(); //The mapping stays valid
                }
            } catch (ErrnoException | RuntimeException e) {
                Log.w(TAG, "No shared memory for " + floats + " floats, using the heap: " + e.getMessage());
            }
        }
        return ByteBuffer.allocateDirect(4 * floats).order(ByteOrder.nativeOrder()).asFloatBuffer();
    }
}
