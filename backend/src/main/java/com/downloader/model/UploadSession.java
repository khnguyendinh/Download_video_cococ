package com.downloader.model;

import lombok.Data;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
public class UploadSession {

    private String sessionId;
    private String playlistId;
    private List<String> filePaths = new ArrayList<>();

    private int totalFiles;
    private int completedFiles;
    private int failedFiles;

    private String currentFile;
    private double currentFileProgress; // 0 → 100

    /** PENDING | RUNNING | DONE | FAILED */
    private String status = "PENDING";

    private List<UploadResult> results = new ArrayList<>();
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;

    // Transient: không serialize SSE emitter
    private transient SseEmitter emitter;

    @Data
    public static class UploadResult {
        private String filePath;
        private String fileName;
        private String videoId;
        /** SUCCESS | FAILED */
        private String status;
        private String error;
    }
}
