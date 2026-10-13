package de.rwth_aachen.phyphox;

import android.annotation.SuppressLint;
import android.location.GnssAntennaInfo;
import android.location.GnssCapabilities;
import android.location.GnssSignalType;
import android.location.GnssStatus;
import android.location.LocationManager;
import android.os.Build;
import android.text.TextUtils;

import androidx.annotation.RequiresApi;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

//What the GNSS chipset reports about itself, for Device info: model, capabilities, signal types and antennas, so it
//is clear what a phone can deliver before an experiment asks for raw measurements. Each part needs a newer Android.
public final class GnssInfo {

    private GnssInfo() {
    }

    //Device info's HTML for this phone
    public static String getFormattedInfo(LocationManager locationManager) {
        if (locationManager == null)
            return "Unknown<br />";
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P)
            return "API < 28<br />";
        String model = locationManager.getGnssHardwareModelName();
        int year = locationManager.getGnssYearOfHardware();
        GnssCapabilities capabilities = null;
        List<GnssAntennaInfo> antennas = null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            capabilities = locationManager.getGnssCapabilities();
            antennas = locationManager.getGnssAntennaInfos();
        }
        return format(model, year, capabilities, antennas);
    }

    //Capabilities and antennas are null where Android does not report them (before API 31, or not known yet)
    static String format(String model, int year, GnssCapabilities capabilities, List<GnssAntennaInfo> antennas) {
        StringBuilder sb = new StringBuilder();
        line(sb, "Hardware model", model != null ? model : "unknown");
        line(sb, "Year of hardware", year > 0 ? String.valueOf(year) : "before 2016");
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            line(sb, "Capabilities", "API < 31");
            return sb.toString();
        }
        if (capabilities == null) {
            line(sb, "Capabilities", "not reported");
        } else {
            line(sb, "Raw measurements", yesNo(capabilities.hasMeasurements()));
            line(sb, "Navigation messages", yesNo(capabilities.hasNavigationMessages()));
            line(sb, "Antenna info", yesNo(capabilities.hasAntennaInfo()));
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
                appendCapabilities34(sb, capabilities);
            else
                line(sb, "Further capabilities", "API < 34");
        }
        if (antennas == null || antennas.isEmpty()) {
            line(sb, "Antennas", "none reported");
        } else {
            for (GnssAntennaInfo antenna : antennas) {
                GnssAntennaInfo.PhaseCenterOffset offset = antenna.getPhaseCenterOffset();
                line(sb, String.format(Locale.US, "Antenna %.2f MHz", antenna.getCarrierFrequencyMHz()),
                        String.format(Locale.US, "phase centre offset x %.2f ± %.2f, y %.2f ± %.2f, z %.2f ± %.2f mm",
                                offset.getXOffsetMm(), offset.getXOffsetUncertaintyMm(),
                                offset.getYOffsetMm(), offset.getYOffsetUncertaintyMm(),
                                offset.getZOffsetMm(), offset.getZOffsetUncertaintyMm()));
            }
        }
        return sb.toString();
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private static void appendCapabilities34(StringBuilder sb, GnssCapabilities capabilities) {
        line(sb, "Carrier phase (accumulated delta range)", support(capabilities.hasAccumulatedDeltaRange()));
        line(sb, "Correlation vectors", yesNo(capabilities.hasMeasurementCorrelationVectors()));
        line(sb, "Satellite PVT", yesNo(capabilities.hasSatellitePvt()));

        List<String> corrections = new ArrayList<>();
        if (capabilities.hasMeasurementCorrectionsForDriving())
            corrections.add("for driving");
        if (capabilities.hasMeasurementCorrectionsLosSats())
            corrections.add("line-of-sight satellites");
        if (capabilities.hasMeasurementCorrectionsExcessPathLength())
            corrections.add("excess path length");
        if (capabilities.hasMeasurementCorrectionsReflectingPlane())
            corrections.add("reflecting plane");
        line(sb, "Measurement corrections", !capabilities.hasMeasurementCorrections() ? "no"
                : corrections.isEmpty() ? "yes" : "yes (" + TextUtils.join(", ", corrections) + ")");

        List<String> power = new ArrayList<>();
        if (capabilities.hasPowerTotal())
            power.add("total");
        if (capabilities.hasPowerSinglebandTracking())
            power.add("single-band tracking");
        if (capabilities.hasPowerMultibandTracking())
            power.add("multi-band tracking");
        if (capabilities.hasPowerSinglebandAcquisition())
            power.add("single-band acquisition");
        if (capabilities.hasPowerMultibandAcquisition())
            power.add("multi-band acquisition");
        if (capabilities.hasPowerOtherModes())
            power.add("other modes");
        line(sb, "Power statistics", power.isEmpty() ? "no" : TextUtils.join(", ", power));

        List<String> assistance = new ArrayList<>();
        if (capabilities.hasMsb())
            assistance.add("MS-based");
        if (capabilities.hasMsa())
            assistance.add("MS-assisted");
        line(sb, "Assisted GNSS", assistance.isEmpty() ? "no" : TextUtils.join(", ", assistance));

        line(sb, "Low power mode", yesNo(capabilities.hasLowPowerMode()));
        line(sb, "Satellite blocklist", yesNo(capabilities.hasSatelliteBlocklist()));
        line(sb, "Scheduling", yesNo(capabilities.hasScheduling()));
        line(sb, "Single shot fix", yesNo(capabilities.hasSingleShotFix()));
        line(sb, "On-demand time", yesNo(capabilities.hasOnDemandTime()));
        line(sb, "Geofencing", yesNo(capabilities.hasGeofencing()));

        List<GnssSignalType> signals = capabilities.getGnssSignalTypes();
        if (signals.isEmpty()) {
            line(sb, "Signal types", "none reported");
        } else {
            for (GnssSignalType signal : signals)
                line(sb, "Signal", String.format(Locale.US, "%s %.3f MHz, code %s", constellationName(signal.getConstellationType()),
                        signal.getCarrierFrequencyHz() / 1e6, signal.getCodeType()));
        }
    }

    //The constants are inlined, so the NavIC one (API 29) is safe on older Android
    @SuppressLint("InlinedApi")
    static String constellationName(int constellation) {
        switch (constellation) {
            case GnssStatus.CONSTELLATION_GPS: return "GPS";
            case GnssStatus.CONSTELLATION_SBAS: return "SBAS";
            case GnssStatus.CONSTELLATION_GLONASS: return "GLONASS";
            case GnssStatus.CONSTELLATION_QZSS: return "QZSS";
            case GnssStatus.CONSTELLATION_BEIDOU: return "BeiDou";
            case GnssStatus.CONSTELLATION_GALILEO: return "Galileo";
            case GnssStatus.CONSTELLATION_IRNSS: return "NavIC";
            default: return "constellation " + constellation;
        }
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private static String support(int capability) {
        switch (capability) {
            case GnssCapabilities.CAPABILITY_SUPPORTED: return "supported";
            case GnssCapabilities.CAPABILITY_UNSUPPORTED: return "unsupported";
            default: return "unknown";
        }
    }

    private static String yesNo(boolean value) {
        return value ? "yes" : "no";
    }

    private static void line(StringBuilder sb, String label, String value) {
        sb.append("- ").append(label).append(": ").append(value).append("<br />");
    }
}
