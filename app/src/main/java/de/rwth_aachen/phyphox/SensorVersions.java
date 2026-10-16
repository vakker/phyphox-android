package de.rwth_aachen.phyphox;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

//Which version of each sensor the data come from, for meta/sensors.csv. The experiment menu switches a sensor
//between its calibrated and uncalibrated version (SensorInput.uncalibratedVersion) while stopped, without clearing
//the data, so one data set can hold both: a row says from which experiment time on an input delivered which
//Android sensor. An input gets a row at its first start and another whenever a later start uses another sensor.
//A clear empties the record, a saved state keeps it.
public class SensorVersions implements Serializable {
    public static final String CSV = "meta/sensors.csv";

    public static class Row implements Serializable {
        public final double time; //Experiment time of the start from which on the input delivered this sensor
        public final String sensor; //phyphox name of the input, e.g. "gyroscope"
        public final String calibrated; //"true", "false", or empty for a type Android offers in one version only
        public final String type; //Android's name of the type, e.g. "android.sensor.gyroscope_uncalibrated"
        public final String name;
        public final String vendor;

        public Row(double time, String sensor, String calibrated, String type, String name, String vendor) {
            this.time = time;
            this.sensor = sensor;
            this.calibrated = calibrated;
            this.type = type;
            this.name = name;
            this.vendor = vendor;
        }
    }

    private final List<Row> rows = new ArrayList<>();
    private final Map<SensorInput, Integer> lastType = new IdentityHashMap<>(); //Android type of each input at its last start

    //After the inputs have been started at the experiment time of a START
    public synchronized void started(List<SensorInput> inputs, double experimentTime) {
        for (SensorInput input : inputs) {
            if (input.sensor == null)
                continue; //Not available on this device, it delivers nothing
            Integer last = lastType.get(input);
            if (last != null && last == input.type)
                continue;
            lastType.put(input, input.type);
            rows.add(new Row(experimentTime, input.sensorName.name(), calibrated(input.type),
                    orEmpty(input.sensor.getStringType()), orEmpty(input.sensor.getName()), orEmpty(input.sensor.getVendor())));
        }
    }

    //A row read from a saved state. The inputs' last sensors stay unknown, so the next start adds a row for each.
    public synchronized void add(Row row) {
        rows.add(row);
    }

    public synchronized void clear() {
        rows.clear();
        lastType.clear();
    }

    public synchronized boolean isEmpty() {
        return rows.isEmpty();
    }

    public synchronized List<Row> getRows() {
        return new ArrayList<>(rows);
    }

    static String calibrated(int type) {
        if (SensorInput.calibratedVersion(type) != type)
            return "false";
        if (SensorInput.uncalibratedVersion(type) >= 0)
            return "true";
        return "";
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }
}
