package de.rwth_aachen.phyphox.camera.video;

import android.annotation.SuppressLint;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.SessionConfiguration;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Log;
import android.util.Range;
import android.util.Size;
import android.view.Surface;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Vector;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;

import de.rwth_aachen.phyphox.DataBuffer;
import de.rwth_aachen.phyphox.DataOutput;
import de.rwth_aachen.phyphox.ExperimentTimeReference;

//A video of the measurement for visual odometry (only in this build): Camera2 renders into the input
//surface of an H.264 encoder, and MediaMuxer writes MP4 files of about a minute each (switched at a
//key frame) into media/ of the run folder, so a crash loses at most the last one. Every frame's
//presentation time is its experiment time, the clock of the other sensors, and each capture result
//adds a row: experiment time of the start of the exposure, exposure, rolling-shutter skew, frame
//duration (s), ISO, focus distance (diopters) and frame number.
//The camera's fixed properties (intrinsics, distortion, pose, arrays, the crop of the video) go to
//meta/camera.csv next to it.
@RequiresApi(api = Build.VERSION_CODES.M)
public class VideoInput {
    private static final String TAG = "VideoInput";
    public static final String MEDIA = "media";
    public static final String CAMERA_CSV = "meta/camera.csv";
    public static final String[] COMPONENTS = {"t", "exposure", "skew", "duration", "iso", "focus", "frame"};

    //How to record; every field is an attribute of <video>
    public static class Settings {
        public boolean front = false;
        public int width = 1920;
        public int height = 1080;
        public int rate = 30;
        public int bitrate = 16000000;
        public double focus = 0; //Diopters, 0 is infinity, NaN for continuous autofocus
        public boolean stabilization = false;
        public double segment = 60; //Seconds per file
    }

    private final Settings settings;
    private final Lock dataLock;
    private final ExperimentTimeReference timeReference;
    private final CameraManager cameraManager;
    private final String cameraId;
    public File fallbackDir; //Where the video goes without a run folder
    private final DataBuffer[] buffers = new DataBuffer[COMPONENTS.length];

    private HandlerThread thread;
    private Handler handler;
    private CameraDevice camera;
    private CameraCaptureSession session;
    private MediaCodec encoder;
    private Surface surface;
    private MediaFormat outputFormat;
    private MediaMuxer muxer;
    private int track = -1;
    private File mediaDir;
    private int segmentIndex = 0;
    private double segmentStart = Double.NaN;
    private long lastPts = -1;
    private Size size;
    private CaptureResult firstResult;
    private CountDownLatch stopped;
    private final List<File> segments = Collections.synchronizedList(new ArrayList<>());
    private volatile boolean recording = false;

    //Sensor timestamps of the latest capture results, to give every encoded frame the exact one
    private final long[] recent = new long[64];
    private int recentCount = 0;
    //The encoder's timestamps are the sensor's (Android 13 asks for that) or on the monotonic clock
    private long encoderOffset = Long.MIN_VALUE;
    private long monotonicOffset = 0; //elapsedRealtimeNanos - nanoTime when recording started
    private boolean realtimeSource = true; //The sensor's timestamps are on the clock of elapsedRealtimeNanos

    public VideoInput(Settings settings, Vector<DataOutput> outputs, Lock dataLock, ExperimentTimeReference timeReference, CameraManager cameraManager) {
        this.settings = settings;
        this.dataLock = dataLock;
        this.timeReference = timeReference;
        this.cameraManager = cameraManager;
        this.cameraId = findCamera(cameraManager, settings.front);
        if (outputs != null)
            for (int i = 0; i < buffers.length && i < outputs.size(); i++)
                buffers[i] = outputs.get(i) != null ? outputs.get(i).buffer : null;
    }

