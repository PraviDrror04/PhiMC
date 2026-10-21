import dev.dsh.phimc.client.PhiraApi;
import dev.dsh.phimc.client.PhiraBrowser;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Phira 在线谱面库「连续搜索卡死」的 headless 复现 + 回归自测（纯 Java，不需要 MC / 图形界面）。
 *
 * <p>走的是界面真正走的那条路：{@link PhiraBrowser#search} → {@link PhiraApi#runAsync}
 * → {@link PhiraApi#search}，然后像渲染线程那样每 50ms 读一次 {@code loading()} /
 * {@code error()} / {@code charts()} 快照。真网络（不打桩）。
 *
 * <p>用法（gson + slf4j-api 在 classpath 上）：
 * <pre>
 * javac -encoding UTF-8 -cp "&lt;gson&gt;;&lt;slf4j&gt;" -d &lt;out&gt; PhiraApi.java PhiraChart.java PhiraBrowser.java NetStressCheck.java
 * java -cp "&lt;out&gt;;&lt;gson&gt;;&lt;slf4j&gt;" NetStressCheck           # 全部 5 段
 * java -cp "&lt;out&gt;;&lt;gson&gt;;&lt;slf4j&gt;" NetStressCheck 10 20000    # 10 次搜索，单次看门狗 20s
 * java -cp "&lt;out&gt;;&lt;gson&gt;;&lt;slf4j&gt;" NetStressCheck stale       # 只跑「连点 6 次」（复现卡死最快）
 * </pre>
 *
 * <p>五段：0 单域名探测 / 1 连续 10 次搜索 / 2 60 秒缓存 + 仅上架开关 / 3 连点丢旧结果 /
 * 4 失败路径（超时、HTTP 错误、恢复）/ 5 线程池（满、恢复）。
 *
 * <p><b>修前跑</b>（baseline）：第 1 次要 19.4s，10 次里 8 次超过 5s、总计 110s；
 * 「连点 6 次」直接 {@code loading=true} 收不回来（主域名 {@code api.phira.cn} 慢 + 单线程池 +
 * 旧结果被序号丢掉，队列越排越长）。
 * <b>修后跑</b>：10 次全部 &lt; 5s（多数 ~50ms），失败路径都有中文原因、状态能收回来。
 */
public final class NetStressCheck {

    private static final long POLL_MS = 50;
    private static int failed;

    public static void main(String[] args) throws Exception {
        // 单独跑「连点」这一段（复现「第二次起卡死」最快的一条路）
        if (args.length > 0 && "stale".equals(args[0])) {
            section3StaleResults();
            System.out.println(failed == 0 ? "通过 ✓" : "失败 ✗");
            System.exit(failed == 0 ? 0 : 1);
        }
        int runs = args.length > 0 ? Integer.parseInt(args[0]) : 10;
        long stuckMs = args.length > 1 ? Long.parseLong(args[1]) : 60_000L;

        section0DomainProbe();
        section1TenSearches(runs, stuckMs);
        section2CacheAndStableFilter();
        section3StaleResults();
        section4FailurePaths();
        section5PoolHealth();

        System.out.println();
        if (failed == 0) {
            System.out.println("全部通过 ✓");
        } else {
            System.out.println("有 " + failed + " 项失败 ✗");
            System.exit(1);
        }
        System.exit(0);
    }

    // ------------------------------------------------------------------
    // 0. 单域名探测
    // ------------------------------------------------------------------
    private static void section0DomainProbe() throws Exception {
        System.out.println("== 0. 单域名探测（用 mod 的超时口径，各打 2 次）==");
        for (int round = 1; round <= 2; round++) {
            for (String base : PhiraApi.BASES) {
                long t = System.nanoTime();
                String verdict;
                try {
                    String body = rawGet(base + "/chart?search=&page=1&pageNum=1&order=-rating&division=regular");
                    verdict = "OK   " + cut(body, 60);
                } catch (Throwable e) {
                    verdict = "FAIL " + e.getClass().getSimpleName() + ": " + e.getMessage();
                }
                System.out.printf("  round%d %-26s %6dms  %s%n", round, base,
                        (System.nanoTime() - t) / 1_000_000L, verdict);
            }
        }
    }

    // ------------------------------------------------------------------
    // 1. 连续 N 次搜索（用户报的那个操作）
    // ------------------------------------------------------------------
    private static void section1TenSearches(int runs, long stuckMs) throws Exception {
        String[] keys = {"", "R.I.P.", "eicateve", "Aoharu", "IN", "Kyouki", "piano", "shadow", "Lyrith", "Sekai"};
        System.out.println();
        System.out.println("== 1. 连续 " + runs + " 次搜索（界面路径，关键词每次都不同）==");
        System.out.println("  #  关键词        开始ms   结束ms    用时  终态     总条数  本页  错误");
        PhiraBrowser browser = new PhiraBrowser();
        PhiraApi.clearCache();
        long t0 = System.nanoTime();
        int stuck = 0;
        int slow = 0;
        List<String> bad = new ArrayList<>();
        for (int i = 0; i < runs; i++) {
            String key = keys[i % keys.length] + (i >= keys.length ? " #" + (i / keys.length + 1) : "");
            long start = ms(t0);
            browser.search(key, PhiraBrowser.ORDER_RATING, 1, 20);
            while (browser.loading() && ms(t0) - start < stuckMs) Thread.sleep(POLL_MS);
            long end = ms(t0);
            long took = end - start;
            String state;
            if (browser.loading()) {
                state = "STUCK";
                stuck++;
                bad.add("#" + (i + 1) + " 卡死(>" + stuckMs + "ms)");
            } else if (!browser.error().isEmpty()) {
                state = "FAILED";
                bad.add("#" + (i + 1) + " 失败：" + browser.error());
            } else {
                state = "OK";
            }
            if (!"STUCK".equals(state) && took > 5_000) {
                slow++;
                bad.add("#" + (i + 1) + " 用时 " + took + "ms");
            }
            System.out.printf("  %-2d %-12s %7d %8d %6dms  %-7s %6d %4d  %s%n", i + 1,
                    key.isEmpty() ? "(空)" : key, start, end, took, state,
                    browser.total(), browser.charts().size(), browser.error());
            Thread.sleep(150);
        }
        System.out.printf("  小计：卡死 %d 次，超过 5s %d 次，总计 %dms%n", stuck, slow, ms(t0));
        check(stuck == 0 && slow == 0, "10 次搜索每次 < 5s 且都能收尾" + (bad.isEmpty() ? "" : "（" + bad + "）"));
    }

    // ------------------------------------------------------------------
    // 2. 60 秒缓存 + 「仅上架」开关（缓存键里带 onlyStable，不能串）
    // ------------------------------------------------------------------
    private static void section2CacheAndStableFilter() throws Exception {
        System.out.println();
        System.out.println("== 2. 60 秒缓存 与「仅上架」开关 ==");
        PhiraBrowser b = new PhiraBrowser();
        PhiraApi.clearCache();
        runOnce(b, "", 1, true);
        int stableCount = b.total();
        long second = runOnce(b, "", 1, true);
        System.out.printf("  仅上架=开：count=%d，第二次（命中缓存）%dms%n", stableCount, second);
        check(second < 100, "同一搜索词第二次命中缓存（" + second + "ms，不走网络）");

        PhiraBrowser.setOnlyStable(false);
        long offMs = runOnce(b, "", 1, true);
        int allCount = b.total();
        System.out.printf("  仅上架=关：count=%d（%dms）%n", allCount, offMs);
        check(allCount > stableCount, "关掉「仅上架」后总数变大：" + allCount + " > " + stableCount);

        PhiraBrowser.setOnlyStable(true);
        long backMs = runOnce(b, "", 1, true);
        System.out.printf("  切回仅上架：count=%d（%dms，命中缓存）%n", b.total(), backMs);
        check(b.total() == stableCount, "切回来命中「开」的那条缓存，没有串到「关」的 key");
    }

    // ------------------------------------------------------------------
    // 3. 连点：旧请求的结果必须被序号丢掉
    // ------------------------------------------------------------------
    private static void section3StaleResults() throws Exception {
        System.out.println();
        System.out.println("== 3. 连点 6 次（每 40ms 一次，最后那次必须赢）==");
        PhiraBrowser b = new PhiraBrowser();
        PhiraApi.clearCache();
        String[] keys = {"a", "ab", "abc", "abcd", "abcde", "final"};
        for (String k : keys) {
            b.search(k, PhiraBrowser.ORDER_RATING, 1, 20);
            Thread.sleep(40);
        }
        long t0 = System.nanoTime();
        while (b.loading() && ms(t0) < 15_000) Thread.sleep(POLL_MS);
        Thread.sleep(500);                                   // 给「慢的旧结果」时间回来（回来也必须被丢）
        System.out.printf("  终态：query=%s loading=%s total=%d err=%s%n",
                b.query(), b.loading(), b.total(), b.error());
        check(!b.loading(), "连点之后 loading 收得回来");
        check("final".equals(b.query()), "界面停在最后一次的搜索词（旧结果没把界面刷回去）");
    }

    // ------------------------------------------------------------------
    // 4. 失败路径：超时 / HTTP 错误
    // ------------------------------------------------------------------
    private static void section4FailurePaths() throws Exception {
        System.out.println();
        System.out.println("== 4. 失败路径（超时 / HTTP 错误）==");
        String[] good = PhiraApi.BASES.clone();
        try {
            // ① 两个域名都黑洞（10.255.255.1 是私有地址，连接会一直挂到超时）
            PhiraApi.BASES[0] = "https://10.255.255.1:81";
            PhiraApi.BASES[1] = "https://10.255.255.1:82";
            PhiraApi.clearCache();
            PhiraBrowser b = new PhiraBrowser();
            long took = timedSearch(b, "timeout-case");
            System.out.printf("  ① 超时：%dms state=%s err=%s%n", took, state(b), b.error());
            check(!b.loading(), "超时后 loading 收回（不会永远转圈）");
            check(!b.error().isBlank() && !b.error().matches(".*[A-Za-z]{6,}.*"),
                    "超时的原因是中文（" + b.error() + "）");
            check(took < 5_000, "超时一次不超过 5s（实际 " + took + "ms）");

            // ② 路径不对 → 服务端 4xx / 不是 JSON，两种都算「有中文原因的失败」
            PhiraApi.BASES[0] = "https://phira.5wyxi.com/no-such-path";
            PhiraApi.BASES[1] = "https://phira.5wyxi.com/no-such-path";
            PhiraApi.clearCache();
            PhiraBrowser b2 = new PhiraBrowser();
            long took2 = timedSearch(b2, "http-err");
            System.out.printf("  ② HTTP 错误：%dms state=%s err=%s%n", took2, state(b2), b2.error());
            check(!b2.loading() && !b2.error().isBlank(), "HTTP 错误也变成 FAILED + 中文原因");
        } finally {
            System.arraycopy(good, 0, PhiraApi.BASES, 0, good.length);
            PhiraApi.clearCache();
        }

        // ③ 恢复正常后必须还能搜（一次失败不能把线程池 / 域名健康度带坏）
        PhiraBrowser b3 = new PhiraBrowser();
        long took3 = timedSearch(b3, "");
        System.out.printf("  ③ 恢复：%dms state=%s total=%d%n", took3, state(b3), b3.total());
        check("OK".equals(state(b3)) && b3.total() > 0, "失败过后立刻还能正常搜索（" + took3 + "ms）");
    }

    // ------------------------------------------------------------------
    // 5. 线程池健康度 / 池满
    // ------------------------------------------------------------------
    private static void section5PoolHealth() throws Exception {
        System.out.println();
        System.out.println("== 5. 线程池（不卡死、满了也不丢状态）==");
        PhiraBrowser b = new PhiraBrowser();
        long t1 = timedSearch(b, "");
        System.out.printf("  正常一次搜索：%dms%n", t1);

        // 占满 2 条线程 + 8 个队列位（12 个 400ms 的活）：模拟「线程池满」
        AtomicInteger ran = new AtomicInteger();
        for (int i = 0; i < 12; i++) {
            PhiraApi.runAsync(() -> {
                ran.incrementAndGet();
                try {
                    Thread.sleep(400);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        PhiraBrowser b2 = new PhiraBrowser();
        long t2 = timedSearch(b2, "pool-full");
        System.out.printf("  池子被占满时搜索：%dms state=%s err=%s%n", t2, state(b2), b2.error());
        check(!b2.loading(), "线程池满时也不会卡在 loading（丢旧任务 / 排队后仍然收尾）");
        check(t2 < 5_000, "线程池满时也在 5s 内收尾（实际 " + t2 + "ms）");
        Thread.sleep(600);

        PhiraBrowser b3 = new PhiraBrowser();
        long t3 = timedSearch(b3, "");
        System.out.printf("  池子恢复后搜索：%dms state=%s total=%d%n", t3, state(b3), b3.total());
        check("OK".equals(state(b3)) && t3 < 5_000, "占用任务跑完后，搜索回到 5s 内");
        System.out.println("  12 个占用任务里真跑了 " + ran.get() + " 个（队列满时最老的会被丢掉，这是设计行为）");
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    /** 发起一次搜索并等到 loading 结束（最多 15s），返回耗时。 */
    private static long timedSearch(PhiraBrowser b, String key) throws Exception {
        long t = System.nanoTime();
        b.search(key, PhiraBrowser.ORDER_RATING, 1, 20);
        while (b.loading() && ms(t) < 15_000) Thread.sleep(POLL_MS);
        return ms(t);
    }

    private static String state(PhiraBrowser b) {
        if (b.loading()) return "STUCK";
        return b.error().isEmpty() ? "OK" : "FAILED";
    }

    /** 缓存命中的微秒级测量用：轮询间隔压到 5ms。 */
    private static long runOnce(PhiraBrowser b, String key, int page, boolean stable) throws Exception {
        long t = System.nanoTime();
        b.search(key, PhiraBrowser.ORDER_RATING, page, 20);
        while (b.loading() && ms(t) < 15_000) Thread.sleep(5);
        return ms(t);
    }

    private static long ms(long t0) {
        return (System.nanoTime() - t0) / 1_000_000L;
    }

    private static String cut(String s, int n) {
        if (s == null) return "-";
        return s.length() <= n ? s : s.substring(0, n) + "…";
    }

    private static void check(boolean ok, String what) {
        System.out.println((ok ? "  ✓ " : "  ✗ ") + what);
        if (!ok) failed++;
    }

    /** 用和 mod 一样的超时口径直接打一个 URL（判断某个域名是死是活）。 */
    private static String rawGet(String url) throws Exception {
        java.net.http.HttpClient c = java.net.http.HttpClient.newBuilder()
                .connectTimeout(PhiraApi.CONNECT_TIMEOUT)
                .followRedirects(java.net.http.HttpClient.Redirect.NORMAL)
                .build();
        java.net.http.HttpRequest r = java.net.http.HttpRequest.newBuilder(java.net.URI.create(url))
                .timeout(PhiraApi.REQUEST_TIMEOUT)
                .header("User-Agent", "PhiMC/0.1 (Minecraft mod; local)")
                .GET().build();
        java.net.http.HttpResponse<String> resp = c.send(r,
                java.net.http.HttpResponse.BodyHandlers.ofString(java.nio.charset.StandardCharsets.UTF_8));
        if (resp.statusCode() != 200) throw new IllegalStateException("HTTP " + resp.statusCode());
        return resp.body();
    }
}
