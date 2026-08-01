package com.downloader.service;

import com.downloader.model.DownloadJob;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

@Slf4j
@Service
public class JobPersistenceService {

    @Value("${downloader.history-file:download-history.json}")
    private String historyFilePath;

    private final ObjectMapper objectMapper;
    private final ReentrantLock fileLock = new ReentrantLock();

    public JobPersistenceService() {
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
        this.objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        this.objectMapper.configure(
                com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    /**
     * Lưu toàn bộ map jobs ra file JSON
     */
    public void saveAll(Map<String, DownloadJob> jobs) {
        fileLock.lock();
        try {
            File file = new File(historyFilePath);
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(file, jobs);
            log.debug("✅ Đã lưu {} jobs vào {}", jobs.size(), historyFilePath);
        } catch (IOException e) {
            log.error("❌ Lỗi ghi file lịch sử: {}", e.getMessage());
        } finally {
            fileLock.unlock();
        }
    }

    /**
     * Đọc tất cả jobs từ file JSON
     */
    public Map<String, DownloadJob> loadAll() {
        fileLock.lock();
        try {
            File file = new File(historyFilePath);
            if (!file.exists() || file.length() == 0) {
                log.info("📄 Chưa có file lịch sử ({}), bắt đầu mới.", historyFilePath);
                return new LinkedHashMap<>();
            }
            Map<String, DownloadJob> loaded = objectMapper.readValue(
                    file, new TypeReference<LinkedHashMap<String, DownloadJob>>() {});
            log.info("📂 Đã load {} jobs từ lịch sử ({})", loaded.size(), historyFilePath);
            return loaded;
        } catch (IOException e) {
            log.error("❌ Lỗi đọc file lịch sử: {} — bắt đầu với danh sách rỗng.", e.getMessage());
            return new LinkedHashMap<>();
        } finally {
            fileLock.unlock();
        }
    }

    /**
     * Xóa 1 job rồi ghi lại file
     */
    public void deleteJob(String jobId, Map<String, DownloadJob> jobs) {
        jobs.remove(jobId);
        saveAll(jobs);
        log.info("🗑️ Đã xóa job {} khỏi lịch sử", jobId);
    }
}
