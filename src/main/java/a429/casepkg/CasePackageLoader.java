package a429.casepkg;

import a429.db.Json;
import a429.domain.DictEntry;
import a429.domain.DictVersion;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Loads the shipped offline case package from resources or an extracted directory. */
public class CasePackageLoader {

    public static final String PACKAGE_DIR = "cases/word-decoding-bench";

    public CasePackage loadFromResources() {
        try (InputStream in = resource(PACKAGE_DIR + "/case.json")) {
            if (in == null) {
                throw new IllegalStateException("missing shipped case package: " + PACKAGE_DIR + "/case.json");
            }
            return parse(in, PACKAGE_DIR);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read case package", e);
        }
    }

    public CasePackage loadFromDirectory(Path dir) {
        try {
            return parse(Files.newInputStream(dir.resolve("case.json")), dir.toString());
        } catch (IOException e) {
            throw new IllegalStateException("cannot read case package from " + dir, e);
        }
    }

    private CasePackage parse(InputStream caseJson, String base) throws IOException {
        JsonNode root = Json.MAPPER.readTree(caseJson);
        List<CasePackage.ChannelSpec> channels = new ArrayList<>();
        for (JsonNode ch : root.path("channels")) {
            Map<String, Object> notes = new LinkedHashMap<>();
            ch.path("notes").fields().forEachRemaining(f -> notes.put(f.getKey(),
                    Json.MAPPER.convertValue(f.getValue(), Object.class)));
            channels.add(new CasePackage.ChannelSpec(ch.path("id").asText(),
                    ch.path("name").asText(), ch.path("configuredSpeed").asText(), notes));
        }
        List<CasePackage.DictionarySpec> dicts = new ArrayList<>();
        for (JsonNode d : root.path("dictionaries")) {
            Map<String, Object> changeNote = new LinkedHashMap<>();
            d.path("changeNote").fields().forEachRemaining(f -> changeNote.put(f.getKey(),
                    Json.MAPPER.convertValue(f.getValue(), Object.class)));
            dicts.add(new CasePackage.DictionarySpec(d.path("id").asText(),
                    d.path("version").asText(), d.path("activate").asBoolean(false),
                    d.path("path").asText(), changeNote));
        }
        List<CasePackage.WordSpec> words = new ArrayList<>();
        for (JsonNode w : root.path("words")) {
            words.add(new CasePackage.WordSpec(w.path("id").asText(), w.path("seq").asLong(),
                    w.path("receiveText").asText(), w.path("channelId").asText(),
                    w.path("rawHex").asText(),
                    w.path("origin").asText("shipped")));
        }
        List<CasePackage.MutationSpec> mutations = new ArrayList<>();
        for (JsonNode m : root.path("mutations")) {
            List<CasePackage.ExpectationSpec> expectations = new ArrayList<>();
            for (JsonNode e : m.path("expectations")) {
                expectations.add(new CasePackage.ExpectationSpec(e.path("check").asText(),
                        e.path("accepted").asBoolean(),
                        Json.MAPPER.convertValue(e.path("expected"), Object.class),
                        e.path("rationale").asText("")));
            }
            List<CasePackage.ExpectedObjectSpec> unaffected = new ArrayList<>();
            for (JsonNode u : m.path("unaffectedObjects")) {
                unaffected.add(new CasePackage.ExpectedObjectSpec(u.path("objectId").asText(),
                        u.path("aspect").asText(),
                        Json.MAPPER.convertValue(u.path("value"), Object.class)));
            }
            mutations.add(new CasePackage.MutationSpec(m.path("id").asText(),
                    m.path("title").asText(), m.path("alters").asText(),
                    m.path("wordId").asText(""), expectations, unaffected));
        }
        JsonNode p = root.path("initialParams");
        CasePackage.RateParamsSpec params = new CasePackage.RateParamsSpec(
                p.path("gapThresholdMs").asDouble(20.0),
                p.path("toleranceFraction").asDouble(0.12),
                p.path("labelConvention").asText("CANONICAL"));
        return new CasePackage(root.path("id").asText(), root.path("title").asText(),
                root.path("basis").asText(""), root.path("rulesVersion").asText("rules-1.0.0"),
                List.copyOf(channels), List.copyOf(dicts), List.copyOf(words),
                List.copyOf(mutations), params);
    }

    public DictVersion loadDictionary(CasePackage.DictionarySpec spec) {
        String resourcePath = PACKAGE_DIR + "/" + spec.path();
        try (InputStream in = resource(resourcePath)) {
            if (in == null) {
                throw new IllegalStateException("missing dictionary file: " + resourcePath);
            }
            return DictMapper.parse(spec, in);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read dictionary " + resourcePath, e);
        }
    }

    public DictVersion loadDictionaryFromDirectory(Path baseDir, CasePackage.DictionarySpec spec) {
        try (InputStream in = Files.newInputStream(baseDir.resolve(spec.path()))) {
            return DictMapper.parse(spec, in);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read dictionary " + spec.path(), e);
        }
    }

    private InputStream resource(String path) {
        return Thread.currentThread().getContextClassLoader().getResourceAsStream(path);
    }
}
