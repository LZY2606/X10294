package avionics.arinc429;

import io.javalin.Javalin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/** 端到端浏览器可见流程：载入示例 -> 分支裁定 -> 制造冲突 -> 撤销 -> 导出/删除/再导入 -> 重开验证历史。 */
class EndToEndTest {

    private Javalin app;
    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.ALWAYS).build();

    @AfterEach
    void tearDown() {
        if (app != null) app.stop();
    }

    private String start(Path dir) throws Exception {
        Connection conn = Db.open(dir.resolve("test.db").toString());
        app = new Web(new Store(conn), dir.resolve("snapshot.json")).create();
        app.start("127.0.0.1", 0);
        return "http://127.0.0.1:" + app.port();
    }

    private HttpResponse<String> get(String url) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(url)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String url, String form) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(url))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(form)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void fullReviewFlow() throws Exception {
        Path dir = Files.createTempDirectory("arinc-e2e");
        String base = start(dir);

        // 1. 首页载入仓库内示例
        assertTrue(get(base + "/").body().contains("字路译码台"));
        HttpResponse<String> loaded = post(base + "/case/load", "");
        assertTrue(loaded.body().contains("imp-baseline"));
        assertTrue(loaded.body().contains("035"));
        assertTrue(loaded.body().contains("270"));   // 位序反转的八进制展示
        assertTrue(loaded.body().contains("BAD"));   // 奇偶错误
        assertTrue(loaded.body().contains("<svg"));  // 相邻字间隔图

        // 2. 字详情：位域 + 工程值 + 字典命中来源
        String word = get(base + "/imports/imp-baseline/words/w-imp-baseline-2?branch=br-baseline").body();
        assertTrue(word.contains("<svg"));
        assertTrue(word.contains("-12.340"));
        assertTrue(word.contains("de-035-v1"));
        String word4 = get(base + "/imports/imp-baseline/words/w-imp-baseline-4?branch=br-baseline").body();
        assertTrue(word4.contains("非法")); // BCD 非法数字诊断

        // 3. 分支页：速率候选，案例冲突已保留（br-baseline-A-s0-c0 accepted）
        String branch = get(base + "/branches/br-baseline").body();
        assertTrue(branch.contains("br-baseline-A-s0-c0"));
        assertTrue(branch.contains("accepted"));
        assertTrue(branch.contains("dict-v2") || branch.contains("dict-v1"));
        Matcher m = Pattern.compile("当前版本 <code>v(\\d+)</code>").matcher(branch);
        assertTrue(m.find());
        long version = Long.parseLong(m.group(1));

        // 4. 一次分支裁定：接受 20Hz 候选
        HttpResponse<String> decided = post(base + "/branches/br-baseline/decide",
                "targetType=rate-candidate&targetId=br-baseline-A-s1-c0&action=accept&baseVersion=" + version);
        assertTrue(get(base + "/branches/br-baseline").body().contains("br-baseline-A-s1-c0"));

        // 5. 制造冲突：用过期 baseVersion 再提交（模拟两个窗口同时保存）
        HttpResponse<String> conflict = http.send(
                HttpRequest.newBuilder(URI.create(base + "/branches/br-baseline/decide"))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "targetType=rate-candidate&targetId=br-baseline-A-s1-c0&action=reject&baseVersion="
                                        + version)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(409, conflict.statusCode());
        assertTrue(conflict.body().contains("版本冲突"));

        // 6. 撤销裁定，恢复前一分支状态
        String decId = "dec-br-baseline-" + (version + 1);
        post(base + "/branches/br-baseline/undo",
                "decisionId=" + decId + "&baseVersion=" + (version + 1));
        String afterUndo = get(base + "/branches/br-baseline").body();
        assertTrue(afterUndo.contains("已撤销"));

        // 7. 导出 -> 删除本地数据库 -> 再导入：身份、顺序、来源关系保持
        post(base + "/snapshot/save", "");
        post(base + "/db/reset", "");
        assertFalse(get(base + "/").body().contains("imp-baseline"));
        post(base + "/snapshot/load", "");
        String reloaded = get(base + "/imports/imp-baseline").body();
        assertTrue(reloaded.contains("w-imp-baseline-1"));
        assertTrue(reloaded.contains("270"));
        assertTrue(get(base + "/branches/br-baseline").body().contains("accepted"));

        // 8. 事件回看：恢复当时的依赖版本和可见结论
        String events = get(base + "/branches/br-baseline/events").body();
        Matcher ev = Pattern.compile("/events/(ev-\\d+)/view").matcher(events);
        assertTrue(ev.find());
        String view = get(base + "/events/" + ev.group(1) + "/view?branch=br-baseline").body();
        assertTrue(view.contains("当时"));

        // 9. 重启（同一数据库文件）后历史仍在
        app.stop();
        String base2 = start(dir);
        assertTrue(get(base2 + "/branches/br-baseline/events").body().contains("decision-conflict"));
        assertTrue(get(base2 + "/branches/br-baseline").body().contains("已撤销"));
    }
}
