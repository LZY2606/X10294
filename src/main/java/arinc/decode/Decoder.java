package arinc.decode;

import arinc.model.DictEntry;
import arinc.model.Dictionary;
import arinc.model.DecodedValue;
import java.util.ArrayList;
import java.util.List;

/**
 * Pure ARINC 429 word decoding. Bit 1 of the word is the LSB of the 32-bit integer
 * (transmission order). The octal label is a display transform only; stored bits
 * are never modified by decoding.
 */
public final class Decoder {
  private Decoder() {}

  public static int labelByte(long word) {
    return (int) (word & 0xFFL);
  }

  public static int reverse8(int b) {
    int r = 0;
    for (int i = 0; i < 8; i++) {
      if (((b >>> i) & 1) != 0) {
        r |= 1 << (7 - i);
      }
    }
    return r;
  }

  /** Octal display of the label (bits 1-8, transmitted MSB-first). View transform only. */
  public static String labelOctal(long word) {
    return String.format("%03o", reverse8(labelByte(word)));
  }

  public static int sdi(long word) {
    return (int) ((word >>> 8) & 0x3);
  }

  /** Data field: bits 11-29 (19 bits; bit 29 is the BNR sign bit). */
  public static long dataField(long word) {
    return (word >>> 10) & 0x7FFFFL;
  }

  /** Sign/Status Matrix: bits 30-31. */
  public static int ssm(long word) {
    return (int) ((word >>> 29) & 0x3);
  }

  public static int parityBit(long word) {
    return (int) ((word >>> 31) & 0x1);
  }

  /** ARINC 429 uses odd parity across all 32 bits. */
  public static boolean parityOk(long word) {
    return Long.bitCount(word & 0xFFFFFFFFL) % 2 == 1;
  }

  public static String ssmStatus(String encoding, int ssm) {
    if ("BNR".equals(encoding)) {
      return switch (ssm) {
        case 0 -> "FAILURE_WARNING";
        case 1 -> "NO_COMPUTED_DATA";
        case 2 -> "FUNCTIONAL_TEST";
        default -> "NORMAL";
      };
    }
    return switch (ssm) {
      case 0 -> "PLUS";
      case 1 -> "NO_COMPUTED_DATA";
      case 2 -> "FUNCTIONAL_TEST";
      default -> "MINUS";
    };
  }

  public static DecodedValue decode(long rawWordId, long word, Dictionary dict) {
    String id = "dv:" + rawWordId + ":" + dict.version();
    List<String> diagnostics = new ArrayList<>();
    String label = labelOctal(word);
    if (!parityOk(word)) {
      diagnostics.add("PARITY_ERROR");
    }
    DictEntry entry = dict.find(label);
    if (entry == null) {
      String alt = String.format("%03o", labelByte(word));
      if (dict.find(alt) != null) {
        diagnostics.add("BIT_ORDER_REVERSED_SUSPECT:" + alt);
      } else {
        diagnostics.add("UNKNOWN_LABEL");
      }
      return new DecodedValue(id, rawWordId, dict.version(), "UNKNOWN", null, null,
          "label " + label, diagnostics, "none");
    }
    String hit = dict.version() + ":labels/" + label;
    int ssm = ssm(word);
    if (!entry.validSsm().contains(ssm)) {
      diagnostics.add("SSM_INVALID_STATE:" + ssm);
    }
    switch (entry.encoding()) {
      case "BNR" -> {
        long raw = dataField(word);
        long signed = raw >= (1L << 18) ? raw - (1L << 19) : raw;
        double value = signed * entry.resolution();
        return new DecodedValue(id, rawWordId, dict.version(), "BNR", value, entry.unit(),
            format(value, entry.unit()) + " [" + ssmStatus("BNR", ssm) + "]", diagnostics, hit);
      }
      case "BCD" -> {
        long data = dataField(word);
        long digits = 0;
        long place = 1;
        for (int i = 0; i < 5; i++) {
          int nib = (int) ((data >>> (4 * i)) & 0xF);
          if (nib > 9) {
            diagnostics.add("BCD_ILLEGAL_DIGIT");
            return new DecodedValue(id, rawWordId, dict.version(), "BCD", null, entry.unit(),
                "unconvertible, raw data 0x" + Long.toHexString(data), diagnostics, hit);
          }
          digits += nib * place;
          place *= 10;
        }
        if (ssm == 3) {
          digits = -digits;
        }
        double value = digits * entry.resolution();
        return new DecodedValue(id, rawWordId, dict.version(), "BCD", value, entry.unit(),
            format(value, entry.unit()) + " [" + ssmStatus("BCD", ssm) + "]", diagnostics, hit);
      }
      case "DISC" -> {
        long data = dataField(word);
        return new DecodedValue(id, rawWordId, dict.version(), "DISC", (double) data, entry.unit(),
            "bits " + Long.toBinaryString(data) + " [" + ssmStatus("DISC", ssm) + "]",
            diagnostics, hit);
      }
      default -> throw new IllegalArgumentException("unknown encoding " + entry.encoding());
    }
  }

  private static String format(double v, String unit) {
    String num = v == Math.floor(v) && !Double.isInfinite(v)
        ? Long.toString((long) v)
        : Double.toString(v);
    return unit == null || unit.isEmpty() ? num : num + " " + unit;
  }
}
