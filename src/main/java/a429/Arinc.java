package a429;

public final class Arinc {

    public static final int BNR_SIGN_BIT = 29;

    private Arinc() {
    }

    public static int reverse8(int value) {
        int result = 0;
        for (int i = 0; i < 8; i++) {
            result = (result << 1) | ((value >>> i) & 1);
        }
        return result & 0xFF;
    }

    public static int labelByteRaw(long word) {
        return (int) (word & 0xFF);
    }

    public static String labelOctal(long word) {
        return String.format("%03o", reverse8(labelByteRaw(word)));
    }

    public static String labelOctalUnreversed(long word) {
        return String.format("%03o", labelByteRaw(word));
    }

    public static String labelBits(long word) {
        int b = labelByteRaw(word);
        StringBuilder sb = new StringBuilder(8);
        for (int i = 7; i >= 0; i--) {
            sb.append((b >>> i) & 1);
        }
        return sb.toString();
    }

    public static int sdi(long word) {
        return (int) ((word >>> 8) & 0x3);
    }

    public static int ssm(long word) {
        return (int) ((word >>> 29) & 0x3);
    }

    public static long dataField(long word) {
        return (word >>> 10) & 0x7FFFFL;
    }

    public static int bnrSign(long word) {
        return (int) ((word >>> (BNR_SIGN_BIT - 1)) & 1);
    }

    public static long bnrMagnitude(long word) {
        return dataField(word) & 0x3FFFFL;
    }

    public static boolean parityOdd(long word) {
        return Long.bitCount(word & 0xFFFFFFFFL) % 2 == 1;
    }

    public static String bits32(long word) {
        StringBuilder sb = new StringBuilder(32);
        for (int i = 31; i >= 0; i--) {
            sb.append((word >>> i) & 1);
        }
        return sb.toString();
    }

    public static String rawHex(long word) {
        return String.format("%08X", word & 0xFFFFFFFFL);
    }

    public static long parseHex(String hex) {
        return Long.parseLong(hex.trim().replace("0x", ""), 16);
    }
}
