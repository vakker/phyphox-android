package de.rwth_aachen.phyphox.ExperimentList.handler;

import static de.rwth_aachen.phyphox.ExperimentList.model.Const.EXPERIMENT_ISASSET;
import static de.rwth_aachen.phyphox.ExperimentList.model.Const.EXPERIMENT_XML;

import android.content.Context;
import android.content.Intent;
import android.util.Log;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.Toast;

import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import de.rwth_aachen.phyphox.Experiment;
import de.rwth_aachen.phyphox.R;

public class SimpleExperimentCreator {

    private final Context context;
    private final EditText titleEditText;
    private final EditText rateEditText;

    private final List<SensorCheckbox> sensorCheckboxes = new ArrayList<>();

    private String fileName;

    enum SensorType {
        ACCELEROMETER,
        GYROSCOPE,
        HUMIDITY,
        LIGHT,
        LINEAR_ACCELERATION,
        LOCATION,
        MAGNETOMETER,
        PRESSURE,
        PROXIMITY,
        TEMPERATURE,
        ATTITUDE,
        GRAVITY,
        //GNSS details of the location input, only in this build. Each needs LOCATION, which they share one <location> with.
        GNSS_SATELLITES,
        GNSS_NMEA,
        GNSS_RAW
    }

    //The GNSS outputs as {component, export column}. Their containers carry the component names; each table has its
    //own time column (the per-fix one shares the location's loc_time).
    private static final String[][] GNSS_FIX_COLUMNS = {
            {"utc", "UTC (s)"}, {"satellitesInView", "Satellites in view"}, {"satellitesUsed", "Satellites used"},
            {"signalsInView", "Signals in view"}, {"signalsUsed", "Signals used"},
            {"cn0Mean", "C/N0 mean (dB-Hz)"}, {"cn0Max", "C/N0 max (dB-Hz)"}, {"cn0Min", "C/N0 min (dB-Hz)"},
            {"meanCn0", "C/N0 mean, fix extras (dB-Hz)"}, {"maxCn0", "C/N0 max, fix extras (dB-Hz)"},
            {"vAccuracy", "Speed accuracy (m/s)"}, {"dirAccuracy", "Direction accuracy (°)"},
            {"zMsl", "Altitude MSL, Android (m)"}, {"zMslAccuracy", "Altitude MSL accuracy (m)"},
            {"tUncertainty", "Time uncertainty (s)"}, {"mock", "Mock"}};
    private static final String[][] GNSS_SATELLITE_COLUMNS = {
            {"svT", "Time (s)"}, {"svId", "Satellite id"}, {"svConstellation", "Constellation"}, {"svCn0", "C/N0 (dB-Hz)"},
            {"svUsed", "Used in fix"}, {"svElevation", "Elevation (°)"}, {"svAzimuth", "Azimuth (°)"},
            {"svCarrierFrequency", "Carrier frequency (Hz)"}, {"svEphemeris", "Ephemeris"}, {"svAlmanac", "Almanac"},
            {"svBasebandCn0", "Baseband C/N0 (dB-Hz)"}};
    private static final String[][] GNSS_START_COLUMNS = {{"ttff", "Time to first fix (s)"}};
    private static final String[][] GNSS_GGA_COLUMNS = {
            {"ggaT", "Time (s)"}, {"ggaTime", "UTC time of day (s)"}, {"ggaQuality", "Fix quality"},
            {"ggaSatellites", "Satellites in use"}, {"ggaHdop", "HDOP"}, {"ggaAltitude", "Altitude MSL, receiver (m)"},
            {"ggaGeoidSeparation", "Geoid separation (m)"}};
    private static final String[][] GNSS_GSA_COLUMNS = {
            {"gsaT", "Time (s)"}, {"gsaMode", "Fix mode"}, {"gsaPdop", "PDOP"}, {"gsaHdop", "HDOP"}, {"gsaVdop", "VDOP"},
            {"gsaSystem", "System id"}};
    private static final String[][] GNSS_CLOCK_COLUMNS = {
            {"clockT", "Time (s)"}, {"clockTimeNanos", "TimeNanos"}, {"clockTimeUncertainty", "TimeUncertaintyNanos"},
            {"clockLeapSecond", "LeapSecond"}, {"clockFullBiasNanos", "FullBiasNanos (rounded)"}, {"clockBiasNanos", "BiasNanos"},
            {"clockBiasUncertainty", "BiasUncertaintyNanos"}, {"clockDrift", "DriftNanosPerSecond"},
            {"clockDriftUncertainty", "DriftUncertaintyNanosPerSecond"}, {"clockDiscontinuity", "HardwareClockDiscontinuityCount"},
            {"clockGpsWeek", "GPS week"}, {"clockGpsTow", "GPS time of week (s)"}};
    private static final String[][] GNSS_MEASUREMENT_COLUMNS = {
            {"measT", "Time (s)"}, {"measSvid", "Svid"}, {"measConstellation", "ConstellationType"},
            {"measCarrierFrequency", "CarrierFrequencyHz"}, {"measCodeType", "CodeType (character code)"},
            {"measTimeOffset", "TimeOffsetNanos"}, {"measState", "State"}, {"measReceivedSvTime", "ReceivedSvTimeNanos"},
            {"measReceivedSvTimeUncertainty", "ReceivedSvTimeUncertaintyNanos"}, {"measCn0", "Cn0DbHz"},
            {"measBasebandCn0", "BasebandCn0DbHz"}, {"measPseudorangeRate", "PseudorangeRateMetersPerSecond"},
            {"measPseudorangeRateUncertainty", "PseudorangeRateUncertaintyMetersPerSecond"},
            {"measAdrState", "AccumulatedDeltaRangeState"}, {"measAdr", "AccumulatedDeltaRangeMeters"},
            {"measAdrUncertainty", "AccumulatedDeltaRangeUncertaintyMeters"}, {"measMultipath", "MultipathIndicator"},
            {"measAgc", "AgcDb"}};
    private static final String[][] GNSS_STATUS_COLUMNS = {{"measStatus", "Status"}};

