import { Component, OnDestroy, OnInit } from '@angular/core';
import { Router } from '@angular/router';
import { NzMessageService } from 'ng-zorro-antd/message';
import { ApiService, VideoFileInfo, ScanFolderResponse } from '../../_shared/services/api.service';
import { environment } from '../../../environments/environment';

export interface UploadFileState {
  filePath: string;
  fileName: string;
  status: 'PENDING' | 'UPLOADING' | 'DONE' | 'ERROR';
  progress: number;
  videoId?: string;
  error?: string;
}

export interface SelectableVideo extends VideoFileInfo {
  selected: boolean;
  formattedSize: string;
  previousStatus?: 'SUCCESS' | 'FAILED' | null;
}

@Component({
  selector: 'app-upload',
  templateUrl: './upload.component.html',
  styleUrls: ['./upload.component.scss'],
})
export class UploadComponent implements OnInit, OnDestroy {

  // Expose Math for template
  readonly Math = Math;

  // Auth
  authStatus: 'checking' | 'no-credentials' | 'not-authenticated' | 'redirecting' | 'authenticated' = 'checking';

  // Folder scan
  folderPath = '';
  scannedFiles: SelectableVideo[] = [];
  scanning = false;
  scanError = '';
  hasPreviousUpload = false;
  previousFailedCount = 0;
  previousSuccessCount = 0;

  // Playlist
  playlistId = '';

  // Upload state
  uploadState: 'idle' | 'running' | 'done' = 'idle';
  fileStates: UploadFileState[] = [];
  overallStatus = '';
  sessionId = '';

  // History
  historySessions: any[] = [];

  private eventSource?: EventSource;
  private authMessageListener?: (e: MessageEvent) => void;

  constructor(
    private api: ApiService,
    private message: NzMessageService,
    public router: Router
  ) {}

  ngOnInit(): void {
    this.checkAuth();
    this.listenForAuthCallback();
    this.loadHistory();
  }

  loadHistory(): void {
    this.api.getUploadHistory().subscribe({
      next: (res) => {
        // Sắp xếp lịch sử mới nhất lên đầu
        this.historySessions = res.sort((a, b) => new Date(b.startedAt || b.finishedAt || 0).getTime() - new Date(a.startedAt || a.finishedAt || 0).getTime());
      },
      error: () => {}
    });
  }

  ngOnDestroy(): void {
    this.eventSource?.close();
    if (this.authMessageListener) {
      window.removeEventListener('message', this.authMessageListener);
    }
  }

  /** Lắng nghe thông báo từ tab OAuth khi đăng nhập xong */
  private listenForAuthCallback(): void {
    this.authMessageListener = (event: MessageEvent) => {
      if (event.origin !== window.location.origin) return;
      if (event.data?.type === 'YOUTUBE_AUTH_SUCCESS') {
        this.message.success('✅ Đăng nhập Google thành công!');
        this.checkAuth();
      }
    };
    window.addEventListener('message', this.authMessageListener);
  }

  // ─────────────────────────────────────────────────────
  // Auth
  // ─────────────────────────────────────────────────────

  checkAuth(): void {
    this.authStatus = 'checking';
    this.api.checkUploadAuthStatus().subscribe({
      next: (res: any) => {
        if (!res.credentialsAvailable) {
          this.authStatus = 'no-credentials';
        } else if (res.authenticated) {
          this.authStatus = 'authenticated';
        } else {
          // Có credentials nhưng chưa login → tự động redirect sang Google
          this.authStatus = 'redirecting';
          this.autoRedirectToGoogle();
        }
      },
      error: () => { this.authStatus = 'not-authenticated'; }
    });
  }

  /** Tự động redirect toàn trang sang Google OAuth — không cần button */
  private autoRedirectToGoogle(): void {
    this.api.getUploadAuthUrl().subscribe({
      next: (res: any) => {
        if (res.url) {
          window.location.href = res.url; // Redirect toàn trang (không mở tab mới)
        }
      },
      error: () => { this.authStatus = 'not-authenticated'; }
    });
  }

  /** Fallback: user bấm nút thử lại khi auto-redirect thất bại */
  retryLogin(): void {
    this.authStatus = 'redirecting';
    this.autoRedirectToGoogle();
  }

  // ─────────────────────────────────────────────────────
  // Folder Scan
  // ─────────────────────────────────────────────────────

