package com.downloader.controller;

import com.downloader.model.VideoInfo;
import com.downloader.service.YtDlpService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/playlist")
@RequiredArgsConstructor
public class PlaylistController {

    private final YtDlpService ytDlpService;

    /**
     * Lấy thông tin playlist trước khi tải
     */
    @GetMapping("/info")
    public ResponseEntity<?> getPlaylistInfo(@RequestParam String url) {
        try {
            log.info("Fetching playlist info for: {}", url);
            YtDlpService.PlaylistResult result = ytDlpService.getPlaylistInfo(url);
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "playlistTitle", result.playlistTitle(),
                    "total", result.videos().size(),
                    "videos", result.videos()
            ));
        } catch (Exception e) {
            log.error("Error fetching playlist info", e);
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", "Không thể lấy thông tin playlist: " + e.getMessage()
            ));
        }
    }
}
