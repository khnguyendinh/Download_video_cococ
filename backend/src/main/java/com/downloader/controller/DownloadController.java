package com.downloader.controller;

import com.downloader.model.DownloadJob;
import com.downloader.model.DownloadRequest;
import com.downloader.service.DownloadJobService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Collection;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/download")
@RequiredArgsConstructor
public class DownloadController {

    private final DownloadJobService jobService;

    /**
     * Bắt đầu tải playlist
     */
    @PostMapping("/start")
    public ResponseEntity<?> startDownload(@Valid @RequestBody DownloadRequest request) {
        try {
            log.info("Starting download job for playlist: {}", request.getPlaylistUrl());

            // Tạo job
            DownloadJob job = jobService.createJob(request);

            // Chạy async
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
        if (job == null) {
            return ResponseEntity.notFound().build();
        }
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
        if (cancelled) {
            return ResponseEntity.ok(Map.of("success", true, "message", "Job đang được hủy"));
        }
        return ResponseEntity.badRequest().body(Map.of(
                "success", false,
                "message", "Không thể hủy job (job không tồn tại hoặc đã kết thúc)"
        ));
    }
}
