package de.rwth_aachen.phyphox;

import java.io.Serializable;
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
class ChunkedDoubles implements Serializable {
    static final int SHIFT = 10;
    static final int CHUNK = 1 << SHIFT; //1024 values, 8 kB
    private static final int MASK = CHUNK - 1;
    private static final int FIRST_CAPACITY = 16;

    private double[][] chunks = new double[0][];
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
        return chunks[position >>> SHIFT][position & MASK];
    }

    double getLast() {
        return get(size - 1);
    }

    void add(double value) {
        if (chunkCount == 0) {
            if (chunks.length == 0)
                chunks = new double[1][];
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
                if (chunkCount == chunks.length)
                    chunks = Arrays.copyOf(chunks, 2 * chunkCount);
                last = new double[CHUNK];
                chunks[chunkCount++] = last;
                offset = 0;
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
            Arrays.fill(chunks, chunkCount - drop, chunkCount, null);
            chunkCount -= drop;
            head -= drop << SHIFT;
        }
    }

    void clear() {
        chunks = new double[0][];
        chunkCount = 0;
        head = 0;
        size = 0;
    }

    //An independent copy, without the unused room of this one
    ChunkedDoubles copy() {
        ChunkedDoubles copy = new ChunkedDoubles();
        if (size == 0)
            return copy;
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
        copy.chunkCount = copy.chunks.length;
        copy.size = size;
        return copy;
    }

    //Copies count values from index from on into target, chunk by chunk
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
        return new Snapshot(Arrays.copyOf(chunks, chunkCount), head, size);
    }

    //The values of the moment it was taken, sharing the chunks (see above). Take it under the data lock
    //like any other read; it may be read later from any thread.
    static final class Snapshot implements Iterable<Double> {
        private final double[][] chunks;
        private final int head;
        private final int size;

        private Snapshot(double[][] chunks, int head, int size) {
            this.chunks = chunks;
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
            return chunks[position >>> SHIFT][position & MASK];
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
