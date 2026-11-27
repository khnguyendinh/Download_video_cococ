# 🎬 YouTube Playlist Downloader & Uploader

> Ứng dụng web tải nhiều playlist YouTube xuống máy tính và upload video lên YouTube — chạy hoàn toàn trên máy local.

---

## 📋 Mục lục

1. [Yêu cầu hệ thống](#-yêu-cầu-hệ-thống)
2. [Cài đặt lần đầu](#-cài-đặt-lần-đầu)
3. [Khởi động ứng dụng](#-khởi-động-ứng-dụng)
4. [Chạy trên máy khác](#-chạy-trên-máy-khác)
5. [Chạy bằng Docker](#-chạy-bằng-docker)
6. [Hướng dẫn sử dụng](#-hướng-dẫn-sử-dụng)
7. [Cấu trúc thư mục](#-cấu-trúc-thư-mục)
8. [Cấu hình nâng cao](#-cấu-hình-nâng-cao)
9. [Xử lý sự cố](#-xử-lý-sự-cố)

---

## 💻 Yêu cầu hệ thống

| Phần mềm | Phiên bản | Ghi chú |
|----------|-----------|---------|
| **Java (JDK)** | 21 trở lên | https://adoptium.net/ (`pom.xml` đặt `java.version` = 21) |
| **Maven** | 3.8+ | Project không có `mvnw`, phải cài Maven |
| **Node.js** | 18+ | https://nodejs.org/ |
| **yt-dlp** | Mới nhất | Xem hướng dẫn bên dưới |
| **ffmpeg** | Bất kỳ | Để merge video+audio |

### Cài đặt yt-dlp

```powershell
# Cách 1: Dùng winget
winget install yt-dlp

# Cách 2: Tải file .exe về và đặt vào C:\Windows\System32
# https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp.exe
```

Kiểm tra cài thành công:
```powershell
yt-dlp --version
```

### Cài đặt ffmpeg

```powershell
winget install ffmpeg
```

---

## 🛠️ Cài đặt lần đầu

### 1. Cài dependencies Frontend

```powershell
cd frontend
npm install
```

### 2. (Tùy chọn) Cấu hình đường dẫn yt-dlp / ffmpeg

Nếu `yt-dlp` không nằm trong PATH, mở file `backend/src/main/resources/application.yml` và chỉnh:

```yaml
ytdlp:
  path: C:/path/to/yt-dlp.exe
  ffmpeg-path: C:/ffmpeg/bin/ffmpeg.exe
```

---

## 🚀 Khởi động ứng dụng

Mở **2 terminal** chạy song song:

### Terminal 1 — Backend (Spring Boot)

```powershell
cd backend
mvn spring-boot:run
```

> Backend chạy tại: **http://localhost:8080**

### Terminal 2 — Frontend (Angular)

```powershell
cd frontend
npm run start
```

> Frontend chạy tại: **http://localhost:4200** (trình duyệt tự mở)

> ⏱️ Thời gian khởi động: Backend ~5 giây, Frontend ~10 giây.

---

## 🖥️ Chạy trên máy khác

Có 2 cách:

- **Cài trực tiếp trên Windows**: đủ tất cả chức năng. Nên dùng cách này.
- **Chạy bằng Docker**: không phải cài Java/Node/yt-dlp/ffmpeg, nhưng **mất một số chức năng** (xem [Chạy bằng Docker](#-chạy-bằng-docker)).

### Những thứ cần tải / chuẩn bị

| Thứ cần có | Lấy ở đâu | Có trong git không? |
|------------|-----------|---------------------|
| Source code | `git clone https://github.com/khnguyendinh/Download_video_cococ.git` | ✅ |
| Java 21, Maven, Node.js 18+, yt-dlp, ffmpeg | Xem [Yêu cầu hệ thống](#-yêu-cầu-hệ-thống) (Docker thì không cần) | ❌ |
| Google Chrome (hoặc Edge) | https://www.google.com/chrome/ — dùng cho tab **Khoá học** / **Tài liệu** | ❌ |
| File OAuth `client_secret_*.json` | Copy từ máy cũ (`backend/src/main/resources/`) hoặc tạo mới, xem [credentials not found](#-lỗi-credentials-not-found-ở-tab-upload) | ❌ (bị `.gitignore`, là file bí mật) |
| Thư mục `backend/tokens/` | Copy từ máy cũ để khỏi đăng nhập Google lại (không bắt buộc) | ❌ |
| Thư mục `backend/browser-profile/` | Copy từ máy cũ để giữ đăng nhập trang khoá học (không bắt buộc) | ❌ |

> ⚠️ `client_secret_*.json`, `tokens/` và `browser-profile/` chứa thông tin đăng nhập. Chỉ copy qua USB / ổ mạng nội bộ, **không** commit lên git, không gửi qua chat.

> ℹ️ Tên file credentials đang được khai báo cứng trong `application.yml` (`youtube.credentials-file`). Nếu dùng file `client_secret_*.json` khác tên thì phải sửa dòng này cho khớp.

### Các bước (cài trực tiếp trên Windows)

```powershell
git clone https://github.com/khnguyendinh/Download_video_cococ.git
cd Download_video_cococ

# 1. Cài phần mềm
winget install EclipseAdoptium.Temurin.21.JDK
winget install Apache.Maven
winget install OpenJS.NodeJS.LTS
winget install yt-dlp
winget install ffmpeg

# 2. Copy client_secret_*.json vào backend\src\main\resources\

# 3. Cài thư viện frontend
cd frontend
npm install
```

Sau đó chạy theo [Khởi động ứng dụng](#-khởi-động-ứng-dụng).

---

## 🐳 Chạy bằng Docker

> Repo **chưa có sẵn** `Dockerfile` / `docker-compose.yml`. Muốn chạy bằng Docker thì tạo 4 file dưới đây ở thư mục gốc project.

### Cần cài và tải gì

**Trên máy host** (chỉ cần những thứ này):

| Thứ cần có | Ghi chú |
|------------|---------|
| **Docker Desktop** | https://www.docker.com/products/docker-desktop/ — trên Windows bật WSL 2 khi cài (`wsl --install` nếu máy chưa có) |
| **Git** | Để clone source |
| File `client_secret_*.json` | Như phần trên, Docker không tự có file này |

**Bên trong image** (Docker tự tải khi build, không cần cài tay):

| Thành phần | Dùng để |
|------------|---------|
| `maven:3.9-eclipse-temurin-21` | Build backend ra file `.jar` |
| `eclipse-temurin:21-jre` | Chạy backend |
| `ffmpeg` (apt) | Ghép video + audio khi tải, tab Convert |
| `yt-dlp_linux` (GitHub releases) | Tải video YouTube |
| `node:18` | Build frontend Angular |
| `nginx:alpine` | Phục vụ giao diện Angular, chuyển `/api` sang backend |
| Playwright driver | Thư viện Java tự tải khi chạy lần đầu, cần mạng Internet |

### ⚠️ Chức năng KHÔNG chạy được trong Docker

| Chức năng | Lý do | Cách xử lý |
|-----------|-------|------------|
| Nút **chọn file** ở tab Convert | Dùng hộp thoại Windows qua `powershell.exe` (`ConvertController`) | Gõ tay đường dẫn trong container, vd `/downloads/abc.mp3` |
| Tab **Khoá học** / **Tài liệu** (phần cần đăng nhập) | Backend mở cửa sổ Chrome thật (tìm `chrome.exe`) để người dùng tự đăng nhập, container không có màn hình | Chạy các tab này bằng cách cài trực tiếp trên Windows |
| Đường dẫn kiểu `D:\Downloads` | Container chỉ thấy thư mục được mount | Nhập `/downloads/...` thay cho `D:\Downloads\...` |

### File `Dockerfile.backend`

```dockerfile
# ---- Build ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY backend/pom.xml .
RUN mvn -q dependency:go-offline
COPY backend/src ./src
RUN mvn -q package -DskipTests

# ---- Run ----
FROM eclipse-temurin:21-jre
RUN apt-get update \
 && apt-get install -y --no-install-recommends ffmpeg curl ca-certificates \
 && curl -L https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp_linux -o /usr/local/bin/yt-dlp \
 && chmod a+rx /usr/local/bin/yt-dlp \
 && rm -rf /var/lib/apt/lists/*
COPY --from=build /src/target/youtube-playlist-downloader-1.0.0.jar /app/app.jar
# Thư mục làm việc = nơi lưu download-history.json, upload-history.json, tokens/ (mount ra ngoài)
WORKDIR /app/data
ENV JAVA_TOOL_OPTIONS=-Dfile.encoding=UTF-8
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
```

### File `Dockerfile.frontend`

```dockerfile
FROM node:18 AS build
WORKDIR /src
COPY frontend/package*.json ./
RUN npm ci
COPY frontend/ .
RUN npm run build:prod

FROM nginx:alpine
COPY --from=build /src/dist/frontend /usr/share/nginx/html
COPY docker/nginx.conf /etc/nginx/conf.d/default.conf
```

### File `docker/nginx.conf`

```nginx
server {
    listen 80;
    root /usr/share/nginx/html;

    location /api/ {
        proxy_pass http://backend:8080;
        # Tắt buffer để tiến trình realtime (SSE) hiện ngay
        proxy_buffering off;
        proxy_read_timeout 1d;
    }

    location / {
        try_files $uri $uri/ /index.html;
    }
}
```

### File `docker-compose.yml`

```yaml
services:
  backend:
    build: { context: ., dockerfile: Dockerfile.backend }
    ports:
      - "8080:8080"          # cần mở để Google OAuth gọi lại /api/upload/oauth2callback
    volumes:
      - ./docker-data:/app/data                 # lịch sử tải/upload + tokens/
      - D:/Downloads:/downloads                 # thư mục lưu video, đổi theo máy
      - ./secrets/client_secret.json:/app/secrets/client_secret.json:ro
    command: ["--youtube.credentials-file=/app/secrets/client_secret.json"]
    restart: unless-stopped

  frontend:
    build: { context: ., dockerfile: Dockerfile.frontend }
    ports:
      - "4200:80"            # giữ cổng 4200 vì backend redirect cứng về http://localhost:4200/oauth-callback
    depends_on: [backend]
    restart: unless-stopped
```

### Chạy

```powershell
# Đặt file client_secret_*.json vào secrets\client_secret.json (thư mục secrets\ không commit)
docker compose up -d --build
```

Mở **http://localhost:4200**. Ở ô thư mục lưu, nhập `/downloads` (không nhập `D:\Downloads`).

Xem log / dừng:

```powershell
docker compose logs -f backend
docker compose down
```

> 💡 yt-dlp cần cập nhật thường xuyên khi YouTube đổi cách chặn. Build lại image để lấy bản mới: `docker compose build --no-cache backend`.

---

## 📖 Hướng dẫn sử dụng

### Tab Tải xuống (⬇)

Tab này cho phép **tải nhiều playlist YouTube về máy**, chạy tuần tự từng playlist.

#### Bước 1 — Nhập thư mục lưu

Nhập đường dẫn thư mục gốc để lưu file, ví dụ:

```
D:\Downloads
E:\Videos
```

> 📁 Mỗi playlist sẽ tự động tạo một **subfolder riêng** bên trong thư mục này.

#### Bước 2 — Thêm playlist

| Cột | Mô tả |
|-----|-------|
| **YouTube Playlist URL** | Link playlist, ví dụ: `https://www.youtube.com/playlist?list=PL...` |
| **Tên Folder** | Tên subfolder sẽ tạo (có thể để trống, click Preview để auto-fill) |
| **👁 Preview** | Xem trước số video + tự động điền tên folder |

Nhấn **"+ Thêm playlist"** để thêm nhiều dòng.

#### Bước 3 — Bắt đầu tải

Nhấn **"Tải tất cả (X playlist)"** — các playlist sẽ chạy **tuần tự** (1 → 2 → 3...).

#### Theo dõi tiến trình

- 📊 Thanh tiến trình tổng playlist
- 🎬 Video đang tải + tiến trình từng video
- ✅ / ❌ / ⏳ Trạng thái từng video
- 📋 Log realtime từ yt-dlp

#### Các nút thao tác

| Nút | Chức năng |
|-----|-----------|
| **🔄 Tải lại** | Retry playlist bị lỗi (bỏ qua file đã tải) |
| **🗑 Xóa** | Xóa job khỏi danh sách |
| **Retry X video lỗi** | Chỉ tải lại các video bị lỗi |
| **Xóa tất cả & Bắt đầu mới** | Reset về màn hình nhập ban đầu |

> 💡 **Thêm playlist trong khi đang chạy**: Bạn có thể thêm playlist mới vào hàng chờ ngay cả khi đang tải!

---

### Tab YouTube Upload (☁)

Tab này cho phép **upload video từ máy tính lên kênh YouTube** của bạn.

#### Bước 1 — Xác thực Google

1. Chuyển sang tab **"☁ YouTube Upload"**
2. Nhấn **"Đăng nhập Google"** — trình duyệt sẽ mở trang xác thực Google
3. Đăng nhập bằng tài khoản YouTube và cấp quyền
4. Sau khi xác thực, trang tự redirect về ứng dụng

> 🔑 Token được lưu vào `backend/tokens/` — các lần sau không cần đăng nhập lại.

#### Bước 2 — Chọn thư mục video

Nhập đường dẫn thư mục chứa các file `.mp4` cần upload.

#### Bước 3 — Upload

Nhấn **"Bắt đầu Upload"** và theo dõi tiến trình upload từng video.

---

## 📂 Cấu trúc thư mục

```
Download_video_cococ/
├── backend/
│   ├── src/main/java/com/downloader/
│   │   ├── controller/
│   │   │   ├── DownloadController.java    # API tải video
│   │   │   ├── UploadController.java      # API upload YouTube
│   │   │   └── PlaylistController.java    # API lấy info playlist
│   │   ├── service/
│   │   │   ├── YtDlpService.java          # Giao tiếp với yt-dlp
│   │   │   ├── DownloadJobService.java    # Quản lý download jobs
│   │   │   ├── YouTubeAuthService.java    # OAuth2 Google
│   │   │   └── YouTubeUploadService.java  # Upload lên YouTube
│   │   └── model/
│   ├── src/main/resources/
│   │   └── application.yml               # ⚙️ File cấu hình chính
│   ├── download-history.json             # 📜 Lịch sử download
│   └── tokens/                           # 🔑 Token OAuth2 (tự tạo)
│
├── frontend/                             # Angular UI
│   └── src/app/pages/
│       ├── downloader/                   # Trang tải xuống
│       └── upload/                       # Trang YouTube Upload
│
└── README.md
```

---

## ⚙️ Cấu hình nâng cao

File: `backend/src/main/resources/application.yml`

```yaml
# Cổng backend (mặc định 8080)
server:
  port: 8080

# yt-dlp và ffmpeg
ytdlp:
  path: yt-dlp                         # Hoặc đường dẫn tuyệt đối
  ffmpeg-path: ffmpeg                  # Hoặc đường dẫn tuyệt đối

# File lưu lịch sử jobs
downloader:
  history-file: download-history.json

# YouTube API OAuth2
youtube:
  credentials-file: src/main/resources/credentials.json
  tokens-dir: tokens
  redirect-uri: http://localhost:8080/api/upload/oauth2callback
```

---

## 🔧 Xử lý sự cố

### ❌ Lỗi "yt-dlp not found"

```
Nguyên nhân: yt-dlp chưa cài hoặc chưa thêm vào PATH
Cách sửa  : winget install yt-dlp
            Hoặc chỉ định đường dẫn tuyệt đối trong application.yml
```

### ❌ Backend không khởi động (cổng bị chiếm)

```
Cách sửa: Đổi port trong application.yml
  server:
    port: 8090
```

### ❌ Video tải về không có âm thanh

```
Nguyên nhân: ffmpeg chưa cài
Cách sửa  : winget install ffmpeg
```

### ❌ Lỗi "credentials not found" ở tab Upload

```
Nguyên nhân: Thiếu file credentials.json từ Google Cloud Console
Cách sửa:
  1. Vào https://console.cloud.google.com/
  2. Tạo project → Enable YouTube Data API v3
  3. Tạo OAuth 2.0 Client ID (loại "Desktop App")
  4. Tải credentials.json về và đặt vào backend/src/main/resources/
```

### ❌ Token hết hạn / không upload được

```
Cách sửa: Xóa thư mục backend/tokens/ rồi đăng nhập lại Google
```

---

## 🌐 API Reference

| Method | Endpoint | Mô tả |
|--------|----------|-------|
| `POST` | `/api/download/batch` | Tải nhiều playlist tuần tự |
| `GET` | `/api/download/progress/{jobId}` | SSE stream tiến trình realtime |
| `GET` | `/api/download/jobs` | Danh sách tất cả jobs |
| `POST` | `/api/download/cancel/{jobId}` | Hủy job đang chạy |
| `DELETE` | `/api/download/jobs/{jobId}` | Xóa job khỏi lịch sử |
| `GET` | `/api/upload/auth-status` | Kiểm tra trạng thái xác thực |
| `GET` | `/api/upload/auth-url` | Lấy URL đăng nhập Google |
| `POST` | `/api/upload/start` | Bắt đầu upload session |

---

<div align="center">
  Made with ❤️ — Chúc bạn tải video vui vẻ! 🎉
</div>
