package de.rwth_aachen.phyphox;

import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

//Databuffer class
//Each databuffer can be identified by a name (mapped in phyphoxExperiment class)

public class DataBuffer implements Serializable {
    public String name; //The key name
    public String clearGroup; // If set, this buffer is excluded from a clear by the user unless the user specifically selects to delete this group
    private ChunkedDoubles buffer; //The actual buffer
    public int size; //The target size
    public double value; //The last added value for easy access and graceful returning NaN for empty buffers
    public boolean isStatic = false; //If set to static, this buffer should only be filled once and cannot be cleared thereafter
    public Double [] init = new Double[0];
    public boolean staticAndSet = false;

    //Analysis modules and graphs can register to receive notifications if a buffer changes.
    public Set<BufferNotification> updateListeners = new HashSet<>();

    //This is not logically connected to the data Buffer itself, but it is more efficient to calculate only changes to the buffer, where we can keep track of those changes
    transient private FloatBufferRepresentation floatCopy = null; //If a float copy has been requested, we keep it around as it will probably be requested again...
    transient private FloatBufferRepresentation floatCopyBarValue = null; //If a float copy for bar charts has been requested, we keep it around as it will probably be requested again...
    transient private FloatBufferRepresentation floatCopyBarAxis = null; //If a float copy for bar charts has been requested, we keep it around as it will probably be requested again...
    transient private List<ExperimentTimeReferenceSet> experimentTimeReferenceSets = null;
    transient public final Object experimentTimeReferenceSetsLock = new Object();
    ExperimentTimeReference experimentTimeReference;
    boolean linearTime = false;
    private double lineWidth = 1.0; //Used to calculate the right geometry of bar charts

    private int floatCopyCapacity = 0;
    private int floatCopyBarValueCapacity = 0;
    private int floatCopyBarAxisCapacity = 0;

    private double min = Double.NaN;
    private double max = Double.NaN;

    //Counted for the run folder (RunFolder), which writes a container again only if it changed and
    //lets one spill only if it was not cleared since its last checkpoint
    private transient int changes = 0;
    private transient int clears = 0;

    //Contructor. Set key name and target size.
    protected DataBuffer(String name, String clearGroup, int size, ExperimentTimeReference experimentTimeReference) {
        this.experimentTimeReference = experimentTimeReference;
        this.size = size;
        this.name = name;
        this.clearGroup = clearGroup;

        //Note on the choice of ChunkedDoubles
        //For data acquisition during an experiment we require the ability to append data
        //consistently fast, which fails for the ArrayList if it needs to be extended and copied to
        //a new memory block, and removing the first element of a fixed-sized buffer would shift
        //the entire array. Hence, phyphox used a LinkedList for both, which takes about 40 bytes
        //per value (a node and a Double object), five times the value itself, and limits how long
        //a measurement at high sensor rates can run. ChunkedDoubles keeps plain doubles in arrays
        //of up to 1024 values: 8 bytes per value, appending and removing from the front stay fast
        //(see there).

        this.buffer = new ChunkedDoubles();

        this.value = Double.NaN;
    }

    //Analysis and view modules can register to learn about updates
    public void register(BufferNotification listener) {
        updateListeners.add(listener);
    }

    public void unregister(BufferNotification listener) {
        updateListeners.remove(listener);
    }

    public void notifyListeners(boolean clear, boolean reset) {
        for (BufferNotification listener : updateListeners) {
            listener.notifyUpdate(clear, reset);
        }
    }

