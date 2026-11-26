package com.downloader.controller;

import com.downloader.service.DocumentDownloadService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Tải tài liệu (PDF / Slide / Sheet / Doc): nhập link trang tài liệu hoặc link Google Drive/Docs,
 * chọn file rồi tải lần lượt vào thư mục.
 */
@Slf4j
@RestController
@RequestMapping("/api/docs")
@RequiredArgsConstructor
public class DocumentController {

    private final DocumentDownloadService service;

    /** Đọc danh sách tài liệu từ link */
    @PostMapping("/scan")
    public ResponseEntity<?> scan(@RequestBody Map<String, String> body) {
        try {
            return ResponseEntity.ok(service.scan(body.get("url")));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        } catch (Exception e) {
            log.warn("Docs: quét lỗi", e);
            return ResponseEntity.badRequest().body(Map.of("message", "Không đọc được trang: " + e.getMessage()));
        }
    }

    /** Đưa các tài liệu đã chọn vào hàng đợi tải */
    @PostMapping("/download")
    public ResponseEntity<?> download(@RequestBody DownloadRequest req) {
        try {
            DocumentDownloadService.DocJob job = service.enqueue(
                    req.getSavePath(), req.getFolderName(), req.getIds(), req.getSheetFormat());
            return ResponseEntity.ok(Map.of("success", true, "jobId", job.getId(), "total", job.getItems().size()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    @GetMapping("/jobs")
    public List<DocumentDownloadService.DocJob> jobs() {
        return service.listJobs();
    }

    @PostMapping("/cancel/{jobId}")
    public ResponseEntity<?> cancel(@PathVariable String jobId) {
        return service.cancel(jobId)
                ? ResponseEntity.ok(Map.of("success", true))
                : ResponseEntity.badRequest().body(Map.of("message", "Không tìm thấy job"));
    }

    @PostMapping("/retry/{jobId}")
    public ResponseEntity<?> retry(@PathVariable String jobId) {
        try {
            DocumentDownloadService.DocJob j = service.retry(jobId);
            return ResponseEntity.ok(Map.of("success", true, "jobId", j.getId()));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    @Data
    public static class DownloadRequest {
        private String savePath;
        private String folderName;
        private List<String> ids;
        /** office (mặc định) | pdf — định dạng khi tải Google Sheet/Doc/Slide */
        private String sheetFormat;
    }
}
