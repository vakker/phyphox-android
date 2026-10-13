package de.rwth_aachen.phyphox;

import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.location.GnssAntennaInfo;
import android.location.GnssCapabilities;
import android.location.GnssSignalType;
import android.location.GnssStatus;
import android.location.LocationManager;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

import java.util.Arrays;
import java.util.Collections;

//The GNSS part of Device info: what the chipset reports about itself, formatted line by line
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class GnssInfoTest {

    private static void assertLine(String info, String line) {
        assertTrue("missing \"" + line + "\" in: " + info, info.contains("- " + line + "<br />"));
    }

    @Test
    public void listsWhatTheChipsetReports() {
        GnssCapabilities capabilities = new GnssCapabilities.Builder()
                .setHasMeasurements(true)
                .setHasNavigationMessages(false)
                .setHasAntennaInfo(true)
                .setHasAccumulatedDeltaRange(GnssCapabilities.CAPABILITY_UNKNOWN)
                .setHasSatellitePvt(true)
                .setHasMeasurementCorrections(true)
                .setHasMeasurementCorrectionsForDriving(true)
                .setHasMeasurementCorrectionsLosSats(true)
                .setHasPowerTotal(true)
                .setHasMsb(true)
                .setHasMsa(true)
                .setHasScheduling(true)
                .setGnssSignalTypes(Arrays.asList(
                        GnssSignalType.create(GnssStatus.CONSTELLATION_GPS, 1575.42e6, "C"),
                        GnssSignalType.create(GnssStatus.CONSTELLATION_GALILEO, 1176.45e6, "Q")))
                .build();
        GnssAntennaInfo antenna = new GnssAntennaInfo.Builder(1575.42,
                new GnssAntennaInfo.PhaseCenterOffset(1.2, 0.1, -3.4, 0.2, 5.6, 0.3)).build();

        String info = GnssInfo.format("Qualcomm GNSS GEN9", 2023, capabilities, Collections.singletonList(antenna));

        assertLine(info, "Hardware model: Qualcomm GNSS GEN9");
        assertLine(info, "Year of hardware: 2023");
        assertLine(info, "Raw measurements: yes");
        assertLine(info, "Navigation messages: no");
        assertLine(info, "Antenna info: yes");
        assertLine(info, "Carrier phase (accumulated delta range): unknown");
        assertLine(info, "Correlation vectors: no");
        assertLine(info, "Satellite PVT: yes");
        assertLine(info, "Measurement corrections: yes (for driving, line-of-sight satellites)");
        assertLine(info, "Power statistics: total");
        assertLine(info, "Assisted GNSS: MS-based, MS-assisted");
        assertLine(info, "Scheduling: yes");
        assertLine(info, "Geofencing: no");
        assertLine(info, "Signal: GPS 1575.420 MHz, code C");
        assertLine(info, "Signal: Galileo 1176.450 MHz, code Q");
        assertLine(info, "Antenna 1575.42 MHz: phase centre offset x 1.20 ± 0.10, y -3.40 ± 0.20, z 5.60 ± 0.30 mm");
    }

    @Test
    public void saysWhatIsNotReported() {
        String info = GnssInfo.format(null, 0, null, null);

        assertLine(info, "Hardware model: unknown");
        assertLine(info, "Year of hardware: before 2016");
        assertLine(info, "Capabilities: not reported");
        assertLine(info, "Antennas: none reported");

        String noSignals = GnssInfo.format("x", 2020, new GnssCapabilities.Builder().build(), Collections.emptyList());
        assertLine(noSignals, "Signal types: none reported");
        assertLine(noSignals, "Measurement corrections: no");
        assertLine(noSignals, "Power statistics: no");
        assertLine(noSignals, "Assisted GNSS: no");
    }

    //Reading the real LocationManager, as Device info does
    @Test
    public void readsTheLocationManager() {
        Context context = ApplicationProvider.getApplicationContext();
        LocationManager locationManager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        Shadows.shadowOf(locationManager).setGnssHardwareModelName("Simulated GNSS");
        Shadows.shadowOf(locationManager).setGnssYearOfHardware(2022);

        String info = GnssInfo.getFormattedInfo(locationManager);

        assertLine(info, "Hardware model: Simulated GNSS");
        assertLine(info, "Year of hardware: 2022");
    }
}
