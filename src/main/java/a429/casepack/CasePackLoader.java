package a429.casepack;

import a429.domain.ChannelConfig;
import a429.domain.Dictionary;
import a429.domain.EncodingType;
import a429.domain.LabelEntry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads the domain-specific offline case pack. This is not a generic table
 * import: channels, versioned dictionaries and the authored word fragments are
 * separate materials under resources/casepack/wordline-429.
 */
public final class CasePackLoader {

    public static final String PACK_DIR = "casepack/wordline-429";

    private final ObjectMapper mapper;

    public CasePackLoader() {
        this(new ObjectMapper());
    }

    public CasePackLoader(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public CasePack loadFromResources() {
        try {
            return load(mapper.readTree(CasePackLoader.class.getResourceAsStream("/" + PACK_DIR + "/manifest.json")),
                    mapper.readTree(CasePackLoader.class.getResourceAsStream("/" + PACK_DIR + "/channels.json")),
                    readDictTree(),
                    readFragmentsTree(),
                    readPatchesTree(),
                    readStepsTree());
        } catch (IOException e) {
            throw new IllegalStateException("cannot read bundled case pack", e);
        }
    }

    private JsonNode readDictTree() throws IOException {
        return mapper.readTree(CasePackLoader.class.getResourceAsStream("/" + PACK_DIR + "/dictionaries/all.json"));
    }

    private JsonNode readFragmentsTree() throws IOException {
        return mapper.readTree(CasePackLoader.class.getResourceAsStream("/" + PACK_DIR + "/fragments.json"));
    }

    private JsonNode readPatchesTree() throws IOException {
        return mapper.readTree(CasePackLoader.class.getResourceAsStream("/" + PACK_DIR + "/patches.json"));
    }

    private JsonNode readStepsTree() throws IOException {
        return mapper.readTree(CasePackLoader.class.getResourceAsStream("/" + PACK_DIR + "/scenario.json"));
    }

    public CasePack load(JsonNode manifest, JsonNode channelsNode, JsonNode dictionariesNode,
                         JsonNode fragmentsNode, JsonNode patchesNode, JsonNode stepsNode) {
        String packId = manifest.path("packId").asText();
        String caseId = manifest.path("caseId").asText();
        String title = manifest.path("title").asText();
        double gapFactor = manifest.path("rateParams").path("gapFactor").asDouble(3.0);
        double switchRatio = manifest.path("rateParams").path("switchRatio").asDouble(1.7);
        String defaultDict = manifest.path("defaultDictVersion").asText("dict-v1");

        List<ChannelConfig> channels = new ArrayList<>();
        for (JsonNode ch : channelsNode.get("channels")) {
            channels.add(new ChannelConfig(ch.get("channelId").asText(),
                    ch.get("name").asText(),
                    ch.path("bitReversed").asBoolean(false),
                    ch.path("nominalSpeedHz").asLong(100_000L),
                    ch.path("speedBasis").asText("")));
        }

        Map<String, Dictionary> dicts = new LinkedHashMap<>();
        for (JsonNode d : dictionariesNode.get("dictionaries")) {
            Map<String, LabelEntry> entries = new LinkedHashMap<>();
            for (JsonNode e : d.get("entries")) {
                EncodingType enc = EncodingType.valueOf(e.get("encoding").asText());
                Map<Integer, String> bitNames = new LinkedHashMap<>();
                if (e.has("bitNames")) {
                    e.get("bitNames").fields().forEachRemaining(en ->
                            bitNames.put(Integer.parseInt(en.getKey()), en.getValue().asText()));
                }
                LabelEntry entry = new LabelEntry(
                        e.get("labelOctal").asText(),
                        e.path("sdi").asInt(0),
                        e.get("name").asText(),
                        enc,
                        e.path("resolution").asDouble(1.0),
                        e.path("unit").asText(""),
                        e.path("basis").asText(""),
                        e.path("signed").asBoolean(false),
                        bitNames);
                entries.put(Dictionary.key(entry.labelOctal(), entry.sdi()), entry);
            }
            dicts.put(d.get("version").asText(),
                    new Dictionary(d.get("version").asText(), d.path("releasedAt").asText(""), entries));
        }

        List<PackFragment> fragments = new ArrayList<>();
        for (JsonNode f : fragmentsNode.get("fragments")) {
            List<PackWord> words = new ArrayList<>();
            for (JsonNode w : f.get("words")) {
                words.add(new PackWord(
                        w.get("wordId").asText(),
                        w.get("channelId").asText(),
                        w.get("position").asInt(),
                        w.get("receivedAtNs").asLong(),
                        w.get("receivedAtIso").asText(),
                        w.get("wireHex").asText(),
                        w.path("fragmentId").asText(f.fragmentId()),
                        w.path("note").asText("")));
            }
            fragments.add(new PackFragment(f.get("fragmentId").asText(),
                    f.path("title").asText(""), words));
        }

        List<SourcePatch> patches = new ArrayList<>();
        if (patchesNode != null && patchesNode.has("patches")) {
            for (JsonNode p : patchesNode.get("patches")) {
                patches.add(new SourcePatch(
                        p.get("patchId").asText(),
                        p.get("wordId").asText(),
                        p.path("wireHex").asText(null),
                        p.has("receivedAtNs") && !p.get("receivedAtNs").isNull()
                                ? p.get("receivedAtNs").asLong() : null,
                        p.path("receivedAtIso").asText(null),
                        p.path("shiftMs").asLong(0),
                        p.path("changes").asText(""),
                        p.path("expectedScope").asText("")));
            }
        }

        List<PackStep> steps = new ArrayList<>();
        if (stepsNode != null && stepsNode.has("steps")) {
            for (JsonNode s : stepsNode.get("steps")) {
                List<String> touches = new ArrayList<>();
                s.path("touches").forEach(t -> touches.add(t.asText()));
                List<String> assertions = new ArrayList<>();
                s.path("assertions").forEach(t -> assertions.add(t.asText()));
                steps.add(new PackStep(s.get("order").asInt(), s.get("action").asText(),
                        s.path("title").asText(""), s.path("detail").asText(""),
                        touches, assertions));
            }
        }

        return new CasePack(packId, caseId, title, gapFactor, switchRatio, defaultDict,
                channels, dicts, fragments, patches, steps,
                manifest.path("narrative").asText(""));
    }
}