    public SimpleExperimentCreator(Context context, View layout){
        this.context = context;
        this.titleEditText = layout.findViewById(R.id.neTitle);
        this.rateEditText = layout.findViewById(R.id.neRate);
        this.sensorCheckboxes.add(new SensorCheckbox(layout.findViewById(R.id.neAccelerometer), SensorType.ACCELEROMETER));
        this.sensorCheckboxes.add(new SensorCheckbox(layout.findViewById(R.id.neGyroscope), SensorType.GYROSCOPE));
        this.sensorCheckboxes.add(new SensorCheckbox(layout.findViewById(R.id.neHumidity), SensorType.HUMIDITY));
        this.sensorCheckboxes.add(new SensorCheckbox(layout.findViewById(R.id.neLight), SensorType.LIGHT));
        this.sensorCheckboxes.add(new SensorCheckbox(layout.findViewById(R.id.neLinearAcceleration), SensorType.LINEAR_ACCELERATION));
        this.sensorCheckboxes.add(new SensorCheckbox(layout.findViewById(R.id.neLocation), SensorType.LOCATION));
        this.sensorCheckboxes.add(new SensorCheckbox(layout.findViewById(R.id.neGnssSatellites), SensorType.GNSS_SATELLITES));
        this.sensorCheckboxes.add(new SensorCheckbox(layout.findViewById(R.id.neGnssNmea), SensorType.GNSS_NMEA));
        this.sensorCheckboxes.add(new SensorCheckbox(layout.findViewById(R.id.neGnssRaw), SensorType.GNSS_RAW));
        this.sensorCheckboxes.add(new SensorCheckbox(layout.findViewById(R.id.neMagneticField), SensorType.MAGNETOMETER));
        this.sensorCheckboxes.add(new SensorCheckbox(layout.findViewById(R.id.nePressure), SensorType.PRESSURE));
        this.sensorCheckboxes.add(new SensorCheckbox(layout.findViewById(R.id.neProximity), SensorType.PROXIMITY));
        this.sensorCheckboxes.add(new SensorCheckbox(layout.findViewById(R.id.neTemperature), SensorType.TEMPERATURE));
        this.sensorCheckboxes.add(new SensorCheckbox(layout.findViewById(R.id.neAttitude), SensorType.ATTITUDE));
        this.sensorCheckboxes.add(new SensorCheckbox(layout.findViewById(R.id.neGravity), SensorType.GRAVITY));
    }

    //The GNSS options are parts of Location, as the dialog shows them: one ticks Location, and clearing Location
    //clears them. Call when the dialog is inflated; the creator itself only exists once OK is pressed.
    public static void linkGnssOptions(View layout) {
        CheckBox location = layout.findViewById(R.id.neLocation);
        CheckBox[] gnss = {layout.findViewById(R.id.neGnssSatellites), layout.findViewById(R.id.neGnssNmea), layout.findViewById(R.id.neGnssRaw)};
        for (CheckBox option : gnss)
            option.setOnCheckedChangeListener((box, checked) -> {
                if (checked)
                    location.setChecked(true);
            });
        location.setOnCheckedChangeListener((box, checked) -> {
            if (!checked)
                for (CheckBox option : gnss)
                    option.setChecked(false);
        });
    }

    public void generateAndOpenSimpleExperiment(){
        // Validate and prepare user inputs
        String title = titleEditText.getText().toString();
        double rate = parseRate(rateEditText.getText().toString());

        List<SensorType> enabledSensors = withLocationForGnss(getEnabledSensors());

        // Check if any sensor is selected
        if (enabledSensors.isEmpty()) {
            enabledSensors.add(SensorType.ACCELEROMETER);
            Toast.makeText(context, "No sensor selected. Adding accelerometer as default.", Toast.LENGTH_LONG).show();
        }

        // Generate a unique file name
        fileName = UUID.randomUUID().toString().replaceAll("-", "") + ".phyphox";

        // Write the experiment data to the file
        try (FileOutputStream output = context.openFileOutput(fileName, Context.MODE_PRIVATE)) {

            writeExperimentData(output, title, rate, enabledSensors);

            startIntent();

        } catch (IOException e) {
            Log.e("SimpleExperimentCreator", "Could not create new experiment.", e);
            Toast.makeText(context, "Error creating experiment file.", Toast.LENGTH_LONG).show();
        }

    }

    public void startIntent(){

        Intent intent = new Intent(context, Experiment.class);
        intent.putExtra(EXPERIMENT_XML, fileName);
        intent.putExtra(EXPERIMENT_ISASSET, false);
        intent.setAction(Intent.ACTION_VIEW);
        context.startActivity(intent);
    }

    private double parseRate(String rateString) {
        try {
            return Double.parseDouble(rateString.replace(',', '.'));
        } catch (NumberFormatException e) {
            Toast.makeText(context, "Invalid sensor rate. Fall back to fastest rate.", Toast.LENGTH_LONG).show();
            return 0;
        }
    }

    private List<SensorType> getEnabledSensors() {
        List<SensorType> enabledSensors = new ArrayList<>();
        for (SensorCheckbox sensorCheckbox : sensorCheckboxes) {
            if (sensorCheckbox.isChecked()) {
                enabledSensors.add(sensorCheckbox.getSensorType());
            }
        }
        return enabledSensors;
    }

    //The GNSS details come from the location input: Location is added before the first of them if not chosen
    static List<SensorType> withLocationForGnss(List<SensorType> enabledSensors) {
        if (enabledSensors.contains(SensorType.LOCATION))
            return enabledSensors;
        for (int i = 0; i < enabledSensors.size(); i++) {
            SensorType sensor = enabledSensors.get(i);
            if (sensor == SensorType.GNSS_SATELLITES || sensor == SensorType.GNSS_NMEA || sensor == SensorType.GNSS_RAW) {
                enabledSensors.add(i, SensorType.LOCATION);
                break;
            }
        }
        return enabledSensors;
    }

    private static void writeContainers(FileOutputStream output, String[][] columns) throws IOException {
        for (String[] column : columns)
            output.write(("<container size=\"0\">" + column[0] + "</container>").getBytes());
    }

    private static void writeOutputs(FileOutputStream output, String[][] columns) throws IOException {
        for (String[] column : columns)
            output.write(("<output component=\"" + column[0] + "\">" + column[0] + "</output>").getBytes());
    }

    //A set of the columns, after the given time column if the columns do not start with their own
    private static void writeSet(FileOutputStream output, String name, String timeBuffer, String[][] columns) throws IOException {
        output.write(("<set name=\"" + name + "\">").getBytes());
        if (timeBuffer != null)
            output.write(("<data name=\"Time (s)\">" + timeBuffer + "</data>").getBytes());
        for (String[] column : columns)
            output.write(("<data name=\"" + column[1] + "\">" + column[0] + "</data>").getBytes());
        output.write("</set>".getBytes());
    }

    void writeExperimentData(FileOutputStream output, String title, double rate, List<SensorType> enabledSensors) throws IOException {
        output.write("<phyphox version=\"1.14\">".getBytes());

        writeMetadata(output, title);

        writeDataContainers(output, enabledSensors);

        writeSensorInputs(output, rate, enabledSensors);

        writeViews(output, enabledSensors);

        writeAnalysis(output,enabledSensors);

        writeExportDefinitions(output, enabledSensors);

        output.write("</phyphox>".getBytes());
        output.close();
    }

    private void writeMetadata(FileOutputStream output, String title) throws IOException {
        String escapedTitle = escapeXml(title);
        output.write(("<title>" + escapedTitle + "</title>").getBytes());
        output.write(("<category>" + context.getString(R.string.categoryNewExperiment) + "</category>").getBytes());
        output.write("<color>red</color>".getBytes());
        output.write("<description>Get raw data from selected sensors.</description>".getBytes());
    }

