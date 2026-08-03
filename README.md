# 🎬 YouTube Playlist Downloader & Uploader

> Ứng dụng web tải nhiều playlist YouTube xuống máy tính và upload video lên YouTube — chạy hoàn toàn trên máy local.

---

## 📋 Mục lục

1. [Yêu cầu hệ thống](#-yêu-cầu-hệ-thống)
2. [Cài đặt lần đầu](#-cài-đặt-lần-đầu)
3. [Khởi động ứng dụng](#-khởi-động-ứng-dụng)
4. [Hướng dẫn sử dụng](#-hướng-dẫn-sử-dụng)
5. [Cấu trúc thư mục](#-cấu-trúc-thư-mục)
6. [Cấu hình nâng cao](#-cấu-hình-nâng-cao)
7. [Xử lý sự cố](#-xử-lý-sự-cố)

---

## 💻 Yêu cầu hệ thống

| Phần mềm | Phiên bản | Ghi chú |
|----------|-----------|---------|
| **Java (JDK)** | 17 trở lên | https://adoptium.net/ |
| **Maven** | 3.8+ | Hoặc dùng `mvnw` có sẵn |
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
