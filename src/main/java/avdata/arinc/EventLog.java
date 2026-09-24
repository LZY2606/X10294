package avdata.arinc;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Append-only event log with a SHA-256 hash chain, branches and optimistic concurrency.
 *
 *  Every event freezes the dependency basis ({@code depBasis}) it was computed under.
 *  Revisiting an old event restores that basis instead of recomputing with the newest
 *  dictionary/parameters. */
public final class EventLog {

    public static final String MAIN_BRANCH = "branch_main";

    private final Db db;

    public EventLog(Db db) {
        this.db = db;
    }

    public static final class Conflict extends RuntimeException {
        public final int expectedRev;
        public final int actualRev;
        public final String diff;

        public Conflict(int expectedRev, int actualRev, String diff) {
            super("version conflict: expected rev " + expectedRev + " but branch is at rev "
                    + actualRev);
            this.expectedRev = expectedRev;
            this.actualRev = actualRev;
            this.diff = diff;
        }
    }

    public void ensureMainBranch(String caseId, long now) {
        if (branchExists(MAIN_BRANCH)) {
            return;
        }
        createBranchRow(caseId, MAIN_BRANCH, "main", null, null, now, null, 1);
    }

    public boolean branchExists(String branchId) {
        try (PreparedStatement ps = db.conn()
                .prepareStatement("select 1 from branches where uid=?")) {
            ps.setString(1, branchId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private void createBranchRow(String caseId, String uid, String name, String parentBranch,
                                 String baseEvent, long now, String headEvent, int rev) {
        try (PreparedStatement ps = db.conn().prepareStatement(
                "insert into branches(uid,case_id,name,parent_branch,base_event,created_ms,"
                        + "head_event,rev) values(?,?,?,?,?,?,?,?)")) {
            ps.setString(1, uid);
            ps.setString(2, caseId);
            ps.setString(3, name);
            ps.setString(4, parentBranch);
            ps.setString(5, baseEvent);
            ps.setLong(6, now);
            ps.setString(7, headEvent);
            ps.setInt(8, rev);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new Repository.ImportException("branch create failed: " + e.getMessage());
        }
    }

    public static final class BranchInfo {
        public final String uid;
        public final String name;
        public final String parentBranch;
        public final String baseEvent;
        public final String headEvent;
        public final int rev;

        BranchInfo(String uid, String name, String parentBranch, String baseEvent,
                   String headEvent, int rev) {
            this.uid = uid;
            this.name = name;
            this.parentBranch = parentBranch;
            this.baseEvent = baseEvent;
            this.headEvent = headEvent;
            this.rev = rev;
        }
    }

    public BranchInfo branchInfo(String branchId) {
        try (PreparedStatement ps = db.conn().prepareStatement(
                "select uid,name,parent_branch,base_event,head_event,rev from branches where uid=?")) {
            ps.setString(1, branchId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return new BranchInfo(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getInt(6));
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public List<BranchInfo> listBranches(String caseId) {
        List<BranchInfo> out = new ArrayList<>();
        try (PreparedStatement ps = db.conn().prepareStatement(
                "select uid,name,parent_branch,base_event,head_event,rev from branches "
                        + "where case_id=? order by created_ms")) {
            ps.setString(1, caseId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new BranchInfo(rs.getString(1), rs.getString(2), rs.getString(3),
                            rs.getString(4), rs.getString(5), rs.getInt(6)));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return out;
    }

    public String createBranch(String caseId, String name, String parentBranch, String baseEvent,
                               long now) {
        String uid = StableId.of("branch", caseId, name,
                parentBranch == null ? "" : parentBranch, baseEvent == null ? "" : baseEvent,
                Long.toString(now));
        createBranchRow(caseId, uid, name, parentBranch, baseEvent, now, baseEvent, 1);
        return uid;
    }

    /** Append an event with optimistic concurrency control.
     *
     * @param expectedRev caller's observed branch rev; a mismatch from a concurrent save raises
     *                    {@link Conflict} carrying the two divergent heads. */
    public Records.Event append(String caseId, String branchId, String type, String summary,
                                Map<String, Object> payload, String depBasis, int expectedRev,
                                long now) {
        BranchInfo info = branchInfo(branchId);
        if (info == null) {
            throw new Repository.ImportException("unknown branch: " + branchId);
        }
        if (expectedRev != info.rev) {
            String diff = describeDiff(info, expectedRev, caseId);
            throw new Conflict(expectedRev, info.rev, diff);
        }
        String prevHash = info.headEvent == null
                ? "GENESIS"
                : event(info.headEvent).selfHash;
        int ord = countEventsOnBranch(branchId);
        String uid = StableId.of("event", caseId, branchId, type, Integer.toString(ord),
                Json.write(new TreeMap<>(payload)));
        String selfHash = StableId.hash(uid, type, Json.write(new TreeMap<>(payload)),
                prevHash, depBasis);
        Records.Event event = new Records.Event(uid, now, branchId, type, info.headEvent, summary,
                Map.copyOf(payload), prevHash, selfHash, depBasis);
        insertEvent(caseId, event, ord);
        updateHead(branchId, uid, info.rev + 1);
        return event;
    }

    private String describeDiff(BranchInfo info, int expectedRev, String caseId) {
        List<Records.Event> mine = eventsOnBranch(info.uid);
        int safe = Math.min(expectedRev, mine.size());
        StringBuilder sb = new StringBuilder();
        sb.append("your save was based on rev ").append(expectedRev)
                .append("; branch now contains events:\n");
        for (int i = Math.max(0, safe - 1); i < mine.size(); i++) {
            Records.Event e = mine.get(i);
            sb.append(" - ").append(e.uid).append(' ').append(e.type).append(": ")
                    .append(e.summary).append('\n');
        }
        return sb.toString();
    }

    private int countEventsOnBranch(String branchId) {
        try (PreparedStatement ps = db.conn()
                .prepareStatement("select count(*) from events where branch_id=?")) {
            ps.setString(1, branchId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private void insertEvent(String caseId, Records.Event e, int ord) {
        try (PreparedStatement ps = db.conn().prepareStatement(
                "insert into events(uid,case_id,branch_id,epoch_ms,type,parent_event,summary,"
                        + "payload_json,prev_hash,self_hash,dep_basis,ord) "
                        + "values(?,?,?,?,?,?,?,?,?,?,?,?)")) {
            int i = 0;
            ps.setString(++i, e.uid);
            ps.setString(++i, caseId);
            ps.setString(++i, e.branchId);
            ps.setLong(++i, e.epochMs);
            ps.setString(++i, e.type);
            ps.setString(++i, e.parentEventId);
            ps.setString(++i, e.summary);
            ps.setString(++i, Json.write(e.payload));
            ps.setString(++i, e.prevHash);
            ps.setString(++i, e.selfHash);
            ps.setString(++i, e.depBasis);
            ps.setInt(++i, ord);
            ps.executeUpdate();
        } catch (SQLException ex) {
            throw new Repository.ImportException("event append failed: " + ex.getMessage());
        }
    }

    private void updateHead(String branchId, String eventUid, int newRev) {
        try (PreparedStatement ps = db.conn()
                .prepareStatement("update branches set head_event=?, rev=? where uid=?")) {
            ps.setString(1, eventUid);
            ps.setInt(2, newRev);
            ps.setString(3, branchId);
            int n = ps.executeUpdate();
            if (n != 1) {
                throw new Repository.ImportException("branch head update affected " + n + " rows");
            }
        } catch (SQLException e) {
            throw new Repository.ImportException("branch head update failed: " + e.getMessage());
        }
    }

    public Records.Event event(String uid) {
        try (PreparedStatement ps = db.conn()
                .prepareStatement("select uid,epoch_ms,branch_id,type,parent_event,summary,"
                        + "payload_json,prev_hash,self_hash,dep_basis from events where uid=?")) {
            ps.setString(1, uid);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                int i = 0;
                String evUid = rs.getString(++i);
                long epoch = rs.getLong(++i);
                String branch = rs.getString(++i);
                String type = rs.getString(++i);
                String parent = rs.getString(++i);
                String summary = rs.getString(++i);
                Map<String, Object> payload = Json.read(rs.getString(++i));
                String prevHash = rs.getString(++i);
                String selfHash = rs.getString(++i);
                String depBasis = rs.getString(++i);
                return new Records.Event(evUid, epoch, branch, type, parent, summary, payload,
                        prevHash, selfHash, depBasis);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public List<Records.Event> eventsOnBranch(String branchId) {
        List<Records.Event> out = new ArrayList<>();
        try (PreparedStatement ps = db.conn()
                .prepareStatement("select uid from events where branch_id=? order by ord")) {
            ps.setString(1, branchId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(event(rs.getString(1)));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return out;
    }

    /** Walk a branch back to its base, including the parent branch prefix up to the base event. */
    public List<Records.Event> ancestry(String branchId) {
        List<Records.Event> prefix = new ArrayList<>();
        BranchInfo info = branchInfo(branchId);
        while (info != null && info.parentBranch != null) {
            for (Records.Event e : eventsOnBranch(info.parentBranch)) {
                prefix.add(e);
                if (e.uid.equals(info.baseEvent)) {
                    break;
                }
            }
            info = branchInfo(info.parentBranch);
        }
        if (info != null) {
            prefix.addAll(eventsOnBranch(info.uid));
        }
        // de-duplicate while preserving order
        List<Records.Event> out = new ArrayList<>();
        java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<>();
        for (Records.Event e : prefix) {
            if (seen.add(e.uid)) {
                out.add(e);
            }
        }
        return out;
    }

    /** Verify the hash chain of a branch (including inherited prefix). Returns the first broken
     *  event uid, or null when intact. */
    public String verifyChain(String branchId) {
        String prev = "GENESIS";
        for (Records.Event e : ancestry(branchId)) {
            String expect = StableId.hash(e.uid, e.type, Json.write(new TreeMap<>(e.payload)),
                    prev, e.depBasis);
            if (!expect.equals(e.selfHash)) {
                return e.uid;
            }
            prev = e.selfHash;
        }
        return null;
    }

    // ------------------------------------------------------------------
    // dependency basis & invalidation
    // ------------------------------------------------------------------

    public void setDepBasis(String caseId, String scope, String key, String version,
                            String sinceEvent) {
        try (PreparedStatement ps = db.conn().prepareStatement(
                "insert or replace into dep_basis(case_id,scope,key,version,since_event) "
                        + "values(?,?,?,?,?)")) {
            ps.setString(1, caseId);
            ps.setString(2, scope);
            ps.setString(3, key);
            ps.setString(4, version);
            ps.setString(5, sinceEvent);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new Repository.ImportException("dep basis write failed: " + e.getMessage());
        }
    }

    public Map<String, String> depBasis(String caseId, String scope) {
        Map<String, String> out = new TreeMap<>();
        try (PreparedStatement ps = db.conn().prepareStatement(
                "select key,version from dep_basis where case_id=? and scope=? order by key")) {
            ps.setString(1, caseId);
            ps.setString(2, scope);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.put(rs.getString(1), rs.getString(2));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return out;
    }
}
