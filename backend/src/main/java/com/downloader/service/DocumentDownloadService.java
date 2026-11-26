package com.downloader.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tải tài liệu (PDF / Slide / Sheet / Doc) từ trang "Tài liệu" (vd davincihcm-hocdautu.com/tai-lieu.html)
 * hoặc từ link Google Drive / Google Docs dán trực tiếp.
 *
 * Trang tài liệu nạp danh sách qua một Google Apps Script (/exec) — tool tìm URL đó trong HTML / file JS
 * của trang rồi gọi thẳng. File trên Drive được tải trực tiếp; nếu Drive chặn thì dùng chức năng
 * "?action=file&id=..." của chính Apps Script (trả base64) làm dự phòng.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentDownloadService {

    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/130.0 Safari/537.36";
    private static final Pattern APPS_SCRIPT = Pattern.compile(
            "https://script\\.google\\.com/macros/s/[A-Za-z0-9_-]+/exec");
    private static final Pattern SCRIPT_SRC = Pattern.compile(
            "<script[^>]+src=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);
    private static final Pattern DRIVE_FILE = Pattern.compile(
            "drive\\.google\\.com/(?:file/d/([^/?#]+)|(?:open|uc)\\?[^#]*?id=([^&#]+))");
    private static final Pattern GOOGLE_DOC = Pattern.compile(
            "docs\\.google\\.com/(document|spreadsheets|presentation)/d/([^/?#]+)");

    private final ObjectMapper mapper;

    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.ALWAYS)
            .connectTimeout(Duration.ofSeconds(20))
            .build();

    /** Tài liệu của lần quét gần nhất, theo id */
    private final Map<String, Doc> lastScan = new ConcurrentHashMap<>();
    private volatile String lastApi;

    private final Map<String, DocJob> jobs = new ConcurrentHashMap<>();
    /** 1 thread → các job tải lần lượt */
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "doc-download");
        t.setDaemon(true);
        return t;
    });

    @PreDestroy
    void shutdown() {
        worker.shutdownNow();
    }

    // ═══════════════════════════ Quét danh sách ═══════════════════════════

    public ScanResult scan(String rawUrl) throws Exception {
        String url = rawUrl == null ? "" : rawUrl.trim();
        if (!url.matches("(?i)^https?://.+")) {
            throw new IllegalArgumentException("Link không hợp lệ (cần bắt đầu bằng http:// hoặc https://)");
        }

        List<Doc> docs = new ArrayList<>();
        String api = null;
        String title;

        if (DRIVE_FILE.matcher(url).find() || GOOGLE_DOC.matcher(url).find()) {
            // Link Drive / Docs dán trực tiếp → 1 tài liệu
            Doc d = new Doc();
            d.setId("link-" + Integer.toHexString(url.hashCode()));
            d.setTitle("");
            d.setType(GOOGLE_DOC.matcher(url).find() ? googleKindLabel(url) : "File");
            d.setLink(url);
            docs.add(d);
            title = "Link trực tiếp";
        } else {
            String html = getText(url);
            title = extractTitle(html);
            JsonNode root = null;
            for (String cand : findAppsScripts(url, html)) {
                try {
                    JsonNode r = mapper.readTree(getText(cand + (cand.contains("?") ? "&" : "?") + "t=" + System.currentTimeMillis()));
                    if (r.path("docs").isArray()) {
                        api = cand;
                        root = r;
                        break;
                    }
                } catch (Exception e) {
                    log.debug("Docs: API {} không phải nguồn tài liệu: {}", cand, e.getMessage());
                }
            }
            if (root == null) {
                throw new IllegalStateException("Không tìm thấy nguồn danh sách tài liệu trên trang này");
            }
            for (JsonNode n : root.path("docs")) {
                Doc d = new Doc();
                d.setId(n.path("id").asText(UUID.randomUUID().toString()));
                d.setTitle(n.path("title").asText(""));
                d.setType(n.path("type").asText(""));
                d.setLevel(n.path("level").asText(""));
                d.setDate(n.path("date").asText(""));
                d.setDesc(n.path("desc").asText(""));
                d.setLink(resolveLink(url, n.path("link").asText("")));
                d.setContent(n.path("content").asText(""));
                docs.add(d);
            }
        }

        for (Doc d : docs) {
            d.setKind(kindOf(d));
            d.setDownloadable(!"none".equals(d.getKind()));
        }
        lastScan.clear();
        docs.forEach(d -> lastScan.put(d.getId(), d));
        lastApi = api;
        log.info("Docs: quét {} → {} tài liệu", url, docs.size());
        return new ScanResult(url, title, docs);
    }

    /**
     * Mọi URL Apps Script trong HTML và các file JS cùng site của trang (một trang có thể có nhiều,
     * vd lịch học + tài liệu). File JS có tên chứa "doc" được xếp lên trước.
     */
    private List<String> findAppsScripts(String pageUrl, String html) {
        Set<String> found = new LinkedHashSet<>();
        Matcher m = APPS_SCRIPT.matcher(html);
        while (m.find()) found.add(m.group());

        List<URI> scripts = new ArrayList<>();
        Matcher s = SCRIPT_SRC.matcher(html);
        URI base = URI.create(pageUrl);
        while (s.find()) {
            try {
                URI js = base.resolve(s.group(1).trim());
                if (js.getHost() != null && js.getHost().equalsIgnoreCase(base.getHost())) scripts.add(js);
            } catch (Exception ignored) {
            }
        }
        scripts.sort(Comparator.comparing(u -> u.getPath().toLowerCase().contains("doc") ? 0 : 1));
        for (URI js : scripts) {
            try {
                Matcher jm = APPS_SCRIPT.matcher(getText(js.toString()));
                while (jm.find()) found.add(jm.group());
            } catch (Exception e) {
                log.debug("Docs: bỏ qua script {}: {}", js, e.getMessage());
            }
        }
        return new ArrayList<>(found);
    }

    /** Giống resolveLink() của trang: tên file trơn → assets/docs/<tên> */
    private String resolveLink(String pageUrl, String l) {
        l = l == null ? "" : l.trim();
        if (l.isEmpty()) return "";
        if (l.matches("(?i)^https?://.*")) return l;
        if (l.startsWith("//")) return "https:" + l;
        URI base = URI.create(pageUrl);
        if (l.startsWith("/") || l.toLowerCase().startsWith("assets/")) return base.resolve(l).toString();
        String name = l.replaceAll("^.*[\\\\/]", "");
        return base.resolve("assets/docs/" + encode(name).replace("+", "%20")).toString();
    }

    private static String encode(String s) {
        return java.net.URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private String kindOf(Doc d) {
        String link = d.getLink() == null ? "" : d.getLink();
        Matcher g = GOOGLE_DOC.matcher(link);
        if (g.find()) {
            return switch (g.group(1)) {
                case "spreadsheets" -> "sheet";
                case "presentation" -> "slide";
                default -> "doc";
            };
        }
        if (DRIVE_FILE.matcher(link).find()) return "drive";
        if (!link.isEmpty()) return "url";
        if (d.getContent() != null && !d.getContent().isBlank()) return "text";
        return "none";
    }

    private String googleKindLabel(String url) {
        Matcher g = GOOGLE_DOC.matcher(url);
        if (!g.find()) return "File";
        return switch (g.group(1)) {
            case "spreadsheets" -> "Sheet";
            case "presentation" -> "Slide";
            default -> "Doc";
        };
    }

    private String extractTitle(String html) {
        Matcher m = Pattern.compile("<title>([^<]*)</title>", Pattern.CASE_INSENSITIVE).matcher(html);
        return m.find() ? m.group(1).trim() : "";
    }

    // ═══════════════════════════ Hàng đợi tải ═══════════════════════════

    public DocJob enqueue(String savePath, String folderName, List<String> ids, String sheetFormat) {
        if (savePath == null || savePath.isBlank()) throw new IllegalArgumentException("Chưa nhập thư mục lưu");
        Set<String> wanted = ids == null ? Set.of() : new LinkedHashSet<>(ids);
        List<DocItem> items = new ArrayList<>();
        int idx = 0;
        for (String id : wanted) {
            Doc d = lastScan.get(id);
            if (d == null || !d.isDownloadable()) continue;
            DocItem it = new DocItem();
            it.setIndex(++idx);
            it.setDoc(d);
            it.setTitle(d.getTitle().isBlank() ? d.getLink() : d.getTitle());
            items.add(it);
        }
        if (items.isEmpty()) throw new IllegalArgumentException("Chưa chọn tài liệu nào tải được");

        String folder = folderName == null ? "" : sanitize(folderName.trim());
        Path dir = folder.isEmpty() ? Paths.get(savePath.trim()) : Paths.get(savePath.trim(), folder);

        DocJob job = new DocJob();
        job.setId(UUID.randomUUID().toString().substring(0, 8));
        job.setFolder(dir.toString());
        job.setSheetFormat("pdf".equalsIgnoreCase(sheetFormat) ? "pdf" : "office");
        job.setApi(lastApi);
        job.setItems(items);
        job.setStatus("PENDING");
        job.setCreatedAt(LocalDateTime.now().toString());
        jobs.put(job.getId(), job);
        worker.submit(() -> run(job));
        log.info("Docs: job {} — {} tài liệu → {}", job.getId(), items.size(), dir);
        return job;
    }

    public List<DocJob> listJobs() {
        List<DocJob> list = new ArrayList<>(jobs.values());
        list.sort(Comparator.comparing(DocJob::getCreatedAt).reversed());
        return list;
    }

    public boolean cancel(String jobId) {
        DocJob j = jobs.get(jobId);
        if (j == null) return false;
        j.setCancelled(true);
        return true;
    }

    /** Tải lại các file lỗi / bị huỷ của job (chạy lại cùng job, xếp cuối hàng đợi) */
    public DocJob retry(String jobId) {
        DocJob j = jobs.get(jobId);
        if (j == null) throw new IllegalArgumentException("Không tìm thấy job");
        if ("PENDING".equals(j.getStatus()) || "RUNNING".equals(j.getStatus())) {
            throw new IllegalStateException("Job đang chạy");
        }
        long n = j.getItems().stream().filter(i -> !"DONE".equals(i.getStatus()) && !"SKIPPED".equals(i.getStatus())).count();
        if (n == 0) throw new IllegalStateException("Không có file nào cần tải lại");
        j.setCancelled(false);
        j.setStatus("PENDING");
        j.getItems().forEach(i -> {
            if (!"DONE".equals(i.getStatus()) && !"SKIPPED".equals(i.getStatus())) {
                i.setStatus("PENDING");
                i.setError(null);
                i.setProgress(0);
            }
        });
        worker.submit(() -> run(j));
        return j;
    }

    private void run(DocJob job) {
        job.setStatus("RUNNING");
        try {
            Files.createDirectories(Paths.get(job.getFolder()));
        } catch (IOException e) {
            job.getItems().forEach(i -> { if ("PENDING".equals(i.getStatus())) fail(i, "Không tạo được thư mục: " + e.getMessage()); });
            job.setStatus("FAILED");
            return;
        }
        for (DocItem it : job.getItems()) {
            if (!"PENDING".equals(it.getStatus())) continue;
            if (job.isCancelled()) {
                it.setStatus("CANCELLED");
                continue;
            }
            it.setStatus("DOWNLOADING");
            try {
                downloadItem(job, it);
            } catch (Exception e) {
                log.warn("Docs: lỗi tải '{}': {}", it.getTitle(), e.getMessage());
                fail(it, e.getMessage() == null ? e.toString() : e.getMessage());
            }
        }
        boolean anyFail = job.getItems().stream().anyMatch(i -> "FAILED".equals(i.getStatus()));
        job.setStatus(job.isCancelled() ? "CANCELLED" : anyFail ? "FAILED" : "COMPLETED");
        job.setFinishedAt(LocalDateTime.now().toString());
    }

    private void fail(DocItem it, String msg) {
        it.setStatus("FAILED");
        it.setError(msg);
    }

    private void downloadItem(DocJob job, DocItem it) throws Exception {
        Doc d = it.getDoc();
        Path dir = Paths.get(job.getFolder());
        String base = sanitize(d.getTitle());

        switch (d.getKind()) {
            case "text" -> {
                Path out = dir.resolve((base.isEmpty() ? d.getId() : base) + ".txt");
                if (skipIfExists(it, out)) return;
                Files.writeString(out, d.getContent(), StandardCharsets.UTF_8);
                done(it, out);
            }
            case "sheet", "doc", "slide" -> {
                Matcher g = GOOGLE_DOC.matcher(d.getLink());
                g.find();
                String id = g.group(2);
                String fmt = "pdf".equals(job.getSheetFormat()) ? "pdf"
                        : switch (d.getKind()) { case "sheet" -> "xlsx"; case "slide" -> "pptx"; default -> "docx"; };
                String exportUrl = "https://docs.google.com/" + g.group(1) + "/d/" + id + "/export?format=" + fmt;
                if (!tryHttp(it, exportUrl, dir, base, "." + fmt)) {
                    appsScriptFallback(job, it, id, dir, base);
                }
            }
            case "drive" -> {
                Matcher m = DRIVE_FILE.matcher(d.getLink());
                m.find();
                String id = m.group(1) != null ? m.group(1) : URLDecoder.decode(m.group(2), StandardCharsets.UTF_8);
                String direct = "https://drive.usercontent.google.com/download?id=" + id + "&export=download&confirm=t";
                if (!tryHttp(it, direct, dir, base, null)) {
                    appsScriptFallback(job, it, id, dir, base);
                }
            }
            case "url" -> {
                if (!tryHttp(it, d.getLink(), dir, base, null)) {
                    throw new IOException(it.getError() != null ? it.getError() : "Không tải được link");
                }
            }
            default -> throw new IOException("Tài liệu không có link tải");
        }
    }

    /**
     * Tải 1 URL bằng HTTP, ghi ra file .part rồi đổi tên. Trả về false nếu server trả HTML
     * (trang đăng nhập / cảnh báo) hoặc lỗi HTTP — để còn thử cách khác.
     */
    private boolean tryHttp(DocItem it, String url, Path dir, String base, String forcedExt) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .header("User-Agent", UA)
                    .timeout(Duration.ofMinutes(10))
                    .GET().build();
            HttpResponse<InputStream> res = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
            String ctype = res.headers().firstValue("content-type").orElse("").toLowerCase();
            if (res.statusCode() >= 400 || ctype.startsWith("text/html")) {
                res.body().close();
                it.setError("HTTP " + res.statusCode() + (ctype.startsWith("text/html") ? " (trang HTML, không phải file)" : ""));
                log.debug("Docs: {} → {} {}", url, res.statusCode(), ctype);
                return false;
            }
            String serverName = fileNameFromHeader(res.headers().firstValue("content-disposition").orElse(""));
            if (serverName.isEmpty()) {
                String path = URI.create(url).getPath();
                if (path != null && path.matches(".*\\.[A-Za-z0-9]{2,5}$")) {
                    serverName = URLDecoder.decode(path.substring(path.lastIndexOf('/') + 1), StandardCharsets.UTF_8);
                }
            }
            String ext = forcedExt != null ? forcedExt : extOf(serverName, ctype);
            String name = base.isEmpty() ? sanitize(stripExt(serverName)) : base;
            if (name.isEmpty()) name = it.getDoc().getId();
            Path out = dir.resolve(name + ext);
            if (skipIfExists(it, out)) {
                res.body().close();
                return true;
            }
            long total = res.headers().firstValueAsLong("content-length").orElse(-1);
            it.setTotalBytes(total);
            Path part = out.resolveSibling(out.getFileName() + ".part");
            try (InputStream in = res.body(); OutputStream os = Files.newOutputStream(part)) {
                byte[] buf = new byte[64 * 1024];
                long got = 0;
                int n;
                while ((n = in.read(buf)) > 0) {
                    os.write(buf, 0, n);
                    got += n;
                    it.setBytes(got);
                    if (total > 0) it.setProgress(Math.min(99.9, got * 100.0 / total));
                }
            }
            Files.move(part, out, StandardCopyOption.REPLACE_EXISTING);
            it.setError(null);
            done(it, out);
            return true;
        } catch (Exception e) {
            it.setError(e.getMessage());
            log.debug("Docs: tải {} lỗi: {}", url, e.toString());
            return false;
        }
    }

    /** Dự phòng: Apps Script của trang đọc file Drive và trả base64 (Google Sheet/Doc → PDF) */
    private void appsScriptFallback(DocJob job, DocItem it, String fileId, Path dir, String base) throws Exception {
        if (job.getApi() == null) {
            throw new IOException("Không tải được từ Google (" + Objects.toString(it.getError(), "bị chặn")
                    + "). File có thể chưa chia sẻ công khai.");
        }
        it.setProgress(0);
        JsonNode j = mapper.readTree(getText(job.getApi() + "?action=file&id="
                + encode(fileId)));
        String data = j.path("data").asText("");
        if (data.isEmpty()) {
            throw new IOException("Apps Script không trả được file: " + j.path("error").asText("không rõ"));
        }
        byte[] bytes = Base64.getDecoder().decode(data);
        String serverName = j.path("name").asText("");
        String ext = extOf(serverName, "");
        if (ext.isEmpty()) ext = isPdf(bytes) ? ".pdf" : ".bin";
        String name = base.isEmpty() ? sanitize(stripExt(serverName)) : base;
        if (name.isEmpty()) name = it.getDoc().getId();
        Path out = dir.resolve(name + ext);
        if (skipIfExists(it, out)) return;
        Files.write(out, bytes);
        it.setBytes(bytes.length);
        it.setTotalBytes(bytes.length);
        it.setError(null);
        it.setNote("tải qua API của trang");
        done(it, out);
    }

    private boolean skipIfExists(DocItem it, Path out) throws IOException {
        if (Files.exists(out) && Files.size(out) > 0) {
            it.setStatus("SKIPPED");
            it.setFilePath(out.toString());
            it.setBytes(Files.size(out));
            it.setProgress(100);
            it.setNote("đã có sẵn, bỏ qua");
            return true;
        }
        return false;
    }

    private void done(DocItem it, Path out) throws IOException {
        it.setStatus("DONE");
        it.setFilePath(out.toString());
        it.setBytes(Files.size(out));
        it.setProgress(100);
    }

    // ═══════════════════════════ Tiện ích ═══════════════════════════

    private String getText(String url) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", UA)
                .timeout(Duration.ofMinutes(5))
                .GET().build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (res.statusCode() >= 400) throw new IOException("HTTP " + res.statusCode() + " khi mở " + url);
        return res.body();
    }

    private static String fileNameFromHeader(String cd) {
        if (cd == null || cd.isEmpty()) return "";
        Matcher star = Pattern.compile("filename\\*\\s*=\\s*(?:UTF-8|utf-8)''([^;]+)").matcher(cd);
        if (star.find()) return URLDecoder.decode(star.group(1).trim(), StandardCharsets.UTF_8);
        Matcher m = Pattern.compile("filename\\s*=\\s*\"?([^\";]+)\"?").matcher(cd);
        return m.find() ? m.group(1).trim() : "";
    }

    private static String extOf(String name, String ctype) {
        if (name != null) {
            int dot = name.lastIndexOf('.');
            if (dot > 0 && dot >= name.length() - 6) return name.substring(dot).toLowerCase();
        }
        if (ctype.contains("pdf")) return ".pdf";
        if (ctype.contains("spreadsheetml")) return ".xlsx";
        if (ctype.contains("presentationml")) return ".pptx";
        if (ctype.contains("wordprocessingml")) return ".docx";
        if (ctype.contains("ms-excel")) return ".xls";
        if (ctype.contains("ms-powerpoint")) return ".ppt";
        if (ctype.contains("msword")) return ".doc";
        if (ctype.contains("zip")) return ".zip";
        return "";
    }

    private static String stripExt(String name) {
        if (name == null) return "";
        int dot = name.lastIndexOf('.');
        return dot > 0 && dot >= name.length() - 6 ? name.substring(0, dot) : name;
    }

    private static boolean isPdf(byte[] b) {
        return b.length > 4 && b[0] == '%' && b[1] == 'P' && b[2] == 'D' && b[3] == 'F';
    }

    /** Bỏ ký tự cấm trong tên file Windows */
    static String sanitize(String s) {
        if (s == null) return "";
        String r = s.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", " ").replaceAll("\\s+", " ").trim();
        r = r.replaceAll("[. ]+$", "");
        return r.length() > 150 ? r.substring(0, 150).trim() : r;
    }

    // ═══════════════════════════ Model ═══════════════════════════

    public record ScanResult(String pageUrl, String pageTitle, List<Doc> docs) {}

    @Data
    public static class Doc {
        private String id;
        private String title;
        private String type;
        private String level;
        private String date;
        private String desc;
        private String link;
        /** sheet | doc | slide | drive | url | text | none */
        private String kind;
        private boolean downloadable;
        @com.fasterxml.jackson.annotation.JsonIgnore
        private String content;
    }

    @Data
    public static class DocItem {
        private int index;
        private String title;
        @com.fasterxml.jackson.annotation.JsonIgnore
        private Doc doc;
        /** PENDING | DOWNLOADING | DONE | SKIPPED | FAILED | CANCELLED */
        private volatile String status = "PENDING";
        private volatile double progress;
        private volatile long bytes;
        private volatile long totalBytes = -1;
        private String filePath;
        private String error;
        private String note;

        public String getType() {
            return doc == null ? "" : doc.getType();
        }
    }

    @Data
    public static class DocJob {
        private String id;
        private String folder;
        /** office = xlsx/docx/pptx, pdf = xuất PDF (cho Google Sheet/Doc/Slide) */
        private String sheetFormat;
        @com.fasterxml.jackson.annotation.JsonIgnore
        private String api;
        private List<DocItem> items;
        /** PENDING | RUNNING | COMPLETED | FAILED | CANCELLED */
        private volatile String status;
        private volatile boolean cancelled;
        private String createdAt;
        private String finishedAt;
    }
}
