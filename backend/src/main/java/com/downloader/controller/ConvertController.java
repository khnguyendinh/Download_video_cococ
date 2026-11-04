package com.downloader.controller;

import com.downloader.model.ConvertJob;
import com.downloader.service.AudioToVideoService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.MediaType;
import org.springframework.http.MediaTypeFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Convert audio + ảnh nền → MP4:
 *   GET  /api/convert/pick?type=audio|image — mở hộp thoại chọn file của Windows, trả về đường dẫn
 *   POST /api/convert/start                 — bắt đầu convert { audioPaths, imagePath }
 *   GET  /api/convert/status/{id}           — trạng thái + phần trăm
 *   POST /api/convert/cancel/{id}           — huỷ
 */
@Slf4j
@RestController
@RequestMapping("/api/convert")
@RequiredArgsConstructor
public class ConvertController {

    static final String AUDIO_FILTER =
            "File audio|*.mp3;*.m4a;*.aac;*.wav;*.flac;*.ogg;*.opus;*.wma;*.aiff;*.aif;*.amr;*.ac3|Tất cả file|*.*";
    static final String IMAGE_FILTER =
            "Ảnh|*.jpg;*.jpeg;*.png;*.bmp;*.webp;*.gif;*.tif;*.tiff|Tất cả file|*.*";

    private final AudioToVideoService convertService;

    /** Thư mục mở lần trước, để lần sau hộp thoại mở đúng chỗ. */
    private volatile String lastDir = "";

    /** Hộp thoại đang mở; lần bấm mới sẽ đóng nó chứ không xếp hàng chờ. */
    private Process activeDialog;

    @GetMapping("/pick")
    public ResponseEntity<?> pick(@RequestParam(defaultValue = "audio") String type) {
        boolean audio = !"image".equals(type);
        log.info("Mở hộp thoại chọn file: {}", audio ? "audio" : "image");
        try {
            List<String> files = openFileDialog(
                    audio ? "Chọn file audio (chọn được nhiều file)" : "Chọn ảnh nền",
                    audio ? AUDIO_FILTER : IMAGE_FILTER,
                    audio);
            if (!files.isEmpty()) {
                File parent = new File(files.get(0)).getParentFile();
                if (parent != null) lastDir = parent.getAbsolutePath();
            }
            return ResponseEntity.ok(Map.of("files", files));
        } catch (Exception e) {
            log.error("Không mở được hộp thoại chọn file", e);
            return ResponseEntity.internalServerError().body(Map.of("error", "Không mở được hộp thoại chọn file: " + e.getMessage()));
        }
    }

    /** Xem trước ảnh nền đã chọn (chỉ phục vụ file ảnh). */
    @GetMapping("/preview")
    public ResponseEntity<?> preview(@RequestParam String path) {
        File f = new File(path);
        String name = f.getName().toLowerCase();
        if (!f.isFile() || !name.matches(".*\\.(jpe?g|png|bmp|webp|gif)$")) {
            return ResponseEntity.notFound().build();
        }
        MediaType type = MediaTypeFactory.getMediaType(name).orElse(MediaType.APPLICATION_OCTET_STREAM);
        return ResponseEntity.ok().contentType(type).body(new FileSystemResource(f));
    }

    @PostMapping("/start")
    public ResponseEntity<?> start(@RequestBody StartRequest req) {
        if (req.getAudioPaths() == null || req.getAudioPaths().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Chưa chọn file audio"));
        }
        if (req.getImagePath() == null || !new File(req.getImagePath()).isFile()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Ảnh nền không tồn tại"));
        }
        for (String p : req.getAudioPaths()) {
            if (!new File(p).isFile()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Không tìm thấy file: " + p));
            }
        }
        return ResponseEntity.ok(convertService.start(req.getAudioPaths(), req.getImagePath()));
    }

    @GetMapping("/status/{id}")
    public ResponseEntity<?> status(@PathVariable String id) {
        ConvertJob job = convertService.get(id);
        return job == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(job);
    }

    @PostMapping("/cancel/{id}")
    public ResponseEntity<?> cancel(@PathVariable String id) {
        convertService.cancel(id);
        return ResponseEntity.ok(Map.of("cancelled", true));
    }

    // ─────────────────────────────────────────────────────

