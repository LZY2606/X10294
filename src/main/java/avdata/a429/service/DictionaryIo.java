package avdata.a429.service;

import avdata.a429.decode.DictionaryEntry;
import avdata.a429.decode.LabelDictionary;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/** Parses the JSON dictionary format shipped in the offline case pack. */
public final class DictionaryIo {
    private DictionaryIo() {}

    public static LabelDictionary parse(String version, String payload) {
        JsonObject root = Json.tree(payload).getAsJsonObject();
        JsonArray arr = root.getAsJsonArray("entries");
        List<DictionaryEntry> entries = new ArrayList<>();
        for (JsonElement el : arr) {
            JsonObject o = el.getAsJsonObject();
            List<DictionaryEntry.DiscreteBit> bits = new ArrayList<>();
            if (o.has("discrete")) {
                for (JsonElement d : o.getAsJsonArray("discrete")) {
                    JsonObject db = d.getAsJsonObject();
                    bits.add(new DictionaryEntry.DiscreteBit(db.get("bit").getAsInt(),
                            db.get("meaning").getAsString()));
                }
            }
            entries.add(new DictionaryEntry(
                    o.get("label").getAsString(),
                    o.has("sdi") ? o.get("sdi").getAsString() : "*",
                    o.get("encoding").getAsString(),
                    o.get("name").getAsString(),
                    o.has("unit") ? o.get("unit").getAsString() : "",
                    o.has("basis") ? o.get("basis").getAsString() : "",
                    o.has("resolution") ? o.get("resolution").getAsDouble() : 0,
                    o.has("offset") ? o.get("offset").getAsDouble() : 0,
                    o.get("dataLsb").getAsInt(),
                    o.get("dataMsb").getAsInt(),
                    o.has("signBit") ? o.get("signBit").getAsInt() : 0,
                    bits));
        }
        return new LabelDictionary(version, entries);
    }
}