    private void putBarAxisValue(FloatBuffer buffer, double last, double value, int offset) {
        if (Double.isNaN(last) || Double.isNaN(value)) {
            buffer.put(offset, Float.NaN);
            buffer.put(offset + 1, Float.NaN);
            buffer.put(offset + 2, Float.NaN);
            buffer.put(offset + 3, Float.NaN);
            buffer.put(offset + 4, Float.NaN);
            buffer.put(offset + 5, Float.NaN);
        } else {
            float d = (float)((value-last)*(1.-lineWidth)/2.);
            float vOff = (float)value - d;
            float lOff = (float)last + d;
            buffer.put(offset, lOff);
            buffer.put(offset + 1, vOff);
            buffer.put(offset + 2, lOff);
            buffer.put(offset + 3, vOff);
            buffer.put(offset + 4, lOff);
            buffer.put(offset + 5, vOff);
        }
    }

    private void putBarValueValue(FloatBuffer buffer, double last, int offset) {
        if (Double.isNaN(last)) {
            buffer.put(offset, Float.NaN);
            buffer.put(offset + 1, Float.NaN);
            buffer.put(offset + 2, Float.NaN);
            buffer.put(offset + 3, Float.NaN);
            buffer.put(offset + 4, Float.NaN);
            buffer.put(offset + 5, Float.NaN);
        } else {
            buffer.put(offset, 0.f);
            buffer.put(offset + 1, 0.f);
            buffer.put(offset + 2, (float) last);
            buffer.put(offset + 3, (float) last);
            buffer.put(offset + 4, (float) last);
            buffer.put(offset + 5, 0.f);
        }
    }

