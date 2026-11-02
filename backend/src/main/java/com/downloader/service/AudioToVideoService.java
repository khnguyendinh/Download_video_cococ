package com.downloader.service;

import com.downloader.model.ConvertJob;
import com.downloader.model.ConvertJob.Item;
import com.downloader.model.ConvertJob.ItemStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Convert audio (mp3, m4a, wav, flac...) + 1 ảnh nền → video .mp4 bằng ffmpeg.
 * File .mp4 được đặt cùng thư mục, cùng tên với file audio.
 * Các file được convert tuần tự; tiến trình đọc từ "-progress pipe:1" của ffmpeg.
 */
@Slf4j
@Service
public class AudioToVideoService {

    private static final Pattern DURATION = Pattern.compile("Duration:\\s*(\\d+):(\\d{2}):(\\d{2}(?:\\.\\d+)?)");
    private static final Pattern AUDIO_AAC = Pattern.compile("Audio:\\s*aac\\b");

    @Value("${ytdlp.ffmpeg-path:}")
    private String ffmpegPathConfig;

    private final Map<String, ConvertJob> jobs = new ConcurrentHashMap<>();
    private final Map<String, Process> runningProcess = new ConcurrentHashMap<>();
    private final Set<String> cancelRequested = ConcurrentHashMap.newKeySet();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "audio-to-video");
        t.setDaemon(true);
        return t;
    });

    public ConvertJob start(List<String> audioPaths, String imagePath) {
        ConvertJob job = new ConvertJob();
        job.setId(UUID.randomUUID().toString());
        job.setImagePath(imagePath);
        for (String p : audioPaths) {
            File audio = new File(p);
            Item item = new Item();
            item.setAudioPath(audio.getAbsolutePath());
            item.setAudioName(audio.getName());
            item.setOutputPath(outputFor(audio).getAbsolutePath());
            job.getItems().add(item);
        }
        jobs.put(job.getId(), job);
        executor.submit(() -> runJob(job));
        return job;
    }

    public ConvertJob get(String id) {
        return jobs.get(id);
    }

    public void cancel(String id) {
        ConvertJob job = jobs.get(id);
        if (job == null || job.getStatus() != ConvertJob.Status.RUNNING) return;
        cancelRequested.add(id);
        Process p = runningProcess.get(id);
        if (p != null) p.destroyForcibly();
    }

    @PreDestroy
    void shutdown() {
        runningProcess.values().forEach(Process::destroyForcibly);
        executor.shutdownNow();
    }

    // ─────────────────────────────────────────────────────

    private void runJob(ConvertJob job) {
        String ffmpeg = resolveFfmpeg();
        for (Item item : job.getItems()) {
            if (cancelRequested.contains(job.getId())) {
                item.setStatus(ItemStatus.CANCELLED);
                continue;
            }
            try {
                convertOne(job, item, ffmpeg);
            } catch (Exception e) {
                if (cancelRequested.contains(job.getId())) {
                    item.setStatus(ItemStatus.CANCELLED);
                } else {
                    log.warn("Convert lỗi '{}': {}", item.getAudioPath(), e.getMessage());
                    item.setStatus(ItemStatus.ERROR);
                    item.setError(e.getMessage());
                }
            }
        }
        job.setStatus(cancelRequested.remove(job.getId()) ? ConvertJob.Status.CANCELLED : ConvertJob.Status.DONE);
    }

    private void convertOne(ConvertJob job, Item item, String ffmpeg) throws Exception {
        item.setStatus(ItemStatus.CONVERTING);
        item.setProgress(0);

        File audio = new File(item.getAudioPath());
        if (!audio.isFile()) throw new IllegalStateException("Không tìm thấy file audio");
        if (!new File(job.getImagePath()).isFile()) throw new IllegalStateException("Không tìm thấy ảnh nền");

        // 1. Đọc thông tin audio (thời lượng, codec)
        String probe = probe(ffmpeg, audio);
        double duration = parseDuration(probe);
        boolean isAac = AUDIO_AAC.matcher(probe).find();
        item.setDurationSec(duration);

        // 2. Ghi ra file tạm, xong mới đổi tên → không để lại file .mp4 hỏng
        File output = new File(item.getOutputPath());
        File temp = new File(output.getAbsolutePath() + ".part");

        List<String> cmd = new ArrayList<>(List.of(
                ffmpeg, "-hide_banner", "-y",
                "-loop", "1", "-framerate", "1", "-i", job.getImagePath(),
                "-i", audio.getAbsolutePath(),
                "-map", "0:v:0", "-map", "1:a:0",
                "-vf", "scale=1920:1080:force_original_aspect_ratio=decrease:out_range=tv,"
                        + "pad=1920:1080:(ow-iw)/2:(oh-ih)/2:color=black,setsar=1,format=yuv420p",
                "-c:v", "libx264", "-preset", "veryfast", "-tune", "stillimage", "-r", "1", "-g", "10"
        ));
        if (isAac) {
            cmd.addAll(List.of("-c:a", "copy"));
        } else {
            cmd.addAll(List.of("-c:a", "aac", "-b:a", "192k"));
        }
        if (duration > 0) {
            cmd.addAll(List.of("-t", String.format(Locale.ROOT, "%.3f", duration)));
        } else {
            cmd.add("-shortest");
        }
        cmd.addAll(List.of("-movflags", "+faststart", "-progress", "pipe:1", "-nostats",
                "-f", "mp4", temp.getAbsolutePath()));

        log.info("Convert '{}' → '{}'", audio.getName(), output.getName());
        Process process = new ProcessBuilder(cmd).start();
        runningProcess.put(job.getId(), process);

        // stderr: giữ vài dòng cuối để báo lỗi
        Deque<String> errTail = new ArrayDeque<>();
        Thread errReader = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    synchronized (errTail) {
                        errTail.addLast(line);
                        if (errTail.size() > 15) errTail.removeFirst();
                    }
                }
            } catch (Exception ignored) { }
        });
        errReader.setDaemon(true);
        errReader.start();

        // stdout: key=value của -progress
        try (BufferedReader r = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (duration > 0 && (line.startsWith("out_time_us=") || line.startsWith("out_time_ms="))) {
                    try {
                        double sec = Long.parseLong(line.substring(line.indexOf('=') + 1).trim()) / 1_000_000.0;
                        item.setProgress(Math.max(0, Math.min(99.9, sec / duration * 100)));
                    } catch (NumberFormatException ignored) { }
                }
            }
        }
        int exit = process.waitFor();
        errReader.join(2000);
        runningProcess.remove(job.getId());

        if (exit != 0) {
            Files.deleteIfExists(temp.toPath());
            if (cancelRequested.contains(job.getId())) throw new InterruptedException("Đã huỷ");
            String tail;
            synchronized (errTail) { tail = String.join("\n", errTail); }
            throw new IllegalStateException("ffmpeg lỗi (exit " + exit + "): " + lastMeaningfulLine(tail));
        }

        Files.move(temp.toPath(), output.toPath(), StandardCopyOption.REPLACE_EXISTING);
        item.setProgress(100);
        item.setStatus(ItemStatus.DONE);
        log.info("Convert xong: {}", output.getAbsolutePath());
    }

    /** ffmpeg -i <file> không có output → exit 1 nhưng vẫn in thông tin stream ra stderr. */
    private String probe(String ffmpeg, File audio) throws Exception {
        Process p = new ProcessBuilder(ffmpeg, "-hide_banner", "-i", audio.getAbsolutePath())
                .redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        p.waitFor();
        return out;
    }

    private double parseDuration(String probe) {
        Matcher m = DURATION.matcher(probe);
        if (!m.find()) return 0;
        return Integer.parseInt(m.group(1)) * 3600
                + Integer.parseInt(m.group(2)) * 60
                + Double.parseDouble(m.group(3));
    }

    private String lastMeaningfulLine(String tail) {
        String[] lines = tail.split("\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            String l = lines[i].trim();
            if (!l.isEmpty() && !l.startsWith("Conversion failed")) return l;
        }
        return tail;
    }

    private File outputFor(File audio) {
        String name = audio.getName();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        return new File(audio.getParentFile(), base + ".mp4");
    }

    /** Ưu tiên: cấu hình ytdlp.ffmpeg-path → ffmpeg.exe cạnh backend → ffmpeg trong PATH. */
    String resolveFfmpeg() {
        if (ffmpegPathConfig != null && !ffmpegPathConfig.isBlank()) {
            File f = new File(ffmpegPathConfig);
            if (f.isDirectory()) f = new File(f, "ffmpeg.exe");
            return f.getAbsolutePath();
        }
        File local = new File("ffmpeg.exe");
        if (local.isFile()) return local.getAbsolutePath();
        return "ffmpeg";
    }
}
