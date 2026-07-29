package com.downloader.controller;

import com.downloader.model.DownloadJob;
import com.downloader.model.DownloadRequest;
import com.downloader.service.DownloadJobService;
import jakarta.validation.Valid;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.*;

@Slf4j
@RestController
@RequestMapping("/api/download")
@RequiredArgsConstructor
public class DownloadController {

    private final DownloadJobService jobService;

    /**
     * Bắt đầu tải 1 playlist
     */
    @PostMapping("/start")
    public ResponseEntity<?> startDownload(@Valid @RequestBody DownloadRequest request) {
        try {
            log.info("Starting download job for playlist: {}", request.getPlaylistUrl());
            DownloadJob job = jobService.createJob(request);
            jobService.executeJob(job.getId());
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "jobId", job.getId(),
                    "message", "Job đã được tạo và đang chạy"
            ));
        } catch (Exception e) {
            log.error("Error starting download", e);
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", "Lỗi khởi động download: " + e.getMessage()
            ));
        }
    }

    /**
     * Bắt đầu tải nhiều playlist TUẦN TỰ (lần lượt từng link)
     */
    @PostMapping("/batch")
    public ResponseEntity<?> batchDownload(@RequestBody BatchRequest batchRequest) {
        try {
            if (batchRequest.getItems() == null || batchRequest.getItems().isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("success", false, "message", "Danh sách playlist trống"));
            }

            List<Map<String, String>> jobInfos = new ArrayList<>();
            List<String> jobIds = new ArrayList<>();

            // Tạo tất cả jobs trước (PENDING)
            for (BatchRequest.PlaylistItem item : batchRequest.getItems()) {
                if (item.getPlaylistUrl() == null || item.getPlaylistUrl().isBlank()) continue;
                if (item.getFolderName() == null || item.getFolderName().isBlank()) continue;

                DownloadRequest req = new DownloadRequest();
                req.setPlaylistUrl(item.getPlaylistUrl().trim());
                req.setFolderName(item.getFolderName().trim());
                req.setSavePath(batchRequest.getSavePath().trim());

                DownloadJob job = jobService.createJob(req);
                jobIds.add(job.getId());
                jobInfos.add(Map.of(
                        "jobId", job.getId(),
                        "playlistUrl", item.getPlaylistUrl(),
                        "folderName", item.getFolderName()
                ));
                log.info("Batch: queued job {} for folder '{}'", job.getId(), item.getFolderName());
            }

            // Chạy tuần tự trong 1 background thread
            jobService.executeJobsSequentially(jobIds);

            return ResponseEntity.ok(Map.of("success", true, "jobs", jobInfos));
        } catch (Exception e) {
            log.error("Error starting batch download", e);
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", e.getMessage()));
        }
    }

    /**
     * SSE endpoint để nhận real-time progress
     */
    @GetMapping(value = "/progress/{jobId}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter getProgress(@PathVariable String jobId) {
        log.info("SSE connection registered for job: {}", jobId);
        return jobService.registerEmitter(jobId);
    }

    /**
     * Lấy trạng thái job
     */
    @GetMapping("/status/{jobId}")
    public ResponseEntity<?> getJobStatus(@PathVariable String jobId) {
        DownloadJob job = jobService.getJob(jobId);
        if (job == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(job);
    }

    /**
     * Lấy danh sách tất cả jobs
     */
    @GetMapping("/jobs")
    public ResponseEntity<Collection<DownloadJob>> getAllJobs() {
        return ResponseEntity.ok(jobService.getAllJobs());
    }

    /**
     * Hủy job đang chạy
     */
    @PostMapping("/cancel/{jobId}")
    public ResponseEntity<?> cancelJob(@PathVariable String jobId) {
        boolean cancelled = jobService.cancelJob(jobId);
        if (cancelled) return ResponseEntity.ok(Map.of("success", true, "message", "Job đang được hủy"));
        return ResponseEntity.badRequest().body(Map.of("success", false, "message", "Không thể hủy job"));
    }

    // DTO cho batch request
    @Data
    public static class BatchRequest {
        private String savePath;
        private List<PlaylistItem> items;

        @Data
        public static class PlaylistItem {
            private String playlistUrl;
            private String folderName;
        }
    }
}
