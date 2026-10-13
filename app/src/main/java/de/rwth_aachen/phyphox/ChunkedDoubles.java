package de.rwth_aachen.phyphox;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.nio.DoubleBuffer;
import java.util.Arrays;
import java.util.Iterator;
import java.util.NoSuchElementException;

//The values of a DataBuffer as plain doubles in arrays ("chunks") of up to CHUNK values. A value
//takes 8 bytes, where a LinkedList<Double> takes about 40 (a 24 byte node and a 16 byte Double).
//Appending never copies more than one small chunk and removing from the front (a buffer with a
//fixed size) drops whole chunks, so both stay fast however long the buffer grows.
//Layout: a single chunk may be smaller than CHUNK and grows by copying; once there is more than
//one, every chunk holds exactly CHUNK values. Value i sits at position head + i counted across the
//chunks.
//Every slot is written once: values are only appended behind the last one, a small chunk that is
//compacted or grown is copied into a new array and clear() starts over with new arrays. Hence a
//Snapshot can share the chunks without copying a value and keeps its values while the buffer goes
//on, which lets the export, the saved state and the remote server read without holding the data
//lock and without a copy of the data.
//A full chunk can also live in a file instead of the heap (see ChunkFile): once spillTo() is called,
//every chunk that fills up is written to the file and read back through a mapped view, so a long
//measurement keeps only its last chunk on the heap. The chunks in the file are always the first ones,
//in order, and the last chunk is always an array; the file then holds the values from the first one
//on, a data file of a saved state. Removing values from the front ends the spilling, as the file
//would no longer match.
class ChunkedDoubles implements Serializable {
    static final int SHIFT = 10;
    static final int CHUNK = 1 << SHIFT; //1024 values, 8 kB
    private static final int MASK = CHUNK - 1;
    private static final int FIRST_CAPACITY = 16;

    //Chunk c is the array chunks[c], or the view mapped[c] into a file if that array is null
    private transient double[][] chunks = new double[0][];
    private transient DoubleBuffer[] mapped = new DoubleBuffer[0];
    private transient ChunkFile file; //Where full chunks go, null to keep them on the heap
    private transient int spilled = 0; //The first spilled chunks are views into a file
    private int chunkCount = 0;
    private int head = 0; //Position of the first value in the first chunk
    private int size = 0;

    int size() {
        return size;
    }

    double get(int i) {
        if (i < 0 || i >= size)
            throw new IndexOutOfBoundsException("Index " + i + ", size " + size);
        int position = head + i;
        double[] chunk = chunks[position >>> SHIFT];
        return chunk != null ? chunk[position & MASK] : mapped[position >>> SHIFT].get(position & MASK);
    }

    double getLast() {
        return get(size - 1);
    }

    void add(double value) {
        if (chunkCount == 0) {
            if (chunks.length == 0) {
                chunks = new double[1][];
                mapped = new DoubleBuffer[1];
            }
            chunks[0] = new double[FIRST_CAPACITY];
            chunkCount = 1;
            head = 0;
        }
        double[] last = chunks[chunkCount - 1];
        int offset = head + size - ((chunkCount - 1) << SHIFT); //Position in the last chunk
        if (offset == last.length) {
            if (last.length < CHUNK) {
                //The only chunk is full: a new array, twice as large unless at most half of it is in use
                int capacity = size <= last.length / 2 ? last.length : Math.min(2 * last.length, CHUNK);
                double[] grown = new double[capacity];
                System.arraycopy(last, head, grown, 0, size);
                chunks[0] = grown;
                last = grown;
                head = 0;
                offset = size;
            } else {
                if (chunkCount == chunks.length) {
                    chunks = Arrays.copyOf(chunks, 2 * chunkCount);
                    mapped = Arrays.copyOf(mapped, 2 * chunkCount);
                }
                last = new double[CHUNK];
                chunks[chunkCount++] = last;
                offset = 0;
                if (file != null)
                    spill(chunkCount - 2);
            }
        }
        last[offset] = value;
        size++;
    }

    //Removes the first n values (all of them if n is larger than the size)
    void removeFirst(int n) {
        if (n <= 0)
            return;
        n = Math.min(n, size);
        head += n;
        size -= n;
        int drop = head >>> SHIFT; //Chunks that hold no value any more. Only full-sized ones can.
        if (drop > 0) {
            System.arraycopy(chunks, drop, chunks, 0, chunkCount - drop);
            System.arraycopy(mapped, drop, mapped, 0, chunkCount - drop);
            Arrays.fill(chunks, chunkCount - drop, chunkCount, null);
            Arrays.fill(mapped, chunkCount - drop, chunkCount, null);
            chunkCount -= drop;
            head -= drop << SHIFT;
            spilled = Math.max(0, spilled - drop);
        }
        file = null;
    }

    void clear() {
        chunks = new double[0][];
        mapped = new DoubleBuffer[0];
        chunkCount = 0;
        head = 0;
        size = 0;
        spilled = 0;
        if (file != null)
            file.restart();
    }

    //From now on full chunks go to the file, beginning with those already full
    void spillTo(ChunkFile file) {
        this.file = file;
        for (int c = 0; c < chunkCount - 1; c++)
            spill(c);
    }

