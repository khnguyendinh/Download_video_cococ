package com.downloader.controller;

import com.downloader.model.UploadSession;
import com.downloader.service.YouTubeAuthService;
import com.downloader.service.YouTubeUploadService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * REST endpoints cho tính năng YouTube Upload:
 *   GET  /api/upload/auth-status       — kiểm tra trạng thái xác thực
 *   GET  /api/upload/auth-url          — lấy URL đăng nhập Google
 *   GET  /api/upload/oauth2callback    — callback từ Google OAuth2
 *   POST /api/upload/start             — tạo session upload
 *   GET  /api/upload/progress/{id}     — SSE stream tiến trình (đồng thời kích hoạt upload)
 *   GET  /api/upload/session/{id}      — lấy snapshot session
 */
@Slf4j
@RestController
@RequestMapping("/api/upload")
@RequiredArgsConstructor
public class UploadController {

    private final YouTubeAuthService authService;
    private final YouTubeUploadService uploadService;

    // ─────────────────────────────────────────────────────
    // Auth
    // ─────────────────────────────────────────────────────

    @GetMapping("/auth-status")
    public ResponseEntity<?> authStatus() {
        boolean hasCredentials = authService.isCredentialsAvailable();
        boolean authenticated = hasCredentials && authService.isAuthenticated();
        return ResponseEntity.ok(Map.of(
                "credentialsAvailable", hasCredentials,
                "authenticated", authenticated
        ));
    }

