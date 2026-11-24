package com.downloader.controller;

import com.downloader.model.DownloadJob;
import com.downloader.model.VideoInfo;
import com.downloader.service.DownloadJobService;
import com.downloader.service.SiteBrowserService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * Tải video khoá học (davincihcm-hocdautu.com): mở Chrome để người dùng đăng nhập,
 * đọc danh sách bài, rồi đưa các video đã chọn vào hàng đợi tải (chạy lần lượt).
 * Tiến độ / huỷ / xoá job dùng chung API /api/download/*.
 */
@Slf4j
@RestController
@RequestMapping("/api/site")
@RequiredArgsConstructor
public class SiteDownloadController {

    private final SiteBrowserService browserService;
    private final DownloadJobService jobService;

    /** Mở link trong Chrome; chờ đăng nhập → quay lại trang → đọc bài học */
    @PostMapping("/open")
    public ResponseEntity<?> open(@RequestBody Map<String, String> body) {
        try {
            return ResponseEntity.ok(browserService.open(body.get("url")));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    /** Trạng thái hiện tại (frontend poll) */
    @GetMapping("/status")
    public SiteBrowserService.Status status() {
        return browserService.getStatus();
    }

    /** Đóng cửa sổ Chrome */
    @PostMapping("/close")
    public ResponseEntity<?> close() {
        browserService.close();
        return ResponseEntity.ok(Map.of("success", true));
    }

    /** Đưa các bài đã chọn vào hàng đợi tải */
    @PostMapping("/enqueue")
    public ResponseEntity<?> enqueue(@RequestBody EnqueueRequest req) {
        SiteBrowserService.ScanResult scan = browserService.getScan();
        if (scan == null) {
            return ResponseEntity.badRequest().body(Map.of("message", "Chưa đọc được danh sách bài học"));
        }
        if (req.getSavePath() == null || req.getSavePath().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("message", "Chưa nhập thư mục lưu"));
        }
        if (req.getFolderName() == null || req.getFolderName().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("message", "Chưa nhập tên folder"));
        }

        Set<String> wanted = req.getLessonIds() == null ? Set.of() : new HashSet<>(req.getLessonIds());
        List<VideoInfo> videos = new ArrayList<>();
        for (SiteBrowserService.Lesson l : scan.lessons()) {
            if (l.url().isEmpty() || !wanted.contains(l.id())) continue;
            videos.add(VideoInfo.builder()
                    .id(l.id())
                    .title(l.title())
                    .url(l.url())
                    .duration(l.duration())
                    .index(l.index())
                    // Đánh số theo thứ tự bài để file sắp xếp đúng trong thư mục
                    .fileBaseName(String.format("%02d. %s", l.index(), l.title()))
                    .status(VideoInfo.VideoStatus.PENDING)
                    .progress(0)
                    .build());
        }
        if (videos.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("message", "Chưa chọn bài nào có video"));
        }

        DownloadJob job = jobService.createPresetJob(scan.pageUrl(), req.getFolderName().trim(),
                req.getSavePath().trim(), SiteBrowserService.SITE_REFERER, videos);
        jobService.enqueuePresetJob(job.getId());
        log.info("Site: thêm job {} vào hàng đợi ({} video, folder {})", job.getId(), videos.size(), job.getFolderName());
        return ResponseEntity.ok(Map.of("success", true, "jobId", job.getId(), "total", videos.size()));
    }

    /** Tải lại các video lỗi / chưa tải xong của 1 job → xếp cuối hàng đợi */
    @PostMapping("/retry/{jobId}")
    public ResponseEntity<?> retry(@PathVariable String jobId) {
        try {
            DownloadJob retry = jobService.createRetryJob(jobId, true);
            jobService.enqueuePresetJob(retry.getId());
            return ResponseEntity.ok(Map.of("success", true, "jobId", retry.getId(), "total", retry.getTotalVideos()));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    @Data
    public static class EnqueueRequest {
        private String savePath;
        private String folderName;
        private List<String> lessonIds;
    }
}
