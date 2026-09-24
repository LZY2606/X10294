package avionics.arinc429;

import io.javalin.Javalin;
import io.javalin.http.Context;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 复核界面：位域、相邻字间隔、速率候选、工程值换算和字典命中来源。 */
public class Web {

    private final Store store;
    private final CaseLoader caseLoader;
    private final Path snapshotPath;

    public Web(Store store, Path snapshotPath) {
        this.store = store;
        this.caseLoader = new CaseLoader(store);
        this.snapshotPath = snapshotPath;
    }

    public Javalin create() {
        Javalin app = Javalin.create(cfg -> cfg.showJavalinBanner = false);
        app.get("/", this::overview);
        app.post("/case/load", this::loadCase);
        app.get("/imports/{id}", this::importPage);
        app.get("/imports/{id}/words/{wordId}", this::wordPage);
        app.get("/branches/{id}", this::branchPage);
        app.post("/branches/{id}/decide", this::decide);
        app.post("/branches/{id}/undo", this::undo);
        app.post("/branches/{id}/reinterpret", this::reinterpret);
        app.get("/branches/{id}/events", this::eventsPage);
        app.get("/events/{id}/view", this::eventView);
        app.get("/export.json", this::exportJson);
        app.post("/snapshot/save", this::snapshotSave);
        app.post("/db/reset", this::dbReset);
        app.post("/snapshot/load", this::snapshotLoad);
        app.exception(Store.ConflictException.class, (e, ctx) ->
                ctx.status(409).html(conflictPage(e)));
        app.exception(Exception.class, (e, ctx) ->
                ctx.status(500).html(layout("错误", "<h1>错误</h1><pre>" + esc(e.toString()) + "</pre>")));
        return app;
    }

    // ---------------- 页面 ----------------