    @GetMapping("/auth-url")
    public ResponseEntity<?> authUrl() {
        try {
            String url = authService.getAuthUrl();
            return ResponseEntity.ok(Map.of("url", url));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Google redirect sau khi user cho phép quyền.
     * Xử lý token rồi redirect về Angular /oauth-callback?success=true|false
     */
    @GetMapping("/oauth2callback")
    public ResponseEntity<Void> oauth2Callback(@RequestParam(required = false) String code,
                                               @RequestParam(required = false) String error) {
        String frontendBase = "http://localhost:4200/oauth-callback";
        if (error != null) {
            log.warn("[YouTube Auth] Người dùng từ chối quyền: {}", error);
            return redirect(frontendBase + "?success=false&error=" + encode("Bạn đã từ chối cấp quyền."));
        }
        if (code == null) {
            return redirect(frontendBase + "?success=false&error=" + encode("Không nhận được mã xác thực từ Google."));
        }
        try {
            authService.handleCallback(code);
            return redirect(frontendBase + "?success=true");
        } catch (Exception e) {
            log.error("[YouTube Auth] Lỗi callback", e);
            return redirect(frontendBase + "?success=false&error=" + encode(e.getMessage()));
        }
    }

    // ─────────────────────────────────────────────────────
    // History
    // ─────────────────────────────────────────────────────

    @GetMapping("/history")
    public ResponseEntity<?> getHistory() {
        return ResponseEntity.ok(uploadService.getSessions());
    }

    @DeleteMapping("/history/{sessionId}")
    public ResponseEntity<?> deleteHistory(@PathVariable String sessionId) {
        uploadService.removeSession(sessionId);
        return ResponseEntity.ok(Map.of("success", true, "message", "Đã xóa"));
    }

    // ─────────────────────────────────────────────────────
    // Upload
    // ─────────────────────────────────────────────────────

    @PostMapping("/start")
    public ResponseEntity<?> startUpload(@RequestBody UploadRequest req) {
        try {
            if (!authService.isAuthenticated()) {
                return ResponseEntity.status(401).body(Map.of(
                        "success", false,
                        "message", "Chưa xác thực. Vui lòng đăng nhập Google trước."
                ));
            }
            if (req.getFilePaths() == null || req.getFilePaths().isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of(
                        "success", false,
                        "message", "Danh sách file trống"
                ));
            }
            UploadSession session = uploadService.createSession(req.getPlaylistId(), req.getFilePaths());
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "sessionId", session.getSessionId(),
                    "totalFiles", session.getTotalFiles()
            ));
        } catch (Exception e) {
            log.error("[Upload] Lỗi tạo session", e);
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", e.getMessage()));
        }
    }

    /**
     * SSE stream — kết nối xong là upload bắt đầu ngay.
     */
    @GetMapping(value = "/progress/{sessionId}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter progress(@PathVariable String sessionId) {
        log.info("[Upload] SSE kết nối cho session: {}", sessionId);
        return uploadService.registerEmitterAndStart(sessionId);
    }

    @GetMapping("/session/{sessionId}")
    public ResponseEntity<?> getSession(@PathVariable String sessionId) {
        UploadSession session = uploadService.getSession(sessionId);
        if (session == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(session);
    }

    // ─────────────────────────────────────────────────────
    // DTOs
    // ─────────────────────────────────────────────────────

    @Data
    public static class UploadRequest {
        private String playlistId;
        private List<String> filePaths;
    }

    // ─────────────────────────────────────────────────────
    // Scan Folder
    // ─────────────────────────────────────────────────────

    private static final Set<String> VIDEO_EXTENSIONS = Set.of(
            ".mp4", ".mkv", ".avi", ".mov", ".webm", ".flv", ".wmv", ".m4v", ".ts", ".3gp"
    );

    @GetMapping("/scan-folder")
    public ResponseEntity<?> scanFolder(@RequestParam String path) {
        File folder = new File(path);
        if (!folder.exists()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Thư mục không tồn tại: " + path));
        }
        if (!folder.isDirectory()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Không phải thư mục: " + path));
        }

        // Đọc upload_status.json nếu có
        var previousStatus = uploadService.readUploadStatus(path);

        File[] files = folder.listFiles();
        List<Map<String, Object>> result = new ArrayList<>();

        if (files != null) {
            Arrays.sort(files, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
            for (File f : files) {
                if (!f.isFile()) continue;
                String name = f.getName();
                String ext = name.contains(".") ? name.substring(name.lastIndexOf('.')).toLowerCase() : "";
                if (!VIDEO_EXTENSIONS.contains(ext)) continue;

                Map<String, Object> info = new HashMap<>();
                info.put("fileName", name);
                info.put("filePath", f.getAbsolutePath());
                info.put("fileSize", f.length());

                // Gắn trạng thái upload trước (nếu có)
                if (previousStatus.containsKey(name)) {
                    var prev = previousStatus.get(name);
                    info.put("previousStatus", prev.get("status"));   // SUCCESS | FAILED
                    info.put("previousVideoId", prev.get("videoId"));
                    info.put("previousError",   prev.get("error"));
                    info.put("previousUploadAt", prev.get("uploadedAt"));
                } else {
                    info.put("previousStatus", null);
                }

                result.add(info);
            }
        }

        // Metadata của lần upload trước
        boolean hasPreviousUpload = !previousStatus.isEmpty();
        long failedCount = result.stream()
            .filter(r -> "FAILED".equals(r.get("previousStatus"))).count();
        long successCount = result.stream()
            .filter(r -> "SUCCESS".equals(r.get("previousStatus"))).count();

        return ResponseEntity.ok(Map.of(
                "files", result,
                "total", result.size(),
                "folder", folder.getAbsolutePath(),
                "hasPreviousUpload", hasPreviousUpload,
                "previousFailedCount", failedCount,
                "previousSuccessCount", successCount
        ));
    }


    /**
     * POST /api/upload/init-status
     * Khởi tạo upload_status.json cho một folder với tên file lấy trực tiếp từ ổ đĩa.
     * Body: { folderPath, playlistId, uploadedTitles: ["..."] }
     * - File nào tên chứa 1 trong uploadedTitles → SUCCESS
     * - Còn lại → FAILED (chưa upload)
     */
    @PostMapping("/init-status")
    public ResponseEntity<?> initStatus(@RequestBody Map<String, Object> body) {
        String folderPath = (String) body.get("folderPath");
        String playlistId = (String) body.getOrDefault("playlistId", "");
        @SuppressWarnings("unchecked")
        List<String> uploadedTitles = (List<String>) body.getOrDefault("uploadedTitles", new ArrayList<>());

        File folder = new File(folderPath);
        if (!folder.exists() || !folder.isDirectory()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Thư mục không tồn tại: " + folderPath));
        }

        File[] files = folder.listFiles();
        if (files == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Không thể đọc thư mục"));
        }

        Arrays.sort(files, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));

        List<Map<String, Object>> results = new ArrayList<>();
        int successCount = 0, failedCount = 0;

        for (File f : files) {
            if (!f.isFile()) continue;
            String name = f.getName();
            String ext = name.contains(".") ? name.substring(name.lastIndexOf('.')).toLowerCase() : "";
            if (!VIDEO_EXTENSIONS.contains(ext)) continue;

            // Kiểm tra xem file có trong danh sách đã upload không
            // So sánh bằng cách trim và ignore case
            final String nameLower = name.toLowerCase().trim();
            boolean isUploaded = uploadedTitles.stream().anyMatch(title ->
                nameLower.contains(title.toLowerCase().trim()) ||
                title.toLowerCase().trim().contains(nameLower.replace(".mp4","").replace(".mkv","").replace(".webm","").trim())
            );

            Map<String, Object> entry = new HashMap<>();
            entry.put("fileName", name);           // Tên file thực từ File.getName()
            entry.put("filePath", f.getAbsolutePath());
            entry.put("videoId", null);
            entry.put("uploadedAt", isUploaded ? java.time.LocalDateTime.now().toString() : null);

            if (isUploaded) {
                entry.put("status", "SUCCESS");
                entry.put("error", null);
                successCount++;
            } else {
                entry.put("status", "FAILED");
                entry.put("error", "Chưa upload");
                failedCount++;
            }
            results.add(entry);
        }

        // Ghi file JSON
        try {
            Map<String, Object> statusData = new java.util.LinkedHashMap<>();
            statusData.put("folderPath", folder.getAbsolutePath());
            statusData.put("lastUploadAt", java.time.LocalDateTime.now().toString());
            statusData.put("playlistId", playlistId);
            statusData.put("results", results);

            com.fasterxml.jackson.databind.ObjectMapper mapper =
                new com.fasterxml.jackson.databind.ObjectMapper();
            mapper.enable(com.fasterxml.jackson.databind.SerializationFeature.INDENT_OUTPUT);

            File statusFile = new File(folder, "upload_status.json");
            mapper.writeValue(statusFile, statusData);

            log.info("[InitStatus] Đã ghi upload_status.json: {} SUCCESS, {} FAILED tại {}",
                successCount, failedCount, statusFile.getAbsolutePath());

            return ResponseEntity.ok(Map.of(
                "message", "Đã tạo upload_status.json",
                "successCount", successCount,
                "failedCount", failedCount,
                "totalFiles", results.size(),
                "statusFile", statusFile.getAbsolutePath()
            ));
        } catch (Exception e) {
            log.error("[InitStatus] Lỗi ghi file", e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    private ResponseEntity<Void> redirect(String url) {
        return ResponseEntity.status(302)
                .header("Location", url)
                .build();
    }

    private String encode(String value) {
        if (value == null) return "";
        try {
            return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            return value;
        }
    }
}