    private String escapeXml(String input) {
        return input.replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;")
                .replace("&", "&amp;");
    }

    private void writeDataContainers(FileOutputStream output, List<SensorType> enabledSensors) throws IOException {
        output.write("<data-containers>".getBytes());
        for (SensorType sensor : enabledSensors) {
            switch (sensor) {
                case ACCELEROMETER:
                    output.write(("<container size=\"0\">acc_time</container>").getBytes());
                    output.write(("<container size=\"0\">accX</container>").getBytes());
                    output.write(("<container size=\"0\">accY</container>").getBytes());
                    output.write(("<container size=\"0\">accZ</container>").getBytes());
                    break;
                case GYROSCOPE:
                    output.write(("<container size=\"0\">gyr_time</container>").getBytes());
                    output.write(("<container size=\"0\">gyrX</container>").getBytes());
                    output.write(("<container size=\"0\">gyrY</container>").getBytes());
                    output.write(("<container size=\"0\">gyrZ</container>").getBytes());
                    break;
                case HUMIDITY:
                    output.write(("<container size=\"0\">hum_time</container>").getBytes());
                    output.write(("<container size=\"0\">hum</container>").getBytes());
                    break;
                case LIGHT:
                    output.write(("<container size=\"0\">light_time</container>").getBytes());
                    output.write(("<container size=\"0\">light</container>").getBytes());
                    break;
                case LINEAR_ACCELERATION:
                    output.write(("<container size=\"0\">lin_time</container>").getBytes());
                    output.write(("<container size=\"0\">linX</container>").getBytes());
                    output.write(("<container size=\"0\">linY</container>").getBytes());
                    output.write(("<container size=\"0\">linZ</container>").getBytes());
                    break;
                case LOCATION:
                    output.write(("<container size=\"0\">loc_time</container>").getBytes());
                    output.write(("<container size=\"0\">locLat</container>").getBytes());
                    output.write(("<container size=\"0\">locLon</container>").getBytes());
                    output.write(("<container size=\"0\">locZ</container>").getBytes());
                    output.write(("<container size=\"0\">locV</container>").getBytes());
                    output.write(("<container size=\"0\">locDir</container>").getBytes());
                    output.write(("<container size=\"0\">locAccuracy</container>").getBytes());
                    output.write(("<container size=\"0\">locZAccuracy</container>").getBytes());
                    output.write(("<container size=\"0\">locStatus</container>").getBytes());
                    output.write(("<container size=\"0\">locSatellites</container>").getBytes());
                    break;
                case MAGNETOMETER:
                    output.write(("<container size=\"0\">mag_time</container>").getBytes());
                    output.write(("<container size=\"0\">magX</container>").getBytes());
                    output.write(("<container size=\"0\">magY</container>").getBytes());
                    output.write(("<container size=\"0\">magZ</container>").getBytes());
                    break;
                case PRESSURE:
                    output.write(("<container size=\"0\">pressure_time</container>").getBytes());
                    output.write(("<container size=\"0\">pressure</container>").getBytes());
                    break;
                case PROXIMITY:
                    output.write(("<container size=\"0\">prox_time</container>").getBytes());
                    output.write(("<container size=\"0\">prox</container>").getBytes());
                    break;
                case TEMPERATURE:
                    output.write(("<container size=\"0\">temp_time</container>").getBytes());
                    output.write(("<container size=\"0\">temp</container>").getBytes());
                    break;
                case ATTITUDE:
                    output.write(("<container size=\"0\">attWIn</container>").getBytes());
                    output.write(("<container size=\"0\">attXIn</container>").getBytes());
                    output.write(("<container size=\"0\">attYIn</container>").getBytes());
                    output.write(("<container size=\"0\">attZIn</container>").getBytes());
                    output.write(("<container size=\"0\">attWOff</container>").getBytes());
                    output.write(("<container size=\"0\">attXOff</container>").getBytes());
                    output.write(("<container size=\"0\">attYOff</container>").getBytes());
                    output.write(("<container size=\"0\">attZOff</container>").getBytes());
                    output.write(("<container size=\"0\">attW</container>").getBytes());
                    output.write(("<container size=\"0\">attX</container>").getBytes());
                    output.write(("<container size=\"0\">attY</container>").getBytes());
                    output.write(("<container size=\"0\">attZ</container>").getBytes());
                    output.write(("<container size=\"0\">attT</container>").getBytes());
                    output.write(("<container size=\"0\">direct</container>").getBytes());
                    output.write(("<container size=\"0\">yaw</container>").getBytes());
                    output.write(("<container size=\"0\">pitch</container>").getBytes());
                    output.write(("<container size=\"0\">roll</container>").getBytes());
                    output.write(("<container size=\"1\" init=\"1\">attW0</container>").getBytes());
                    output.write(("<container size=\"1\" init=\"0\">attX0</container>").getBytes());
                    output.write(("<container size=\"1\" init=\"0\">attY0</container>").getBytes());
                    output.write(("<container size=\"1\" init=\"0\">attZ0</container>").getBytes());
                    output.write(("<container size=\"1\" init=\"1\">attWLast</container>").getBytes());
                    output.write(("<container size=\"1\" init=\"0\">attXLast</container>").getBytes());
                    output.write(("<container size=\"1\" init=\"0\">attYLast</container>").getBytes());
                    output.write(("<container size=\"1\" init=\"0\">attZLast</container>").getBytes());
                    output.write(("<container size=\"1\">count</container>").getBytes());
                    output.write(("<container size=\"1\">attTMax</container>").getBytes());
                    break;
                case GRAVITY:
                    output.write(("<container size=\"0\">graT</container>").getBytes());
                    output.write(("<container size=\"0\">graX</container>").getBytes());
                    output.write(("<container size=\"0\">graY</container>").getBytes());
                    output.write(("<container size=\"0\">graZ</container>").getBytes());
                    break;
                case GNSS_SATELLITES:
                    writeContainers(output, GNSS_FIX_COLUMNS);
                    writeContainers(output, GNSS_SATELLITE_COLUMNS);
                    writeContainers(output, GNSS_START_COLUMNS);
                    break;
                case GNSS_NMEA:
                    writeContainers(output, GNSS_GGA_COLUMNS);
                    writeContainers(output, GNSS_GSA_COLUMNS);
                    break;
                case GNSS_RAW:
                    writeContainers(output, GNSS_CLOCK_COLUMNS);
                    writeContainers(output, GNSS_MEASUREMENT_COLUMNS);
                    writeContainers(output, GNSS_STATUS_COLUMNS);
                    break;
            }
        }
        output.write("</data-containers>".getBytes());
    }

