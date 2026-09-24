package avionics.arinc429;

import org.junit.jupiter.api.Test;
import java.sql.Connection;

class DebugTest {
    @Test
    void dump() throws Exception {
        Connection conn = Db.open(":memory:");
        Store store = new Store(conn);
        new CaseLoader(store).load();
        System.out.println("== candidates ==");
        for (Store.CandidateView cv : store.candidatesOf("br-baseline")) {
            System.out.println(cv.id() + " seg=" + cv.segmentId() + " rate=" + cv.rateHz() + " status=" + cv.status());
        }
        System.out.println("== segments ==");
        for (Store.SegmentView s : store.segmentsOf("br-baseline")) {
            System.out.println(s.id() + " ch=" + s.channel() + " seq=" + s.startSeq() + ".." + s.endSeq() + " gap=" + s.gapBefore());
        }
        System.out.println("== events ==");
        for (Store.Event e : store.events(null, null)) {
            System.out.println(e.id() + " " + e.type() + " " + e.payload());
        }
    }
}
