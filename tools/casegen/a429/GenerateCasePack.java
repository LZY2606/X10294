package a429;

import a429.decode.Bits429;
import a429.decode.WordBuilder;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

/**
 * Generates the offline domain case pack under
 * src/main/resources/casepack/wordline-429 using the real word encoder, so
 * parity bits, octal labels and reversed captures are guaranteed consistent
 * with the decoder under review.
 *
 * Run with: ./gradlew generateCasePack
 */
public final class GenerateCasePack {

    static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    static final String CASE_ID = "wordline429-evidence-v1";
    static final String PACK_ID = "pack-wordline-429-v1";
    static final long T0 = 1_760_000_000_000_000_000L; // monotonic-clock anchor ns
    static final Instant T0_INSTANT = Instant.parse("2025-10-09T01:20:00Z");

    public static void main(String[] args) throws Exception {
        Path out = Path.of(args.length > 0 ? args[0]
                : "src/main/resources/casepack/wordline-429");
        Files.createDirectories(out.resolve("dictionaries"));

        write(out.resolve("manifest.json"), manifest());
        write(out.resolve("channels.json"), channels());
        write(out.resolve("dictionaries/all.json"), dictionaries());
        write(out.resolve("fragments.json"), fragments(out));
        write(out.resolve("patches.json"), patches(out));
        write(out.resolve("scenario.json"), scenario());
        System.out.println("case pack written to " + out);
    }

    private static void write(Path p, ObjectNode n) throws Exception {
        Files.writeString(p, JSON.writeValueAsString(n) + "\n");
    }

    private static ObjectNode manifest() {
        ObjectNode n = JSON.createObjectNode();
        n.put("packId", PACK_ID);
        n.put("caseId", CASE_ID);
        n.put("title", "字路译码台：ARINC 429 标签、奇偶与速率分段证据");
        ObjectNode params = n.putObject("rateParams");
        params.put("gapFactor", 3.0);
        params.put("switchRatio", 1.7);
        n.put("defaultDictVersion", "dict-v1");
        n.put("narrative", "航电数据小组导入时间戳与32位原始字、通道配置和标签字典，"
                + "先保留原位序列/接收时间/通道/奇偶/label/SDI/SSM/数据域/字典版本基线，"
                + "再穿越位序反转、奇偶错误、八进制label、BNR负值、BCD非法数字、速率切换与SSM无效。");
        return n;
    }