    ChunkFile spillFile() {
        return file;
    }

    //Chunks that live in a file and not on the heap
    int mappedChunks() {
        int n = 0;
        for (int c = 0; c < chunkCount; c++)
            if (chunks[c] == null && mapped[c] != null)
                n++;
        return n;
    }

    //Writes chunk c, the next one in order and full, to the file. If the file fails the chunk stays
    //an array and the spilling ends.
    private void spill(int c) {
        if (c != spilled || head != 0 || file.written() != spilled || chunks[c].length != CHUNK) {
            file = null;
            return;
        }
        DoubleBuffer view = file.append(chunks[c]);
        if (view == null) {
            file = null;
            return;
        }
        mapped[c] = view;
        chunks[c] = null;
        spilled++;
    }

    //Brings the file up to every value by writing those of the last chunk into the slot after the
    //full ones. False if the store does not spill (any more).
    boolean writeTail() {
        if (file == null || !file.usable() || head != 0 || spilled != Math.max(0, chunkCount - 1))
            return false;
        if (chunkCount == 0)
            return true;
        return file.writeTail(chunks[chunkCount - 1], 0, size - (spilled << SHIFT));
    }

    //An independent copy, without the unused room of this one. Chunks in a file are shared (they never
    //change), the rest is copied; the copy does not write to the file.
    ChunkedDoubles copy() {
        ChunkedDoubles copy = new ChunkedDoubles();
        if (size == 0)
            return copy;
        if (spilled > 0) {
            copy.chunks = new double[chunkCount][];
            copy.mapped = Arrays.copyOf(mapped, chunkCount);
            for (int c = spilled; c < chunkCount; c++)
                copy.chunks[c] = chunks[c].clone();
            copy.chunkCount = chunkCount;
            copy.spilled = spilled;
            copy.head = head;
            copy.size = size;
            return copy;
        }
        if (size <= CHUNK) {
            copy.chunks = new double[][]{new double[Math.max(size, FIRST_CAPACITY)]};
            copyTo(copy.chunks[0], 0, size);
        } else {
            copy.chunks = new double[(size + MASK) >>> SHIFT][];
            for (int c = 0; c < copy.chunks.length; c++) {
                copy.chunks[c] = new double[CHUNK];
                copyTo(copy.chunks[c], c << SHIFT, Math.min(CHUNK, size - (c << SHIFT)));
            }
        }
        copy.mapped = new DoubleBuffer[copy.chunks.length];
        copy.chunkCount = copy.chunks.length;
        copy.size = size;
        return copy;
    }

    //Copies count values from index from on into target, chunk by chunk. Only for arrays.
    private void copyTo(double[] target, int from, int count) {
        int done = 0;
        while (done < count) {
            int position = head + from + done;
            double[] chunk = chunks[position >>> SHIFT];
            int offset = position & MASK;
            int n = Math.min(count - done, chunk.length - offset);
            System.arraycopy(chunk, offset, target, done, n);
            done += n;
        }
    }

    Snapshot snapshot() {
        return new Snapshot(Arrays.copyOf(chunks, chunkCount), Arrays.copyOf(mapped, chunkCount), head, size);
    }

    //Serialized as the plain values; a file stays behind
    private void writeObject(ObjectOutputStream out) throws IOException {
        out.defaultWriteObject();
        for (int i = 0; i < size; i++)
            out.writeDouble(get(i));
    }

    private void readObject(ObjectInputStream in) throws IOException, ClassNotFoundException {
        in.defaultReadObject();
        int n = size;
        chunks = new double[0][];
        mapped = new DoubleBuffer[0];
        chunkCount = 0;
        head = 0;
        size = 0;
        spilled = 0;
        for (int i = 0; i < n; i++)
            add(in.readDouble());
    }

    //The values of the moment it was taken, sharing the chunks (see above). Take it under the data lock
    //like any other read; it may be read later from any thread.
    static final class Snapshot implements Iterable<Double> {
        private final double[][] chunks;
        private final DoubleBuffer[] mapped;
        private final int head;
        private final int size;

        private Snapshot(double[][] chunks, DoubleBuffer[] mapped, int head, int size) {
            this.chunks = chunks;
            this.mapped = mapped;
            this.head = head;
            this.size = size;
        }

        int size() {
            return size;
        }

        double get(int i) {
            if (i < 0 || i >= size)
                throw new IndexOutOfBoundsException("Index " + i + ", size " + size);
            int position = head + i;
            double[] chunk = chunks[position >>> SHIFT];
            return chunk != null ? chunk[position & MASK] : mapped[position >>> SHIFT].get(position & MASK);
        }

        Double[] toBoxedArray() {
            Double[] array = new Double[size];
            for (int i = 0; i < size; i++)
                array[i] = get(i);
            return array;
        }

        @Override
        public Iterator<Double> iterator() {
            return new Iterator<Double>() {
                private int next = 0;

                @Override
                public boolean hasNext() {
                    return next < size;
                }

                @Override
                public Double next() {
                    if (next >= size)
                        throw new NoSuchElementException();
                    return get(next++);
                }
            };
        }
    }
}