    //The first camera facing the given way, null if there is none
    static String findCamera(CameraManager cameraManager, boolean front) {
        try {
            for (String id : cameraManager.getCameraIdList()) {
                Integer facing = cameraManager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == (front ? CameraCharacteristics.LENS_FACING_FRONT : CameraCharacteristics.LENS_FACING_BACK))
                    return id;
            }
        } catch (Exception e) {
            Log.e(TAG, "Could not list the cameras: " + e.getMessage());
        }
        return null;
    }

    public Settings settings() {
        return settings;
    }

    public boolean isAvailable() {
        return cameraId != null;
    }

    public List<File> segments() {
        synchronized (segments) {
            return new ArrayList<>(segments);
        }
    }

    public File mediaDir() {
        return mediaDir;
    }

    //Starts recording into dir/media (the run folder) and writes dir/meta/camera.csv
    @SuppressLint("MissingPermission") //The parser asked for it
    public void start(File dir) throws IOException {
        if (recording || cameraId == null)
            return;
        CameraCharacteristics characteristics;
        try {
            characteristics = cameraManager.getCameraCharacteristics(cameraId);
        } catch (Exception e) {
            throw new IOException("No camera " + cameraId + ": " + e.getMessage());
        }
        Integer source = characteristics.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE);
        realtimeSource = source != null && source == CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME;
        monotonicOffset = SystemClock.elapsedRealtimeNanos() - System.nanoTime();
        encoderOffset = Long.MIN_VALUE;
        recentCount = 0;
        size = pickSize(characteristics);
        mediaDir = new File(dir, MEDIA);
        if (!mediaDir.isDirectory() && !mediaDir.mkdirs())
            throw new IOException("Could not create " + mediaDir);
        firstResult = null;
        CameraMetadata.write(new File(dir, CAMERA_CSV), cameraId, characteristics, null, size, settings);

        thread = new HandlerThread("VideoInput");
        thread.start();
        handler = new Handler(thread.getLooper());
        stopped = new CountDownLatch(1);
        recording = true;

        MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, size.getWidth(), size.getHeight());
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        format.setInteger(MediaFormat.KEY_BIT_RATE, settings.bitrate);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, settings.rate);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
        encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
        encoder.setCallback(encoderCallback, handler);
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        surface = encoder.createInputSurface();
        encoder.start();

        try {
            cameraManager.openCamera(cameraId, cameraCallback, handler);
        } catch (Exception e) {
            stop();
            throw new IOException("Could not open camera " + cameraId + ": " + e.getMessage());
        }
    }

    //Stops recording and waits (up to 5 s) until the last file is complete
    public void stop() {
        if (!recording)
            return;
        recording = false;
        Handler h = handler;
        if (h != null)
            h.post(() -> {
                closeCamera();
                if (encoder != null) {
                    try {
                        encoder.signalEndOfInputStream(); //The encoder callback finishes on the end of stream
                    } catch (RuntimeException e) {
                        finish();
                    }
                } else
                    finish();
            });
        try {
            if (!stopped.await(5, TimeUnit.SECONDS)) {
                Log.e(TAG, "The encoder did not finish in time");
                h.post(this::finish);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private Size pickSize(CameraCharacteristics characteristics) {
        StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        Size wanted = new Size(settings.width, settings.height);
        if (map == null)
            return wanted;
        Size[] sizes = map.getOutputSizes(MediaCodec.class);
        if (sizes == null || sizes.length == 0)
            return wanted;
        Size best = sizes[0];
        for (Size s : sizes) {
            if (s.equals(wanted))
                return s;
            if (Math.abs(s.getWidth() - wanted.getWidth()) + Math.abs(s.getHeight() - wanted.getHeight())
                    < Math.abs(best.getWidth() - wanted.getWidth()) + Math.abs(best.getHeight() - wanted.getHeight()))
                best = s;
        }
        return best;
    }

    private final CameraDevice.StateCallback cameraCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(@NonNull CameraDevice device) {
            camera = device;
            if (!recording) {
                closeCamera();
                return;
            }
            try {
                CameraCaptureSession.StateCallback sessionCallback = new CameraCaptureSession.StateCallback() {
                    @Override
                    public void onConfigured(@NonNull CameraCaptureSession s) {
                        session = s;
                        try {
                            s.setRepeatingRequest(request(), captureCallback, handler);
                        } catch (Exception e) {
                            Log.e(TAG, "Could not start the capture: " + e.getMessage());
                        }
                    }

                    @Override
                    public void onConfigureFailed(@NonNull CameraCaptureSession s) {
                        Log.e(TAG, "The capture session could not be configured");
                    }
                };
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    OutputConfiguration output = new OutputConfiguration(surface);
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                        output.setTimestampBase(OutputConfiguration.TIMESTAMP_BASE_SENSOR); //Not moved to the monotonic clock for the encoder
                    device.createCaptureSession(new SessionConfiguration(SessionConfiguration.SESSION_REGULAR,
                            Collections.singletonList(output), handler::post, sessionCallback));
                } else
                    device.createCaptureSession(Collections.singletonList(surface), sessionCallback, handler);
            } catch (Exception e) {
                Log.e(TAG, "Could not create the capture session: " + e.getMessage());
            }
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice device) {
            Log.w(TAG, "Camera disconnected");
            device.close();
            camera = null;
        }

        @Override
        public void onError(@NonNull CameraDevice device, int error) {
            Log.e(TAG, "Camera error " + error);
            device.close();
            camera = null;
        }
    };

    private CaptureRequest request() throws Exception {
        CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(cameraId);
        CaptureRequest.Builder builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
        builder.addTarget(surface);
        builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
        Range<Integer>[] ranges = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
        if (ranges != null) {
            Range<Integer> best = null;
            for (Range<Integer> r : ranges)
                if (r.getUpper() == settings.rate && (best == null || r.getLower() > best.getLower()))
                    best = r;
            if (best != null)
                builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, best);
        }
        if (Double.isNaN(settings.focus))
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
        else {
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF);
            builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, (float) settings.focus);
        }
        if (!settings.stabilization) {
            builder.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF);
            builder.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF);
        }
        return builder.build();
    }

    //The sensor's timestamp on the clock of elapsedRealtimeNanos
    private long toRealtime(long sensorTimestamp) {
        return realtimeSource ? sensorTimestamp : sensorTimestamp + monotonicOffset;
    }

    private final CameraCaptureSession.CaptureCallback captureCallback = new CameraCaptureSession.CaptureCallback() {
        @Override
        public void onCaptureCompleted(@NonNull CameraCaptureSession s, @NonNull CaptureRequest request, @NonNull TotalCaptureResult result) {
            Long timestamp = result.get(CaptureResult.SENSOR_TIMESTAMP);
            if (timestamp == null)
                return;
            synchronized (recent) {
                recent[recentCount % recent.length] = timestamp;
                recentCount++;
            }
            if (firstResult == null) {
                firstResult = result;
                try {
                    //Now with the crop of the video
                    CameraMetadata.write(new File(mediaDir.getParentFile(), CAMERA_CSV), cameraId,
                            cameraManager.getCameraCharacteristics(cameraId), result, size, settings);
                } catch (Exception e) {
                    Log.e(TAG, "Could not write " + CAMERA_CSV + ": " + e.getMessage());
                }
            }
            double t = timeReference.getExperimentTimeFromEvent(toRealtime(timestamp));
            double[] row = {
                    t,
                    nanosToSeconds(result.get(CaptureResult.SENSOR_EXPOSURE_TIME)),
                    nanosToSeconds(result.get(CaptureResult.SENSOR_ROLLING_SHUTTER_SKEW)),
                    nanosToSeconds(result.get(CaptureResult.SENSOR_FRAME_DURATION)),
                    numberOf(result.get(CaptureResult.SENSOR_SENSITIVITY)),
                    numberOf(result.get(CaptureResult.LENS_FOCUS_DISTANCE)),
                    result.getFrameNumber()};
            dataLock.lock();
            try {
                for (int i = 0; i < buffers.length; i++)
                    if (buffers[i] != null)
                        buffers[i].append(row[i]);
            } finally {
                dataLock.unlock();
            }
        }
    };

    private static double nanosToSeconds(Long nanos) {
        return nanos == null ? Double.NaN : nanos * 1e-9;
    }

    private static double numberOf(Number n) {
        return n == null ? Double.NaN : n.doubleValue();
    }

    //The sensor timestamp of an encoded frame: the encoder's own (microseconds) on the sensor's clock
    //or on the monotonic one, whichever matches a capture result within 2 ms, then that result's
    private long sensorTimestampOf(long presentationTimeUs) {
        long pts = presentationTimeUs * 1000;
        long[] candidates = encoderOffset != Long.MIN_VALUE ? new long[]{pts + encoderOffset} : new long[]{pts, pts - monotonicOffset};
        synchronized (recent) {
            int n = Math.min(recentCount, recent.length);
            for (long candidate : candidates) {
                long best = 0;
                long bestDistance = Long.MAX_VALUE;
                for (int i = 0; i < n; i++) {
                    long distance = Math.abs(recent[i] - candidate);
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        best = recent[i];
                    }
                }
                if (bestDistance < 2000000) {
                    if (encoderOffset == Long.MIN_VALUE)
                        encoderOffset = candidate - pts;
                    return best;
                }
            }
        }
        return candidates[0];
    }

    private final MediaCodec.Callback encoderCallback = new MediaCodec.Callback() {
        @Override
        public void onInputBufferAvailable(@NonNull MediaCodec codec, int index) {
            //Input comes through the surface
        }

        @Override
        public void onOutputBufferAvailable(@NonNull MediaCodec codec, int index, @NonNull MediaCodec.BufferInfo info) {
            try {
                if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && info.size > 0)
                    writeFrame(codec.getOutputBuffer(index), info);
                codec.releaseOutputBuffer(index, false);
            } catch (RuntimeException e) {
                Log.e(TAG, "Could not write a frame: " + e.getMessage());
            }
            if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0)
                finish();
        }

        @Override
        public void onError(@NonNull MediaCodec codec, @NonNull MediaCodec.CodecException e) {
            Log.e(TAG, "Encoder error: " + e.getMessage());
            closeCamera();
            finish();
        }

        @Override
        public void onOutputFormatChanged(@NonNull MediaCodec codec, @NonNull MediaFormat format) {
            outputFormat = format;
        }
    };

    private void writeFrame(ByteBuffer data, MediaCodec.BufferInfo info) {
        if (outputFormat == null || data == null)
            return;
        double t = timeReference.getExperimentTimeFromEvent(toRealtime(sensorTimestampOf(info.presentationTimeUs)));
        boolean key = (info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;
        if (muxer == null || (key && t - segmentStart >= settings.segment)) {
            if (!key)
                return; //A file begins with a key frame
            closeSegment();
            File file = new File(mediaDir, String.format(Locale.ROOT, "video-%03d.mp4", segmentIndex++));
            try {
                muxer = new MediaMuxer(file.getPath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
                track = muxer.addTrack(outputFormat);
                muxer.start();
                segments.add(file);
                segmentStart = t;
            } catch (IOException | RuntimeException e) {
                Log.e(TAG, "Could not start " + file + ": " + e.getMessage());
                muxer = null;
                return;
            }
        }
        //The presentation time is the experiment time, strictly increasing
        long pts = Math.max(Math.round(t * 1e6), lastPts + 1);
        lastPts = pts;
        MediaCodec.BufferInfo out = new MediaCodec.BufferInfo();
        out.set(info.offset, info.size, pts, info.flags & ~MediaCodec.BUFFER_FLAG_END_OF_STREAM);
        muxer.writeSampleData(track, data, out);
    }

    private void closeSegment() {
        if (muxer == null)
            return;
        try {
            muxer.stop();
        } catch (RuntimeException e) {
            Log.e(TAG, "Could not finish a video file: " + e.getMessage());
        }
        muxer.release();
        muxer = null;
        track = -1;
    }

    private void closeCamera() {
        if (session != null) {
            try {
                session.close();
            } catch (RuntimeException ignored) {
            }
            session = null;
        }
        if (camera != null) {
            camera.close();
            camera = null;
        }
    }

    //On the thread: the last file, the encoder and the thread end
    private void finish() {
        closeSegment();
        if (encoder != null) {
            try {
                encoder.stop();
            } catch (RuntimeException ignored) {
            }
            encoder.release();
            encoder = null;
        }
        if (surface != null) {
            surface.release();
            surface = null;
        }
        outputFormat = null;
        lastPts = -1;
        segmentStart = Double.NaN;
        if (thread != null)
            thread.quitSafely();
        thread = null;
        handler = null;
        if (stopped != null)
            stopped.countDown();
    }
}