    //Append a value to the buffer.
    private void append(double value, boolean notify) {
        if (staticAndSet)
            return;
        double last = this.value;
        this.value = value; //Update last value
        if (this.size > 0 && buffer.size()+1 > this.size) { //If the buffer becomes larger than the target size, remove the first item (queue!)
            buffer.removeFirst(1);
            min = Double.NaN;
            max = Double.NaN;
            if (floatCopy != null) {
                synchronized (floatCopy.lock) {
                    floatCopy.offset++;
                    floatCopy.size--;
                }
            }
            if (floatCopyBarAxis != null) {
                synchronized (floatCopyBarAxis.lock) {
                    floatCopyBarAxis.offset+=6;
                    floatCopyBarAxis.size-=6;
                }
            }
            if (floatCopyBarValue != null) {
                synchronized (floatCopyBarValue.lock) {
                    floatCopyBarValue.offset+=6;
                    floatCopyBarValue.size-=6;
                }
            }
            if (experimentTimeReferenceSets != null) {
                synchronized (experimentTimeReferenceSetsLock) {
                    for (int i = experimentTimeReferenceSets.size()-1; i >= 0; i--) {
                        experimentTimeReferenceSets.get(i).index--;
                    }
                    if (experimentTimeReferenceSets.get(0).index < 0) {
                        experimentTimeReferenceSets.get(0).index = 0;
                        experimentTimeReferenceSets.get(0).count--;
                    }
                    if (experimentTimeReferenceSets.get(0).count <= 0) {
                        experimentTimeReferenceSets.remove(0);
                    }
                }
            }

        }
        buffer.add(value);
        changes++;
        if (!Double.isNaN(min) && !Double.isInfinite(min))
            min = Math.min(min, value);
        if (!Double.isNaN(max) && !Double.isInfinite(max))
            max = Math.max(max, value);

        if (floatCopy != null) {
            synchronized (floatCopy.lock) {
                if (floatCopyCapacity < floatCopy.offset + floatCopy.size + 1) {
                    if (floatCopyCapacity < buffer.size() * 2)
                        floatCopyCapacity *= 2;
                    FloatBuffer newData = OffHeapFloats.allocate(floatCopyCapacity);
                    floatCopy.data.position(floatCopy.offset);
                    newData.put(floatCopy.data);
                    floatCopy.data = newData;
                    floatCopy.offset = 0;
                }
                floatCopy.data.put(floatCopy.offset + floatCopy.size, (float) value);
                floatCopy.size++;
            }
        }

        if (floatCopyBarValue != null) {
            synchronized (floatCopyBarValue.lock) {
                if (floatCopyBarValueCapacity < floatCopyBarValue.offset + floatCopyBarValue.size + 6) {
                    if (floatCopyBarValueCapacity < buffer.size() * 2 * 6)
                        floatCopyBarValueCapacity *= 2;
                    FloatBuffer newData = OffHeapFloats.allocate(floatCopyBarValueCapacity);
                    floatCopyBarValue.data.position(floatCopyBarValue.offset);
                    newData.put(floatCopyBarValue.data);
                    floatCopyBarValue.data = newData;
                    floatCopyBarValue.offset = 0;
                }

                putBarValueValue(floatCopyBarValue.data, last, floatCopyBarValue.offset + floatCopyBarValue.size);
                floatCopyBarValue.size += 6;
            }
        }
        if (floatCopyBarAxis != null) {
            synchronized (floatCopyBarAxis.lock) {
                if (floatCopyBarAxisCapacity < floatCopyBarAxis.offset + floatCopyBarAxis.size + 6) {
                    if (floatCopyBarAxisCapacity < buffer.size() * 2 * 6)
                        floatCopyBarAxisCapacity *= 2;
                    FloatBuffer newData = OffHeapFloats.allocate(floatCopyBarAxisCapacity);
                    floatCopyBarAxis.data.position(floatCopyBarAxis.offset);
                    newData.put(floatCopyBarAxis.data);
                    floatCopyBarAxis.data = newData;
                    floatCopyBarAxis.offset = 0;
                }

                putBarAxisValue(floatCopyBarAxis.data, last, value, floatCopyBarAxis.offset + floatCopyBarAxis.size);
                floatCopyBarAxis.size += 6;
            }
        }
        if (experimentTimeReferenceSets != null) {
            synchronized (experimentTimeReferenceSetsLock) {
                int referenceIndex = linearTime ? experimentTimeReference.getReferenceIndexFromLinearTime(value) : experimentTimeReference.getReferenceIndexFromExperimentTime(value);
                if (experimentTimeReferenceSets.size() > 0) {
                    ExperimentTimeReferenceSet lastSet = experimentTimeReferenceSets.get(experimentTimeReferenceSets.size()-1);
                    if (lastSet.referenceIndex == referenceIndex) {
                        lastSet.count++;
                    } else {
                        experimentTimeReferenceSets.add(new ExperimentTimeReferenceSet(lastSet.index + lastSet.count, 1, experimentTimeReference.getExperimentTimeReferenceByIndex(referenceIndex), experimentTimeReference.getSystemTimeReferenceByIndex(referenceIndex), referenceIndex, experimentTimeReference.getPausedByIndex(referenceIndex)));
                    }
                } else {
                    experimentTimeReferenceSets.add(new ExperimentTimeReferenceSet(0, 1, experimentTimeReference.getExperimentTimeReferenceByIndex(referenceIndex), experimentTimeReference.getSystemTimeReferenceByIndex(referenceIndex), referenceIndex, experimentTimeReference.getPausedByIndex(referenceIndex)));
                }
            }
        }

        if (notify) {
            notifyListeners(false, false);
        }
    }

    public void append(double value) {
        append(value, true);
    }

    //Get the number of elements actually filled into the buffer
    public int getFilledSize() {
        return buffer.size();
    }