    private void writeSensorInputs(FileOutputStream output, double rate, List<SensorType> enabledSensors) throws IOException {
        output.write("<input>".getBytes());
        for (SensorType sensor : enabledSensors) {
            switch (sensor) {
                case ACCELEROMETER:
                    output.write(("<sensor type=\"accelerometer\" rate=\"" + rate + "\" >" +
                            "<output component=\"x\">accX</output>" +
                            "<output component=\"y\">accY</output>" +
                            "<output component=\"z\">accZ</output>" +
                            "<output component=\"t\">acc_time</output>" +
                            "</sensor>").getBytes());
                    break;
                case GYROSCOPE:
                    output.write(("<sensor type=\"gyroscope\" rate=\"" + rate + "\" >" +
                            "<output component=\"x\">gyrX</output>" +
                            "<output component=\"y\">gyrY</output>" +
                            "<output component=\"z\">gyrZ</output>" +
                            "<output component=\"t\">gyr_time</output>" +
                            "</sensor>").getBytes());
                    break;
                case HUMIDITY:
                    output.write(("<sensor type=\"humidity\" rate=\"" + rate + "\" >" +
                            "<output component=\"x\">hum</output>" +
                            "<output component=\"t\">hum_time</output>" +
                            "</sensor>").getBytes());
                    break;
                case LIGHT:
                    output.write(("<sensor type=\"light\" rate=\"" + rate + "\" >" +
                            "<output component=\"x\">light</output>" +
                            "<output component=\"t\">light_time</output>" +
                            "</sensor>").getBytes());
                    break;
                case LINEAR_ACCELERATION:
                    output.write(("<sensor type=\"linear_acceleration\" rate=\"" + rate + "\" >" +
                            "<output component=\"x\">linX</output>" +
                            "<output component=\"y\">linY</output>" +
                            "<output component=\"z\">linZ</output>" +
                            "<output component=\"t\">lin_time</output>" +
                            "</sensor>").getBytes());
                    break;
                case LOCATION:
                    output.write(("<location>" +
                            "<output component=\"lat\">locLat</output>" +
                            "<output component=\"lon\">locLon</output>" +
                            "<output component=\"z\">locZ</output>" +
                            "<output component=\"t\">loc_time</output>" +
                            "<output component=\"v\">locV</output>" +
                            "<output component=\"dir\">locDir</output>" +
                            "<output component=\"accuracy\">locAccuracy</output>" +
                            "<output component=\"zAccuracy\">locZAccuracy</output>" +
                            "<output component=\"status\">locStatus</output>" +
                            "<output component=\"satellites\">locSatellites</output>").getBytes());
                    if (enabledSensors.contains(SensorType.GNSS_SATELLITES)) {
                        writeOutputs(output, GNSS_FIX_COLUMNS);
                        writeOutputs(output, GNSS_SATELLITE_COLUMNS);
                        writeOutputs(output, GNSS_START_COLUMNS);
                    }
                    if (enabledSensors.contains(SensorType.GNSS_NMEA)) {
                        writeOutputs(output, GNSS_GGA_COLUMNS);
                        writeOutputs(output, GNSS_GSA_COLUMNS);
                    }
                    if (enabledSensors.contains(SensorType.GNSS_RAW)) {
                        writeOutputs(output, GNSS_CLOCK_COLUMNS);
                        writeOutputs(output, GNSS_MEASUREMENT_COLUMNS);
                        writeOutputs(output, GNSS_STATUS_COLUMNS);
                    }
                    output.write("</location>".getBytes());
                    break;
                case MAGNETOMETER:
                    output.write(("<sensor type=\"magnetic_field\" rate=\"" + rate + "\" >" +
                            "<output component=\"x\">magX</output>" +
                            "<output component=\"y\">magY</output>" +
                            "<output component=\"z\">magZ</output>" +
                            "<output component=\"t\">mag_time</output>" +
                            "</sensor>").getBytes());
                    break;
                case PRESSURE:
                    output.write(("<sensor type=\"pressure\" rate=\"" + rate + "\" >" +
                            "<output component=\"x\">pressure</output>" +
                            "<output component=\"t\">pressure_time</output>" +
                            "</sensor>").getBytes());
                    break;
                case PROXIMITY:
                    output.write(("<sensor type=\"proximity\" rate=\"" + rate + "\" >" +
                            "<output component=\"x\">prox</output>" +
                            "<output component=\"t\">prox_time</output>" +
                            "</sensor>").getBytes());
                    break;
                case TEMPERATURE:
                    output.write(("<sensor type=\"temperature\" rate=\"" + rate + "\" >" +
                            "<output component=\"x\">temp</output>" +
                            "<output component=\"t\">temp_time</output>" +
                            "</sensor>").getBytes());
                    break;
                case ATTITUDE:
                    output.write(("<sensor type=\"attitude\" rate=\"" + rate + "\" >" +
                            "<output component=\"x\">attXIn</output>" +
                            "<output component=\"y\">attYIn</output>" +
                            "<output component=\"z\">attZIn</output>" +
                            "<output component=\"abs\">attWIn</output>" +
                            "<output component=\"t\">attT</output>" +
                            "</sensor>").getBytes());
                    break;
                case GRAVITY:
                    output.write(("<sensor type=\"gravity\"  rate=\"" + rate + "\" >" +
                            "<output component=\"x\">graX</output>" +
                            "<output component=\"y\">graY</output>" +
                            "<output component=\"z\">graZ</output>" +
                            "<output component=\"t\">graT</output>" +
                            "</sensor>").getBytes());
                    break;
            }
        }
        output.write("</input>".getBytes());
    }

