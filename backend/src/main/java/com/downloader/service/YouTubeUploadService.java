package com.downloader.service;

import com.downloader.model.UploadSession;
import com.downloader.model.UploadSession.UploadResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.api.client.googleapis.media.MediaHttpUploader;
import com.google.api.client.http.InputStreamContent;
import com.google.api.services.youtube.YouTube;
import com.google.api.services.youtube.model.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;

/**
 * Xử lý upload video lên YouTube:
 * - Upload tuần tự từng file
 * - Set privacy = private
 * - Thêm vào playlist chỉ định
 * - Phát SSE progress real-time
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class YouTubeUploadService {

    private final YouTubeAuthService authService;
    private final ObjectMapper objectMapper;

    private final Map<String, UploadSession> sessions = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newCachedThreadPool();

    // ─────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────

    /** Tạo session mới, trả về sessionId (chưa bắt đầu upload) */
    public UploadSession createSession(String playlistId, List<String> filePaths) {
        UploadSession session = new UploadSession();
        session.setSessionId(UUID.randomUUID().toString());
        session.setPlaylistId(playlistId == null ? "" : playlistId.trim());
        session.setFilePaths(filePaths);
        session.setTotalFiles(filePaths.size());
        session.setStatus("PENDING");
        sessions.put(session.getSessionId(), session);
        log.info("[Upload] Tạo session {} với {} file(s)", session.getSessionId(), filePaths.size());
        return session;
    }

    /**
     * Đăng ký SSE emitter và BẮT ĐẦU upload ngay.
     * Frontend gọi SSE endpoint → upload tự động khởi động.
     */
    public SseEmitter registerEmitterAndStart(String sessionId) {
        UploadSession session = sessions.get(sessionId);
        if (session == null) {
            SseEmitter emitter = new SseEmitter(30_000L);
            try { emitter.send(SseEmitter.event().name("ERROR").data("{\"message\":\"Session không tồn tại\"}")); } catch (Exception ignored) {}
            emitter.complete();
            return emitter;
        }

        SseEmitter emitter = new SseEmitter(Long.MAX_VALUE);
        session.setEmitter(emitter);

        emitter.onTimeout(emitter::complete);
        emitter.onError(e -> log.warn("[Upload] SSE lỗi session {}: {}", sessionId, e.getMessage()));

        // Kick off upload in background
        executor.submit(() -> runUpload(session));

        return emitter;
    }

    public UploadSession getSession(String sessionId) {
        return sessions.get(sessionId);
    }

    // ─────────────────────────────────────────────────────
    // Upload logic
    // ─────────────────────────────────────────────────────

    private void runUpload(UploadSession session) {
        session.setStatus("RUNNING");
        session.setStartedAt(LocalDateTime.now());
        sendEvent(session, "STATUS", null);

        try {
            YouTube youtube = authService.getYouTubeClient();

            for (String rawPath : session.getFilePaths()) {
                String filePath = rawPath.trim();
                if (filePath.isEmpty()) continue;

                File file = new File(filePath);
                session.setCurrentFile(file.getName());
                session.setCurrentFileProgress(0);
                sendEvent(session, "FILE_START", "Bắt đầu upload: " + file.getName());

                UploadResult result = new UploadResult();
                result.setFilePath(filePath);
                result.setFileName(file.getName());

                try {
                    if (!file.exists()) throw new FileNotFoundException("File không tồn tại: " + filePath);

                    String videoId = uploadFile(youtube, file, session);
                    result.setVideoId(videoId);
                    result.setStatus("SUCCESS");

                    if (!session.getPlaylistId().isBlank()) {
                        addToPlaylist(youtube, videoId, session.getPlaylistId());
                        log.info("[Upload] Đã thêm video {} vào playlist {}", videoId, session.getPlaylistId());
                    }

                    session.setCompletedFiles(session.getCompletedFiles() + 1);
                    sendEvent(session, "FILE_DONE", "Hoàn thành: " + file.getName());

                } catch (Exception e) {
                    log.error("[Upload] Lỗi upload file {}", filePath, e);
                    result.setStatus("FAILED");
                    result.setError(e.getMessage());
                    session.setFailedFiles(session.getFailedFiles() + 1);
                    sendEvent(session, "FILE_ERROR", "Lỗi: " + file.getName() + " — " + e.getMessage());
                }

                session.getResults().add(result);
            }

            session.setStatus("DONE");
            session.setFinishedAt(LocalDateTime.now());
            saveUploadStatus(session); // Ghi upload_status.json vào folder
            sendEvent(session, "ALL_DONE", "Upload hoàn tất! " + session.getCompletedFiles() + "/" + session.getTotalFiles() + " file thành công.");

        } catch (Exception e) {
            log.error("[Upload] Lỗi session upload", e);
            session.setStatus("FAILED");
            session.setFinishedAt(LocalDateTime.now());
            sendEvent(session, "ERROR", "Lỗi: " + e.getMessage());
        } finally {
            if (session.getEmitter() != null) {
                try { session.getEmitter().complete(); } catch (Exception ignored) {}
            }
        }
    }

    // ─────────────────────────────────────────────────────
    // upload_status.json
    // ─────────────────────────────────────────────────────

    /** Ghi upload_status.json vào thư mục chứa video */
    private void saveUploadStatus(UploadSession session) {
        if (session.getFilePaths() == null || session.getFilePaths().isEmpty()) return;
        try {
            // Lấy folder của file đầu tiên
            File firstFile = new File(session.getFilePaths().get(0));
            File folder = firstFile.getParentFile();
            if (folder == null || !folder.exists()) return;

            File statusFile = new File(folder, "upload_status.json");

            // Đọc status cũ nếu tồn tại (merge thêm vào, không ghi đè)
            java.util.Map<String, Object> statusMap = new java.util.LinkedHashMap<>();
            if (statusFile.exists()) {
                try {
                    statusMap = objectMapper.readValue(statusFile,
                        new com.fasterxml.jackson.core.type.TypeReference<java.util.Map<String, Object>>() {});
                } catch (Exception ignored) {}
            }

            // Cập nhật metadata
            statusMap.put("folderPath", folder.getAbsolutePath());
            statusMap.put("lastUploadAt", session.getFinishedAt().toString());
            statusMap.put("playlistId", session.getPlaylistId());

            // Merge results: ưu tiên giữ SUCCESS cũ, ghi đè nếu lần này SUCCESS
            @SuppressWarnings("unchecked")
            java.util.List<java.util.Map<String, Object>> existingResults =
                (java.util.List<java.util.Map<String, Object>>) statusMap.getOrDefault("results", new java.util.ArrayList<>());

            java.util.Map<String, java.util.Map<String, Object>> resultByFile = new java.util.LinkedHashMap<>();
            for (java.util.Map<String, Object> r : existingResults) {
                resultByFile.put((String) r.get("fileName"), r);
            }

            for (UploadSession.UploadResult r : session.getResults()) {
                java.util.Map<String, Object> entry = new java.util.LinkedHashMap<>();
                entry.put("fileName", r.getFileName());
                entry.put("filePath", r.getFilePath());
                entry.put("status", r.getStatus());
                entry.put("videoId", r.getVideoId());
                entry.put("error", r.getError());
                entry.put("uploadedAt", session.getFinishedAt().toString());

                // Giữ SUCCESS cũ, ghi đè chỉ khi lần này SUCCESS hoặc chưa có
                java.util.Map<String, Object> existing = resultByFile.get(r.getFileName());
                if (existing == null || "SUCCESS".equals(r.getStatus())) {
                    resultByFile.put(r.getFileName(), entry);
                }
            }

            statusMap.put("results", new java.util.ArrayList<>(resultByFile.values()));

            objectMapper.writerWithDefaultPrettyPrinter().writeValue(statusFile, statusMap);
            log.info("[Upload] Đã lưu upload_status.json tại {}", statusFile.getAbsolutePath());

        } catch (Exception e) {
            log.warn("[Upload] Không thể ghi upload_status.json: {}", e.getMessage());
        }
    }

    /**
     * Đọc upload_status.json trong folder (nếu có).
     * Trả về Map: fileName → {status, videoId, error, uploadedAt}
     */
    public java.util.Map<String, java.util.Map<String, Object>> readUploadStatus(String folderPath) {
        File statusFile = new File(folderPath, "upload_status.json");
        if (!statusFile.exists()) return java.util.Collections.emptyMap();
        try {
            java.util.Map<String, Object> statusMap = objectMapper.readValue(statusFile,
                new com.fasterxml.jackson.core.type.TypeReference<java.util.Map<String, Object>>() {});

            @SuppressWarnings("unchecked")
            java.util.List<java.util.Map<String, Object>> results =
                (java.util.List<java.util.Map<String, Object>>) statusMap.getOrDefault("results", java.util.Collections.emptyList());

            java.util.Map<String, java.util.Map<String, Object>> map = new java.util.LinkedHashMap<>();
            for (java.util.Map<String, Object> r : results) {
                map.put((String) r.get("fileName"), r);
            }
            return map;
        } catch (Exception e) {
            log.warn("[Upload] Không đọc được upload_status.json: {}", e.getMessage());
            return java.util.Collections.emptyMap();
        }
    }

    private String uploadFile(YouTube youtube, File file, UploadSession session) throws Exception {
        // Metadata
        Video video = new Video();

        VideoStatus status = new VideoStatus();
        status.setPrivacyStatus("unlisted"); // Không công khai
        video.setStatus(status);

        VideoSnippet snippet = new VideoSnippet();
        String title = file.getName().replaceAll("\\.[^.]+$", ""); // bỏ đuôi file
        snippet.setTitle(title);
        snippet.setDescription("Uploaded by YouTube Playlist Uploader");
        video.setSnippet(snippet);

        // Media content
        InputStreamContent mediaContent = new InputStreamContent("video/*", new BufferedInputStream(new FileInputStream(file)));
        mediaContent.setLength(file.length());

        YouTube.Videos.Insert insert = youtube.videos()
                .insert(Arrays.asList("snippet", "status"), video, mediaContent);

        // Resumable upload + progress tracking
        MediaHttpUploader uploader = insert.getMediaHttpUploader();
        uploader.setDirectUploadEnabled(false);
        uploader.setChunkSize(MediaHttpUploader.MINIMUM_CHUNK_SIZE * 32); // ~8MB mỗi chunk

        uploader.setProgressListener(httpUploader -> {
            switch (httpUploader.getUploadState()) {
                case MEDIA_IN_PROGRESS -> {
                    double pct = httpUploader.getProgress() * 100;
                    session.setCurrentFileProgress(pct);
                    sendEvent(session, "FILE_PROGRESS", null);
                }
                case MEDIA_COMPLETE -> {
                    session.setCurrentFileProgress(100);
                    sendEvent(session, "FILE_PROGRESS", null);
                }
                default -> { /* ignore */ }
            }
        });

        Video returnedVideo = insert.execute();
        return returnedVideo.getId();
    }

    private void addToPlaylist(YouTube youtube, String videoId, String playlistId) throws Exception {
        ResourceId resourceId = new ResourceId();
        resourceId.setKind("youtube#video");
        resourceId.setVideoId(videoId);

        PlaylistItemSnippet itemSnippet = new PlaylistItemSnippet();
        itemSnippet.setPlaylistId(playlistId);
        itemSnippet.setResourceId(resourceId);

        PlaylistItem item = new PlaylistItem();
        item.setSnippet(itemSnippet);

        youtube.playlistItems().insert(Collections.singletonList("snippet"), item).execute();
    }

    // ─────────────────────────────────────────────────────
    // SSE helper
    // ─────────────────────────────────────────────────────

    private void sendEvent(UploadSession session, String eventName, String message) {
        if (session.getEmitter() == null) return;
        try {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("event", eventName);
            data.put("message", message);
            data.put("status", session.getStatus());
            data.put("totalFiles", session.getTotalFiles());
            data.put("completedFiles", session.getCompletedFiles());
            data.put("failedFiles", session.getFailedFiles());
            data.put("currentFile", session.getCurrentFile());
            data.put("currentFileProgress", Math.round(session.getCurrentFileProgress() * 10.0) / 10.0);
            data.put("results", session.getResults());

            String json = objectMapper.writeValueAsString(data);
            session.getEmitter().send(SseEmitter.event().name(eventName).data(json));
        } catch (Exception e) {
            log.warn("[Upload] SSE send lỗi: {}", e.getMessage());
        }
    }
}