    //Append a double-array with [count] entries.
    public void append(Double value[], Integer count, boolean notify) {
        if (staticAndSet)
            return;
        if (4*count < buffer.size() && (!Double.isNaN(min) || !Double.isNaN(max) || floatCopy != null || floatCopyBarValue != null || floatCopyBarAxis != null || experimentTimeReferenceSets != null)) {
            // In these cases the buffer is connected to a view element and adding every item individually is probably faster
            for (int i = 0; i < count; i++) {
                append(unboxed(value[i]), false);
            }
        } else {
            //Just dump the data into the array and make sure it does not overflow.
            if (count > 0)
                this.value = unboxed(value[count - 1]); //Update last value
            int first = 0;
            if (this.size > 0) {
                if (count >= this.size) {
                    buffer.clear();
                    first = count - this.size;
                } else if (buffer.size() + count > this.size) { //If the buffer becomes larger than the target size, remove the first items (queue!)
                    buffer.removeFirst(buffer.size() + count - this.size);
                }
            }
            for (int i = first; i < count; i++)
                buffer.add(unboxed(value[i]));
            changes++;
            min = Double.NaN;
            max = Double.NaN;
            floatCopy = null;
            floatCopyBarValue = null;
            floatCopyBarAxis = null;
            experimentTimeReferenceSets = null;
        }

        if (notify)
            notifyListeners(false, false);
    }

    public void append(Double value[], Integer count) {
        append(value, count, true);
    }

    //A missing value (an array entry never set) is stored as NaN
    private static double unboxed(Double value) {
        return value == null ? Double.NaN : value;
    }

    //Append a short-array with [count] entries. This will be scaled to [-1:+1] and is used for audio data
    public void append(short value[], int count) {
        for (int i = 0; i < count; i++)
            append((double)value[i]/(double)Short.MAX_VALUE, false); //Normalize to [-1:+1] and append
        notifyListeners(false, false);
    }

    //Append a float-array with [count] entries. This is used for audio data
    public void append(float value[], int count) {
        for (int i = 0; i < count; i++)
            append(value[i], false); //Normalize to [-1:+1] and append
        notifyListeners(false, false);
    }

    //Wrapper function to set this buffer's static-state
    public void setStatic(boolean isStatic) {
        this.isStatic = isStatic;
    }

    //Wrapper function to set this buffer's static-state
    public void setInit(Double[] init) {
        this.init = init;
        this.append(init, init.length, false);
        if (init.length > 0)
            markSet();
    }

    //Delete all data and set last item to NaN (if not static)
    public void clear(boolean reset, boolean notify) {
        if (isStatic) {
            if (reset)
                staticAndSet = false;
            else
                return;
        }
        discard();

        if (reset)
            this.append(init, init.length);

        if (notify)
            notifyListeners(true, reset);
    }

    //Replaces the contents with the values of a saved state, whatever init put here; the newest size
    //values if there are more, and a static buffer that received data counts as filled
    //(saved-states.md, "Loading semantics")
    //Read from the data file of the state with count values: a container without a fixed size maps
    //the file's full chunks instead of reading every value onto the heap
    public void restoreFrom(File file, int count) throws IOException {
        staticAndSet = false;
        discard();
        int keep = this.size > 0 ? Math.min(count, this.size) : count;
        buffer = ChunkedDoubles.fromFile(file, count - keep, keep);
        changes++;
        if (keep > 0)
            value = buffer.getLast();
        if (count > 0)
            markSet();
    }

    private void discard() {
        buffer.clear();
        changes++;
        clears++;
        value = Double.NaN;
        if (floatCopy != null) {
            synchronized (floatCopy.lock) {
                //Instead of just resetting the offset and length to zero, we abandon the buffer, so
                //a new one will be created. The reason to do this is that the copy of the old
                //buffer remains in tact if a graph has just received it as a data source. So the
                //data is available until the graph gets a new reference and it is removed by the
                //garbage collector. Otherwise the reference might be reset before the graph is
                //redrawn which results in flickering.
                floatCopy = null;
            }
        }
        if (floatCopyBarValue != null) {
            //see above
            synchronized (floatCopyBarValue.lock) {
                floatCopyBarValue = null;
            }
        }
        if (floatCopyBarAxis != null) {
            //see above
            synchronized (floatCopyBarAxis.lock) {
                floatCopyBarAxis = null;
            }
        }
        if (experimentTimeReferenceSets != null) {
            synchronized (experimentTimeReferenceSetsLock) {
                experimentTimeReferenceSets = null;
            }
        }
        min = Double.NaN;
        max = Double.NaN;
    }

