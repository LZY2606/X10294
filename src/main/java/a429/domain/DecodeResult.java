package a429.domain;

import java.util.List;

/**
 * 单字译码结果（派生层）。原始层永不改动，无法转换的字段以
 * {@code value=null}+诊断留在原处，而不是被丢弃。
 */
public record DecodeResult(
        String labelOctal,
        int sdi,
        int ssm,
        String ssmName,
        boolean ssmValid,
        String encoding,
        Double value,             // 规范化工程值（含单位由 unit 给出）；无法转换为 null
        String unit,
        String datum,
        List<Nibble> bcdNibbles,  // BCD 原始 nibble（含非法数字）
        List<DiscreteState> discreteStates,
        List<String> diagnostics,
        boolean dictionaryHit
) {
    public record Nibble(int position, int wireHi, int wireLo, int rawValue, boolean legal) {
    }

    public record DiscreteState(int bit, String name, boolean set, String zeroMeaning, String oneMeaning) {
    }

    public boolean usableForAggregate(boolean parityOk) {
        return parityOk && ssmValid && dictionaryHit && value != null;
    }
}
