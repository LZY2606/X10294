package arinc429.service;

import arinc429.casepack.CasepackService;
import arinc429.db.Database;
import arinc429.db.Store;
import arinc429.domain.Codec;
import arinc429.domain.Json;
import arinc429.domain.Models.Aggregate;
import arinc429.domain.Models.DecodedWord;
import arinc429.domain.Models.Dictionary;
import arinc429.domain.Models.RuleParams;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Application service. Sources (raw words, configs, dictionaries, rules) are
 * stored separately from interpretations. Everything derived is appended to
 * a branch and keyed by (id, event); historical conclusions are never
 * overwritten with the newest calculation.
 */
public final class RepositoryService {

    private final Store store;
    private final CasepackService casepack;
    private final DecodeService decoder = new DecodeService();
    private final AggregateService aggregator = new AggregateService();
    private final RateService rateService = new RateService();

    public RepositoryService(Database db, CasepackService casepack) {
        this.store = new Store(db);
        this.casepack = casepack;
    }

    public Store store() {
        return store;
    }

    public static String nowUs() {
        return String.valueOf(Instant.now().toEpochMilli() * 1000L);
    }

    /* ----------------------------------------------------------------- */
    /* Initial import from the bundled case pack, fully transactional.  */
    /* ----------------------------------------------------------------- */

    public synchronized Map<String, Object> importCasepack(String actor) {
        Connection c = store.connection();
        try {
            c.setAutoCommit(false);
            Integer existing = (Integer) store.queryOne(c,
                    "SELECT COUNT(*) AS n FROM material WHERE kind='casepack'").get("n");
            if (existing != null && existing > 0) {
                c.rollback();
                throw new IllegalStateException("casepack already imported; use a branch or reset");
            }
            String at = nowUs();
            List<Map<String, Object>> words = casepack.words();
            List<Map<String, Object>> channels = casepack.channels();
            Map<String, Object> manifest = casepack.manifest();

            int mv = 1;
            store.update(c, "INSERT INTO material(id,kind,version,source_path,imported_at_us,payload) "
                            + "VALUES('mat-baseline','casepack',1,'casepack/manifest.json',?,?)",
                    at, Json.write(manifest));

            for (Map<String, Object> ch : channels) {
                store.update(c, "INSERT INTO channel_config(id,material_version,name,bus_bps,payload) VALUES(?,?,?,?,?)",
                        String.valueOf(ch.get("id")), mv, String.valueOf(ch.get("name")),
                        ch.get("configured_bps"), Json.write(ch));
            }
            Dictionary dict = casepack.dictionary("dict-1.0");
            RuleParams rules = casepack.rules("rules-1.0");
            upsertDictionary(c, dict, "casepack/dictionary-dict-1.0.json", at);
            upsertRules(c, rules, at, "initial rule parameters");

            insertWords(c, words, mv);

            String branch = "main";
            store.update(c, "INSERT INTO branch(id,parent_branch,base_event_id,material_version,"
                            + "dictionary_version,rule_version,head_event_id,created_at_us) "
                            + "VALUES(?,?,?,?,?,?,?,?)",
                    branch, null, null, mv, dict.version(), rules.version(), null, at);

            Map<String, Object> derive = deriveOn(c, branch, mv, dict.version(), rules.version(),
                    "import", actor, new LinkedHashMap<>(Map.of(
                            "summary", "initial casepack import",
                            "wordCount", words.size(),
                            "channels", channels.size())));

            c.commit();
            return Map.of("materialVersion", mv, "branch", branch,
                    "dictionaryVersion", dict.version(), "ruleVersion", rules.version(),
                    "event", derive.get("event"), "wordCount", words.size());
        } catch (RuntimeException e) {
            rollback(c);
            throw e;
        } catch (SQLException e) {
            rollback(c);
            throw new IllegalStateException("import failed; transaction rolled back", e);
        } finally {
            close(c);
        }
    }

    private void insertWords(Connection c, List<Map<String, Object>> words, int mv) {
        int order = 0;
        for (Map<String, Object> w : words) {
            order++;
            String id = String.valueOf(w.get("id"));
            String orig = w.get("orig_index") == null ? String.valueOf(order) : String.valueOf(w.get("orig_index"));
            String t = String.valueOf(w.get("receive_time_us"));
            String ch = String.valueOf(w.get("channel"));
            String text = String.valueOf(w.get("raw_text"));
            int bits = Codec.parseRawText(text);
            store.update(c, "INSERT INTO raw_word(id,material_version,orig_index,receive_time_us,channel,raw_text,raw_bits,present) "
                            + "VALUES(?,?,?,?,?,?,?,1)",
                    id, mv, orig, t, ch, text, bits);
        }
    }

    private void upsertDictionary(Connection c, Dictionary dict, String sourcePath, String at) {
        store.update(c, "INSERT INTO dict_version(version,source_path,imported_at_us,payload) VALUES(?,?,?,?) "
                        + "ON CONFLICT(version) DO UPDATE SET payload=excluded.payload",
                dict.version(), sourcePath, at, Json.write(dict));
    }

    private void upsertRules(Connection c, RuleParams rules, String at, String note) {
        store.update(c, "INSERT INTO rule_version(version,imported_at_us,rate_tolerance,acquisition_gap_us,ssm_table,note) "
                        + "VALUES(?,?,?,?,?,?) ON CONFLICT(version) DO UPDATE SET "
                        + "rate_tolerance=excluded.rate_tolerance, "
                        + "acquisition_gap_us=excluded.acquisition_gap_us, ssm_table=excluded.ssm_table",
                rules.version(), at, rules.rateTolerance(), rules.acquisitionGapUs(),
                Json.write(rules.ssmTable()), note);
    }
}