    private void writeViews(FileOutputStream output, List<SensorType> enabledSensors) throws IOException {
        output.write("<views>".getBytes());
        for (SensorType sensor : enabledSensors) {
            switch (sensor) {
                case ACCELEROMETER:
                    output.write("<view label=\"Accelerometer\">".getBytes());
                    output.write(("<graph label=\"Acceleration X\" timeOnX=\"true\" labelX=\"t (s)\" labelY=\"a (m/s²)\" partialUpdate=\"true\"><input axis=\"x\">acc_time</input><input axis=\"y\">accX</input></graph>").getBytes());
                    output.write(("<graph label=\"Acceleration Y\" timeOnX=\"true\" labelX=\"t (s)\" labelY=\"a (m/s²)\" partialUpdate=\"true\"><input axis=\"x\">acc_time</input><input axis=\"y\">accY</input></graph>").getBytes());
                    output.write(("<graph label=\"Acceleration Z\" timeOnX=\"true\" labelX=\"t (s)\" labelY=\"a (m/s²)\" partialUpdate=\"true\"><input axis=\"x\">acc_time</input><input axis=\"y\">accZ</input></graph>").getBytes());
                    output.write("</view>".getBytes());
                    break;
                case GYROSCOPE:
                    output.write("<view label=\"Gyroscope\">".getBytes());
                    output.write(("<graph label=\"Gyroscope X\" timeOnX=\"true\" labelX=\"t (s)\" labelY=\"w (rad/s)\" partialUpdate=\"true\"><input axis=\"x\">gyr_time</input><input axis=\"y\">gyrX</input></graph>").getBytes());
                    output.write(("<graph label=\"Gyroscope Y\" timeOnX=\"true\" labelX=\"t (s)\" labelY=\"w (rad/s)\" partialUpdate=\"true\"><input axis=\"x\">gyr_time</input><input axis=\"y\">gyrY</input></graph>").getBytes());
                    output.write(("<graph label=\"Gyroscope Z\" timeOnX=\"true\" labelX=\"t (s)\" labelY=\"w (rad/s)\" partialUpdate=\"true\"><input axis=\"x\">gyr_time</input><input axis=\"y\">gyrZ</input></graph>").getBytes());
                    output.write("</view>".getBytes());
                    break;
                case HUMIDITY:
                    output.write("<view label=\"Humidity\">".getBytes());
                    output.write(("<graph label=\"Humidity\" timeOnX=\"true\" labelX=\"t (s)\" labelY=\"Relative Humidity (%)\" partialUpdate=\"true\"><input axis=\"x\">hum_time</input><input axis=\"y\">hum</input></graph>").getBytes());
                    output.write("</view>".getBytes());
                    break;
                case LIGHT:
                    output.write("<view label=\"Light\">".getBytes());
                    output.write(("<graph label=\"Illuminance\" timeOnX=\"true\" labelX=\"t (s)\" labelY=\"Ev (lx)\" partialUpdate=\"true\"><input axis=\"x\">light_time</input><input axis=\"y\">light</input></graph>").getBytes());
                    output.write("</view>".getBytes());
                    break;
                case LINEAR_ACCELERATION:
                    output.write("<view label=\"Linear Acceleration\">".getBytes());
                    output.write(("<graph label=\"Linear Acceleration X\" timeOnX=\"true\" labelX=\"t (s)\" labelY=\"a (m/s²)\" partialUpdate=\"true\"><input axis=\"x\">lin_time</input><input axis=\"y\">linX</input></graph>").getBytes());
                    output.write(("<graph label=\"Linear Acceleration Y\" timeOnX=\"true\" labelX=\"t (s)\" labelY=\"a (m/s²)\" partialUpdate=\"true\"><input axis=\"x\">lin_time</input><input axis=\"y\">linY</input></graph>").getBytes());
                    output.write(("<graph label=\"Linear Acceleration Z\" timeOnX=\"true\" labelX=\"t (s)\" labelY=\"a (m/s²)\" partialUpdate=\"true\"><input axis=\"x\">lin_time</input><input axis=\"y\">linZ</input></graph>").getBytes());
                    output.write("</view>".getBytes());
                    break;
                case LOCATION:
                    output.write("<view label=\"Location\">".getBytes());
                    output.write(("<graph label=\"Latitude\" timeOnX=\"true\" labelX=\"t (s)\" labelY=\"Latitude (°)\" partialUpdate=\"true\"><input axis=\"x\">loc_time</input><input axis=\"y\">locLat</input></graph>").getBytes());
                    output.write(("<graph label=\"Longitude\" timeOnX=\"true\" labelX=\"t (s)\" labelY=\"Longitude (°)\" partialUpdate=\"true\"><input axis=\"x\">loc_time</input><input axis=\"y\">locLon</input></graph>").getBytes());
                    output.write(("<graph label=\"Height\" timeOnX=\"true\" labelX=\"t (s)\" labelY=\"z (m)\" partialUpdate=\"true\"><input axis=\"x\">loc_time</input><input axis=\"y\">locZ</input></graph>").getBytes());
                    output.write(("<graph label=\"Velocity\" timeOnX=\"true\" labelX=\"t (s)\" labelY=\"v (m/s)\" partialUpdate=\"true\"><input axis=\"x\">loc_time</input><input axis=\"y\">locV</input></graph>").getBytes());
                    output.write(("<graph label=\"Direction\" timeOnX=\"true\" labelX=\"t (s)\" labelY=\"heading (°)\" partialUpdate=\"true\"><input axis=\"x\">loc_time</input><input axis=\"y\">locDir</input></graph>").getBytes());
                    output.write(("<value label=\"Horizontal Accuracy\" size=\"1\" precision=\"1\" unit=\"m\"><input>locAccuracy</input></value>").getBytes());
                    output.write(("<value label=\"Vertical Accuracy\" size=\"1\" precision=\"1\" unit=\"m\"><input>locZAccuracy</input></value>").getBytes());
                    output.write(("<value label=\"Satellites\" size=\"1\" precision=\"0\"><input>locSatellites</input></value>").getBytes());
                    output.write(("<value label=\"Status\" size=\"1\" precision=\"0\"><input>locStatus</input><map max=\"-1\">GPS disabled</map><map max=\"0\">Waiting for signal</map><map max=\"1\">Active</map></value>").getBytes());
                    output.write("</view>".getBytes());
                    break;
                case MAGNETOMETER:
                    output.write("<view label=\"Magnetometer\">".getBytes());
                    output.write(("<graph label=\"Magnetic field X\" timeOnX=\"true\" labelX=\"t (s)\" labelY=\"B (µT)\" partialUpdate=\"true\"><input axis=\"x\">mag_time</input><input axis=\"y\">magX</input></graph>").getBytes());
                    output.write(("<graph label=\"Magnetic field Y\" timeOnX=\"true\" labelX=\"t (s)\" labelY=\"B (µT)\" partialUpdate=\"true\"><input axis=\"x\">mag_time</input><input axis=\"y\">magY</input></graph>").getBytes());
                    output.write(("<graph label=\"Magnetic field Z\" timeOnX=\"true\" labelX=\"t (s)\" labelY=\"B (µT)\" partialUpdate=\"true\"><input axis=\"x\">mag_time</input><input axis=\"y\">magZ</input></graph>").getBytes());
                    output.write("</view>".getBytes());
                    break;
                case PRESSURE:
                    output.write("<view label=\"Pressure\">".getBytes());
                    output.write(("<graph label=\"Pressure\" timeOnX=\"true\" labelX=\"t (s)\" labelY=\"P (hPa)\" partialUpdate=\"true\"><input axis=\"x\">pressure_time</input><input axis=\"y\">pressure</input></graph>").getBytes());
                    output.write("</view>".getBytes());
                    break;
                case PROXIMITY:
                    output.write("<view label=\"Proximity\">".getBytes());
                    output.write(("<graph label=\"Proximity\" timeOnX=\"true\" labelX=\"t (s)\" labelY=\"Distance (cm)\" partialUpdate=\"true\"><input axis=\"x\">prox_time</input><input axis=\"y\">prox</input></graph>").getBytes());
                    output.write("</view>".getBytes());
                    break;
                case TEMPERATURE:
                    output.write("<view label=\"Temperature\">".getBytes());
                    output.write(("<graph label=\"Temperature\" timeOnX=\"true\" labelX=\"t (s)\" labelY=\"Temperature (°C)\" partialUpdate=\"true\"><input axis=\"x\">temp_time</input><input axis=\"y\">temp</input></graph>").getBytes());
                    output.write("</view>".getBytes());
                    break;
                case ATTITUDE:
                    output.write("<view label=\"Attitude with Euler's Angle\">".getBytes());
                    output.write("<button label=\"Zero\"><input>attWLast</input><output>attW0</output><input>attXLast</input><output>attX0</output><input>attYLast</input><output>attY0</output><input>attZLast</input><output>attZ0</output></button>".getBytes());
                    output.write(("<graph label=\"Direct\" timeOnX=\"true\" labelX=\"t (s)\" labelY=\"⍺\" partialUpdate=\"true\"><input axis=\"x\">attT</input><input axis=\"y\">direct</input></graph>").getBytes());
                    output.write(("<graph label=\"Yaw\" timeOnX=\"true\" labelX=\"t (s)\" labelY=\"ψ\" partialUpdate=\"true\"><input axis=\"x\">attT</input><input axis=\"y\">yaw</input></graph>").getBytes());
                    output.write(("<graph label=\"Pitch\" timeOnX=\"true\" labelX=\"t (s)\" labelY=\"θ\" partialUpdate=\"true\"><input axis=\"x\">attT</input><input axis=\"y\">pitch</input></graph>").getBytes());
                    output.write(("<graph label=\"Roll\" timeOnX=\"true\" labelX=\"t (s)\" labelY=\"φ\" partialUpdate=\"true\"><input axis=\"x\">attT</input><input axis=\"y\">roll</input></graph>").getBytes());
                    output.write("</view>".getBytes());
                    output.write("<view label=\"Quaternions\">".getBytes());
                    output.write(("<graph label=\"Quaternion w\" timeOnX=\"true\" labelX=\"t (s)\" labelY=\"w\" partialUpdate=\"true\"><input axis=\"x\">attT</input><input axis=\"y\">attW</input></graph>").getBytes());
                    output.write(("<graph label=\"Quaternion x\" timeOnX=\"true\" labelX=\"t (s)\" labelY=\"x\" partialUpdate=\"true\"><input axis=\"x\">attT</input><input axis=\"y\">attX</input></graph>").getBytes());
                    output.write(("<graph label=\"Quaternion y\" timeOnX=\"true\" labelX=\"t (s)\" labelY=\"y\" partialUpdate=\"true\"><input axis=\"x\">attT</input><input axis=\"y\">attY</input></graph>").getBytes());
                    output.write(("<graph label=\"Quaternion z\" timeOnX=\"true\" labelX=\"t (s)\" labelY=\"z\" partialUpdate=\"true\"><input axis=\"x\">attT</input><input axis=\"y\">attZ</input></graph>").getBytes());
                    output.write("</view>".getBytes());
                    break;
                case GRAVITY:
                    output.write("<view label=\"Gravity\">".getBytes());
                    output.write(("<graph label=\"Gravity X\" timeOnX=\"true\" labelX=\"t (s)\" labelY=\"a (m/s²)\" partialUpdate=\"true\"><input axis=\"x\">graT</input><input axis=\"y\">graX</input></graph>").getBytes());
                    output.write(("<graph label=\"Gravity Y\" timeOnX=\"true\" labelX=\"t (s)\" labelY=\"a (m/s²)\" partialUpdate=\"true\"><input axis=\"x\">graT</input><input axis=\"y\">graY</input></graph>").getBytes());
                    output.write(("<graph label=\"Gravity Z\" timeOnX=\"true\" labelX=\"t (s)\" labelY=\"a (m/s²)\" partialUpdate=\"true\"><input axis=\"x\">graT</input><input axis=\"y\">graZ</input></graph>").getBytes());
                    output.write("</view>".getBytes());
                    break;
                case GNSS_SATELLITES:
                    output.write("<view label=\"GNSS satellites\">".getBytes());
                    output.write(("<graph label=\"C/N0 per tracked signal\" timeOnX=\"true\" labelX=\"t (s)\" labelY=\"C/N0 (dB-Hz)\" partialUpdate=\"true\" style=\"dots\"><input axis=\"x\">svT</input><input axis=\"y\">svCn0</input></graph>").getBytes());
                    output.write(("<graph label=\"Satellites (in view, used in the fix)\" timeOnX=\"true\" labelX=\"t (s)\" labelY=\"Satellites\" partialUpdate=\"true\"><input axis=\"x\">loc_time</input><input axis=\"y\">satellitesInView</input><input axis=\"x\">loc_time</input><input axis=\"y\">satellitesUsed</input></graph>").getBytes());
                    output.write(("<graph label=\"Signals (in view, used in the fix)\" timeOnX=\"true\" labelX=\"t (s)\" labelY=\"Signals\" partialUpdate=\"true\"><input axis=\"x\">loc_time</input><input axis=\"y\">signalsInView</input><input axis=\"x\">loc_time</input><input axis=\"y\">signalsUsed</input></graph>").getBytes());
                    output.write(("<value label=\"Time to first fix\" size=\"1\" precision=\"1\" unit=\"s\"><input>ttff</input></value>").getBytes());
                    output.write("</view>".getBytes());
                    break;
                case GNSS_NMEA:
                    output.write("<view label=\"GNSS NMEA\">".getBytes());
                    output.write(("<graph label=\"Dilution of precision (PDOP, HDOP, VDOP)\" timeOnX=\"true\" labelX=\"t (s)\" labelY=\"DOP\" partialUpdate=\"true\" style=\"dots\"><input axis=\"x\">gsaT</input><input axis=\"y\">gsaPdop</input><input axis=\"x\">gsaT</input><input axis=\"y\">gsaHdop</input><input axis=\"x\">gsaT</input><input axis=\"y\">gsaVdop</input></graph>").getBytes());
                    output.write(("<graph label=\"Fix quality\" timeOnX=\"true\" labelX=\"t (s)\" labelY=\"Quality\" partialUpdate=\"true\" style=\"dots\"><input axis=\"x\">ggaT</input><input axis=\"y\">ggaQuality</input></graph>").getBytes());
                    output.write("</view>".getBytes());
                    break;
                case GNSS_RAW:
                    output.write("<view label=\"GNSS raw\">".getBytes());
                    output.write(("<value label=\"Raw measurements\"><input>measStatus</input><map max=\"0\">not supported</map><map min=\"1\" max=\"1\">ready</map><map min=\"2\" max=\"2\">location disabled</map><map min=\"3\">not allowed</map></value>").getBytes());
                    output.write(("<graph label=\"C/N0 per signal\" timeOnX=\"true\" labelX=\"t (s)\" labelY=\"C/N0 (dB-Hz)\" partialUpdate=\"true\" style=\"dots\"><input axis=\"x\">measT</input><input axis=\"y\">measCn0</input></graph>").getBytes());
                    output.write(("<graph label=\"Receiver clock drift\" timeOnX=\"true\" labelX=\"t (s)\" labelY=\"Drift (ns/s)\" partialUpdate=\"true\"><input axis=\"x\">clockT</input><input axis=\"y\">clockDrift</input></graph>").getBytes());
                    output.write("</view>".getBytes());
                    break;
            }
        }
        output.write("</views>".getBytes());
    }

