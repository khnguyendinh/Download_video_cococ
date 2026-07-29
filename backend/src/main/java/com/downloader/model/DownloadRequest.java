package com.downloader.model;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class DownloadRequest {

    @NotBlank(message = "Playlist URL không được để trống")
    private String playlistUrl;

    @NotBlank(message = "Tên folder không được để trống")
    private String folderName;

    @NotBlank(message = "Đường dẫn lưu không được để trống")
    private String savePath;
}