  scanFolder(): void {
    const path = this.folderPath.trim();
    if (!path) { this.message.warning('Vui lòng nhập đường dẫn thư mục'); return; }

    this.scanning = true;
    this.scanError = '';
    this.scannedFiles = [];
    this.hasPreviousUpload = false;
    this.previousFailedCount = 0;
    this.previousSuccessCount = 0;

    this.api.scanFolder(path).subscribe({
      next: (res: ScanFolderResponse) => {
        this.scanning = false;
        if (!res.files || res.files.length === 0) {
          this.scanError = 'Không tìm thấy file video nào trong thư mục này.';
          return;
        }

        this.hasPreviousUpload = res.hasPreviousUpload ?? false;
        this.previousFailedCount = res.previousFailedCount ?? 0;
        this.previousSuccessCount = res.previousSuccessCount ?? 0;

        this.scannedFiles = res.files.map(f => ({
          ...f,
          // Mặc định: chọn file FAILED hoặc chưa upload; bỏ chọn SUCCESS
          selected: f.previousStatus !== 'SUCCESS',
          formattedSize: this.formatSize(f.fileSize),
          previousStatus: f.previousStatus ?? null,
        }));

        if (this.hasPreviousUpload) {
          if (this.previousFailedCount > 0) {
            this.message.warning(`Có ${this.previousFailedCount} video lỗi từ lần trước — đã tự chọn để reupload`);
          } else {
            this.message.success(`Tất cả ${this.previousSuccessCount} video đã upload thành công trước đó`);
          }
        } else {
          this.message.success(`Tìm thấy ${res.files.length} video`);
        }
      },
      error: (err) => {
        this.scanning = false;
        this.scanError = err?.error?.error || 'Không thể quét thư mục. Kiểm tra đường dẫn.';
      }
    });
  }

  /** Chỉ chọn các video bị lỗi lần trước */
  selectOnlyFailed(): void {
    this.scannedFiles.forEach(f => f.selected = f.previousStatus === 'FAILED');
  }

  /** Bỏ chọn các video đã thành công */
  selectOnlyNew(): void {
    this.scannedFiles.forEach(f => f.selected = f.previousStatus !== 'SUCCESS');
  }

  get allSelected(): boolean {
    return this.scannedFiles.length > 0 && this.scannedFiles.every(f => f.selected);
  }

  get someSelected(): boolean {
    return this.scannedFiles.some(f => f.selected) && !this.allSelected;
  }

  get selectedFiles(): SelectableVideo[] {
    return this.scannedFiles.filter(f => f.selected);
  }

  toggleAll(checked: boolean): void {
    this.scannedFiles.forEach(f => {
      if (f.previousStatus !== 'SUCCESS') {
        f.selected = checked;
      }
    });
  }

  get canStart(): boolean {
    return this.authStatus === 'authenticated'
      && this.selectedFiles.length > 0
      && this.uploadState === 'idle';
  }

  private formatSize(bytes: number): string {
    if (bytes < 1024) return bytes + ' B';
    if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB';
    if (bytes < 1024 * 1024 * 1024) return (bytes / 1024 / 1024).toFixed(1) + ' MB';
    return (bytes / 1024 / 1024 / 1024).toFixed(2) + ' GB';
  }

  // ─────────────────────────────────────────────────────
  // History Actions
  // ─────────────────────────────────────────────────────

  retryHistoryJob(job: any): void {
    // 1. Tự động điền folder path
    if (job.results && job.results.length > 0) {
      // Lấy đường dẫn thư mục từ file đầu tiên
      const firstFile = job.results[0].filePath;
      const lastSlash = Math.max(firstFile.lastIndexOf('/'), firstFile.lastIndexOf('\\'));
      if (lastSlash >= 0) {
        this.folderPath = firstFile.substring(0, lastSlash);
      }
    }
    
    // Nếu vẫn không có folderPath, thì fallback (phòng hờ)
    if (!this.folderPath) {
      this.message.error('Không tìm thấy thư mục của session này');
      return;
    }
    
    this.playlistId = job.playlistId || '';
    
    // 2. Tự động bấm Quét
    this.scanFolder();
    
    // Cuộn lên đầu trang
    window.scrollTo({ top: 0, behavior: 'smooth' });
    this.message.info('Đã tải lại thư mục. Các file thành công đã tự động bị bỏ qua!');
  }

  deleteHistoryJob(sessionId: string): void {
    this.api.deleteUploadHistory(sessionId).subscribe({
      next: () => {
        this.message.success('Đã xóa khỏi lịch sử');
        this.loadHistory();
      },
      error: () => this.message.error('Lỗi khi xóa')
    });
  }

  formatDateTime(iso: string): string {
    if (!iso) return '';
    const d = new Date(iso);
    return d.toLocaleString('vi-VN', { hour12: false });
  }

  getVideoStatusColor(status: string): string {
    switch (status) {
      case 'SUCCESS':
      case 'DONE': return 'green';
      case 'FAILED':
      case 'ERROR': return 'red';
      case 'UPLOADING': return 'blue';
      case 'PENDING': return 'default';
      default: return 'default';
    }
  }

  getVideoStatusText(status: string): string {
    switch (status) {
      case 'SUCCESS':
      case 'DONE': return 'Upload hoàn thành / Đã tồn tại';
      case 'FAILED':
      case 'ERROR': return 'Upload lỗi';
      case 'UPLOADING': return 'Đang upload...';
      case 'PENDING': return 'Chờ upload';
      default: return status || 'Không xác định';
    }
  }

