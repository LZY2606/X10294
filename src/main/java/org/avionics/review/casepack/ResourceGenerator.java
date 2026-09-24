package org.avionics.review.casepack;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.file.Files;
import java.nio.file.Path;

/** Dev utility: regenerates src/main/resources/casepack/*.json. */
public final class ResourceGenerator {

    private ResourceGenerator() {
    }

    public static void main(String[] args) throws Exception {
        ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        CasePack pack = CasePackFactory.build();
        Path out = Path.of(args.length > 0 ? args[0]
                : "src/main/resources/casepack/arinc429-word-decoder-bench.json");
        Files.createDirectories(out.getParent());
        mapper.writeValue(out.toFile(), pack);
        System.out.println("wrote " + out + " words=" + pack.words().size()
                + " probes=" + pack.probes().size());
    }
}
