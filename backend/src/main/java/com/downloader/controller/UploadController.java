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
                result.add(info);
            }
        }

        return ResponseEntity.ok(Map.of(
                "files", result,
                "total", result.size(),
                "folder", folder.getAbsolutePath()
        ));
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