    private void writeExportDefinitions(FileOutputStream output, List<SensorType> enabledSensors) throws IOException {
        output.write("<export>".getBytes());
        for (SensorType sensor : enabledSensors) {
            switch (sensor) {
                case ACCELEROMETER:
                    output.write("<set name=\"Accelerometer\">".getBytes());
                    output.write("<data name=\"Time (s)\">acc_time</data>".getBytes());
                    output.write("<data name=\"Acceleration x (m/s^2)\">accX</data>".getBytes());
                    output.write("<data name=\"Acceleration y (m/s^2)\">accY</data>".getBytes());
                    output.write("<data name=\"Acceleration z (m/s^2)\">accZ</data>".getBytes());
                    output.write("</set>".getBytes());
                    break;
                case GYROSCOPE:
                    output.write("<set name=\"Gyroscope\">".getBytes());
                    output.write("<data name=\"Time (s)\">gyr_time</data>".getBytes());
                    output.write("<data name=\"Gyroscope x (rad/s)\">gyrX</data>".getBytes());
                    output.write("<data name=\"Gyroscope y (rad/s)\">gyrY</data>".getBytes());
                    output.write("<data name=\"Gyroscope z (rad/s)\">gyrZ</data>".getBytes());
                    output.write("</set>".getBytes());
                    break;
                case HUMIDITY:
                    output.write("<set name=\"Humidity\">".getBytes());
                    output.write("<data name=\"Time (s)\">hum_time</data>".getBytes());
                    output.write("<data name=\"Relative Humidity (%)\">hum</data>".getBytes());
                    output.write("</set>".getBytes());
                    break;
                case LIGHT:
                    output.write("<set name=\"Light\">".getBytes());
                    output.write("<data name=\"Time (s)\">light_time</data>".getBytes());
                    output.write("<data name=\"Illuminance (lx)\">light</data>".getBytes());
                    output.write("</set>".getBytes());
                    break;
                case LINEAR_ACCELERATION:
                    output.write("<set name=\"Linear Acceleration\">".getBytes());
                    output.write("<data name=\"Time (s)\">lin_time</data>".getBytes());
                    output.write("<data name=\"Linear Acceleration x (m/s^2)\">linX</data>".getBytes());
                    output.write("<data name=\"Linear Acceleration y (m/s^2)\">linY</data>".getBytes());
                    output.write("<data name=\"Linear Acceleration z (m/s^2)\">linZ</data>".getBytes());
                    output.write("</set>".getBytes());
                    break;
                case LOCATION:
                    output.write("<set name=\"Location\">".getBytes());
                    output.write("<data name=\"Time (s)\">loc_time</data>".getBytes());
                    output.write("<data name=\"Latitude (°)\">locLat</data>".getBytes());
                    output.write("<data name=\"Longitude (°)\">locLon</data>".getBytes());
                    output.write("<data name=\"Height (m)\">locZ</data>".getBytes());
                    output.write("<data name=\"Velocity (m/s)\">locV</data>".getBytes());
                    output.write("<data name=\"Direction (°)\">locDir</data>".getBytes());
                    output.write("<data name=\"Horizontal Accuracy (m)\">locAccuracy</data>".getBytes());
                    output.write("<data name=\"Vertical Accuracy (m)\">locZAccuracy</data>".getBytes());
                    output.write("</set>".getBytes());
                    break;
                case MAGNETOMETER:
                    output.write("<set name=\"Magnetometer\">".getBytes());
                    output.write("<data name=\"Time (s)\">mag_time</data>".getBytes());
                    output.write("<data name=\"Magnetic field x (µT)\">magX</data>".getBytes());
                    output.write("<data name=\"Magnetic field y (µT)\">magY</data>".getBytes());
                    output.write("<data name=\"Magnetic field z (µT)\">magZ</data>".getBytes());
                    output.write("</set>".getBytes());
                    break;
                case PRESSURE:
                    output.write("<set name=\"Pressure\">".getBytes());
                    output.write("<data name=\"Time (s)\">pressure_time</data>".getBytes());
                    output.write("<data name=\"Pressure (hPa)\">pressure</data>".getBytes());
                    output.write("</set>".getBytes());
                    break;
                case PROXIMITY:
                    output.write("<set name=\"Proximity\">".getBytes());
                    output.write("<data name=\"Time (s)\">prox_time</data>".getBytes());
                    output.write("<data name=\"Distance (cm)\">prox</data>".getBytes());
                    output.write("</set>".getBytes());
                    break;
                case TEMPERATURE:
                    output.write("<set name=\"Temperature\">".getBytes());
                    output.write("<data name=\"Time (s)\">temp_time</data>".getBytes());
                    output.write("<data name=\"Temperature (°C)\">temp</data>".getBytes());
                    output.write("</set>".getBytes());
                    break;
                case ATTITUDE:
                    output.write("<set name=\"Orientation\">".getBytes());
                    output.write("<data name=\"Time (s)\">attT</data>".getBytes());
                    output.write("<data name=\"w\">attW</data>".getBytes());
                    output.write("<data name=\"x\">attX</data>".getBytes());
                    output.write("<data name=\"y\">attY</data>".getBytes());
                    output.write("<data name=\"z\">attZ</data>".getBytes());
                    output.write("<data name=\"Direct (°)\">direct</data>".getBytes());
                    output.write("<data name=\"Yaw (°)\">yaw</data>".getBytes());
                    output.write("<data name=\"Pitch (°)\">pitch</data>".getBytes());
                    output.write("<data name=\"Roll (°)\">roll</data>".getBytes());
                    output.write("</set>".getBytes());
                    break;
                case GRAVITY:
                    output.write("<set name=\"Gravity\">".getBytes());
                    output.write("<data name=\"Time (s)\">graT</data>".getBytes());
                    output.write("<data name=\"Acceleration x (m/s^2)\">graX</data>".getBytes());
                    output.write("<data name=\"Acceleration y (m/s^2)\">graY</data>".getBytes());
                    output.write("<data name=\"Acceleration z (m/s^2)\">graZ</data>".getBytes());
                    output.write("</set>".getBytes());
                    break;
                case GNSS_SATELLITES:
                    writeSet(output, "GNSS fixes", "loc_time", GNSS_FIX_COLUMNS);
                    writeSet(output, "GNSS satellites", null, GNSS_SATELLITE_COLUMNS);
                    writeSet(output, "GNSS receiver starts", null, GNSS_START_COLUMNS);
                    break;
                case GNSS_NMEA:
                    writeSet(output, "NMEA GGA", null, GNSS_GGA_COLUMNS);
                    writeSet(output, "NMEA GSA", null, GNSS_GSA_COLUMNS);
                    break;
                case GNSS_RAW:
                    writeSet(output, "GNSS clock", null, GNSS_CLOCK_COLUMNS);
                    writeSet(output, "GNSS measurements", null, GNSS_MEASUREMENT_COLUMNS);
                    writeSet(output, "GNSS measurement status", null, GNSS_STATUS_COLUMNS);
                    break;
            }
        }

        output.write("</export>".getBytes());
    }

