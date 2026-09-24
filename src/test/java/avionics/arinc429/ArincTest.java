package avionics.arinc429;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ArincTest {

    @Test
    void labelOctalDisplayDoesNotChangeBits() {
        long raw = CaseLoader.assemble(035, true, 0, 1500, 0, true);
        Arinc.Decoded d = Arinc.decode(raw);
        assertEquals(035, d.labelOctal());
        assertEquals("035", d.labelOctalText());
        assertEquals(raw, d.raw()); // 底层位不变
    }

    @Test
    void bitOrderReversedLabelDecodesDifferently() {
        long raw = CaseLoader.assemble(035, false, 0, 300, 0, true);
        Arinc.Decoded d = Arinc.decode(raw);
        assertEquals("270", d.labelOctalText()); // 线上字节未反转 -> 0270
        assertTrue(d.parityOk());
    }

    @Test
    void oddParityChecked() {
        long good = CaseLoader.assemble(035, true, 0, 100, 0, true);
        long bad = CaseLoader.assemble(035, true, 0, 100, 0, false);
        assertTrue(Arinc.decode(good).parityOk());
        assertFalse(Arinc.decode(bad).parityOk());
    }

    @Test
    void bnrNegativeTwoComplement() {
        double v = Arinc.decodeBnr(523054, 0.01); // 0x7FB2E
        assertEquals(-12.34, v, 1e-9);
        assertEquals(15.0, Arinc.decodeBnr(1500, 0.01), 1e-9);
    }

    @Test
    void bcdIllegalDigitKeptWithDiagnostic() {
        Arinc.BcdResult ok = Arinc.decodeBcd(0x1250, 1.0);
        assertTrue(ok.ok());
        assertEquals(1250.0, ok.value(), 1e-9);
        Arinc.BcdResult bad = Arinc.decodeBcd(0x12A5, 1.0);
        assertFalse(bad.ok());
        assertTrue(bad.diagnostic().contains("非法"));
    }

    @Test
    void ssmInvalidStateFlagged() {
        long raw = CaseLoader.assemble(035, true, 0, 200, 3, true);
        Arinc.Decoded d = Arinc.decode(raw);
        assertEquals(3, d.ssm());
        assertTrue(d.ssmInvalid());
    }

    @Test
    void discreteBitsListed() {
        assertEquals(java.util.List.of(1, 3), Arinc.discreteBits(5));
        assertTrue(Arinc.discreteBits(0).isEmpty());
    }
}
