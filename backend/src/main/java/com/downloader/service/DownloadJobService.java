package com.downloader.service;

import com.downloader.model.DownloadJob;
import com.downloader.model.DownloadRequest;
import com.downloader.model.VideoInfo;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class DownloadJobService {

    private final YtDlpService ytDlpService;
    private final JobPersistenceService persistenceService;

    // In-memory store cho các jobs (được load từ file JSON khi khởi động)
    private final Map<String, DownloadJob> jobs = new ConcurrentHashMap<>();

    // SSE emitters cho từng job
    private final Map<String, List<SseEmitter>> jobEmitters = new ConcurrentHashMap<>();

    /**
     * Load lịch sử jobs từ file JSON khi khởi động app
     */
    @PostConstruct
    public void loadPersistedJobs() {
        Map<String, DownloadJob> saved = persistenceService.loadAll();
        if (!saved.isEmpty()) {
            // Những job đang ở trạng thái dang dở (PENDING/FETCHING_INFO/DOWNLOADING)
            // khi load lại → đánh dấu là FAILED vì server đã restart
            saved.forEach((id, job) -> {
                if (job.getStatus() == DownloadJob.JobStatus.PENDING
                        || job.getStatus() == DownloadJob.JobStatus.FETCHING_INFO
                        || job.getStatus() == DownloadJob.JobStatus.DOWNLOADING) {
                    job.setStatus(DownloadJob.JobStatus.FAILED);
                    job.setFinishedAt(LocalDateTime.now());
                    job.calculateDuration();
                    job.addLog("⚠️ App restart — job bị gián đoạn");
                }
            });
            jobs.putAll(saved);
            // Ghi lại để cập nhật trạng thái đã sửa
            persistenceService.saveAll(jobs);
            log.info("📂 Đã restore {} job(s) từ lịch sử", saved.size());
        }
    }

    /**
     * Tạo job mới và trả về jobId
     */
    public DownloadJob createJob(DownloadRequest request) {
        String jobId = UUID.randomUUID().toString();
        DownloadJob job = DownloadJob.builder()
                .id(jobId)
                .playlistUrl(request.getPlaylistUrl())
                .folderName(request.getFolderName())
                .savePath(request.getSavePath())
                .status(DownloadJob.JobStatus.PENDING)
                .startedAt(LocalDateTime.now())
                .videos(new CopyOnWriteArrayList<>())
                .logs(new CopyOnWriteArrayList<>())
                .build();

        jobs.put(jobId, job);
        job.addLog("🚀 Job tạo thành công: " + jobId);

        // Lưu ngay khi tạo job
        persistenceService.saveAll(jobs);
        return job;
    }

    /**
     * Thực thi download job (chạy async)
     */
    @Async
    public void executeJob(String jobId) {
        executeJobInternal(jobId);
    }

    /**
     * Thực thi nhiều jobs TUẦN TỰ (lần lượt từng job) trong 1 background thread
     */
    @Async
    public void executeJobsSequentially(List<String> jobIds) {
        log.info("Starting sequential batch: {} jobs", jobIds.size());
        for (String jobId : jobIds) {
            DownloadJob job = jobs.get(jobId);
            if (job == null) continue;
            if (job.getStatus() == DownloadJob.JobStatus.CANCELLED) {
                log.info("Job {} was cancelled, skipping", jobId);
                continue;
            }
            log.info("Sequential batch: executing job {} (folder: {})", jobId, job.getFolderName());
            executeJobInternal(jobId);
            log.info("Sequential batch: job {} finished with status {}", jobId, job.getStatus());
        }
        log.info("Sequential batch finished all {} jobs", jobIds.size());
    }

    /**
     * Tạo retry job từ các video FAILED của job gốc
     */
    public DownloadJob createRetryJob(String originalJobId) {
        DownloadJob original = jobs.get(originalJobId);
        if (original == null) throw new RuntimeException("Job không tồn tại: " + originalJobId);

        List<VideoInfo> failedVideos = original.getVideos().stream()
                .filter(v -> v.getStatus() == VideoInfo.VideoStatus.FAILED)
                .map(v -> VideoInfo.builder()
                        .id(v.getId()).title(v.getTitle()).url(v.getUrl())
                        .duration(v.getDuration()).index(v.getIndex())
                        .status(VideoInfo.VideoStatus.PENDING).progress(0).build())
                .collect(Collectors.toList());

        if (failedVideos.isEmpty()) throw new RuntimeException("Không có video nào bị lỗi để retry");

        String newJobId = UUID.randomUUID().toString();
        DownloadJob retryJob = DownloadJob.builder()
                .id(newJobId)
                .playlistUrl(original.getPlaylistUrl())
                .folderName(original.getFolderName())
                .savePath(original.getSavePath())
                .status(DownloadJob.JobStatus.PENDING)
                .startedAt(LocalDateTime.now())
                .videos(new CopyOnWriteArrayList<>(failedVideos))
                .logs(new CopyOnWriteArrayList<>())
                .totalVideos(failedVideos.size())
                .completedVideos(0)
                .failedVideos(0)
                .build();

        jobs.put(newJobId, retryJob);
        retryJob.addLog("🔄 Retry " + failedVideos.size() + " video lỗi từ [" + original.getFolderName() + "]");
        persistenceService.saveAll(jobs);
        log.info("Created retry job {} for {} failed videos from original job {}", newJobId, failedVideos.size(), originalJobId);
        return retryJob;
    }

    /**
     * Chạy retry jobs tuần tự
     */
    @Async
    public void executeRetryJobsSequentially(List<String> jobIds) {
        log.info("Starting sequential retry batch: {} jobs", jobIds.size());
        for (String jobId : jobIds) {
            DownloadJob job = jobs.get(jobId);
            if (job == null) continue;
            if (job.getStatus() == DownloadJob.JobStatus.CANCELLED) {
                log.info("Retry job {} was cancelled, skipping", jobId);
                continue;
            }
            log.info("Sequential retry: executing job {} (folder: {})", jobId, job.getFolderName());
            executeRetryJobInternal(jobId);
            log.info("Sequential retry: job {} finished with status {}", jobId, job.getStatus());
        }
        log.info("Sequential retry batch finished all {} jobs", jobIds.size());
    }

    /**
     * Logic nội bộ thực sự download — gọi từ cả executeJob và executeJobsSequentially
     */
    private void executeJobInternal(String jobId) {
        DownloadJob job = jobs.get(jobId);
        if (job == null) {
            log.error("Job not found: {}", jobId);
            return;
        }

        try {
            // 1. Fetch playlist info
            job.setStatus(DownloadJob.JobStatus.FETCHING_INFO);
            job.addLog("🔍 Đang lấy thông tin playlist...");
            sendSseEvent(jobId, "status", buildStatusEvent(job));

            YtDlpService.PlaylistResult result = ytDlpService.getPlaylistInfo(job.getPlaylistUrl());
            List<VideoInfo> videos = result.videos();
            if (videos.isEmpty()) {
                job.setStatus(DownloadJob.JobStatus.FAILED);
                job.setFinishedAt(LocalDateTime.now());
                job.calculateDuration();
                job.addLog("❌ Không tìm thấy video nào trong playlist!");
                sendSseEvent(jobId, "status", buildStatusEvent(job));
                sendSseEvent(jobId, "done", Map.of("success", false, "message", "Playlist trống hoặc URL không hợp lệ"));
                persistenceService.saveAll(jobs);
                return;
            }

            String playlistTitle = result.playlistTitle();
            if (playlistTitle != null && !playlistTitle.isBlank()) {
                job.addLog("📋 Playlist: " + playlistTitle);
            }

            job.getVideos().addAll(videos);
            job.setTotalVideos(videos.size());
            job.addLog("✅ Tìm thấy " + videos.size() + " video");
            sendSseEvent(jobId, "playlist_info", Map.of("videos", videos, "total", videos.size(), "playlistTitle", playlistTitle != null ? playlistTitle : ""));


            // 2. Tạo output directory
            String outputDir = Paths.get(job.getSavePath(), job.getFolderName()).toString();
            Path outputPath = Paths.get(outputDir);
            Files.createDirectories(outputPath);
            job.addLog("📁 Thư mục lưu: " + outputDir);

            // 3. Download từng video
            job.setStatus(DownloadJob.JobStatus.DOWNLOADING);
            sendSseEvent(jobId, "status", buildStatusEvent(job));

            for (VideoInfo video : videos) {
                // Kiểm tra nếu job bị hủy
                if (job.getStatus() == DownloadJob.JobStatus.CANCELLED) {
                    job.addLog("⛔ Job bị hủy bởi người dùng");
                    break;
                }

                job.setCurrentVideoTitle(video.getTitle());
                video.setStatus(VideoInfo.VideoStatus.DOWNLOADING);
                job.addLog("⬇ [" + video.getIndex() + "/" + job.getTotalVideos() + "] " + video.getTitle());
                sendSseEvent(jobId, "video_start", Map.of("video", video));

                try {
                    ytDlpService.downloadVideo(video, outputDir, job, (percent, size, eta) -> {
                        video.setProgress(percent);
                        job.setCurrentVideoProgress(percent);

                        Map<String, Object> progressData = new HashMap<>();
                        progressData.put("videoId", video.getId());
                        progressData.put("videoIndex", video.getIndex());
                        progressData.put("percent", percent);
                        progressData.put("size", size);
                        progressData.put("eta", eta);
                        sendSseEvent(jobId, "progress", progressData);
                    });

                    video.setProgress(100);
                    // Nếu yt-dlp skip (file đã có sẵn) thì giữ status SKIPPED, ngược lại COMPLETED
                    if (video.getStatus() != VideoInfo.VideoStatus.SKIPPED) {
                        video.setStatus(VideoInfo.VideoStatus.COMPLETED);
                        job.addLog("✅ Hoàn thành: " + video.getTitle());
                    }
                    job.setCompletedVideos(job.getCompletedVideos() + 1);

                } catch (Exception e) {
                    video.setStatus(VideoInfo.VideoStatus.FAILED);
                    video.setErrorMessage(e.getMessage());
                    job.setFailedVideos(job.getFailedVideos() + 1);
                    job.addLog("❌ Lỗi tải: " + video.getTitle() + " - " + e.getMessage());
                    log.error("Error downloading video: {}", video.getTitle(), e);
                }

                sendSseEvent(jobId, "video_done", Map.of("video", video));
                sendSseEvent(jobId, "status", buildStatusEvent(job));
            }

            // 4. Hoàn thành
            job.setFinishedAt(LocalDateTime.now());
            job.calculateDuration();
            if (job.getStatus() != DownloadJob.JobStatus.CANCELLED) {
                job.setStatus(job.getFailedVideos() == 0
                        ? DownloadJob.JobStatus.COMPLETED
                        : DownloadJob.JobStatus.COMPLETED);
            }

            String summary = String.format("🏁 Hoàn thành! ✅ %d thành công, ❌ %d thất bại",
                    job.getCompletedVideos(), job.getFailedVideos());
            job.addLog(summary);
            sendSseEvent(jobId, "status", buildStatusEvent(job));
            sendSseEvent(jobId, "done", Map.of(
                    "success", true,
                    "message", summary,
                    "completed", job.getCompletedVideos(),
                    "failed", job.getFailedVideos(),
                    "outputDir", outputDir
            ));

            // Lưu sau khi hoàn thành
            persistenceService.saveAll(jobs);

        } catch (Exception e) {
            log.error("Fatal error in job {}", jobId, e);
            job.setStatus(DownloadJob.JobStatus.FAILED);
            job.setFinishedAt(LocalDateTime.now());
            job.calculateDuration();
            job.addLog("💥 Lỗi nghiêm trọng: " + e.getMessage());
            sendSseEvent(jobId, "status", buildStatusEvent(job));
            sendSseEvent(jobId, "done", Map.of("success", false, "message", e.getMessage()));

            // Lưu khi thất bại
            persistenceService.saveAll(jobs);
        } finally {
            // Đóng tất cả SSE emitters sau khi xong
            List<SseEmitter> emitters = jobEmitters.getOrDefault(jobId, new ArrayList<>());
            emitters.forEach(emitter -> {
                try {
                    emitter.complete();
                } catch (Exception ignored) {}
            });
            jobEmitters.remove(jobId);
        }
    }

    /**
     * Retry job — bỏ qua bước fetch playlist, tải thẳng danh sách video đã có
     */
    private void executeRetryJobInternal(String jobId) {
        DownloadJob job = jobs.get(jobId);
        if (job == null) {
            log.error("Retry job not found: {}", jobId);
            return;
        }

        try {
            // Tạo output directory
            String outputDir = Paths.get(job.getSavePath(), job.getFolderName()).toString();
            Path outputPath = Paths.get(outputDir);
            Files.createDirectories(outputPath);
            job.addLog("📁 Thư mục lưu: " + outputDir);

            // Thông báo danh sách video để UI cập nhật
            sendSseEvent(jobId, "playlist_info", Map.of(
                    "videos", job.getVideos(),
                    "total", job.getVideos().size(),
                    "playlistTitle", "[RETRY] " + job.getFolderName()));

            // Bắt đầu download
            job.setStatus(DownloadJob.JobStatus.DOWNLOADING);
            sendSseEvent(jobId, "status", buildStatusEvent(job));

            for (VideoInfo video : job.getVideos()) {
                if (job.getStatus() == DownloadJob.JobStatus.CANCELLED) {
                    job.addLog("⛔ Job bị hủy bởi người dùng");
                    break;
                }

                job.setCurrentVideoTitle(video.getTitle());
                video.setStatus(VideoInfo.VideoStatus.DOWNLOADING);
                job.addLog("⬇ [" + video.getIndex() + "/" + job.getTotalVideos() + "] " + video.getTitle());
                sendSseEvent(jobId, "video_start", Map.of("video", video));

                try {
                    ytDlpService.downloadVideo(video, outputDir, job, (percent, size, eta) -> {
                        video.setProgress(percent);
                        job.setCurrentVideoProgress(percent);
                        Map<String, Object> progressData = new HashMap<>();
                        progressData.put("videoId", video.getId());
                        progressData.put("videoIndex", video.getIndex());
                        progressData.put("percent", percent);
                        progressData.put("size", size);
                        progressData.put("eta", eta);
                        sendSseEvent(jobId, "progress", progressData);
                    });

                    video.setProgress(100);
                    if (video.getStatus() != VideoInfo.VideoStatus.SKIPPED) {
                        video.setStatus(VideoInfo.VideoStatus.COMPLETED);
                        job.addLog("✅ Hoàn thành: " + video.getTitle());
                    }
                    job.setCompletedVideos(job.getCompletedVideos() + 1);

                } catch (Exception e) {
                    video.setStatus(VideoInfo.VideoStatus.FAILED);
                    video.setErrorMessage(e.getMessage());
                    job.setFailedVideos(job.getFailedVideos() + 1);
                    job.addLog("❌ Lỗi tải: " + video.getTitle() + " - " + e.getMessage());
                    log.error("Error retrying video: {}", video.getTitle(), e);
                }

                sendSseEvent(jobId, "video_done", Map.of("video", video));
                sendSseEvent(jobId, "status", buildStatusEvent(job));
            }

            // Hoàn thành
            job.setFinishedAt(LocalDateTime.now());
            job.calculateDuration();
            if (job.getStatus() != DownloadJob.JobStatus.CANCELLED) {
                job.setStatus(DownloadJob.JobStatus.COMPLETED);
            }

            String summary = String.format("🏁 Retry xong! ✅ %d thành công, ❌ %d thất bại",
                    job.getCompletedVideos(), job.getFailedVideos());
            job.addLog(summary);
            sendSseEvent(jobId, "status", buildStatusEvent(job));
            sendSseEvent(jobId, "done", Map.of(
                    "success", true,
                    "message", summary,
                    "completed", job.getCompletedVideos(),
                    "failed", job.getFailedVideos(),
                    "outputDir", outputDir
            ));
            persistenceService.saveAll(jobs);

        } catch (Exception e) {
            log.error("Fatal error in retry job {}", jobId, e);
            job.setStatus(DownloadJob.JobStatus.FAILED);
            job.setFinishedAt(LocalDateTime.now());
            job.calculateDuration();
            job.addLog("💥 Lỗi nghiêm trọng: " + e.getMessage());
            sendSseEvent(jobId, "status", buildStatusEvent(job));
            sendSseEvent(jobId, "done", Map.of("success", false, "message", e.getMessage()));
            persistenceService.saveAll(jobs);
        } finally {
            List<SseEmitter> emitters = jobEmitters.getOrDefault(jobId, new ArrayList<>());
            emitters.forEach(emitter -> {
                try { emitter.complete(); } catch (Exception ignored) {}
            });
            jobEmitters.remove(jobId);
        }
    }

    /**
     * Đăng ký SSE emitter cho job
     */
    public SseEmitter registerEmitter(String jobId) {
        SseEmitter emitter = new SseEmitter(Long.MAX_VALUE);
        jobEmitters.computeIfAbsent(jobId, k -> new CopyOnWriteArrayList<>()).add(emitter);

        emitter.onCompletion(() -> removeEmitter(jobId, emitter));
        emitter.onTimeout(() -> removeEmitter(jobId, emitter));
        emitter.onError(e -> removeEmitter(jobId, emitter));

        // Gửi trạng thái hiện tại ngay khi kết nối
        DownloadJob job = jobs.get(jobId);
        if (job != null) {
            try {
                emitter.send(SseEmitter.event()
                        .name("status")
                        .data(buildStatusEvent(job)));
            } catch (IOException e) {
                log.error("Error sending initial status", e);
            }
        }

        return emitter;
    }

    private void removeEmitter(String jobId, SseEmitter emitter) {
        List<SseEmitter> emitters = jobEmitters.get(jobId);
        if (emitters != null) {
            emitters.remove(emitter);
        }
    }

    /**
     * Gửi SSE event đến tất cả emitters của job
     */
    private void sendSseEvent(String jobId, String eventName, Object data) {
        List<SseEmitter> emitters = jobEmitters.getOrDefault(jobId, new ArrayList<>());
        List<SseEmitter> deadEmitters = new ArrayList<>();

        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event()
                        .name(eventName)
                        .data(data));
            } catch (IOException e) {
                deadEmitters.add(emitter);
            }
        }

        emitters.removeAll(deadEmitters);
    }

    private Map<String, Object> buildStatusEvent(DownloadJob job) {
        Map<String, Object> event = new HashMap<>();
        event.put("jobId", job.getId());
        event.put("status", job.getStatus());
        event.put("totalVideos", job.getTotalVideos());
        event.put("completedVideos", job.getCompletedVideos());
        event.put("failedVideos", job.getFailedVideos());
        event.put("progressPercent", job.getProgressPercent());
        event.put("currentVideoTitle", job.getCurrentVideoTitle());
        event.put("currentVideoProgress", job.getCurrentVideoProgress());
        event.put("logs", job.getLogs());
        return event;
    }

    public DownloadJob getJob(String jobId) {
        return jobs.get(jobId);
    }

    public Collection<DownloadJob> getAllJobs() {
        return jobs.values();
    }

    public boolean cancelJob(String jobId) {
        DownloadJob job = jobs.get(jobId);
        if (job != null && job.getStatus() == DownloadJob.JobStatus.DOWNLOADING) {
            job.setStatus(DownloadJob.JobStatus.CANCELLED);
            job.setFinishedAt(LocalDateTime.now());
            job.calculateDuration();
            job.addLog("⛔ Đang hủy job...");
            persistenceService.saveAll(jobs);
            return true;
        }
        return false;
    }

    /**
     * Xóa job khỏi memory và file JSON
     */
    public boolean deleteJob(String jobId) {
        DownloadJob job = jobs.get(jobId);
        if (job == null) return false;

        // Không cho xóa job đang chạy
        if (job.getStatus() == DownloadJob.JobStatus.DOWNLOADING
                || job.getStatus() == DownloadJob.JobStatus.FETCHING_INFO) {
            return false;
        }

        persistenceService.deleteJob(jobId, jobs);
        log.info("🗑️ Đã xóa job {} (folder: {})", jobId, job.getFolderName());
        return true;
    }
}
