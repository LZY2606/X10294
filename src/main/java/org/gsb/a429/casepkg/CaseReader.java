package org.gsb.a429.casepkg;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.io.IOException;
import java.util.List;

/** 从仓库内离线目录读取案例包。 */
public class CaseReader {
    private final File dir;
    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public CaseReader(File caseDir) {
        this.dir = caseDir;
    }

    public static CaseReader wordDecodeBench(File projectRoot) {
        return new CaseReader(new File(projectRoot, "cases/word-decode-bench"));
    }

    private <T> T read(String file, Class<T> type) {
        try {
            return mapper.readValue(new File(dir, file), type);
        } catch (IOException e) {
            throw new RuntimeException("cannot read case file " + file, e);
        }
    }

    private <T> List<T> readList(String file, Class<T> type) {
        try {
            return mapper.readValue(new File(dir, file),
                    mapper.getTypeFactory().constructCollectionType(List.class, type));
        } catch (IOException e) {
            throw new RuntimeException("cannot read case file " + file, e);
        }
    }

    public CaseModel.Manifest manifest() {
        return read("manifest.json", CaseModel.Manifest.class);
    }

    public List<CaseModel.ChannelFile> channels() {
        return readList("channels.json", CaseModel.ChannelFile.class);
    }

    public List<CaseModel.WordFile> words() {
        return readList("baseline-words.json", CaseModel.WordFile.class);
    }

    public CaseModel.Dictionaries dictionaries() {
        return read("dictionaries.json", CaseModel.Dictionaries.class);
    }

    public CaseModel.Replay replay() {
        return read("replay.json", CaseModel.Replay.class);
    }

    public ObjectMapper mapper() {
        return mapper;
    }
}
