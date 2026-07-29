package com.downloader.service;

import com.downloader.model.DownloadJob;
import com.downloader.model.DownloadRequest;
import com.downloader.model.VideoInfo;
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

@Slf4j
@Service
@RequiredArgsConstructor
public class DownloadJobService {

    private final YtDlpService ytDlpService;

    // In-memory store cho các jobs
    private final Map<String, DownloadJob> jobs = new ConcurrentHashMap<>();

    // SSE emitters cho từng job
    private final Map<String, List<SseEmitter>> jobEmitters = new ConcurrentHashMap<>();

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
        return job;
    }

    /**
     * Thực thi download job (chạy async)
     */
    @Async
    public void executeJob(String jobId) {
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

            List<VideoInfo> videos = ytDlpService.getPlaylistInfo(job.getPlaylistUrl());
            if (videos.isEmpty()) {
                job.setStatus(DownloadJob.JobStatus.FAILED);
                job.addLog("❌ Không tìm thấy video nào trong playlist!");
                sendSseEvent(jobId, "status", buildStatusEvent(job));
                sendSseEvent(jobId, "done", Map.of("success", false, "message", "Playlist trống hoặc URL không hợp lệ"));
                return;
            }

            job.getVideos().addAll(videos);
            job.setTotalVideos(videos.size());
            job.addLog("✅ Tìm thấy " + videos.size() + " video");
            sendSseEvent(jobId, "playlist_info", Map.of("videos", videos, "total", videos.size()));

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
                    video.setStatus(VideoInfo.VideoStatus.COMPLETED);
                    job.setCompletedVideos(job.getCompletedVideos() + 1);
                    job.addLog("✅ Hoàn thành: " + video.getTitle());

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

        } catch (Exception e) {
            log.error("Fatal error in job {}", jobId, e);
            job.setStatus(DownloadJob.JobStatus.FAILED);
            job.setFinishedAt(LocalDateTime.now());
            job.addLog("💥 Lỗi nghiêm trọng: " + e.getMessage());
            sendSseEvent(jobId, "status", buildStatusEvent(job));
            sendSseEvent(jobId, "done", Map.of("success", false, "message", e.getMessage()));
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
            job.addLog("⛔ Đang hủy job...");
            return true;
        }
        return false;
    }
}