    public void clear(boolean reset) {
        clear(reset, true);
    }

    public void markSet() {
        if (isStatic)
            staticAndSet = true;
    }

    //Retrieve the iterator, over the values of this moment
    public Iterator<Double> getIterator() {
        return buffer.snapshot().iterator();
    }

    //Get all values as a double array
    public Double[] getArray() {
        return buffer.snapshot().toBoxedArray();
    }

    //All values without copying them, for reading a large buffer (see ChunkedDoubles.Snapshot)
    ChunkedDoubles.Snapshot getSnapshot() {
        return buffer.snapshot();
    }

    int changeCount() {
        return changes;
    }

    int clearCount() {
        return clears;
    }

    //Full chunks go to the file from now on (ChunkedDoubles.spillTo), until a clear
    void spillTo(ChunkFile file) {
        buffer.spillTo(file);
    }

    //The file the values spill into, null if they stay on the heap
    ChunkFile spillFile() {
        return buffer.spillFile();
    }

    //Brings the spill file up to every value; false if there is none
    boolean writeTail() {
        return buffer.writeTail();
    }

    //Chunks of values that live in a file and not on the heap
    int chunksOnDisk() {
        return buffer.mappedChunks();
    }

    public FloatBufferRepresentation getFloatBuffer() {
        int n = buffer.size();
        if (n == 0)
            return new FloatBufferRepresentation(null, 0, 0);

        if (floatCopy == null) {
            FloatBuffer data = OffHeapFloats.allocate(n);
            floatCopyCapacity = n;
            int i = 0;
            while (i < n) {
                double x = buffer.get(i);
                if ((Double.isNaN(x) || Double.isInfinite(x)))
                    data.put(-3.4e38f);
                else
                    data.put((float) x);
                //This is a bit hacky, but should work in any reasonable situation. Some OpenGL ES
                // implementations (HTC One X, some Samsungs, ...) seem to not properly handle NaN,
                // which makes it impossible to detect invalid data points in the vertex and/or
                // fragment shader. The behavior seems to be unspecified with some devices
                // interpreting NaN as zero (Samsung?) and some devices failing all subsequent
                // calculations ans eventually interpreting the resulting NaN as zero (HTC One X
                // draws a line to the canvas zero coordinate.
                // The value 3.4e38f is close to the smallest possible number represented by a
                // float32 (I do not dare to use the exact minimum as it might be altered by
                // rounding or shader optimization), so it should not occur by accident in any
                // reasonable use case and we use it to tag invalid values. The vertex shader will
                // simply check for values below -3.3e38f and mark them for the fragment shader to
                // be discarded.
                i++;
            }
            floatCopy = new FloatBufferRepresentation(data, 0, n);
        }
        return floatCopy;
    }

    public FloatBufferRepresentation getFloatBufferBarAxis(double lineWidth) {
        this.lineWidth = lineWidth;
        int n = buffer.size()*6;
        if (n <= 0)
            return new FloatBufferRepresentation(null, 0, 0);

        if (floatCopyBarAxis == null) {
            FloatBuffer data = OffHeapFloats.allocate(n);
            floatCopyBarAxisCapacity = n;
            int i = 0;
            double last = Double.NaN;
            while (i < n) {
                double value = buffer.get(i/6);
                putBarAxisValue(data, last, value, i);
                last = value;
                i+=6;
            }
            floatCopyBarAxis = new FloatBufferRepresentation(data, 0, n);
        }
        return floatCopyBarAxis;
    }

    public FloatBufferRepresentation getFloatBufferBarValue() {
        int n = buffer.size()*6;
        if (n <= 0)
            return new FloatBufferRepresentation(null, 0, 0);

        if (floatCopyBarValue == null) {
            FloatBuffer data = OffHeapFloats.allocate(n);
            floatCopyBarValueCapacity = n;
            int i = 0;
            double last = Double.NaN;
            while (i < n) {
                double value = buffer.get(i/6);
                putBarValueValue(data, last, i);
                last = value;
                i+=6;
            }
            floatCopyBarValue = new FloatBufferRepresentation(data, 0, n);
        }
        return floatCopyBarValue;
    }

