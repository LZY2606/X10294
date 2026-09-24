package arinc.model;

import java.util.Map;

public record Dictionary(String version, Map<String, DictEntry> labels) {
  public DictEntry find(String labelOctal) {
    return labels.get(labelOctal);
  }
}