    private static ObjectNode channels() {
        ObjectNode root = JSON.createObjectNode();
        ArrayNode arr = root.putArray("channels");
        ObjectNode a = arr.addObject();
        a.put("channelId", "CH-A");
        a.put("name", "avionics-bus-A (straight capture)");
        a.put("bitReversed", false);
        a.put("nominalSpeedHz", 100_000);
        a.put("speedBasis", "ARINC 429 high-speed bus, 100 kbit/s, bit time 10 us");
        ObjectNode b = arr.addObject();
        b.put("channelId", "CH-B");
        b.put("name", "avionics-bus-B (reversed capture wiring)");
        b.put("bitReversed", true);
        b.put("nominalSpeedHz", 100_000);
        b.put("speedBasis", "ARINC 429 high-speed bus, 100 kbit/s; probe stores bits MSB-first");
        return root;
    }
}

    private static ObjectNode dictionaries() {
        ObjectNode root = JSON.createObjectNode();
        ArrayNode dicts = root.putArray("dictionaries");

        ObjectNode v1 = dicts.addObject();
        v1.put("version", "dict-v1");
        v1.put("releasedAt", "2025-09-01");
        ArrayNode e1 = v1.putArray("entries");
        bnr(e1, "312", "Indicated Airspeed", 0.5, "kt", "ISA knots, ARINC 429 air data reference", true);
        bnr(e1, "203", "Selected Altitude", 1.0, "ft", "feet MSL, 1013.25 hPa datum", false);
        bcd(e1, "214", "Fuel Quantity Left", 1.0, "kg", "kilograms, fuel capacitance probe datum");
        discrete(e1, "270", "Landing Gear Status", new String[][]{
                {"11", "gear-left-down"}, {"12", "gear-nose-down"}, {"13", "gear-right-down"}},
                "discrete bits, gear squat switch reference");

        ObjectNode v2 = dicts.addObject();
        v2.put("version", "dict-v2");
        v2.put("releasedAt", "2025-10-01");
        ArrayNode e2 = v2.putArray("entries");
        bnr(e2, "312", "Indicated Airspeed (fine resolution)", 0.25, "kt",
                "ISA knots, ARINC 429 air data reference", true);
        bnr(e2, "203", "Selected Altitude", 1.0, "ft", "feet MSL, 1013.25 hPa datum", false);
        bcd(e2, "214", "Fuel Quantity Left", 1.0, "kg",
                "kilograms, fuel capacitance probe datum");
        discrete(e2, "270", "Landing Gear Status (revised discrete map)", new String[][]{
                {"11", "gear-left-down-locked"}, {"12", "gear-nose-down-locked"},
                {"13", "weight-on-wheels"}},
                "discrete bits, gear squat switch reference, revised 2025-10");
        return root;
    }

    private static void bnr(ArrayNode entries, String label, String name, double resolution,
                            String unit, String basis, boolean signed) {
        ObjectNode e = entries.addObject();
        e.put("labelOctal", label);
        e.put("sdi", 0);
        e.put("name", name);
        e.put("encoding", "BNR");
        e.put("resolution", resolution);
        e.put("unit", unit);
        e.put("basis", basis);
        e.put("signed", signed);
    }

    private static void bcd(ArrayNode entries, String label, String name, double resolution,
                            String unit, String basis) {
        ObjectNode e = entries.addObject();
        e.put("labelOctal", label);
        e.put("sdi", 0);
        e.put("name", name);
        e.put("encoding", "BCD");
        e.put("resolution", resolution);
        e.put("unit", unit);
        e.put("basis", basis);
        e.put("signed", false);
    }

    private static void discrete(ArrayNode entries, String label, String name,
                                 String[][] bits, String basis) {
        ObjectNode e = entries.addObject();
        e.put("labelOctal", label);
        e.put("sdi", 0);
        e.put("name", name);
        e.put("encoding", "DISCRETE");
        e.put("resolution", 1.0);
        e.put("unit", "bits");
        e.put("basis", basis);
        e.put("signed", false);
        ObjectNode names = e.putObject("bitNames");
        for (String[] bit : bits) {
            names.put(bit[0], bit[1]);
        }
    }

    private static ObjectNode fragments(Path out) {
        ObjectNode root = JSON.createObjectNode();
        ArrayNode fragments = root.putArray("fragments");
        ObjectNode f = fragments.addObject();
        f.put("fragmentId", "frag-baseline-1");
        f.put("title", "initial import baseline - all boundary cases retained in place");
        ArrayNode words = f.putArray("words");

        // CH-A / 312 IAS, BNR: positive values, a negative value, an SSM-invalid word.
        int ias1 = data(200.0 / 0.5);
        int ias2 = data(210.0 / 0.5);
        int iasNeg = data(-12.0 / 0.5); // 19-bit two's complement
        int iasSsmInvalid = data(205.0 / 0.5);
        addWord(words, "w-001", "CH-A", 0, T0 + 0, WordBuilder.hex("312", 0, ias1, 0b00, false),
                "BNR IAS +200 kt, normal SSM");
        addWord(words, "w-002", "CH-A", 1, T0 + 10_000_000L, WordBuilder.hex("312", 0, ias2, 0b00, false),
                "BNR IAS +210 kt, normal SSM");
        addWord(words, "w-003", "CH-A", 2, T0 + 20_000_000L, WordBuilder.hex("312", 0, iasNeg, 0b10, false),
                "BNR IAS -12 kt negative two's complement, SSM no-computed-data -> invalid");
        addWord(words, "w-004", "CH-A", 3, T0 + 30_000_000L, WordBuilder.hex("312", 0, iasSsmInvalid, 0b11, false),
                "SSM=11 failure warning: excluded from aggregate");
        addWord(words, "w-005", "CH-A", 4, T0 + 40_000_000L, WordBuilder.hex("312", 0, ias1, 0b00, true),
                "forced odd-parity error: retained, excluded from aggregate");

        // CH-A / 203 altitude: rate switch + acquisition gap stream (9 words).
        long t = T0 + 100_000_000L;
        addWord(words, "w-010", "CH-A", 10, t, WordBuilder.hex("203", 0, 5000, 0b00, false),
                "rate stream fast segment word 1");
        t += 18_000_000L;
        addWord(words, "w-011", "CH-A", 11, t, WordBuilder.hex("203", 0, 5010, 0b00, false),
                "fast segment word 2");
        t += 18_000_000L;
        addWord(words, "w-012", "CH-A", 12, t, WordBuilder.hex("203", 0, 5020, 0b00, false),
                "fast segment word 3");
        t += 18_000_000L;
        addWord(words, "w-013", "CH-A", 13, t, WordBuilder.hex("203", 0, 5030, 0b00, false),
                "fast segment word 4; next interval ratio 1.78 ~ switchRatio band");
        t += 32_000_000L;
        addWord(words, "w-014", "CH-A", 14, t, WordBuilder.hex("203", 0, 5040, 0b00, false),
                "boundary candidate word: 32/18=1.78");
        t += 32_000_000L;
        addWord(words, "w-015", "CH-A", 15, t, WordBuilder.hex("203", 0, 5050, 0b00, false),
                "slow segment word 2");
        t += 140_000_000L;
        addWord(words, "w-016", "CH-A", 16, t, WordBuilder.hex("203", 0, 5060, 0b00, false),
                "after 140 ms acquisition gap (>3x median)");
        t += 10_000_000L;
        addWord(words, "w-017", "CH-A", 17, t, WordBuilder.hex("203", 0, 5070, 0b00, false),
                "post-gap resume word 1");
        t += 10_000_000L;
        addWord(words, "w-018", "CH-A", 18, t, WordBuilder.hex("203", 0, 5080, 0b00, false),
                "post-gap resume word 2");

        // CH-A / 214 BCD fuel: valid digits and an illegal 0xA nibble.
        addWord(words, "w-020", "CH-A", 20, T0 + 200_000_000L, WordBuilder.hex("214", 0, 0x1234, 0b00, false),
                "BCD fuel 1234 kg, legal digits");
        addWord(words, "w-021", "CH-A", 21, T0 + 210_000_000L, WordBuilder.hex("214", 0, 0x0A56, 0b00, false),
                "BCD illegal digit A in second nibble: stays in place with diagnostic");
        addWord(words, "w-022", "CH-A", 22, T0 + 220_000_000L, WordBuilder.hex("214", 0, 0x1236, 0b01, false),
                "BCD SSM functional test: invalid status");

        // CH-A / 270 discrete gear, normal.
        int gear = (1 << 0) | (1 << 1); // ARINC bits 11,12 set -> data positions 0,1
        addWord(words, "w-030", "CH-A", 30, T0 + 300_000_000L, WordBuilder.hex("270", 0, gear, 0b00, false),
                "discrete gear: left + nose down under dict-v1");

        // CH-B bit-reversed capture: encoded straight then the stored wire is
        // the full 32-bit reversal; config-order decode must recover label 312.
        int iasB = data(180.0 / 0.5);
        int straightB = WordBuilder.word("312", 0, iasB, 0b00, false);
        int reversedWire = Bits429.reverse32(straightB);
        addWord(words, "w-040", "CH-B", 40, T0 + 400_000_000L, String.format("%08X", reversedWire),
                "bit-order reversed capture: config order recovers octal label 312");
        return root;
    }

    private static int data(double bnrValue) {
        long v = Math.round(bnrValue);
        return (int) (v & 0x7FFFF);
    }

    private static void addWord(ArrayNode words, String id, String channel, int position,
                                long ns, String hex, String note) {
        ObjectNode w = words.addObject();
        w.put("wordId", id);
        w.put("channelId", channel);
        w.put("position", position);
        w.put("receivedAtNs", ns);
        w.put("receivedAtIso", T0_INSTANT.plusNanos(ns - T0).toString());
        // ns anchor T0 is a monotonic clock sample; ISO time uses elapsed offset only
        w.put("wireHex", hex);
        w.put("fragmentId", "frag-baseline-1");
        w.put("note", note);
    }

    private static ObjectNode patches(Path out) {
        ObjectNode root = JSON.createObjectNode();
        ArrayNode patches = root.putArray("patches");

        ObjectNode p1 = patches.addObject();
        p1.put("patchId", "patch-local-1");
        p1.put("wordId", "w-014");
        p1.putNull("wireHex");
        p1.putNull("receivedAtNs");
        p1.putNull("receivedAtIso");
        p1.put("shiftMs", 8);
        p1.put("changes", "shift w-014 receive time +8 ms; interval 32->40 ms, ratio 40/18=2.22");
        p1.put("expectedScope", "rate-key wordline429-evidence-v1:rate:CH-A:203 only");

        ObjectNode p2 = patches.addObject();
        p2.put("patchId", "patch-local-2");
        p2.put("wordId", "w-005");
        p2.put("wireHex", WordBuilder.hex("312", 0, data(200.0 / 0.5), 0b00, false));
        p2.putNull("receivedAtNs");
        p2.putNull("receivedAtIso");
        p2.put("shiftMs", 0);
        p2.put("changes", "retransmit w-005 with odd parity repaired; same label/data bits");
        p2.put("expectedScope", "word w-005 engineering and CH-A:312 aggregate only");
        return root;
    }

    private static ObjectNode scenario() {
        ObjectNode root = JSON.createObjectNode();
        ArrayNode steps = root.putArray("steps");

        step(steps, 1, "import", "导入基线并核对原位字段",
                "初始导入后核对原位序列、接收时间、通道、奇偶结果、label、SDI、SSM、数据域与字典版本",
                new String[]{"w-001", "w-005", "w-014", "w-021", "w-040"},
                new String[]{
                        "all words retained with stable ids in original order",
                        "label shows as octal while labelFieldRaw keeps wire bits",
                        "w-005 parityResult=odd-bad; w-004 SSM=11; w-021 BCD illegal digit",
                        "w-040 config-order label 312 despite reversed capture"});
        step(steps, 2, "revise-params", "参数修订：switchRatio 1.7 -> 1.5",
                "只改变速率参数，失效范围限于速率候选",
                new String[]{"rate:CH-A:203"},
                new String[]{
                        "only rate entities invalidated",
                        "engineering and aggregate conclusions unchanged",
                        "historical events still show 1.7-era candidates"});
        step(steps, 3, "source-patch", "局部源数据改动：移动 w-014 时间戳",
                "只改动一个直接来源，检查影响是否被限制在 CH-A:203 速率键",
                new String[]{"w-014", "rate:CH-A:203"},
                new String[]{"invalidation scope contains no other label",
                        "old word revision still readable"});
        step(steps, 4, "conflict", "互相冲突的人工决定",
                "两个窗口基于同一旧版本同时裁定，后提交者看到 409 与双方差异",
                new String[]{"rate:CH-A:203"},
                new String[]{"first commit accepted",
                        "second commit rejected with 409 and both payloads shown",
                        "rebase then succeeds and leaves CONFLICT_RESOLVED event"});
        step(steps, 5, "dict-version", "字典版本分支：dict-v1 -> dict-v2",
                "BNR 分辨率 0.5->0.25 与 discrete 重映射，只在新版本解释中生效",
                new String[]{"w-001", "w-030"},
                new String[]{"octal label unchanged, wire bits unchanged",
                        "IAS values halve under v2; gear discrete names change",
                        "dict-v1 branch conclusions remain readable"});
        step(steps, 6, "replay", "回到旧事件复核",
                "回到任意旧事件恢复当时依赖版本和可见结论",
                new String[]{},
                new String[]{"view at old seq uses old params and old dict",
                        "history is not overwritten by latest results"});
        step(steps, 7, "export", "导出、删库、再导入",
                "导出归档后删除本地数据库再导入，身份、顺序与来源关系保持",
                new String[]{},
                new String[]{"event ids identical", "seq order identical",
                        "provenance refs identical", "bad parity still excluded"});
        return root;
    }

    private static void step(ArrayNode steps, int order, String action, String title,
                             String detail, String[] touches, String[] assertions) {
        ObjectNode s = steps.addObject();
        s.put("order", order);
        s.put("action", action);
        s.put("title", title);
        s.put("detail", detail);
        ArrayNode t = s.putArray("touches");
        for (String x : touches) {
            t.add(x);
        }
        ArrayNode a = s.putArray("assertions");
        for (String x : assertions) {
            a.add(x);
        }
    }
