package avdata.arinc;

import io.javalin.Javalin;
import io.javalin.http.Context;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Local review UI + JSON API. Views never mutate source data; every action appends an event. */
public final class WebServer {

    private final AppService app;

    public WebServer(AppService app) {
        this.app = app;
    }

    public Javalin start(String host, int port) {
        Javalin javalin = Javalin.create(cfg -> cfg.staticFiles.enableWebjars())
                .get("/", ctx -> index(ctx))
                .get("/api/example", this::loadExample)
                .get("/api/state", this::state)
                .get("/api/branches", this::listBranches)
                .post("/api/branches", this::createBranch)
                .post("/api/ruling", this::ruling)
                .post("/api/ruling/revoke", this::revoke)
                .post("/api/params", this::params)
                .post("/api/dictionary", this::dictionary)
                .post("/api/source-patch", this::sourcePatch)
                .get("/api/provenance", this::provenance)
                .get("/api/export", this::exportState)
                .get("/api/expectations", this::expectations);
        javalin.exception(AppService.ActionException.class, (e, ctx) -> {
            ctx.status(409);
            ctx.json(Map.of("error", true, "code", e.code, "message", e.getMessage()));
        });
        javalin.exception(EventLog.Conflict.class, (e, ctx) -> {
            ctx.status(409);
            ctx.json(Map.of("error", true, "code", "CONFLICT", "expectedRev", e.expectedRev,
                    "actualRev", e.actualRev, "diff", e.diff));
        });
        javalin.exception(Repository.ImportException.class, (e, ctx) -> {
            ctx.status(400);
            ctx.json(Map.of("error", true, "code", "IMPORT_FAILED", "message", e.getMessage()));
        });
        javalin.start(host, port);
        return javalin;
    }

    private void index(Context ctx) throws Exception {
        String html = new String(WebServer.class.getResourceAsStream("/web/index.html")
                .readAllBytes(), StandardCharsets.UTF_8);
        ctx.html(html);
    }

    private void loadExample(Context ctx) {
        ctx.json(app.loadExample());
    }

    private void state(Context ctx) {
        ensureLoaded();
        String branch = ctx.queryParam("branch");
        String atEvent = ctx.queryParam("atEvent");
        DerivedEngine.Snapshot s = app.snapshot(branch, atEvent);
        Map<String, Object> out = new LinkedHashMap<>();
        EventLog.BranchInfo info = app.eventLog().branchInfo(s.branchId);
        out.put("caseId", s.caseId);
        out.put("branchId", s.branchId);
        out.put("rev", info.rev);
        out.put("atEventUid", s.atEventUid);
        out.put("depBasis", s.depBasis);
        out.put("dictionaryVersion", s.activeDictionaryVersion);
        out.put("params", Map.of("version", s.params.version, "highScheduleMs",
                s.params.highScheduleMs, "lowScheduleMs", s.params.lowScheduleMs,
                "sinceEvent", s.params.sinceEvent));
        out.put("words", s.words);
        out.put("channels", s.channels);
        out.put("aggregates", s.aggregates);
        out.put("decisions", s.decisions);
        out.put("events", s.events);
        out.put("invalidated", s.invalidated);
        ctx.json(out);
    }

    private void listBranches(Context ctx) {
        ensureLoaded();
        ctx.json(app.branches());
    }

    private void createBranch(Context ctx) {
        Map<String, String> body = body(ctx);
        long now = System.currentTimeMillis();
        ctx.json(app.createBranch(body.getOrDefault("name", "review-branch"),
                body.get("baseEvent"), now));
    }

    private void ruling(Context ctx) {
        Map<String, String> b = body(ctx);
        ctx.json(app.ruling(b.getOrDefault("branch", EventLog.MAIN_BRANCH),
                b.get("targetUid"), b.get("candidate"), Boolean.parseBoolean(b.get("accepted")),
                b.get("note"), rev(b), System.currentTimeMillis()));
    }

    private void revoke(Context ctx) {
        Map<String, String> b = body(ctx);
        ctx.json(app.revoke(b.getOrDefault("branch", EventLog.MAIN_BRANCH), b.get("eventUid"),
                rev(b), System.currentTimeMillis()));
    }

    private void params(Context ctx) {
        Map<String, String> b = body(ctx);
        ctx.json(app.reviseParams(b.getOrDefault("branch", EventLog.MAIN_BRANCH),
                b.getOrDefault("paramsVersion", "2"),
                Double.parseDouble(b.getOrDefault("highScheduleMs", "10")),
                Double.parseDouble(b.getOrDefault("lowScheduleMs", "80")),
                rev(b), System.currentTimeMillis()));
    }

    private void dictionary(Context ctx) {
        Map<String, String> b = body(ctx);
        ctx.json(app.reviseDictionary(b.getOrDefault("branch", EventLog.MAIN_BRANCH),
                b.get("version"), rev(b), System.currentTimeMillis()));
    }

    private void sourcePatch(Context ctx) {
        Map<String, String> b = body(ctx);
        ctx.json(app.sourcePatch(b.getOrDefault("branch", EventLog.MAIN_BRANCH),
                Integer.parseInt(b.get("seq")), b.get("rawHex"), rev(b),
                System.currentTimeMillis()));
    }

    private void provenance(Context ctx) {
        ensureLoaded();
        ctx.json(app.provenance(ctx.queryParam("objectUid"), ctx.queryParam("branch"),
                ctx.queryParam("atEvent")));
    }

    private void exportState(Context ctx) {
        ensureLoaded();
        ctx.json(app.exportState());
    }

    private void expectations(Context ctx) {
        ensureLoaded();
        Map<String, Object> pack = Json.read(AppService.bundledCasePackJson());
        ctx.json(Map.of("script", pack.getOrDefault("script", List.of()),
                "expectations", pack.getOrDefault("expectations", List.of())));
    }

    private void ensureLoaded() {
        if (!app.isSeeded()) {
            app.loadExample();
        }
    }

    private static int rev(Map<String, String> body) {
        try {
            return Integer.parseInt(body.getOrDefault("expectedRev", "1"));
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> body(Context ctx) {
        String text = ctx.body();
        if (text == null || text.isBlank()) {
            return Map.of();
        }
        return Json.MAPPER.convertValue(Json.read(text), Map.class);
    }
}
