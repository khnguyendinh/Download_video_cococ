package com.downloader.service;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Mở Chrome thật của máy (profile riêng, giữ đăng nhập giữa các lần), chờ người dùng tự đăng nhập
 * trang khoá học, rồi quay lại trang bài học và đọc danh sách video bằng chính Firebase của trang.
 *
 * Chrome được chạy như một tiến trình bình thường + bật cổng remote debugging, Playwright chỉ "gắn vào"
 * qua CDP — nhờ vậy cửa sổ không bị gắn cờ automation (đăng nhập Google vẫn hoạt động).
 *
 * Mọi lệnh Playwright chạy trên 1 thread riêng (Playwright không thread-safe).
 */
@Slf4j
@Service
public class SiteBrowserService {

    /** Domain duy nhất được hỗ trợ (logic đọc bài học viết riêng cho trang này) */
    public static final String SITE_HOST = "davincihcm-hocdautu.com";
    public static final String SITE_REFERER = "https://" + SITE_HOST + "/";

    private static final long LOGIN_TIMEOUT_MS = 15 * 60 * 1000L;

    /** Trả về email người dùng nếu trang đã đăng nhập Firebase, ngược lại null */
    private static final String LOGIN_JS = """
            () => {
              try {
                if (!window.firebase || !firebase.apps || !firebase.apps.length) return null;
                const u = firebase.auth().currentUser;
                return u ? (u.email || u.uid) : null;
              } catch (e) { return null; }
            }
            """;

    /** Đọc danh sách bài học của 1 level — giống hệt cách lessons.html tự đọc (cùng quyền của tài khoản) */
    private static final String SCAN_JS = """
            async (lvl) => {
              const db = firebase.firestore();
              const cfgSnap = await db.collection('levels').doc(lvl).get();
              const cfg = cfgSnap.exists ? (cfgSnap.data() || {}) : {};
              const lib = String(cfg.bunnyLibraryId || '').trim();
              const q = await db.collection('lessons')
                  .where('isActive', '==', true).where('level', '==', lvl)
                  .orderBy('order', 'asc').get();
              const ytId = (v) => {
                v = String(v || '').trim();
                const m = v.match(/(?:v=|youtu\\.be\\/|embed\\/|shorts\\/)([\\w-]{11})/);
                return m ? m[1] : (/^[\\w-]{11}$/.test(v) ? v : '');
              };
              const lessons = [];
              q.forEach((d) => {
                const x = d.data() || {};
                const provider = String(x.videoProvider || 'bunny').toLowerCase();
                let url = '', error = '';
                if (provider === 'youtube') {
                  const y = ytId(x.youtubeId || x.youtubeUrl);
                  if (y) url = 'https://www.youtube.com/watch?v=' + y; else error = 'Bài thiếu youtubeId';
                } else if (provider === 'bunny' && x.bunnyVideoId) {
                  const raw = String(x.bunnyVideoId).trim();
                  let l = lib, vid = raw;
                  if (raw.includes('/')) { const p = raw.split('/'); l = p[0]; vid = p[1]; }
                  if (l && vid) url = 'https://iframe.mediadelivery.net/embed/' + encodeURIComponent(l) + '/' + encodeURIComponent(vid);
                  else error = 'Thiếu bunnyLibraryId của level';
                } else {
                  error = 'Bài chưa có video';
                }
                lessons.push({ id: d.id, title: String(x.title || d.id), provider: provider, url: url, error: error,
                               duration: x.duration != null ? String(x.duration) : '' });
              });
              return { levelName: String(cfg.name || cfg.title || lvl), lessons: lessons };
            }
            """;

    public enum State { IDLE, OPENING, WAITING_LOGIN, SCANNING, READY, ERROR }

    public record Lesson(String id, int index, String title, String provider, String url, String error, String duration) {}

    public record ScanResult(String pageUrl, String level, String levelName, List<Lesson> lessons) {}

    public record Status(State state, String message, String email, String targetUrl, ScanResult scan) {}

    @Value("${site.browser-path:}")
    private String browserPath;

    @Value("${site.debug-port:9333}")
    private int debugPort;

