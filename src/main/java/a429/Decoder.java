package a429;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

public final class Decoder {

    public record DictEntry(String label, String mnemonic, String encoding, String unit,
                            double resolution, double offset, List<Integer> validSsm) {

        public static DictEntry from(JsonNode node) {
            List<Integer> ssm = new ArrayList<>();
            JsonNode arr = node.get("validSsm");
            if (arr != null && arr.isArray()) {
                arr.forEach(n -> ssm.add(n.asInt()));
            }
            return new DictEntry(
                    node.path("label").asText(),
                    node.path("mnemonic").asText(),
                    node.path("encoding").asText(""),
                    node.path("unit").asText(""),
                    node.path("resolution").asDouble(1.0),
                    node.path("offset").asDouble(0.0),
                    ssm);
        }
    }

    public record Result(String status, Double engValue, String unit, String encoding,
                         String mnemonic, String diag, boolean dictHit) {
    }

    private Decoder() {
    }

    public static Result decode(long word, DictEntry entry) {
        if (entry == null) {
            return new Result("DICT_MISS", null, "", "", "",
                    "label " + Arinc.labelOctal(word) + " 在当前字典版本中无条目", false);
        }
        String diag = "";
        Double eng = null;
        switch (entry.encoding()) {
            case "BNR" -> {
                long mag = Arinc.bnrMagnitude(word);
                int sign = Arinc.bnrSign(word);
                double raw = (sign == 1 ? -1.0 : 1.0) * mag;
                eng = raw * entry.resolution() + entry.offset();
                diag = "BNR " + (sign == 1 ? "-" : "") + mag + " x " + entry.resolution()
                        + (entry.offset() != 0.0 ? " + " + entry.offset() : "")
                        + " " + entry.unit();
            }
            case "BCD" -> {
                long data = Arinc.dataField(word);
                int[] digits = new int[4];
                boolean illegal = false;
                List<Integer> bad = new ArrayList<>();
                for (int i = 0; i < 4; i++) {
                    int nibble = (int) ((data >>> (4 * i)) & 0xF);
                    digits[i] = nibble;
                    if (nibble > 9) {
                        illegal = true;
                        bad.add(nibble);
                    }
                }
                if (illegal) {
                    diag = "BCD 非法数字 " + bad + "，值保留在原始字，不做换算";
                    return new Result("BCD_ILLEGAL", null, entry.unit(), "BCD",
                            entry.mnemonic(), diag, true);
                }
                int integer = digits[0] + 10 * digits[1] + 100 * digits[2] + 1000 * digits[3];
                eng = integer * entry.resolution() + entry.offset();
                diag = "BCD " + integer + " x " + entry.resolution() + " " + entry.unit();
            }
            case "DISCRETE" -> {
                long data = Arinc.dataField(word);
                List<Integer> bits = new ArrayList<>();
                for (int i = 0; i < 19; i++) {
                    if (((data >>> i) & 1L) == 1L) {
                        bits.add(11 + i);
                    }
                }
                diag = "DISCRETE 置位 bit: " + bits;
            }
            default -> {
                return new Result("DICT_MISS", null, "", "", "",
                        "未知编码 " + entry.encoding(), true);
            }
        }
        List<Integer> valid = entry.validSsm();
        int ssm = Arinc.ssm(word);
        if (valid != null && !valid.isEmpty() && !valid.contains(ssm)) {
            return new Result("SSM_INVALID", eng, entry.unit(), entry.encoding(),
                    entry.mnemonic(), "SSM=" + ssm + " 不在有效集合 " + valid + "，不进入聚合值", true);
        }
        if (!Arinc.parityOdd(word)) {
            return new Result("PARITY_ERROR", eng, entry.unit(), entry.encoding(),
                    entry.mnemonic(), diag + "；奇偶校验失败，不进入聚合值", true);
        }
        return new Result("OK", eng, entry.unit(), entry.encoding(), entry.mnemonic(), diag, true);
    }
}
