package com.downloader.service;

import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.googleapis.auth.oauth2.*;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.client.util.store.FileDataStoreFactory;
import com.google.api.services.youtube.YouTube;
import com.google.api.services.youtube.YouTubeScopes;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.FileReader;
import java.util.Arrays;
import java.util.List;

/**
 * Quản lý OAuth2 với Google / YouTube Data API.
 * Lần đầu: sinh URL → user đăng nhập browser → callback lưu token.
 * Các lần sau: tự load token từ disk.
 */
@Slf4j
@Service
public class YouTubeAuthService {

    private static final String APPLICATION_NAME = "YouTube Playlist Uploader";
    private static final GsonFactory JSON_FACTORY = GsonFactory.getDefaultInstance();
    private static final List<String> SCOPES = Arrays.asList(
            YouTubeScopes.YOUTUBE_UPLOAD,
            YouTubeScopes.YOUTUBE
    );

    @Value("${youtube.credentials-file:credentials.json}")
    private String credentialsFile;

    @Value("${youtube.tokens-dir:tokens}")
    private String tokensDir;

    @Value("${youtube.redirect-uri:http://localhost:8080/api/upload/oauth2callback}")
    private String redirectUri;

    private GoogleAuthorizationCodeFlow flow;
    private boolean credentialsAvailable = false;

    @PostConstruct
    public void init() {
        try {
            File credFile = new File(credentialsFile);
            if (!credFile.exists()) {
                log.warn("[YouTube] credentials.json không tìm thấy tại: {}", credFile.getAbsolutePath());
                log.warn("[YouTube] Hãy tạo OAuth2 credentials tại Google Cloud Console và đặt file vào thư mục backend/");
                return;
            }
            GoogleClientSecrets clientSecrets = GoogleClientSecrets.load(
                    JSON_FACTORY, new FileReader(credFile));

            flow = new GoogleAuthorizationCodeFlow.Builder(
                    GoogleNetHttpTransport.newTrustedTransport(),
                    JSON_FACTORY,
                    clientSecrets,
                    SCOPES
            )
            .setDataStoreFactory(new FileDataStoreFactory(new File(tokensDir)))
            .setAccessType("offline")
            .build();

            credentialsAvailable = true;
            log.info("[YouTube] OAuth2 khởi tạo thành công");
        } catch (Exception e) {
            log.error("[YouTube] Lỗi khởi tạo OAuth2", e);
        }
    }

    /** credentials.json có tồn tại không */
    public boolean isCredentialsAvailable() {
        return credentialsAvailable;
    }

    /** Token hợp lệ (đã đăng nhập và chưa hết hạn) */
    public boolean isAuthenticated() {
        if (!credentialsAvailable || flow == null) return false;
        try {
            Credential cred = flow.loadCredential("user");
            if (cred == null) return false;
            Long expiry = cred.getExpiresInSeconds();
            if (expiry != null && expiry > 60) return true;
            // Thử refresh token
            return cred.refreshToken();
        } catch (Exception e) {
            log.warn("[YouTube] Lỗi kiểm tra auth: {}", e.getMessage());
            return false;
        }
    }

    /** Sinh URL đăng nhập Google OAuth2 */
    public String getAuthUrl() {
        if (flow == null) throw new IllegalStateException("Chưa có credentials.json");
        return flow.newAuthorizationUrl()
                .setRedirectUri(redirectUri)
                .build();
    }

    /** Xử lý callback sau khi user đồng ý quyền, lưu token */
    public void handleCallback(String code) throws Exception {
        if (flow == null) throw new IllegalStateException("Chưa có credentials.json");
        GoogleTokenResponse tokenResponse = flow.newTokenRequest(code)
                .setRedirectUri(redirectUri)
                .execute();
        flow.createAndStoreCredential(tokenResponse, "user");
        log.info("[YouTube] Token lưu thành công");
    }

    /** Lấy YouTube client đã xác thực */
    public YouTube getYouTubeClient() throws Exception {
        if (flow == null) throw new IllegalStateException("Chưa có credentials.json");
        Credential cred = flow.loadCredential("user");
        if (cred == null) throw new IllegalStateException("Chưa xác thực. Vui lòng đăng nhập Google.");
        // Refresh nếu sắp hết hạn
        Long expiry = cred.getExpiresInSeconds();
        if (expiry != null && expiry <= 60) {
            cred.refreshToken();
        }
        return new YouTube.Builder(
                GoogleNetHttpTransport.newTrustedTransport(),
                JSON_FACTORY,
                cred
        ).setApplicationName(APPLICATION_NAME).build();
    }
}
