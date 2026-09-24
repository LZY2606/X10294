package avionics.arinc429;

import avionics.arinc429.Segmenter.Segment;
import avionics.arinc429.Segmenter.WordPoint;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SegmenterTest {

    private static WordPoint p(int seq, long tMs, boolean parityOk) {
        return new WordPoint(seq, tMs * 1_000_000L, parityOk);
    }

    @Test
    void rateSwitchAndGapProduceSegments() {
        List<WordPoint> words = List.of(
                p(1, 0, true), p(2, 100, true), p(3, 200, true), p(4, 300, true),
                p(6, 900, true),
                p(7, 950, true), p(8, 1000, true), p(9, 1050, true), p(10, 1100, true));
        List<Segment> segs = Segmenter.segment("A", words);
        assertEquals(2, segs.size());
        assertFalse(segs.get(0).gapBefore());
        assertTrue(segs.get(1).gapBefore()); // 采集缺口
        assertEquals(10.0, segs.get(0).candidates().get(0).rateHz(), 0.01);
        assertEquals(20.0, segs.get(1).candidates().get(0).rateHz(), 0.01);
    }

    @Test
    void pureRateSwitchSplitsSegment() {
        List<WordPoint> words = List.of(
                p(1, 0, true), p(2, 100, true), p(3, 200, true),
                p(4, 250, true), p(5, 300, true));
        List<Segment> segs = Segmenter.segment("A", words);
        assertEquals(2, segs.size());
        assertEquals(10.0, segs.get(0).candidates().get(0).rateHz(), 0.01);
        assertEquals(20.0, segs.get(1).candidates().get(0).rateHz(), 0.01);
        assertFalse(segs.get(1).gapBefore()); // 速率切换不是采集缺口
    }

    @Test
    void badParityWordsExcludedFromIntervals() {
        List<WordPoint> words = List.of(
                p(1, 0, true), p(2, 100, false), p(3, 200, true), p(4, 300, true));
        List<Segment> segs = Segmenter.segment("A", words);
        assertEquals(1, segs.size()); // 坏奇偶字不制造伪间隔/伪缺口
        assertEquals(10.0, segs.get(0).candidates().get(0).rateHz(), 0.01);
    }

    @Test
    void ambiguousIntervalsKeepAllCandidates() {
        List<WordPoint> words = new ArrayList<>();
        words.add(p(1, 0, true));
        words.add(p(2, 100, true));
        words.add(p(3, 150, true)); // 间隔 100 与 50 并存
        List<Segment> segs = Segmenter.segment("A", words);
        // 无法唯一决定：分段或候选必须保留多种合法解释
        int candidates = segs.stream().mapToInt(s -> s.candidates().size()).sum();
        assertTrue(segs.size() > 1 || candidates > 1);
    }
}