    /**
     * Mở OpenFileDialog qua PowerShell (WinForms) — trình duyệt không cho biết đường dẫn thật
     * của file được chọn, mà file mp4 phải xuất ra đúng thư mục chứa audio.
     */
    private List<String> openFileDialog(String title, String filter, boolean multi) throws Exception {
        String script = String.join("\n",
                "[Console]::OutputEncoding = [System.Text.Encoding]::UTF8",
                "$ProgressPreference = 'SilentlyContinue'",
                "Add-Type -AssemblyName System.Windows.Forms",
                // Process chạy nền không được tự lấy focus nên hộp thoại hay nằm sau trình duyệt → ép lên trên
                "Add-Type -TypeDefinition @'",
                "using System;",
                "using System.Runtime.InteropServices;",
                "public class DialogFront {",
                "  [DllImport(\"user32.dll\", CharSet = CharSet.Unicode)] public static extern IntPtr FindWindow(string c, string t);",
                "  [DllImport(\"user32.dll\")] static extern IntPtr GetForegroundWindow();",
                "  [DllImport(\"user32.dll\")] static extern uint GetWindowThreadProcessId(IntPtr h, IntPtr pid);",
                "  [DllImport(\"user32.dll\")] static extern bool AttachThreadInput(uint a, uint b, bool attach);",
                "  [DllImport(\"user32.dll\")] static extern bool SetForegroundWindow(IntPtr h);",
                "  [DllImport(\"user32.dll\")] static extern bool SetWindowPos(IntPtr h, IntPtr after, int x, int y, int w, int ht, uint flags);",
                "  [DllImport(\"user32.dll\")] static extern void keybd_event(byte k, byte s, uint f, UIntPtr e);",
                "  [DllImport(\"kernel32.dll\")] static extern uint GetCurrentThreadId();",
                "  public static void Force(IntPtr h) {",
                "    SetWindowPos(h, new IntPtr(-1), 0, 0, 0, 0, 0x43);",
                "    uint me = GetCurrentThreadId();",
                "    uint fg = GetWindowThreadProcessId(GetForegroundWindow(), IntPtr.Zero);",
                "    bool attached = fg != 0 && fg != me && AttachThreadInput(me, fg, true);",
                "    SetForegroundWindow(h);",
                "    if (attached) AttachThreadInput(me, fg, false);",
                "    if (GetForegroundWindow() != h) {",
                "      keybd_event(0x12, 0, 0, UIntPtr.Zero);",
                "      keybd_event(0x12, 0, 2, UIntPtr.Zero);",
                "      SetForegroundWindow(h);",
                "    }",
                "  }",
                "}",
                "'@",
                "$owner = New-Object System.Windows.Forms.Form",
                "$owner.TopMost = $true",
                "$d = New-Object System.Windows.Forms.OpenFileDialog",
                "$d.Title = " + psQuote(title),
                "$d.Filter = " + psQuote(filter),
                "$d.Multiselect = $" + multi,
                lastDir.isBlank() ? "" : "$d.InitialDirectory = " + psQuote(lastDir),
                "$t = New-Object System.Windows.Forms.Timer",
                "$t.Interval = 30",
                "$t.add_Tick({ $h = [DialogFront]::FindWindow('#32770', $d.Title); if ($h -ne [IntPtr]::Zero) { $t.Stop(); [DialogFront]::Force($h) } })",
                "$t.Start()",
                "if ($d.ShowDialog($owner) -eq [System.Windows.Forms.DialogResult]::OK) { $d.FileNames | ForEach-Object { [Console]::WriteLine($_) } }",
                "$owner.Dispose()");
        String encoded = Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE));

        Process p = new ProcessBuilder("powershell.exe", "-NoProfile", "-STA", "-NonInteractive",
                "-ExecutionPolicy", "Bypass", "-EncodedCommand", encoded)
                .redirectErrorStream(true)
                .start();
        Process previous;
        synchronized (this) {
            previous = activeDialog;
            activeDialog = p;
        }
        if (previous != null) previous.destroyForcibly();

        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exit = p.waitFor();
        synchronized (this) {
            // Bị lần bấm mới đóng → coi như chưa chọn gì
            if (activeDialog != p) return List.of();
            activeDialog = null;
        }

        List<String> files = new ArrayList<>();
        for (String line : out.split("\\r?\\n")) {
            String s = line.trim().replace("﻿", "");
            if (s.isEmpty()) continue;
            if (new File(s).isFile()) files.add(s);
            else log.warn("File dialog output: {}", s);
        }
        if (exit != 0 && files.isEmpty()) {
            throw new IllegalStateException("PowerShell exit " + exit);
        }
        return files;
    }

    private static String psQuote(String s) {
        return "'" + s.replace("'", "''") + "'";
    }

    @Data
    public static class StartRequest {
        private List<String> audioPaths;
        private String imagePath;
    }
}
