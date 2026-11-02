package com.downloader.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * Một lượt convert: nhiều file audio + 1 ảnh nền → mỗi audio ra 1 file .mp4
 * (cùng tên, cùng thư mục với file audio).
 */
@Data
public class ConvertJob {

    public enum Status { RUNNING, DONE, CANCELLED }

    public enum ItemStatus { PENDING, CONVERTING, DONE, ERROR, CANCELLED }

    @Data
    public static class Item {
        private String audioPath;
        private String audioName;
        private String outputPath;
        private ItemStatus status = ItemStatus.PENDING;
        private double progress;      // 0..100
        private double durationSec;
        private String error;
    }

    private String id;
    private String imagePath;
    private Status status = Status.RUNNING;
    private List<Item> items = new ArrayList<>();

    /** Phần trăm tổng = trung bình phần trăm từng file. */
    public double getOverallProgress() {
        if (items.isEmpty()) return 0;
        double sum = 0;
        for (Item it : items) {
            sum += (it.getStatus() == ItemStatus.DONE || it.getStatus() == ItemStatus.ERROR) ? 100 : it.getProgress();
        }
        return sum / items.size();
    }
}
