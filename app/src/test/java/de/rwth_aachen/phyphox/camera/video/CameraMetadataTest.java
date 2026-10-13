package de.rwth_aachen.phyphox.camera.video;

import static org.junit.Assert.assertEquals;

import android.graphics.Rect;
import android.hardware.camera2.CameraCharacteristics;
import android.util.Size;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowCameraCharacteristics;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

//meta/camera.csv: the intrinsics of the sensor converted to the pixels of the video, which shows the
//centred part of the crop region with its own aspect ratio
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class CameraMetadataTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private static Map<String, String> read(File file) throws Exception {
        Map<String, String> values = new HashMap<>();
        List<String> lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
        for (String line : lines.subList(1, lines.size())) {
            String[] parts = line.split("\",\"");
            values.put(parts[0].substring(1), parts[1].substring(0, parts[1].length() - 1));
        }
        return values;
    }

    @Test
    public void theIntrinsicsAreGivenInVideoPixels() throws Exception {
        CameraCharacteristics characteristics = ShadowCameraCharacteristics.newCameraCharacteristics();
        ShadowCameraCharacteristics shadow = Shadows.shadowOf(characteristics);
        shadow.set(CameraCharacteristics.LENS_FACING, CameraCharacteristics.LENS_FACING_BACK);
        shadow.set(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE, CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME);
        shadow.set(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE, new Rect(8, 8, 4008, 3008));
        shadow.set(CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE, new Rect(0, 0, 4016, 3016));
        shadow.set(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION, new float[]{2758.8f, 2756.8f, 2030.0f, 1522.7f, 0f});
        shadow.set(CameraCharacteristics.LENS_DISTORTION, new float[]{0.0912f, -0.1494f, 0.0770f, 0f, 0f});

        File csv = new File(folder.getRoot(), "meta/camera.csv");
        CameraMetadata.write(csv, "0", characteristics, null, new Size(1920, 1080), new VideoInput.Settings(), -1);
        Map<String, String> values = read(csv);
        assertEquals("back", values.get("facing"));
        assertEquals("realtime", values.get("timestamp source"));
        assertEquals("1920", values.get("video width"));
        assertEquals("0.0912000015", values.get("distortion k1"));

        //Without a capture result the crop region is the whole active array, 4000 x 3000, which begins at
        //8, 8 of the pre-correction array; the 16:9 video shows its centred 4000 x 2250, from its row 375,
        //scaled by 1920 / 4000
        double scale = 1920 / 4000.0;
        assertEquals(2758.8 * scale, Double.parseDouble(values.get("video fx")), 1e-3);
        assertEquals(2756.8 * scale, Double.parseDouble(values.get("video fy")), 1e-3);
        assertEquals((2030.0 - 8) * scale, Double.parseDouble(values.get("video cx")), 1e-3);
        assertEquals((1522.7 - 8 - 375) * scale, Double.parseDouble(values.get("video cy")), 1e-3);
        assertEquals("0", values.get("crop region left"));
        assertEquals("4000", values.get("crop region right"));
        assertEquals("not available", values.get("distortion correction"));

        //With distortion correction off the crop region is in pre-correction pixels: the whole 4016 x 3016,
        //of which the 16:9 video shows 4016 x 2259 from row 378.5
        CameraMetadata.write(csv, "0", characteristics, null, new Size(1920, 1080), new VideoInput.Settings(), 0);
        values = read(csv);
        assertEquals("off", values.get("distortion correction"));
        assertEquals("4016", values.get("crop region right"));
        double offScale = 1920 / 4016.0;
        assertEquals(2030.0 * offScale, Double.parseDouble(values.get("video cx")), 1e-3);
        assertEquals((1522.7 - (3016 - 4016 * 1080 / 1920.0) / 2) * offScale, Double.parseDouble(values.get("video cy")), 1e-3);
    }
}
