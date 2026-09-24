package avio.a429.core;

import java.util.List;

/** Shared immutable records between the interpreter and the store. */
public final class Model {
    private Model() {}

    /** Raw layer row: original representation preserved exactly as imported. */
    public record RawWord(String id, String batchId, int seq, String rxTimeText, long rxNs,
                          String channel, String rawHex, long word, String declaredParity,
                          String labelText, Integer sdi, Integer ssm, String dataHex,
                          String dictVersion, String supersedes) {}

    /** Derived layer row: normalised interpretation, always traceable to raw ids. */
    public record Derived(String id, String branchId, String wordId, String kind,
                          Double num, String text, String unit, String frame, String diag,
                          String dictVersion, String ruleVersion, String payload,
                          List<String> dependsOn) {}

    /** A retained legal candidate; adjudication never deletes alternatives. */
    public record Candidate(String id, String branchId, String kind, String subjectWordId,
                            String payload) {}

    /** Machine-readable judgement basis emitted alongside every interpretation run. */
    public record RuleBasis(String rule, String statement, String appliesTo) {}

    public record InterpretResult(List<Derived> derived, List<Candidate> candidates,
                                  List<RuleBasis> bases) {}
}