    private void overview(Context ctx) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("<h1>字路译码台：ARINC 429 标签、奇偶与速率分段证据</h1>");
        sb.append("<p>航电数据小组导入时间戳和三十二位原始字、通道配置与标签字典，"
                + "审阅字序、速率分段和工程值解释。</p>");
        sb.append("<form method='post' action='/case/load'><button>载入仓库内示例案例</button></form>");
        sb.append("<h2>导入批次</h2><ul>");
        for (Store.ImportRec imp : store.listImports()) {
            sb.append("<li><a href='/imports/").append(imp.id()).append("'>")
                    .append(esc(imp.id())).append("</a> ").append(esc(imp.name()))
                    .append("（字典 ").append(esc(imp.dictVersion())).append("）<ul>");
            for (Store.Branch b : store.branchesOf(imp.id())) {
                sb.append("<li>分支 <a href='/branches/").append(b.id()).append("'>")
                        .append(esc(b.name())).append("</a> [").append(esc(b.id()))
                        .append("] 字典=").append(esc(b.dictVersion()))
                        .append(" 版本=").append(b.version()).append("</li>");
            }
            sb.append("</ul></li>");
        }
        sb.append("</ul>");
        sb.append("<h2>档案</h2>");
        sb.append("<p><a href='/export.json'>导出全部（JSON，稳定身份）</a></p>");
        sb.append("<form method='post' action='/snapshot/save' style='display:inline'>"
                + "<button>导出到本地快照</button></form> ");
        sb.append("<form method='post' action='/db/reset' style='display:inline'>"
                + "<button>删除本地数据库内容</button></form> ");
        sb.append("<form method='post' action='/snapshot/load' style='display:inline'>"
                + "<button>从快照重新导入</button></form>");
        ctx.html(layout("总览", sb.toString()));
    }

    private void loadCase(Context ctx) throws Exception {
        String importId = caseLoader.load();
        ctx.redirect("/imports/" + importId);
    }

    private void importPage(Context ctx) throws Exception {
        String id = ctx.pathParam("id");
        Store.ImportRec imp = store.getImport(id);
        if (imp == null) { ctx.status(404).html(layout("未找到", "<h1>导入不存在</h1>")); return; }
        List<Store.Word> words = store.wordsOf(id);
        List<Store.Branch> branches = store.branchesOf(id);
        String branchId = branches.isEmpty() ? "" : branches.get(0).id();

        StringBuilder sb = new StringBuilder();
        sb.append("<h1>导入 ").append(esc(imp.id())).append("</h1>");
        sb.append("<p>").append(esc(imp.name())).append("</p>");
        sb.append("<p>字典版本：").append(esc(imp.dictVersion()))
                .append("；分支：");
        for (Store.Branch b : branches) {
            sb.append("<a href='/branches/").append(b.id()).append("'>").append(esc(b.name()))
                    .append("</a> ");
        }
        sb.append("</p>");
        sb.append("<h2>原位序列（来源层，不随解释改动）</h2>");
        sb.append("<table><tr><th>seq</th><th>接收时间(ms)</th><th>相邻间隔(ms)</th>"
                + "<th>通道</th><th>原始字</th><th>奇偶</th><th>label(八进制)</th><th>SDI</th>"
                + "<th>SSM</th><th>数据域</th><th>字典版本</th><th>位域</th></tr>");
        Long prevT = null;
        Map<String, Long> prevByCh = new LinkedHashMap<>();
        for (Store.Word w : words) {
            Long prev = prevByCh.get(w.channel());
            String interval = prev == null ? "-" : String.valueOf((w.rxNanos() - prev) / 1_000_000L);
            prevByCh.put(w.channel(), w.rxNanos());
            sb.append("<tr class='").append(w.parityOk() ? "ok" : "bad").append("'>")
                    .append("<td>").append(w.seq()).append("</td>")
                    .append("<td>").append(w.rxNanos() / 1_000_000L).append("</td>")
                    .append("<td>").append(interval).append("</td>")
                    .append("<td>").append(esc(w.channel())).append("</td>")
                    .append("<td><code>0x").append(String.format("%08X", w.raw())).append("</code></td>")
                    .append("<td>").append(w.parityOk() ? "OK" : "BAD").append("</td>")
                    .append("<td>").append(esc(w.labelText())).append("</td>")
                    .append("<td>").append(w.sdi()).append("</td>")
                    .append("<td>").append(w.ssm()).append(w.ssm() == 3 ? "(!)" : "").append("</td>")
                    .append("<td><code>0x").append(String.format("%05X", w.dataField())).append("</code></td>")
                    .append("<td>").append(esc(w.dictVersion())).append("</td>")
                    .append("<td><a href='/imports/").append(id).append("/words/").append(w.id())
                    .append("?branch=").append(branchId).append("'>位域/工程值</a></td></tr>");
        }
        sb.append("</table>");
        sb.append("<h2>相邻字间隔（按通道）</h2>");
        for (String ch : store.channelsOf(id)) {
            List<Store.Word> chWords = words.stream().filter(w -> w.channel().equals(ch)).toList();
            sb.append("<h3>通道 ").append(esc(ch)).append("</h3>").append(intervalSvg(chWords));
        }
        ctx.html(layout("导入 " + id, sb.toString()));
    }

    private void wordPage(Context ctx) throws Exception {
        String wordId = ctx.pathParam("wordId");
        String branchId = ctx.queryParam("branch");
        Store.Word w = store.word(wordId);
        if (w == null) { ctx.status(404).html(layout("未找到", "<h1>字不存在</h1>")); return; }
        if (branchId == null || branchId.isEmpty()) {
            List<Store.Branch> bs = store.branchesOf(w.importId());
            branchId = bs.isEmpty() ? "" : bs.get(0).id();
        }
        StringBuilder sb = new StringBuilder();
        sb.append("<h1>字 ").append(esc(w.id())).append("（seq ").append(w.seq()).append("）</h1>");
        sb.append("<p>稳定身份：<code>").append(esc(w.id())).append("</code>；来源：导入 ")
                .append(esc(w.importId())).append("，接收时间 ").append(w.rxNanos() / 1_000_000L)
                .append(" ms，通道 ").append(esc(w.channel())).append("</p>");
        sb.append("<h2>位域（32 位原始字，原位保留）</h2>").append(bitfieldSvg(w.raw()));
        sb.append("<p><span class='lg p'>奇偶</span> <span class='lg s'>SSM</span> "
                + "<span class='lg d'>数据域</span> <span class='lg i'>SDI</span> "
                + "<span class='lg l'>label</span></p>");
        sb.append("<table><tr><th>域</th><th>值</th></tr>")
                .append("<tr><td>原始字</td><td><code>0x").append(String.format("%08X", w.raw()))
                .append("</code></td></tr>")
                .append("<tr><td>奇偶结果</td><td>").append(w.parityOk() ? "OK" : "BAD（不进入聚合值）")
                .append("</td></tr>")
                .append("<tr><td>label（八进制展示）</td><td>").append(esc(w.labelText()))
                .append("（八进制展示不改变底层位）</td></tr>")
                .append("<tr><td>SDI</td><td>").append(w.sdi()).append("</td></tr>")
                .append("<tr><td>SSM</td><td>").append(w.ssm())
                .append(w.ssm() == 3 ? "（无效/保留状态）" : "").append("</td></tr>")
                .append("<tr><td>数据域</td><td><code>0x")
                .append(String.format("%05X", w.dataField())).append("</code></td></tr></table>");
        if (!branchId.isEmpty()) {
            Store.EngValue ev = store.engValueFor(branchId, w.id());
            if (ev != null) {
                sb.append("<h2>工程值换算（分支 ").append(esc(branchId)).append("）</h2>");
                sb.append("<table><tr><th>项</th><th>值</th></tr>")
                        .append("<tr><td>编码</td><td>").append(esc(ev.encoding())).append("</td></tr>")
                        .append("<tr><td>工程值</td><td>").append(esc(ev.valueText())).append(" ")
                        .append(esc(ev.unit())).append("</td></tr>")
                        .append("<tr><td>坐标基准</td><td>").append(esc(ev.frame())).append("</td></tr>")
                        .append("<tr><td>诊断</td><td>")
                        .append(ev.diagnostic() == null ? "-" : esc(ev.diagnostic())).append("</td></tr>")
                        .append("<tr><td>字典命中来源</td><td>字典 ").append(esc(ev.dictVersion()))
                        .append(" / 条目 ").append(esc(ev.dictEntryId())).append("</td></tr>")
                        .append("<tr><td>有效</td><td>").append(ev.valid() ? "是" : "否（依赖已失效）")
                        .append("</td></tr></table>");
            }
        }
        sb.append("<p><a href='/imports/").append(w.importId()).append("'>返回导入</a></p>");
        ctx.html(layout("字 " + w.id(), sb.toString()));
    }

    private void branchPage(Context ctx) throws Exception {
        String id = ctx.pathParam("id");
        Store.Branch b = store.getBranch(id);
        if (b == null) { ctx.status(404).html(layout("未找到", "<h1>分支不存在</h1>")); return; }
        StringBuilder sb = new StringBuilder();
        sb.append("<h1>分支 ").append(esc(b.name())).append(" [").append(esc(b.id())).append("]</h1>");
        sb.append("<p>导入 <a href='/imports/").append(b.importId()).append("'>")
                .append(esc(b.importId())).append("</a>；字典版本 ").append(esc(b.dictVersion()))
                .append("；当前版本 <code>v").append(b.version()).append("</code>；")
                .append("<a href='/branches/").append(id).append("/events'>事件日志</a></p>");

        sb.append("<h2>速率分段候选（速率变化与采集缺口保留分段候选）</h2>");
        List<Store.CandidateView> cands = store.candidatesOf(id);
        for (Store.SegmentView seg : store.segmentsOf(id)) {
            sb.append("<div class='seg'><h3>分段 ").append(esc(seg.id()))
                    .append("（通道 ").append(esc(seg.channel())).append(" seq ")
                    .append(seg.startSeq()).append("..").append(seg.endSeq()).append("）")
                    .append(seg.gapBefore() ? " <span class='gap'>前有采集缺口</span>" : "")
                    .append(" 状态=").append(esc(seg.status())).append("</h3><table>")
                    .append("<tr><th>候选</th><th>速率(Hz)</th><th>间隔(ms)</th><th>票数</th>")
                    .append("<th>状态</th><th>裁定</th></tr>");
            for (Store.CandidateView cv : cands) {
                if (!cv.segmentId().equals(seg.id())) continue;
                sb.append("<tr><td><code>").append(esc(cv.id())).append("</code></td><td>")
                        .append(String.format(Locale.ROOT, "%.2f", cv.rateHz())).append("</td><td>")
                        .append(String.format(Locale.ROOT, "%.0f", cv.intervalMs())).append("</td><td>")
                        .append(cv.votes()).append("</td><td class='st-").append(esc(cv.status()))
                        .append("'>").append(esc(cv.status())).append("</td><td>")
                        .append(decideForm(id, "rate-candidate", cv.id(), "accept", b.version()))
                        .append(decideForm(id, "rate-candidate", cv.id(), "reject", b.version()))
                        .append("</td></tr>");
            }
            sb.append("</table></div>");
        }

        sb.append("<h2>工程值换算（解释由字典版本决定）</h2>");
        sb.append("<table><tr><th>字</th><th>编码</th><th>工程值</th><th>单位</th><th>坐标基准</th>")
                .append("<th>诊断</th><th>字典命中来源</th><th>有效</th></tr>");
        for (Store.EngValue ev : store.engValuesOf(id)) {
            sb.append("<tr class='").append(ev.valid() ? "ok" : "stale").append("'><td><code>")
                    .append(esc(ev.wordId())).append("</code></td><td>").append(esc(ev.encoding()))
                    .append("</td><td>").append(esc(ev.valueText())).append("</td><td>")
                    .append(esc(ev.unit())).append("</td><td>").append(esc(ev.frame())).append("</td><td>")
                    .append(ev.diagnostic() == null ? "-" : esc(ev.diagnostic())).append("</td><td>")
                    .append(esc(ev.dictVersion())).append("/").append(esc(ev.dictEntryId()))
                    .append("</td><td>").append(ev.valid() ? "是" : "否").append("</td></tr>");
        }
        sb.append("</table>");

        sb.append("<h2>人工裁定（可撤销，撤销恢复前一分支状态）</h2><ul>");
        for (Store.DecisionRecord d : store.decisionsOf(id)) {
            sb.append("<li><code>").append(esc(d.id())).append("</code> ").append(esc(d.action()))
                    .append(" ").append(esc(d.targetId())).append(" @base=v").append(d.baseVersion())
                    .append(" 状态=").append(esc(d.status())).append(d.undone() ? "（已撤销）" : "");
            if (!d.undone()) {
                sb.append(" <form method='post' action='/branches/").append(id)
                        .append("/undo' style='display:inline'>")
                        .append("<input type='hidden' name='decisionId' value='").append(esc(d.id())).append("'>")
                        .append("<input type='hidden' name='baseVersion' value='").append(b.version()).append("'>")
                        .append("<button>撤销</button></form>");
            }
            sb.append("</li>");
        }
        sb.append("</ul>");

        sb.append("<h2>重新解释（新建分支，旧分支保留）</h2>")
                .append("<form method='post' action='/branches/").append(id).append("/reinterpret'>")
                .append("分支名 <input name='name' value='重解释'> 字典版本 ")
                .append("<select name='dictVersion'><option>dict-v1</option><option>dict-v2</option></select>")
                .append("<button>新建分支</button></form>");
        ctx.html(layout("分支 " + b.name(), sb.toString()));
    }

    private String decideForm(String branchId, String targetType, String targetId, String action,
                              long version) {
        return "<form method='post' action='/branches/" + esc(branchId) + "/decide' "
                + "style='display:inline'><input type='hidden' name='targetType' value='"
                + esc(targetType) + "'><input type='hidden' name='targetId' value='" + esc(targetId)
                + "'><input type='hidden' name='action' value='" + esc(action)
                + "'><input type='hidden' name='baseVersion' value='" + version + "'>"
                + "<button>" + ("accept".equals(action) ? "接受" : "否定") + "</button></form> ";
    }

    private void decide(Context ctx) throws Exception {
        String branchId = ctx.pathParam("id");
        store.decide(branchId, ctx.formParam("targetType"), ctx.formParam("targetId"),
                ctx.formParam("action"),
                ctx.formParam("payload") == null ? "{}" : ctx.formParam("payload"),
                Long.parseLong(ctx.formParam("baseVersion")));
        ctx.redirect("/branches/" + branchId);
    }

    private void undo(Context ctx) throws Exception {
        String branchId = ctx.pathParam("id");
        store.undo(branchId, ctx.formParam("decisionId"),
                Long.parseLong(ctx.formParam("baseVersion")));
        ctx.redirect("/branches/" + branchId);
    }

    private void reinterpret(Context ctx) throws Exception {
        String branchId = ctx.pathParam("id");
        Store.Branch b = store.getBranch(branchId);
        String newId = "br-" + ctx.formParam("name") + "-" + System.currentTimeMillis() % 100000;
        store.createBranch(newId, b.importId(), ctx.formParam("name"), branchId,
                ctx.formParam("dictVersion"));
        ctx.redirect("/branches/" + newId);
    }

    private void eventsPage(Context ctx) throws Exception {
        String branchId = ctx.pathParam("id");
        StringBuilder sb = new StringBuilder();
        sb.append("<h1>事件日志（分支 ").append(esc(branchId)).append("）</h1>");
        sb.append("<p>回到任意旧事件可恢复当时的依赖版本和可见结论。</p>");
        sb.append("<table><tr><th>seq</th><th>id</th><th>时间</th><th>类型</th><th>内容</th><th>回看</th></tr>");
        for (Store.Event e : store.events(branchId, null)) {
            sb.append("<tr><td>").append(e.seq()).append("</td><td><code>").append(esc(e.id()))
                    .append("</code></td><td>").append(esc(e.ts())).append("</td><td>")
                    .append(esc(e.type())).append("</td><td><code class='small'>")
                    .append(esc(e.payload())).append("</code></td><td><a href='/events/")
                    .append(esc(e.id())).append("/view?branch=").append(esc(branchId))
                    .append("'>恢复视角</a></td></tr>");
        }
        sb.append("</table>");
        ctx.html(layout("事件日志", sb.toString()));
    }

    /** 历史视角：重放到该事件为止的候选状态与依赖版本，不用最新结果覆盖历史。 */
    private void eventView(Context ctx) throws Exception {
        String eventId = ctx.pathParam("id");
        String branchId = ctx.queryParam("branch");
        Store.Event e = store.event(eventId);
        if (e == null) { ctx.status(404).html(layout("未找到", "<h1>事件不存在</h1>")); return; }
        if (branchId == null || branchId.isEmpty()) branchId = e.branchId();
        StringBuilder sb = new StringBuilder();
        sb.append("<h1>事件 ").append(esc(e.id())).append(" 时点的可见结论</h1>");
        sb.append("<p>类型 ").append(esc(e.type())).append("；时间 ").append(esc(e.ts()))
                .append("；内容 <code class='small'>").append(esc(e.payload())).append("</code></p>");
        if (branchId != null && !branchId.isEmpty()) {
            Store.Branch b = store.getBranch(branchId);
            sb.append("<p>分支 ").append(esc(branchId)).append(" 当时依赖字典版本：")
                    .append(esc(b.dictVersion())).append("</p>");
            Map<String, String> asOf = store.candidateStatusAsOf(branchId, e.seq());
            sb.append("<h2>当时速率候选状态</h2><table><tr><th>候选</th><th>状态（截至该事件）</th></tr>");
            for (Map.Entry<String, String> en : asOf.entrySet()) {
                sb.append("<tr><td><code>").append(esc(en.getKey())).append("</code></td><td>")
                        .append(esc(en.getValue())).append("</td></tr>");
            }
            sb.append("</table>");
            sb.append("<p><a href='/branches/").append(esc(branchId)).append("'>返回分支当前状态</a></p>");
        }
        ctx.html(layout("事件 " + e.id(), sb.toString()));
    }

    // ---------------- 档案 ----------------

    private void exportJson(Context ctx) throws Exception {
        ctx.contentType("application/json; charset=utf-8")
                .header("Content-Disposition", "attachment; filename=arinc429-export.json")
                .result(store.exportAll().toPrettyString());
    }

    private void snapshotSave(Context ctx) throws Exception {
        Files.createDirectories(snapshotPath.getParent());
        Files.writeString(snapshotPath, store.exportAll().toPrettyString());
        ctx.redirect("/");
    }

    private void dbReset(Context ctx) throws Exception {
        store.resetAll();
        ctx.redirect("/");
    }

    private void snapshotLoad(Context ctx) throws Exception {
        String text = Files.readString(snapshotPath);
        store.importAll((com.fasterxml.jackson.databind.node.ObjectNode)
                new com.fasterxml.jackson.databind.ObjectMapper().readTree(text));
        ctx.redirect("/");
    }

    private String conflictPage(Store.ConflictException e) {
        return layout("版本冲突", "<h1>版本冲突（后提交者）</h1>"
                + "<p>你基于版本 v" + e.expectedVersion + " 提交，但当前已是 v" + e.actualVersion
                + "。双方差异如下，原数据未被改写：</p><pre>" + esc(e.currentState) + "</pre>"
                + "<p><a href='javascript:history.back()'>返回重新提交</a></p>");
    }

    // ---------------- SVG ----------------

    static String bitfieldSvg(long raw) {
        StringBuilder sb = new StringBuilder();
        int cell = 26;
        sb.append("<svg width='").append(32 * cell + 10).append("' height='64' ")
                .append("xmlns='http://www.w3.org/2000/svg' role='img' aria-label='bitfield'>");
        for (int i = 31; i >= 0; i--) {
            int x = (31 - i) * cell + 5;
            int bit = (int) ((raw >>> i) & 1L);
            String cls = i == 31 ? "p" : i >= 29 ? "s" : i >= 10 ? "d" : i >= 8 ? "i" : "l";
            sb.append("<rect x='").append(x).append("' y='8' width='").append(cell - 2)
                    .append("' height='30' class='bf ").append(cls).append("'/>");
            sb.append("<text x='").append(x + cell / 2 - 1).append("' y='28' class='bt'>")
                    .append(bit).append("</text>");
            if (i % 4 == 0 || i == 31) {
                sb.append("<text x='").append(x + cell / 2 - 1).append("' y='56' class='bn'>")
                        .append(i + 1).append("</text>");
            }
        }
        sb.append("</svg>");
        return sb.toString();
    }

    static String intervalSvg(List<Store.Word> words) {
        List<Long> intervals = new ArrayList<>();
        for (int i = 1; i < words.size(); i++) {
            intervals.add((words.get(i).rxNanos() - words.get(i - 1).rxNanos()) / 1_000_000L);
        }
        if (intervals.isEmpty()) return "<p>（单字，无间隔）</p>";
        long max = intervals.stream().mapToLong(Long::longValue).max().orElse(1);
        StringBuilder sb = new StringBuilder();
        int barW = 34;
        int h = 120;
        sb.append("<svg width='").append(intervals.size() * barW + 60).append("' height='")
                .append(h + 30).append("' xmlns='http://www.w3.org/2000/svg' role='img' ")
                .append("aria-label='intervals'>");
        for (int i = 0; i < intervals.size(); i++) {
            long v = intervals.get(i);
            int bh = (int) Math.max(2, v * (h - 20) / Math.max(1, max));
            int x = 30 + i * barW;
            String cls = v > 300 ? "gapbar" : "bar";
            sb.append("<rect x='").append(x).append("' y='").append(h - bh)
                    .append("' width='").append(barW - 8).append("' height='").append(bh)
                    .append("' class='").append(cls).append("'/>");
            sb.append("<text x='").append(x).append("' y='").append(h + 14).append("' class='bn'>")
                    .append(v).append("ms</text>");
        }
        sb.append("</svg>");
        return sb.toString();
    }

    // ---------------- 工具 ----------------

    static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    static String layout(String title, String body) {
        return """
                <!doctype html><html lang='zh'><head><meta charset='utf-8'>
                <title>%s</title><style>
                body{font-family:system-ui,sans-serif;margin:2rem;max-width:1100px}
                table{border-collapse:collapse;margin:0.5rem 0}
                th,td{border:1px solid #ccc;padding:4px 8px;font-size:14px}
                tr.bad td{background:#ffe4e4} tr.stale td{background:#f0f0f0;color:#777}
                code{background:#f5f5f5;padding:1px 4px} code.small{font-size:11px}
                .bf{stroke:#333;stroke-width:1} .p{fill:#bbb} .s{fill:#c9a7eb}
                .d{fill:#9ec9ff} .i{fill:#a8e6a3} .l{fill:#ffcf99}
                .bt{font-size:13px;text-anchor:middle} .bn{font-size:10px;fill:#555}
                .lg{display:inline-block;width:70px;padding:2px 6px;border:1px solid #333}
                .bar{fill:#9ec9ff} .gapbar{fill:#ff9c9c}
                .gap{color:#c00;font-weight:bold} .seg{margin-bottom:1rem}
                .st-accepted{color:#080;font-weight:bold} .st-rejected{color:#c00}
                button{margin:2px}
                </style></head><body>%s<hr><p><a href='/'>返回总览</a></p></body></html>
                """.formatted(esc(title), body);
    }
}
