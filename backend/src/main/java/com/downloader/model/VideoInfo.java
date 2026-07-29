package com.downloader.model;

import lombok.Data;
import lombok.Builder;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class VideoInfo {

    private String id;
    private String title;
    private String url;
    private String duration;
    private String thumbnail;
    private int index;
    private VideoStatus status;
    private double progress;
    private String savedFilename;
    private String errorMessage;

    public enum VideoStatus {
        PENDING, DOWNLOADING, COMPLETED, FAILED, SKIPPED
    }
}
