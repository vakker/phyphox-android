package de.rwth_aachen.phyphox;

//Reads the two NMEA sentences the location input records, from any talker ($GP, $GN, $GL, $GA, $GB, ...):
//GGA (UTC time of day, fix quality, satellites in use, HDOP, altitude above mean sea level, geoid separation) and
//GSA (fix mode and dilution of precision). Empty fields are NaN. A sentence with a wrong checksum is ignored.
//Other sentences are not recorded: phyphox buffers hold numbers, not the sentences themselves.
final class NmeaParser {

    private NmeaParser() {
    }

    static final class Gga {
        double time = Double.NaN; //UTC seconds since midnight
        double quality = Double.NaN; //0 no fix, 1 GNSS, 2 DGNSS, 4 RTK fixed, 5 RTK float, 6 dead reckoning, ...
        double satellites = Double.NaN; //Satellites in use
        double hdop = Double.NaN;
        double altitude = Double.NaN; //Above mean sea level (m)
        double geoidSeparation = Double.NaN; //Geoid above the WGS84 ellipsoid (m)
    }

    static final class Gsa {
        double mode = Double.NaN; //1 no fix, 2 2D, 3 3D
        double pdop = Double.NaN;
        double hdop = Double.NaN;
        double vdop = Double.NaN;
        double system = Double.NaN; //NMEA 4.1 system id: 1 GPS, 2 GLONASS, 3 Galileo, 4 BeiDou, 5 QZSS, 6 NavIC
    }

    //A GGA sentence, or null for any other sentence or a malformed one
    static Gga parseGga(String sentence) {
        String[] f = fields(sentence, "GGA");
        if (f == null)
            return null;
        Gga gga = new Gga();
        gga.time = timeOfDay(field(f, 1));
        gga.quality = number(field(f, 6));
        gga.satellites = number(field(f, 7));
        gga.hdop = number(field(f, 8));
        gga.altitude = number(field(f, 9));
        gga.geoidSeparation = number(field(f, 11));
        return gga;
    }

    //A GSA sentence, or null for any other sentence or a malformed one
    static Gsa parseGsa(String sentence) {
        String[] f = fields(sentence, "GSA");
        if (f == null)
            return null;
        Gsa gsa = new Gsa();
        gsa.mode = number(field(f, 2));
        gsa.pdop = number(field(f, 15));
        gsa.hdop = number(field(f, 16));
        gsa.vdop = number(field(f, 17));
        gsa.system = hexDigit(field(f, 18));
        return gsa;
    }

    //The comma-separated fields of a sentence of the given type, the checksum removed, or null
    private static String[] fields(String sentence, String type) {
        if (sentence == null)
            return null;
        String s = sentence.trim();
        if (s.length() < 7 || s.charAt(0) != '$')
            return null;
        int star = s.indexOf('*');
        if (star >= 0) {
            if (star + 3 > s.length())
                return null;
            int checksum = 0;
            for (int i = 1; i < star; i++)
                checksum ^= s.charAt(i);
            try {
                if (Integer.parseInt(s.substring(star + 1, star + 3), 16) != checksum)
                    return null;
            } catch (NumberFormatException e) {
                return null;
            }
            s = s.substring(0, star);
        }
        String[] f = s.split(",", -1);
        //"$" + two-letter talker + sentence type
        if (f[0].length() != 6 || !f[0].endsWith(type))
            return null;
        return f;
    }

    private static String field(String[] f, int index) {
        return index < f.length ? f[index] : "";
    }

    private static double number(String value) {
        if (value.isEmpty())
            return Double.NaN;
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    private static double hexDigit(String value) {
        if (value.isEmpty())
            return Double.NaN;
        try {
            return Integer.parseInt(value, 16);
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    //hhmmss.ss to seconds since midnight
    private static double timeOfDay(String value) {
        if (value.length() < 6)
            return Double.NaN;
        try {
            int hours = Integer.parseInt(value.substring(0, 2));
            int minutes = Integer.parseInt(value.substring(2, 4));
            double seconds = Double.parseDouble(value.substring(4));
            return hours * 3600 + minutes * 60 + seconds;
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }
}
