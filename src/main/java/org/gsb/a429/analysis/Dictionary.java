package org.gsb.a429.analysis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.gsb.a429.db.Db;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 某一字典版本的内存视图。BNR/BCD/discrete 的全部规则都来自这里；
 * 因此解释的依赖版本由 branch.dictVersion + 事件覆盖决定。
 */
public class Dictionary {
    public record Spec(Db.DictEntryRow row, JsonNode spec) {
    }

    private final String version;
    private final List<Spec> entries = new ArrayList<>();

    private Dictionary(String version) {
        this.version = version;
    }

    public static Dictionary load(Db db, ObjectMapper mapper, String version) {
        Dictionary d = new Dictionary(version);
        for (Db.DictEntryRow row : db.dictEntries(version)) {
            try {
                d.entries.add(new Spec(row, mapper.readTree(row.specJson())));
            } catch (Exception e) {
                throw new RuntimeException("bad dict spec in " + row.sid(), e);
            }
        }
        return d;
    }

    public String version() {
        return version;
    }

    /**
     * 按 (label, sdi) 命中。sdi=0..2 优先精确匹配；sdi=3 为“任意 SDI”指示字，按 null 条目匹配。
     * 若同一键存在多条（例如重复定义），全部作为候选返回，交由裁定。
     */
    public List<Spec> match(int labelCode, int sdi) {
        List<Spec> exact = new ArrayList<>();
        List<Spec> any = new ArrayList<>();
        for (Spec s : entries) {
            if (s.row().labelCode() != labelCode) {
                continue;
            }
            if (s.row().sdi() == null) {
                any.add(s);
            } else if (s.row().sdi() == sdi) {
                exact.add(s);
            }
        }
        List<Spec> out = new ArrayList<>(exact);
        if (out.isEmpty()) {
            out.addAll(any);
        }
        return out;
    }

    public Map<String, Spec> bySid() {
        Map<String, Spec> m = new LinkedHashMap<>();
        for (Spec s : entries) {
            m.put(s.row().sid(), s);
        }
        return m;
    }

    public List<Spec> entries() {
        return entries;
    }
}