    @Value("${site.profile-dir:browser-profile}")
    private String profileDir;

    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "site-browser");
        t.setDaemon(true);
        return t;
    });

    /** Tăng mỗi lần open()/close() → vòng chờ đăng nhập cũ tự thoát */
    private final AtomicInteger generation = new AtomicInteger();

    private volatile State state = State.IDLE;
    private volatile String message = "Chưa mở trình duyệt";
    private volatile String email;
    private volatile String targetUrl;
    private volatile ScanResult scan;

    // Chỉ truy cập trên thread worker
    private Process browserProcess;
    private Playwright playwright;
    private Browser browser;

    public Status getStatus() {
        return new Status(state, message, email, targetUrl, scan);
    }

    public ScanResult getScan() {
        return scan;
    }

    /**
     * Mở link trong Chrome; nếu chưa đăng nhập thì chờ người dùng đăng nhập rồi quay lại link và đọc bài học
     */
    public Status open(String url) {
        String level = validateAndGetLevel(url);
        int gen = generation.incrementAndGet();
        targetUrl = url;
        scan = null;
        setState(State.OPENING, "Đang mở Chrome...");
        worker.submit(() -> runFlow(url, level, gen));
        return getStatus();
    }

    /** Đóng cửa sổ Chrome đã mở */
    public void close() {
        generation.incrementAndGet();
        worker.submit(() -> {
            shutdownBrowser(true);
            setState(State.IDLE, "Đã đóng trình duyệt");
        });
    }

    @PreDestroy
    public void destroy() {
        generation.incrementAndGet();
        worker.shutdownNow();
        try { if (playwright != null) playwright.close(); } catch (Exception ignored) {}
    }

    // ─── Luồng chính (thread worker) ─────────────────────────────────────

    private void runFlow(String url, String level, int gen) {
        try {
            ensureBrowser();
            if (gen != generation.get()) return;

            Page page = mainPage();
            page.bringToFront();
            page.navigate(url);

            // 1. Chờ đăng nhập
            String user = currentUser(page);
            if (user == null) {
                setState(State.WAITING_LOGIN, "Hãy đăng nhập trong cửa sổ Chrome vừa mở. Đăng nhập xong mình sẽ tự quay lại trang bài học.");
                long deadline = System.currentTimeMillis() + LOGIN_TIMEOUT_MS;
                while (user == null) {
                    if (gen != generation.get()) return;
                    if (!browser.isConnected()) throw new IllegalStateException("Cửa sổ Chrome đã bị đóng");
                    if (System.currentTimeMillis() > deadline) throw new IllegalStateException("Quá 15 phút chưa đăng nhập");
                    Thread.sleep(1500);
                    page = mainPage();
                    user = currentUser(page);
                }
            }
            email = user;
            log.info("Site: đã đăng nhập ({}), quay lại {}", user, url);

            // 2. Quay về đúng trang người dùng nhập
            setState(State.SCANNING, "Đã đăng nhập " + user + " — đang mở lại trang bài học...");
            if (!sameUrl(page.url(), url)) {
                page.navigate(url);
            }
            page.waitForFunction("() => window.firebase && firebase.apps.length && firebase.auth().currentUser",
                    null, new Page.WaitForFunctionOptions().setTimeout(60_000));
            if (gen != generation.get()) return;

            // 3. Đọc danh sách bài
            setState(State.SCANNING, "Đang đọc danh sách bài học level " + level + "...");
            Object raw = page.evaluate(SCAN_JS, level);
            ScanResult result = parseScan(url, level, raw);
            if (gen != generation.get()) return;
            scan = result;

            long ok = result.lessons().stream().filter(l -> !l.url().isEmpty()).count();
            setState(State.READY, "Tìm thấy " + result.lessons().size() + " bài (" + ok + " bài có video)");
            log.info("Site: level {} có {} bài, {} bài có video", level, result.lessons().size(), ok);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            if (gen != generation.get()) return;
            log.error("Site flow lỗi", e);
            setState(State.ERROR, "Lỗi: " + shortError(e));
        }
    }

    private String currentUser(Page page) {
        try {
            Object r = page.evaluate(LOGIN_JS);
            return r == null ? null : r.toString();
        } catch (Exception e) {
            // Trang đang chuyển hướng → context JS bị huỷ, thử lại ở vòng sau
            return null;
        }
    }

    /** Chọn tab của trang khoá học (ưu tiên), không có thì tab cuối / mở tab mới */
    private Page mainPage() {
        BrowserContext ctx = browser.contexts().isEmpty() ? browser.newContext() : browser.contexts().get(0);
        List<Page> pages = ctx.pages();
        for (int i = pages.size() - 1; i >= 0; i--) {
            Page p = pages.get(i);
            if (!p.isClosed() && p.url().contains(SITE_HOST)) return p;
        }
        for (int i = pages.size() - 1; i >= 0; i--) {
            if (!pages.get(i).isClosed()) return pages.get(i);
        }
        return ctx.newPage();
    }

    @SuppressWarnings("unchecked")
    private ScanResult parseScan(String url, String level, Object raw) {
        if (!(raw instanceof Map<?, ?> map)) throw new IllegalStateException("Không đọc được danh sách bài học");
        String levelName = Objects.toString(map.get("levelName"), level);
        List<Lesson> lessons = new ArrayList<>();
        Object arr = map.get("lessons");
        if (arr instanceof List<?> list) {
            int idx = 1;
            for (Object o : list) {
                Map<String, Object> m = (Map<String, Object>) o;
                lessons.add(new Lesson(
                        Objects.toString(m.get("id"), ""),
                        idx++,
                        Objects.toString(m.get("title"), ""),
                        Objects.toString(m.get("provider"), ""),
                        Objects.toString(m.get("url"), ""),
                        Objects.toString(m.get("error"), ""),
                        Objects.toString(m.get("duration"), "")));
            }
        }
        return new ScanResult(url, level, levelName, lessons);
    }

    // ─── Chrome ──────────────────────────────────────────────────────────

    private void ensureBrowser() throws Exception {
        if (browser != null && browser.isConnected()) return;
        shutdownBrowser(false);

        if (!isPortOpen()) {
            String exe = resolveBrowserExe();
            Path profile = Paths.get(profileDir).toAbsolutePath();
            Files.createDirectories(profile);
            ProcessBuilder pb = new ProcessBuilder(exe,
                    "--remote-debugging-port=" + debugPort,
                    "--user-data-dir=" + profile,
                    "--no-first-run",
                    "--no-default-browser-check",
                    "about:blank");
            pb.redirectErrorStream(true);
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            browserProcess = pb.start();
            log.info("Site: đã mở {} (profile {})", exe, profile);
        }

        playwright = Playwright.create(new Playwright.CreateOptions()
                .setEnv(Map.of("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1")));

        Exception last = null;
        for (int i = 0; i < 30; i++) {
            try {
                browser = playwright.chromium().connectOverCDP("http://127.0.0.1:" + debugPort);
                return;
            } catch (Exception e) {
                last = e;
                Thread.sleep(500);
            }
        }
        throw new IllegalStateException("Không kết nối được Chrome qua cổng " + debugPort + ": " + shortError(last));
    }

    private void shutdownBrowser(boolean closeWindow) {
        try {
            if (closeWindow && browser != null && browser.isConnected()) {
                browser.newBrowserCDPSession().send("Browser.close");
            }
        } catch (Exception ignored) {}
        try { if (browser != null) browser.close(); } catch (Exception ignored) {}
        try { if (playwright != null) playwright.close(); } catch (Exception ignored) {}
        browser = null;
        playwright = null;
        if (closeWindow && browserProcess != null) {
            browserProcess.destroy();
        }
        if (closeWindow) browserProcess = null;
    }

    private boolean isPortOpen() {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", debugPort), 300);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private String resolveBrowserExe() {
        List<String> candidates = new ArrayList<>();
        if (browserPath != null && !browserPath.isBlank()) candidates.add(browserPath);
        String local = System.getenv("LOCALAPPDATA");
        candidates.add("C:/Program Files/Google/Chrome/Application/chrome.exe");
        candidates.add("C:/Program Files (x86)/Google/Chrome/Application/chrome.exe");
        if (local != null) candidates.add(local + "/Google/Chrome/Application/chrome.exe");
        candidates.add("C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe");
        candidates.add("C:/Program Files/Microsoft/Edge/Application/msedge.exe");
        for (String c : candidates) {
            if (Files.isRegularFile(Paths.get(c))) return c;
        }
        throw new IllegalStateException("Không tìm thấy Chrome/Edge — cấu hình site.browser-path trong application.yml");
    }

    // ─── Tiện ích ────────────────────────────────────────────────────────

    /** Chỉ nhận link trang bài học của davincihcm-hocdautu.com có ?level=... */
    static String validateAndGetLevel(String url) {
        if (url == null || url.isBlank()) throw new IllegalArgumentException("Chưa nhập link");
        URI uri;
        try {
            uri = URI.create(url.trim());
        } catch (Exception e) {
            throw new IllegalArgumentException("Link không hợp lệ");
        }
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase();
        if (!host.equals(SITE_HOST) && !host.endsWith("." + SITE_HOST)) {
            throw new IllegalArgumentException("Hiện chỉ hỗ trợ link " + SITE_HOST);
        }
        String query = uri.getRawQuery();
        if (query != null) {
            for (String kv : query.split("&")) {
                int eq = kv.indexOf('=');
                if (eq > 0 && kv.substring(0, eq).equals("level")) {
                    String lvl = URLDecoder.decode(kv.substring(eq + 1), StandardCharsets.UTF_8).trim();
                    if (!lvl.isEmpty()) return lvl;
                }
            }
        }
        throw new IllegalArgumentException("Link cần có ?level=... (vd: https://" + SITE_HOST + "/lessons.html?level=S2)");
    }

    private static boolean sameUrl(String a, String b) {
        return a != null && b != null && a.replaceAll("#.*$", "").equals(b.trim().replaceAll("#.*$", ""));
    }

    private void setState(State s, String msg) {
        state = s;
        message = msg;
    }

    private static String shortError(Exception e) {
        if (e == null) return "";
        String m = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        int nl = m.indexOf('\n');
        return nl > 0 ? m.substring(0, nl) : m;
    }
}
