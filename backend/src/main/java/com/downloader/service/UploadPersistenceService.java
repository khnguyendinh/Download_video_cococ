package com.downloader.service;

import com.downloader.model.UploadSession;
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
public class UploadPersistenceService {

    @Value("${downloader.upload-history-file:upload-history.json}")
    private String historyFilePath;

    private final ObjectMapper objectMapper;
    private final ReentrantLock fileLock = new ReentrantLock();

    public UploadPersistenceService() {
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
        this.objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        this.objectMapper.configure(
                com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    /**
     * Lưu toàn bộ map sessions ra file JSON
     */
    public void saveAll(Map<String, UploadSession> sessions) {
        fileLock.lock();
        try {
            File file = new File(historyFilePath);
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(file, sessions);
            log.debug("✅ Đã lưu {} upload sessions vào {}", sessions.size(), historyFilePath);
        } catch (IOException e) {
            log.error("❌ Lỗi ghi file lịch sử upload: {}", e.getMessage());
        } finally {
            fileLock.unlock();
        }
    }

    /**
     * Đọc tất cả sessions từ file JSON
     */
    public Map<String, UploadSession> loadAll() {
        fileLock.lock();
        try {
            File file = new File(historyFilePath);
            if (!file.exists() || file.length() == 0) {
                log.info("📄 Chưa có file lịch sử upload ({}), bắt đầu mới.", historyFilePath);
                return new LinkedHashMap<>();
            }
            Map<String, UploadSession> loaded = objectMapper.readValue(
                    file, new TypeReference<LinkedHashMap<String, UploadSession>>() {});
            log.info("📂 Đã load {} upload sessions từ lịch sử ({})", loaded.size(), historyFilePath);
            return loaded;
        } catch (IOException e) {
            log.error("❌ Lỗi đọc file lịch sử upload: {} — bắt đầu với danh sách rỗng.", e.getMessage());
            return new LinkedHashMap<>();
        } finally {
            fileLock.unlock();
        }
    }

    /**
     * Xóa 1 session rồi ghi lại file
     */
    public void deleteSession(String sessionId, Map<String, UploadSession> sessions) {
        sessions.remove(sessionId);
        saveAll(sessions);
        log.info("🗑️ Đã xóa upload session {} khỏi lịch sử", sessionId);
    }
}
