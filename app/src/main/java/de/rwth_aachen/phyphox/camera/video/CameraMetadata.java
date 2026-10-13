package de.rwth_aachen.phyphox.camera.video;

import android.graphics.Rect;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CaptureResult;
import android.os.Build;
import android.util.Size;
import android.util.SizeF;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

//meta/camera.csv of a video (VideoInput): the camera's fixed properties and the settings of the
//recording, in the dialect of meta/device.csv ("property","value"). For visual odometry the
//intrinsics matter most: Android gives them (fx, fy, cx, cy, s) in pixels of the pre-correction
//active array, and the video shows the largest centred part of the crop region with its aspect
//ratio, scaled to the video size; "video fx" to "video cy" are the intrinsics converted to the
//pixels of the video that way.
final class CameraMetadata {
    private CameraMetadata() {
    }

    static void write(File file, String cameraId, CameraCharacteristics c, CaptureResult first, Size video, VideoInput.Settings settings) throws IOException {
        StringBuilder sb = new StringBuilder("\"property\",\"value\"\n");
        row(sb, "camera id", cameraId);
        Integer facing = c.get(CameraCharacteristics.LENS_FACING);
        row(sb, "facing", facing == null ? "" : facing == CameraCharacteristics.LENS_FACING_FRONT ? "front" : facing == CameraCharacteristics.LENS_FACING_BACK ? "back" : "external");
        row(sb, "sensor orientation (°)", c.get(CameraCharacteristics.SENSOR_ORIENTATION));
        Integer source = c.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE);
        row(sb, "timestamp source", source == null ? "" : source == CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME ? "realtime" : "unknown");
        row(sb, "video width", video.getWidth());
        row(sb, "video height", video.getHeight());
        row(sb, "video rate (1/s)", settings.rate);
        row(sb, "video bitrate (1/s)", settings.bitrate);
        row(sb, "focus setting (diopters)", Double.isNaN(settings.focus) ? "auto" : number(settings.focus));
        row(sb, "stabilization", settings.stabilization ? "on" : "off");

        Size pixels = c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE);
        if (pixels != null) {
            row(sb, "pixel array width", pixels.getWidth());
            row(sb, "pixel array height", pixels.getHeight());
        }
        SizeF physical = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
        if (physical != null) {
            row(sb, "sensor width (mm)", number(physical.getWidth()));
            row(sb, "sensor height (mm)", number(physical.getHeight()));
        }
        Rect active = c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
        rect(sb, "active array", active);
        Rect preCorrection = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? c.get(CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE) : null;
        rect(sb, "pre-correction active array", preCorrection);
        float[] focalLengths = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
        if (focalLengths != null && focalLengths.length > 0)
            row(sb, "focal length (mm)", number(focalLengths[0]));
        row(sb, "hyperfocal distance (diopters)", c.get(CameraCharacteristics.LENS_INFO_HYPERFOCAL_DISTANCE));
        row(sb, "minimum focus distance (diopters)", c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE));

        float[] intrinsics = null;
        if (first != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
            intrinsics = first.get(CaptureResult.LENS_INTRINSIC_CALIBRATION);
        if (intrinsics == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            intrinsics = c.get(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION);
        if (intrinsics != null && intrinsics.length >= 5) {
            row(sb, "fx", number(intrinsics[0]));
            row(sb, "fy", number(intrinsics[1]));
            row(sb, "cx", number(intrinsics[2]));
            row(sb, "cy", number(intrinsics[3]));
            row(sb, "s", number(intrinsics[4]));
        }
        float[] distortion = null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
            distortion = c.get(CameraCharacteristics.LENS_DISTORTION);
        if (distortion != null && distortion.length >= 5) {
            row(sb, "distortion k1", number(distortion[0]));
            row(sb, "distortion k2", number(distortion[1]));
            row(sb, "distortion k3", number(distortion[2]));
            row(sb, "distortion p1", number(distortion[3]));
            row(sb, "distortion p2", number(distortion[4]));
        }
        float[] translation = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? c.get(CameraCharacteristics.LENS_POSE_TRANSLATION) : null;
        if (translation != null && translation.length >= 3) {
            row(sb, "pose translation x (m)", number(translation[0]));
            row(sb, "pose translation y (m)", number(translation[1]));
            row(sb, "pose translation z (m)", number(translation[2]));
        }
        float[] rotation = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? c.get(CameraCharacteristics.LENS_POSE_ROTATION) : null;
        if (rotation != null && rotation.length >= 4) {
            row(sb, "pose rotation x", number(rotation[0]));
            row(sb, "pose rotation y", number(rotation[1]));
            row(sb, "pose rotation z", number(rotation[2]));
            row(sb, "pose rotation w", number(rotation[3]));
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Integer reference = c.get(CameraCharacteristics.LENS_POSE_REFERENCE);
            row(sb, "pose reference", reference == null ? "" : reference == CameraCharacteristics.LENS_POSE_REFERENCE_PRIMARY_CAMERA ? "primary camera" : reference == CameraCharacteristics.LENS_POSE_REFERENCE_GYROSCOPE ? "gyroscope" : reference.toString());
        }

        //In the coordinates of the active array, whose top left pixel is (0, 0)
        Rect crop = first != null ? first.get(CaptureResult.SCALER_CROP_REGION) : null;
        if (crop == null && active != null)
            crop = new Rect(0, 0, active.width(), active.height());
        rect(sb, "crop region", crop);
        if (crop != null && intrinsics != null && intrinsics.length >= 4) {
            //The centred part of the crop region with the aspect ratio of the video
            double width = crop.width(), height = crop.height();
            double aspect = (double) video.getWidth() / video.getHeight();
            if (width / height > aspect)
                width = height * aspect;
            else
                height = width / aspect;
            double left = crop.left + (crop.width() - width) / 2;
            double top = crop.top + (crop.height() - height) / 2;
            //The intrinsics count from the top left of the pre-correction active array
            double offsetX = active != null && preCorrection != null ? active.left - preCorrection.left : 0;
            double offsetY = active != null && preCorrection != null ? active.top - preCorrection.top : 0;
            double scale = video.getWidth() / width;
            row(sb, "video fx", number(intrinsics[0] * scale));
            row(sb, "video fy", number(intrinsics[1] * scale));
            row(sb, "video cx", number((intrinsics[2] - offsetX - left) * scale));
            row(sb, "video cy", number((intrinsics[3] - offsetY - top) * scale));
        }

        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs())
            throw new IOException("Could not create " + parent);
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        }
    }

    private static void rect(StringBuilder sb, String name, Rect r) {
        if (r == null)
            return;
        row(sb, name + " left", r.left);
        row(sb, name + " top", r.top);
        row(sb, name + " right", r.right);
        row(sb, name + " bottom", r.bottom);
    }

    private static String number(double v) {
        return String.format(Locale.ROOT, "%.9g", v);
    }

    private static void row(StringBuilder sb, String property, Object value) {
        sb.append(quoted(property)).append(',').append(quoted(value == null ? "" : value.toString())).append('\n');
    }

    private static String quoted(String s) {
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }
}