    private void writeAnalysis(FileOutputStream output, List<SensorType> enabledSensors) throws IOException {
        for (SensorType sensor : enabledSensors) {
            if (Objects.requireNonNull(sensor) == SensorType.ATTITUDE) {
                output.write("<analysis>".getBytes());
                output.write("<append><input clear=\"false\">attWIn</input><output>attWLast</output></append>".getBytes());
                output.write("<append><input clear=\"false\">attXIn</input><output>attXLast</output></append>".getBytes());
                output.write("<append><input clear=\"false\">attYIn</input><output>attYLast</output></append>".getBytes());
                output.write("<append><input clear=\"false\">attZIn</input><output>attZLast</output></append>".getBytes());
                output.write("<formula formula=\"abs([1]*[5_]+[2]*[6_]+[3]*[7_]+[4]*[8_])\">".getBytes());
                output.write("<input clear=\"false\">attW0</input><input clear=\"false\">attX0</input><input clear=\"false\">attY0</input><input clear=\"false\">attZ0</input><input clear=\"false\">attWIn</input><input clear=\"false\">attXIn</input><input clear=\"false\">attYIn</input><input clear=\"false\">attZIn</input><output clear=\"true\">attWOff</output>".getBytes());
                output.write("</formula>".getBytes());
                output.write("<formula formula=\"([1]*[6_]-[2]*[5_]-[3]*[8_]+[4]*[7_])*sign([1]*[5_]+[2]*[6_]+[3]*[7_]+[4]*[8_])\">".getBytes());
                output.write("<input clear=\"false\">attW0</input><input clear=\"false\">attX0</input><input clear=\"false\">attY0</input><input clear=\"false\">attZ0</input><input clear=\"false\">attWIn</input><input clear=\"false\">attXIn</input><input clear=\"false\">attYIn</input><input clear=\"false\">attZIn</input><output clear=\"true\">attXOff</output>".getBytes());
                output.write("</formula>".getBytes());
                output.write("<formula formula=\"([1]*[7_]+[2]*[8_]-[3]*[5_]-[4]*[6_])*sign([1]*[5_]+[2]*[6_]+[3]*[7_]+[4]*[8_])\">".getBytes());
                output.write("<input clear=\"false\">attW0</input><input clear=\"false\">attX0</input><input clear=\"false\">attY0</input><input clear=\"false\">attZ0</input><input clear=\"false\">attWIn</input><input clear=\"false\">attXIn</input><input clear=\"false\">attYIn</input><input clear=\"false\">attZIn</input><output clear=\"true\">attYOff</output>".getBytes());
                output.write("</formula>".getBytes());
                output.write("<formula formula=\"([1]*[8_]-[2]*[7_]+[3]*[6_]-[4]*[5_])*sign([1]*[5_]+[2]*[6_]+[3]*[7_]+[4]*[8_])\">".getBytes());
                output.write("<input clear=\"false\">attW0</input><input clear=\"false\">attX0</input><input clear=\"false\">attY0</input><input clear=\"false\">attZ0</input><input clear=\"true\">attWIn</input><input clear=\"true\">attXIn</input><input clear=\"true\">attYIn</input><input clear=\"true\">attZIn</input><output clear=\"true\">attZOff</output>".getBytes());
                output.write("</formula>".getBytes());
                output.write("<formula formula=\"2*acos([1_])*57.295779513\">".getBytes());
                output.write("<input clear=\"false\">attWOff</input><input clear=\"false\">attXOff</input><input clear=\"false\">attYOff</input><input clear=\"false\">attZOff</input><output clear=\"false\">direct</output>".getBytes());
                output.write("</formula>".getBytes());
                output.write("<formula formula=\"atan2(2*([1_]*[2_]+[3_]*[4_]),1-2*([2_]*[2_]+[3_]*[3_]))*57.295779513\">".getBytes());
                output.write("<input clear=\"false\">attWOff</input><input clear=\"false\">attXOff</input><input clear=\"false\">attYOff</input><input clear=\"false\">attZOff</input><output clear=\"false\">yaw</output>".getBytes());
                output.write("</formula>".getBytes());
                output.write("<formula formula=\"asin(2*([1_]*[3_]-[2_]*[4_]))*57.295779513\">".getBytes());
                output.write("<input clear=\"false\">attWOff</input><input clear=\"false\">attXOff</input><input clear=\"false\">attYOff</input><input clear=\"false\">attZOff</input><output clear=\"false\">pitch</output>".getBytes());
                output.write("</formula>".getBytes());
                output.write("<formula formula=\"atan2(2*([1_]*[4_]+[2_]*[3_]),1-2*([3_]*[3_]+[4_]*[4_]))*57.295779513\">".getBytes());
                output.write("<input clear=\"false\">attWOff</input><input clear=\"false\">attXOff</input><input clear=\"false\">attYOff</input><input clear=\"false\">attZOff</input><output clear=\"false\">roll</output>".getBytes());
                output.write("</formula>".getBytes());
                output.write("<append><input clear=\"true\">attWOff</input><output clear=\"false\">attW</output></append>".getBytes());
                output.write("<append><input clear=\"true\">attXOff</input><output clear=\"false\">attX</output></append>".getBytes());
                output.write("<append><input clear=\"true\">attYOff</input><output clear=\"false\">attY</output></append>".getBytes());
                output.write("<append><input clear=\"true\">attZOff</input><output clear=\"false\">attZ</output></append>".getBytes());
                output.write("</analysis>".getBytes());
            }
            }
        }


    static class SensorCheckbox {
        private final CheckBox checkBox;
        private final SimpleExperimentCreator.SensorType sensorType;

        SensorCheckbox(CheckBox checkBox, SimpleExperimentCreator.SensorType sensorType) {
            this.checkBox = checkBox;
            this.sensorType = sensorType;
        }

        public boolean isChecked() {
            return checkBox.isChecked();
        }

        public SimpleExperimentCreator.SensorType getSensorType() {
            return sensorType;
        }
    }

}

