package avdata.a429.decode;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BitsParityTest {
    @Test
    void fieldExtractionFollowsTransmissionOrder() {
        int w = 0;
        w |= 1 << 0;   // bit 1
        w |= 1 << 7;   // bit 8
        w |= 1 << 31;  // bit 32
        assertEquals(0b1000_0001, Bits.field(w, 1, 8));
        assertEquals(1, Bits.bit(w, 32));
        assertEquals(0, Bits.bit(w, 9));
    }

    @Test
    void reverse8MatchesLabelConvention() {
        assertEquals(0b0000_0001, Bits.reverse8(0b1000_0000));
        assertEquals(0xFF, Bits.reverse8(0xFF));
    }

    @Test
    void reverse32SwapsEnds() {
        assertEquals(1, Bits.bit(Bits.reverse32(1 << 31), 1));
        assertEquals(1, Bits.bit(Bits.reverse32(1), 32));
    }

    @Test
    void oddParityDetectsAndRepairs() {
        int good = Parity.withOddParity(0x00000001);
        assertTrue(Parity.isValid(good));
        int bad = good ^ 0x80000000;
        assertFalse(Parity.isValid(bad));
        assertTrue(Parity.isValid(Parity.withOddParity(bad)));
    }

    @Test
    void octalIsDisplayOnly() {
        assertEquals("307", Bits.octal3(0b11000111));
        assertEquals("203", Bits.octal3(0b01000011));
    }
}
