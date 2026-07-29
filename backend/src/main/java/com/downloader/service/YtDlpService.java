package com.downloader.service;

import com.downloader.model.DownloadJob;
import com.downloader.model.VideoInfo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
public class YtDlpService {

    @Value("${ytdlp.path:yt-dlp}")
    private String ytDlpPath;

    @Value("${ytdlp.ffmpeg-path:}")
    private String ffmpegPath;

    /**
     * Lấy thông tin playlist (danh sách video)
     */
    public List<VideoInfo> getPlaylistInfo(String playlistUrl) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(ytDlpPath);
        command.add("--flat-playlist");
        command.add("--print");
        command.add("%(id)s|%(title)s|%(duration_string)s|%(webpage_url)s");
        command.add("--no-warnings");
        command.add(playlistUrl);

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        setUtf8Env(pb);
        Process process = pb.start();

        List<VideoInfo> videos = new ArrayList<>();
        int index = 1;

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                String[] parts = line.split("\\|", 4);
                if (parts.length >= 2) {
                    VideoInfo video = VideoInfo.builder()
                            .id(parts.length > 0 ? parts[0] : "")
                            .title(parts.length > 1 ? parts[1] : "Unknown")
                            .duration(parts.length > 2 ? parts[2] : "")
                            .url(parts.length > 3 ? parts[3] : "")
                            .index(index++)
                            .status(VideoInfo.VideoStatus.PENDING)
                            .progress(0)
                            .build();
                    videos.add(video);
                }
            }
        }

        int exitCode = process.waitFor();
        log.info("yt-dlp playlist info exit code: {}, found {} videos", exitCode, videos.size());

        return videos;
    }

    /**
     * Tải một video cụ thể
     */
    public void downloadVideo(VideoInfo video, String outputDir, DownloadJob job,
                               ProgressCallback callback) throws Exception {
        // Sanitize output dir
        Path outputPath = Paths.get(outputDir);
        Files.createDirectories(outputPath);

        List<String> command = buildDownloadCommand(video.getUrl(), outputDir);

        log.info("Downloading video: {} -> {}", video.getTitle(), outputDir);
        job.addLog("⬇ Bắt đầu tải: " + video.getTitle());

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(outputPath.toFile());
        pb.redirectErrorStream(true);
        setUtf8Env(pb);

        Process process = pb.start();

        // Pattern để parse progress từ yt-dlp output
        // Ví dụ: [download]  45.3% of 150.00MiB at 2.50MiB/s ETA 00:45
        Pattern progressPattern = Pattern.compile(
                "\\[download\\]\\s+(\\d+\\.\\d+)%\\s+of\\s+([\\d.]+\\w+).*ETA\\s+([\\d:]+)"
        );
        // Pattern lấy tên file đã lưu
        Pattern destPattern = Pattern.compile(
                "\\[download\\]\\s+Destination:\\s+(.+)$"
        );
        Pattern mergePattern = Pattern.compile(
                "\\[Merger\\]\\s+Merging formats into\\s+\"(.+)\"$"
        );
        Pattern alreadyPattern = Pattern.compile(
                "\\[download\\]\\s+(.+) has already been downloaded"
        );

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                log.debug("yt-dlp: {}", line);

                // Parse progress
                Matcher progressMatcher = progressPattern.matcher(line);
                if (progressMatcher.find()) {
                    double pct = Double.parseDouble(progressMatcher.group(1));
                    String size = progressMatcher.group(2);
                    String eta = progressMatcher.group(3);
                    video.setProgress(pct);
                    callback.onProgress(pct, size, eta);
                    continue;
                }

                // Parse destination file
                Matcher destMatcher = destPattern.matcher(line);
                if (destMatcher.find()) {
                    String filename = Paths.get(destMatcher.group(1).trim()).getFileName().toString();
                    video.setSavedFilename(filename);
                    job.addLog("💾 File: " + filename);
                    continue;
                }

                // Parse merge
                Matcher mergeMatcher = mergePattern.matcher(line);
                if (mergeMatcher.find()) {
                    String filename = Paths.get(mergeMatcher.group(1).trim()).getFileName().toString();
                    video.setSavedFilename(filename);
                    job.addLog("🔀 Đang ghép video+audio: " + filename);
                    continue;
                }

                // Already downloaded
                Matcher alreadyMatcher = alreadyPattern.matcher(line);
                if (alreadyMatcher.find()) {
                    video.setProgress(100);
                    job.addLog("✅ Đã có sẵn: " + video.getTitle());
                    callback.onProgress(100, "", "");
                    continue;
                }

                // Log các dòng quan trọng khác
                if (line.startsWith("[") || line.contains("ERROR") || line.contains("WARNING")) {
                    job.addLog(line);
                }
            }
        }

        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new RuntimeException("yt-dlp exited with code " + exitCode);
        }
    }

    /**
     * Build lệnh tải video với quality 720p
     */
    private List<String> buildDownloadCommand(String videoUrl, String outputDir) {
        List<String> command = new ArrayList<>();
        command.add(ytDlpPath);

        // Format: ưu tiên 720p mp4, fallback best ≤ 720p
        command.add("-f");
        command.add("bestvideo[height<=720][ext=mp4]+bestaudio[ext=m4a]/bestvideo[height<=720]+bestaudio/best[height<=720]/best");

        // Merge thành mp4
        command.add("--merge-output-format");
        command.add("mp4");

        // Tên file = tên video YouTube (sanitize tự động bởi yt-dlp)
        command.add("-o");
        command.add(outputDir + File.separator + "%(title)s.%(ext)s");

        // ffmpeg path nếu có
        if (ffmpegPath != null && !ffmpegPath.isBlank()) {
            command.add("--ffmpeg-location");
            command.add(ffmpegPath);
        }

        // Tránh lỗi encoding trên Windows
        command.add("--encoding");
        command.add("utf-8");

        // Retry
        command.add("--retries");
        command.add("3");

        // No playlist (tải từng video riêng)
        command.add("--no-playlist");

        // Ghi đè nếu đã tồn tại (bỏ comment nếu muốn skip)
        // command.add("--no-overwrites");

        command.add("--progress");
        command.add("--newline");

        command.add(videoUrl);

        return command;
    }

    @FunctionalInterface
    public interface ProgressCallback {
        void onProgress(double percent, String size, String eta);
    }

    /**
     * Set env vars để yt-dlp xuất UTF-8 trên Windows
     */
    private void setUtf8Env(ProcessBuilder pb) {
        Map<String, String> env = pb.environment();
        // Force Python (yt-dlp) xuất UTF-8
        env.put("PYTHONIOENCODING", "utf-8");
        env.put("PYTHONUTF8", "1");
        // Windows: tránh dùng legacy code page
        env.put("PYTHONLEGACYWINDOWSSTDIO", "0");
    }
}
