package avdata.a429.decode;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DecodeTest {
    private LabelDictionary dict() {
        return new LabelDictionary("d1", List.of(
                new DictionaryEntry("307", "*", "BNR", "ALT", "ft", "MSL",
                        1.0, 0, 11, 29, 29, List.of()),
                new DictionaryEntry("204", "*", "BCD", "ALT_BCD", "ft", "MSL",
                        0, 0, 11, 26, 0, List.of()),
                new DictionaryEntry("203", "*", "DISCRETE", "DISC", "state", "",
                        0, 0, 11, 29, 0,
                        List.of(new DictionaryEntry.DiscreteBit(11, "BIT11")))
        ));
    }

    private int setField(int w, int lsb, int msb, int v) {
        int width = msb - lsb + 1;
        int mask = (1 << width) - 1;
        return (w & ~(mask << (lsb - 1))) | ((v & mask) << (lsb - 1));
    }

    private int mk(String docOctal, String sdi, String ssm, int data) {
        int oct = Integer.parseInt(docOctal, 8);
        int w = setField(0, 1, 8, Bits.reverse8(oct));
        w = setField(w, 9, 10, Integer.parseInt(new StringBuilder(sdi).reverse().toString(), 2));
        w = setField(w, 11, 29, data);
        w = setField(w, 30, 31, Integer.parseInt(new StringBuilder(ssm).reverse().toString(), 2));
        return Parity.withOddParity(w);
    }

    @Test
    void bnrPositiveAndNegative() {
        DecodedWord pos = Decoder.decode(mk("307", "00", "11", 100), dict());
        Interpretation ip = pos.interpretations.get(0);
        assertEquals(100.0, ip.value);
        assertFalse(ip.diagnostics.stream().anyMatch(d -> d.code().equals("BNR_NEGATIVE")));

        int negBits = (1 << 19) - 25;
        DecodedWord neg = Decoder.decode(mk("307", "00", "11", negBits), dict());
        Interpretation in = neg.interpretations.get(0);
        assertEquals(-25.0, in.value);
        assertTrue(in.diagnostics.stream().anyMatch(d -> d.code().equals("BNR_NEGATIVE")));
    }

    @Test
    void bcdIllegalDigitStaysUnconvertible() {
        int w = setField(mk("204", "00", "11", 0), 11, 14, 0xA);
        DecodedWord d = Decoder.decode(w, dict());
        Interpretation it = d.interpretations.get(0);
        assertNull(it.value);
        assertFalse(it.isConvertible());
        assertTrue(it.diagnostics.stream().anyMatch(x -> x.code().equals("BCD_ILLEGAL_DIGIT")));
        assertTrue(it.rawText.contains("A"));
    }

    @Test
    void invalidSsmExcludes() {
        assertTrue(Ssm.isInvalidState("BNR", "00"));
        assertTrue(Ssm.isInvalidState("BNR", "01"));
        assertTrue(Ssm.isInvalidState("BNR", "10"));
        assertFalse(Ssm.isInvalidState("BNR", "11"));
        // BCD sign states are valid; NCD/test are not
        assertFalse(Ssm.isInvalidState("BCD", "00"));
        assertFalse(Ssm.isInvalidState("BCD", "11"));
        assertTrue(Ssm.isInvalidState("BCD", "01"));
    }

    @Test
    void octalLabelDisplayDoesNotChangeBits() {
        int w = mk("203", "00", "11", 42);
        DecodedWord d = Decoder.decode(w, dict());
        assertEquals(Integer.parseInt("203", 8), Bits.reverse8(d.labelBits));
        assertEquals("203", d.labelOctalDocument);
        // transmission-order rendering is a different display of the same 8 bits
        assertEquals(Bits.octal3(d.labelBits), d.labelOctalTransmission);
        // full reversal produces a genuinely different word
        assertNotEquals(w, Bits.reverse32(w));
    }

    @Test
    void interpretationVersionDriven() {
        LabelDictionary v2 = new LabelDictionary("d2", List.of(
                new DictionaryEntry("203", "*", "BNR", "HEADING", "deg", "MAG",
                        1.0, 0, 11, 28, 0, List.of())));
        DecodedWord d = Decoder.decode(mk("203", "00", "11", 90), v2);
        assertEquals("BNR", d.interpretations.get(0).encoding);
        assertEquals("HEADING", d.interpretations.get(0).parameterName);
        assertEquals("d2", d.interpretations.get(0).dictionaryVersion);
    }
}
