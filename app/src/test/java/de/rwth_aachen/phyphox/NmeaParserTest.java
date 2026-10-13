package de.rwth_aachen.phyphox;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Locale;

//The GGA and GSA sentences the location input records, from any talker, with empty fields and checksums
public class NmeaParserTest {

    //The sentence with its checksum, as a receiver sends it
    private static String sentence(String body) {
        int checksum = 0;
        for (int i = 0; i < body.length(); i++)
            checksum ^= body.charAt(i);
        return "$" + body + String.format(Locale.US, "*%02X\r\n", checksum);
    }

    @Test
    public void readsAGgaSentence() {
        NmeaParser.Gga gga = NmeaParser.parseGga(sentence("GPGGA,123519.50,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,"));

        assertNotNull(gga);
        assertEquals(12 * 3600 + 35 * 60 + 19.5, gga.time, 1e-9);
        assertEquals(1, gga.quality, 0);
        assertEquals(8, gga.satellites, 0);
        assertEquals(0.9, gga.hdop, 1e-9);
        assertEquals(545.4, gga.altitude, 1e-9);
        assertEquals(46.9, gga.geoidSeparation, 1e-9);
    }

    @Test
    public void emptyFieldsAreNaN() {
        NmeaParser.Gga gga = NmeaParser.parseGga(sentence("GNGGA,,,,,,0,00,99.99,,,,,,"));

        assertNotNull(gga);
        assertTrue(Double.isNaN(gga.time));
        assertEquals(0, gga.quality, 0);
        assertEquals(0, gga.satellites, 0);
        assertEquals(99.99, gga.hdop, 1e-9);
        assertTrue(Double.isNaN(gga.altitude));
        assertTrue(Double.isNaN(gga.geoidSeparation));
    }

    @Test
    public void readsGsaSentencesWithAndWithoutTheSystemId() {
        NmeaParser.Gsa gsa = NmeaParser.parseGsa(sentence("GNGSA,A,3,04,05,09,12,24,,,,,,,,2.5,1.3,2.1,1"));
        assertNotNull(gsa);
        assertEquals(3, gsa.mode, 0);
        assertEquals(2.5, gsa.pdop, 1e-9);
        assertEquals(1.3, gsa.hdop, 1e-9);
        assertEquals(2.1, gsa.vdop, 1e-9);
        assertEquals(1, gsa.system, 0);

        NmeaParser.Gsa old = NmeaParser.parseGsa(sentence("GPGSA,A,2,04,05,,,,,,,,,,,3.0,2.0,2.2"));
        assertNotNull(old);
        assertEquals(2, old.mode, 0);
        assertTrue("no system id before NMEA 4.1", Double.isNaN(old.system));
    }

    @Test
    public void ignoresAWrongChecksumButAcceptsNone() {
        assertNull(NmeaParser.parseGga("$GPGGA,123519.50,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,*00"));
        NmeaParser.Gga unchecked = NmeaParser.parseGga("$GPGGA,123519.50,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,");
        assertNotNull(unchecked);
        assertEquals(8, unchecked.satellites, 0);
    }

    @Test
    public void otherSentencesAreNotRead() {
        String rmc = sentence("GPRMC,123519,A,4807.038,N,01131.000,E,022.4,084.4,230394,003.1,W");
        assertNull(NmeaParser.parseGga(rmc));
        assertNull(NmeaParser.parseGsa(rmc));
        assertNull(NmeaParser.parseGga(sentence("PQTMVER,MODULE_L89,2.0")));
        assertNull(NmeaParser.parseGsa(sentence("GPGGA,123519.50,,,,,1,08,0.9,,,,,,")));
        assertNull(NmeaParser.parseGga("not a sentence"));
        assertNull(NmeaParser.parseGga(null));
    }
}
