package de.rwth_aachen.phyphox;

import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.DoubleBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;

//The file a ChunkedDoubles writes its full chunks to, as little-endian binary64 values one chunk after
//the other (the data file layout of a saved state). The chunks are read back through memory-mapped
//regions: their pages belong to the file and are not part of the Java heap, so the system can write
//them out and drop them when memory gets scarce.
//Writes go through the channel at a position, so a full disk shows as an IOException, after which
//the file takes no more chunks, and never as a SIGBUS on a mapped page. Every slot is written once
//(see ChunkedDoubles); a restart deletes the file and begins a new one under the same name, and the
//mappings of the old one stay valid as long as a snapshot or copy reads them.
final class ChunkFile {
    private static final String TAG = "ChunkFile";
    static final int CHUNK_BYTES = 8 * ChunkedDoubles.CHUNK;
    static final int REGION_CHUNKS = 128; //Mapped 1 MB at a time
    private static final long REGION_BYTES = (long) REGION_CHUNKS * CHUNK_BYTES;

    private final File file;
    private RandomAccessFile access;
    private FileChannel channel;
    private int written = 0; //Chunks in the file
    private boolean failed = false;
    private boolean ended = false;
    private MappedByteBuffer region; //The region of the latest chunk
    private long regionIndex = -1;
    private final ByteBuffer bytes = ByteBuffer.allocate(CHUNK_BYTES).order(ByteOrder.LITTLE_ENDIAN);

    ChunkFile(File file) {
        this.file = file;
    }

    File file() {
        return file;
    }

    //Chunks written to the file since it was started
    int written() {
        return written;
    }

    //False once a write has failed; the file then takes no more chunks
    boolean usable() {
        return !failed;
    }

    //Appends a full chunk and returns a read-only view of it in the file, or null if it could not be
    //written (the chunk then stays where it is)
    DoubleBuffer append(double[] chunk) {
        if (failed)
            return null;
        try {
            writeAt(written, chunk, chunk.length);
            DoubleBuffer view = view(written);
            written++;
            return view;
        } catch (IOException | RuntimeException e) {
            Log.e(TAG, "Could not write to " + file + ", keeping the data in memory: " + e.getMessage());
            fail();
            return null;
        }
    }

    //Writes the first count values of the chunk that is not yet full into the slot after the last full
    //chunk and cuts the file there, so it holds every value up to now and nothing else. The slot is
    //written again when the chunk is full; no view covers it before that. Cutting the room a mapped
    //region added is safe as the views only cover written chunks, which stay inside the file.
    boolean writeTail(double[] chunk, int from, int count) {
        if (failed)
            return false;
        try {
            if (from == 0) {
                writeAt(written, chunk, count);
            } else {
                double[] values = new double[count];
                System.arraycopy(chunk, from, values, 0, count);
                writeAt(written, values, count);
            }
            channel.truncate((long) written * CHUNK_BYTES + 8L * count);
            return true;
        } catch (IOException | RuntimeException e) {
            Log.e(TAG, "Could not write to " + file + ", keeping the data in memory: " + e.getMessage());
            fail();
            return false;
        }
    }

    //Deletes the file and begins a new, empty one under the same name with the next chunk (after end()
    //it stays closed)
    void restart() {
        close();
        if (written > 0 || file.exists())
            //The mappings of the deleted file stay valid for the snapshots and copies that still read them
            if (!file.delete() && file.exists())
                Log.w(TAG, "Could not delete " + file);
        written = 0;
        failed = ended;
    }

    //Closes the channel; the views stay valid (a mapping does not depend on its channel)
    void close() {
        region = null;
        regionIndex = -1;
        if (access != null) {
            try {
                access.close();
            } catch (IOException ignored) {
            }
        }
        access = null;
        channel = null;
    }

    private void fail() {
        failed = true;
        close();
    }

    //Closes the file for good: it takes no more chunks and is never opened again (the run folder is
    //deleted). Call under the data lock, like every other use by a store.
    void end() {
        ended = true;
        fail();
    }

    private void open() throws IOException {
        if (channel != null)
            return;
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs())
            throw new IOException("Could not create " + parent);
        access = new RandomAccessFile(file, "rw");
        channel = access.getChannel();
    }

    private void writeAt(int slot, double[] values, int count) throws IOException {
        open();
        bytes.clear();
        bytes.asDoubleBuffer().put(values, 0, count);
        bytes.limit(8 * count);
        long position = (long) slot * CHUNK_BYTES;
        while (bytes.hasRemaining())
            position += channel.write(bytes, position);
    }

    //Read-only views of the first chunks of an existing file, e.g. the data file of a saved state.
    //The file must hold them all; the views stay valid when the file is closed or deleted.
    static DoubleBuffer[] mapChunks(File file, int chunks) throws IOException {
        DoubleBuffer[] views = new DoubleBuffer[chunks];
        try (RandomAccessFile access = new RandomAccessFile(file, "r")) {
            FileChannel channel = access.getChannel();
            long length = channel.size();
            if (length < (long) chunks * CHUNK_BYTES)
                throw new IOException(file + " holds fewer than " + chunks + " chunks");
            MappedByteBuffer region = null;
            for (int slot = 0; slot < chunks; slot++) {
                if (slot % REGION_CHUNKS == 0) {
                    long start = (long) slot * CHUNK_BYTES;
                    region = channel.map(FileChannel.MapMode.READ_ONLY, start, Math.min(REGION_BYTES, length - start));
                }
                views[slot] = slice(region, slot);
            }
        }
        return views;
    }

    private static DoubleBuffer slice(MappedByteBuffer region, int slot) {
        ByteBuffer slice = region.duplicate();
        int start = (slot % REGION_CHUNKS) * CHUNK_BYTES;
        slice.position(start);
        slice.limit(start + CHUNK_BYTES);
        return slice.slice().order(ByteOrder.LITTLE_ENDIAN).asDoubleBuffer();
    }

    //A read-only view of a written slot. The region is mapped from the writable channel, which
    //extends the file to the end of the region; the room behind the last chunk stays a hole.
    private DoubleBuffer view(int slot) throws IOException {
        long index = slot / REGION_CHUNKS;
        if (index != regionIndex) {
            region = channel.map(FileChannel.MapMode.READ_ONLY, index * REGION_BYTES, REGION_BYTES);
            regionIndex = index;
        }
        return slice(region, slot);
    }
}
