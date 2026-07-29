package com.downloader.model;

import lombok.Data;
import lombok.Builder;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class DownloadJob {

    private String id;
    private String playlistUrl;
    private String folderName;
    private String savePath;
    private JobStatus status;
    private int totalVideos;
    private int completedVideos;
    private int failedVideos;
    private String currentVideoTitle;
    private double currentVideoProgress;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;

    @Builder.Default
    private List<VideoInfo> videos = new CopyOnWriteArrayList<>();

    @Builder.Default
    private List<String> logs = new CopyOnWriteArrayList<>();

    public enum JobStatus {
        PENDING, FETCHING_INFO, DOWNLOADING, COMPLETED, FAILED, CANCELLED
    }

    public void addLog(String message) {
        logs.add("[" + LocalDateTime.now().toString().substring(11, 19) + "] " + message);
        if (logs.size() > 500) {
            logs.remove(0);
        }
    }

    public int getProgressPercent() {
        if (totalVideos == 0) return 0;
        return (int) ((completedVideos * 100.0) / totalVideos);
    }
}