  // ─────────────────────────────────────────────────────
  // Upload
  // ─────────────────────────────────────────────────────

  startUpload(): void {
    const selected = this.selectedFiles;
    if (!selected.length) { this.message.warning('Vui lòng chọn ít nhất 1 video'); return; }

    const filePaths = selected.map(f => f.filePath);

    // Khởi tạo file states
    this.fileStates = selected.map(f => ({
      filePath: f.filePath,
      fileName: f.fileName,
      status: 'PENDING' as const,
      progress: 0,
    }));
    this.uploadState = 'running';
    this.overallStatus = 'Đang khởi động...';

    this.api.startUpload({ playlistId: this.playlistId, filePaths }).subscribe({
      next: (res: any) => {
        if (!res.success) { this.message.error(res.message || 'Lỗi tạo session'); this.uploadState = 'idle'; return; }
        this.sessionId = res.sessionId;
        this.connectSse(res.sessionId);
      },
      error: (err: any) => {
        this.message.error(err?.error?.message || 'Lỗi kết nối server');
        this.uploadState = 'idle';
      }
    });
  }

  private connectSse(sessionId: string): void {
    const url = `${environment.apiUrl}/api/upload/progress/${sessionId}`;
    this.eventSource = new EventSource(url);

    const handler = (eventName: string) => (e: MessageEvent) => {
      try {
        const data = JSON.parse(e.data);
        this.handleProgress(eventName, data);
      } catch { /* ignore */ }
    };

    ['STATUS', 'FILE_START', 'FILE_PROGRESS', 'FILE_DONE', 'FILE_ERROR', 'ALL_DONE', 'ERROR']
      .forEach(ev => this.eventSource!.addEventListener(ev, handler(ev) as EventListenerOrEventListenerObject));

    this.eventSource.onerror = () => {
      if (this.uploadState !== 'done') {
        this.message.error('Mất kết nối SSE');
      }
    };
  }

  private handleProgress(eventName: string, data: any): void {
    if (data.message) this.overallStatus = data.message;

    if (data.currentFile) {
      const idx = this.fileStates.findIndex(f => f.fileName === data.currentFile);
      if (idx >= 0) {
        const fs = this.fileStates[idx];
        if (eventName === 'FILE_START')    { fs.status = 'UPLOADING'; fs.progress = 0; }
        if (eventName === 'FILE_PROGRESS') { fs.status = 'UPLOADING'; fs.progress = Math.round(data.currentFileProgress); }
        if (eventName === 'FILE_DONE')     { fs.status = 'DONE';      fs.progress = 100; }
        if (eventName === 'FILE_ERROR')    { fs.status = 'ERROR';     fs.progress = 0;   fs.error = data.message; }
      }
    }

    // Gán videoId từ results
    if (data.results?.length) {
      data.results.forEach((r: any) => {
        const fs = this.fileStates.find(f => f.filePath === r.filePath);
        if (fs) {
          fs.videoId = r.videoId;
          if (r.status === 'SUCCESS') fs.status = 'DONE';
          if (r.status === 'FAILED') { fs.status = 'ERROR'; fs.error = r.error; }
        }
      });
    }

    if (eventName === 'ALL_DONE' || eventName === 'ERROR') {
      this.uploadState = 'done';
      this.eventSource?.close();
    }
  }

  retryFailed(): void {
    const failedFiles = this.fileStates.filter(f => f.status === 'ERROR');
    if (!failedFiles.length) {
      this.message.warning('Không có video nào bị lỗi để thử lại');
      return;
    }

    const filePaths = failedFiles.map(f => f.filePath);

    // Xóa session cũ, reset trạng thái các file lỗi về PENDING, giữ nguyên các file thành công
    failedFiles.forEach(f => {
      f.status = 'PENDING';
      f.progress = 0;
      f.error = undefined;
    });

    this.uploadState = 'running';
    this.overallStatus = 'Đang khởi động lại...';
    this.eventSource?.close();

    this.api.startUpload({ playlistId: this.playlistId, filePaths }).subscribe({
      next: (res: any) => {
        if (!res.success) { this.message.error(res.message || 'Lỗi tạo session'); this.uploadState = 'done'; return; }
        this.sessionId = res.sessionId;
        this.connectSse(res.sessionId);
      },
      error: (err: any) => {
        this.message.error(err?.error?.message || 'Lỗi kết nối server');
        this.uploadState = 'done';
      }
    });
  }

  reset(): void {
    this.uploadState = 'idle';
    this.fileStates = [];
    this.sessionId = '';
    this.overallStatus = '';
    this.scannedFiles = [];
    this.folderPath = '';
    this.playlistId = '';
    this.scanError = '';
    this.eventSource?.close();
  }

  get completedCount(): number { return this.fileStates.filter(f => f.status === 'DONE').length; }
  get errorCount():     number { return this.fileStates.filter(f => f.status === 'ERROR').length; }
}
