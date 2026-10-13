package de.rwth_aachen.phyphox;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.Random;

//The CSV export writes every digit of a double in scientific notation, so a value reads back unchanged
public class CsvNumberTest {

    @Test
    public void writesTheShortestDigitsInScientificNotation() {
        assertEquals("3.0E0", DataExport.csvNumber(3.0, '.'));
        assertEquals("9.81E0", DataExport.csvNumber(9.81, '.'));
        assertEquals("1.5E3", DataExport.csvNumber(1500, '.'));
        assertEquals("1.23E-3", DataExport.csvNumber(0.00123, '.'));
        assertEquals("-2.5E-7", DataExport.csvNumber(-2.5e-7, '.'));
        assertEquals("1.760000000123E9", DataExport.csvNumber(1760000000.123, '.'));
        assertEquals("3.45678912345678E14", DataExport.csvNumber(345678912345678.0, '.'));
        assertEquals("0.0E0", DataExport.csvNumber(0, '.'));
        assertEquals("NaN", DataExport.csvNumber(Double.NaN, '.'));
        assertEquals("-Infinity", DataExport.csvNumber(Double.NEGATIVE_INFINITY, '.'));
        assertEquals("9,81E0", DataExport.csvNumber(9.81, ','));
    }

    //What ten significant digits lost: a GPS time of week with its nanoseconds, a carrier phase with its millimetres
    @Test
    public void everyValueReadsBackUnchanged() {
        double[] gnss = {123456.789012345, 12345678.125, 1.0e-9, Double.MIN_VALUE, Double.MAX_VALUE, 0.1, 1.0 / 3};
        for (double value : gnss)
            assertEquals(value, Double.parseDouble(DataExport.csvNumber(value, '.')), 0);

        Random random = new Random(42);
        for (int i = 0; i < 100000; i++) {
            double value = Double.longBitsToDouble(random.nextLong());
            if (Double.isNaN(value) || Double.isInfinite(value))
                continue;
            assertEquals(value, Double.parseDouble(DataExport.csvNumber(value, '.')), 0);
        }
    }
}