    public List<ExperimentTimeReferenceSet> getExperimentTimeReferenceSets(boolean isLinearTime) {
        if (buffer.size() <= 0)
            return new ArrayList<>();

        if (isLinearTime != linearTime) {
            linearTime = isLinearTime;
            synchronized (experimentTimeReferenceSetsLock) {
                experimentTimeReferenceSets = null;
            }
        }

        if (experimentTimeReferenceSets == null && experimentTimeReference.hasMappings()) {
            experimentTimeReferenceSets = new ArrayList<>();
            int lastReferenceIndex = -1;
            int lastchange = 0;
            int i = 0;
            while (i < buffer.size()) {
                double value = buffer.get(i);
                int referenceIndex = isLinearTime ? experimentTimeReference.getReferenceIndexFromLinearTime(value) : experimentTimeReference.getReferenceIndexFromExperimentTime(value);
                if (lastReferenceIndex < 0)
                    lastReferenceIndex = referenceIndex;
                else if (lastReferenceIndex != referenceIndex) {
                    experimentTimeReferenceSets.add(new ExperimentTimeReferenceSet(lastchange, i-lastchange, experimentTimeReference.getExperimentTimeReferenceByIndex(lastReferenceIndex), experimentTimeReference.getSystemTimeReferenceByIndex(lastReferenceIndex), lastReferenceIndex, experimentTimeReference.getPausedByIndex(lastReferenceIndex)));
                    lastchange = i;
                    lastReferenceIndex = referenceIndex;
                }
                i++;
            }
            experimentTimeReferenceSets.add(new ExperimentTimeReferenceSet(lastchange, i-lastchange, experimentTimeReference.getExperimentTimeReferenceByIndex(lastReferenceIndex), experimentTimeReference.getSystemTimeReferenceByIndex(lastReferenceIndex), lastReferenceIndex, experimentTimeReference.getPausedByIndex(lastReferenceIndex)));
        }
        return experimentTimeReferenceSets;
    }

    //Get all values as a short array. The data will be scaled so that (-/+)1 matches (-/+)Short.MAX_VALUE, used for audio data
    public short[] getShortArray() {
        short[] ret = new short[buffer.size()];
        for (int i = 0; i < ret.length; i++)
            ret[i] = (short)(buffer.get(i)*(Short.MAX_VALUE)); //Rescale data to short range
        return ret;
    }

    public DataBuffer copy() {
        DataBuffer db = new DataBuffer(this.name, this.clearGroup, this.size, this.experimentTimeReference);
        db.buffer = this.buffer.copy();
        if (db.buffer.size() > 0)
            db.value = db.buffer.getLast();
        db.isStatic = this.isStatic;
        return db;
    }

    public double getMin() {
        if (!Double.isNaN(min)) {
            return min;
        }

        //The smallest finite value, NaN if there is none
        double result = Double.NaN;
        for (int i = 0; i < buffer.size(); i++) {
            double v = buffer.get(i);
            if (!Double.isNaN(v) && !Double.isInfinite(v) && (Double.isNaN(result) || Double.compare(v, result) < 0))
                result = v;
        }
        min = result;

        return min;
    }

    public double getMax() {
        if (!Double.isNaN(max)) {
            return max;
        }

        //The largest finite value, NaN if there is none
        double result = Double.NaN;
        for (int i = 0; i < buffer.size(); i++) {
            double v = buffer.get(i);
            if (!Double.isNaN(v) && !Double.isInfinite(v) && (Double.isNaN(result) || Double.compare(v, result) > 0))
                result = v;
        }
        max = result;

        return max;
    }
}
