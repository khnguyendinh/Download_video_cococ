import { Injectable } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import {
  DownloadRequest,
  StartDownloadResponse,
  PlaylistInfoResponse,
} from '../models/download-request.model';
import { DownloadJob } from '../models/download-job.model';

@Injectable({
  providedIn: 'root',
})
export class ApiService {
  private baseUrl = environment.apiUrl;

  constructor(private http: HttpClient) {}

  // ─── Download ───────────────────────────────────────────────

  getPlaylistInfo(url: string): Observable<PlaylistInfoResponse> {
    const params = new HttpParams().set('url', url);
    return this.http.get<PlaylistInfoResponse>(`${this.baseUrl}/api/playlist/info`, { params });
  }

  startDownload(request: DownloadRequest): Observable<StartDownloadResponse> {
    return this.http.post<StartDownloadResponse>(`${this.baseUrl}/api/download/start`, request);
  }

  batchDownload(request: { savePath: string; items: { playlistUrl: string; folderName: string }[] }): Observable<any> {
    return this.http.post<any>(`${this.baseUrl}/api/download/batch`, request);
  }

  getJobStatus(jobId: string): Observable<DownloadJob> {
    return this.http.get<DownloadJob>(`${this.baseUrl}/api/download/status/${jobId}`);
  }

  cancelJob(jobId: string): Observable<any> {
    return this.http.post(`${this.baseUrl}/api/download/cancel/${jobId}`, {});
  }

  getAllJobs(): Observable<DownloadJob[]> {
    return this.http.get<DownloadJob[]>(`${this.baseUrl}/api/download/jobs`);
  }

  deleteJob(jobId: string): Observable<any> {
    return this.http.delete<any>(`${this.baseUrl}/api/download/jobs/${jobId}`);
  }

  retryFailedBatch(jobIds: string[]): Observable<any> {
    return this.http.post<any>(`${this.baseUrl}/api/download/retry-failed-batch`, { jobIds });
  }

  // ─── Tải khoá học (mở Chrome đăng nhập) ──────────────────────

  siteOpen(url: string): Observable<SiteStatus> {
    return this.http.post<SiteStatus>(`${this.baseUrl}/api/site/open`, { url });
  }

  siteStatus(): Observable<SiteStatus> {
    return this.http.get<SiteStatus>(`${this.baseUrl}/api/site/status`);
  }

  siteClose(): Observable<any> {
    return this.http.post(`${this.baseUrl}/api/site/close`, {});
  }

  siteEnqueue(payload: { savePath: string; folderName: string; lessonIds: string[] }): Observable<any> {
    return this.http.post<any>(`${this.baseUrl}/api/site/enqueue`, payload);
  }

  siteRetry(jobId: string): Observable<any> {
    return this.http.post<any>(`${this.baseUrl}/api/site/retry/${jobId}`, {});
  }

  // ─── Tải tài liệu (PDF / Slide / Sheet) ──────────────────────

  docsScan(url: string): Observable<DocScan> {
    return this.http.post<DocScan>(`${this.baseUrl}/api/docs/scan`, { url });
  }

  docsDownload(payload: { savePath: string; folderName: string; ids: string[]; sheetFormat: 'office' | 'pdf' }): Observable<any> {
    return this.http.post<any>(`${this.baseUrl}/api/docs/download`, payload);
  }

  docsJobs(): Observable<DocJob[]> {
    return this.http.get<DocJob[]>(`${this.baseUrl}/api/docs/jobs`);
  }

  docsCancel(jobId: string): Observable<any> {
    return this.http.post(`${this.baseUrl}/api/docs/cancel/${jobId}`, {});
  }

  docsRetry(jobId: string): Observable<any> {
    return this.http.post(`${this.baseUrl}/api/docs/retry/${jobId}`, {});
  }

  // ─── YouTube Upload ──────────────────────────────────────────

  checkUploadAuthStatus(): Observable<{ credentialsAvailable: boolean; authenticated: boolean }> {
    return this.http.get<any>(`${this.baseUrl}/api/upload/auth-status`);
  }

  getUploadAuthUrl(): Observable<{ url: string }> {
    return this.http.get<any>(`${this.baseUrl}/api/upload/auth-url`);
  }

  scanFolder(path: string): Observable<ScanFolderResponse> {
    const params = new HttpParams().set('path', path);
    return this.http.get<any>(`${this.baseUrl}/api/upload/scan-folder`, { params });
  }

  startUpload(payload: { playlistId: string; filePaths: string[] }): Observable<any> {
    return this.http.post<any>(`${this.baseUrl}/api/upload/start`, payload);
  }

  getUploadHistory(): Observable<any[]> {
    return this.http.get<any[]>(`${this.baseUrl}/api/upload/history`);
  }

  deleteUploadHistory(sessionId: string): Observable<any> {
    return this.http.delete<any>(`${this.baseUrl}/api/upload/history/${sessionId}`);
  }

  // ─── Convert audio + ảnh → MP4 ───────────────────────────────

  /** Mở hộp thoại chọn file của Windows (phía backend) → trả về đường dẫn thật */
  pickFiles(type: 'audio' | 'image'): Observable<{ files: string[] }> {
    const params = new HttpParams().set('type', type);
    return this.http.get<{ files: string[] }>(`${this.baseUrl}/api/convert/pick`, { params });
  }

  startConvert(payload: { audioPaths: string[]; imagePath: string }): Observable<ConvertJob> {
    return this.http.post<ConvertJob>(`${this.baseUrl}/api/convert/start`, payload);
  }

  getConvertStatus(jobId: string): Observable<ConvertJob> {
    return this.http.get<ConvertJob>(`${this.baseUrl}/api/convert/status/${jobId}`);
  }

  cancelConvert(jobId: string): Observable<any> {
    return this.http.post(`${this.baseUrl}/api/convert/cancel/${jobId}`, {});
  }
}

export interface SiteLesson {
  id: string;
  index: number;
  title: string;
  provider: string;
  url: string;
  error: string;
  duration: string;
}

export interface SiteStatus {
  state: 'IDLE' | 'OPENING' | 'WAITING_LOGIN' | 'SCANNING' | 'READY' | 'ERROR';
  message: string;
  email?: string;
  targetUrl?: string;
  scan?: { pageUrl: string; level: string; levelName: string; lessons: SiteLesson[] };
}

export interface DocInfo {
  id: string;
  title: string;
  type: string;
  level: string;
  date: string;
  desc: string;
  link: string;
  kind: 'sheet' | 'doc' | 'slide' | 'drive' | 'url' | 'text' | 'none';
  downloadable: boolean;
}

export interface DocScan {
  pageUrl: string;
  pageTitle: string;
  docs: DocInfo[];
}

export interface DocItem {
  index: number;
  title: string;
  type: string;
  status: 'PENDING' | 'DOWNLOADING' | 'DONE' | 'SKIPPED' | 'FAILED' | 'CANCELLED';
  progress: number;
  bytes: number;
  totalBytes: number;
  filePath?: string;
  error?: string;
  note?: string;
}

export interface DocJob {
  id: string;
  folder: string;
  sheetFormat: string;
  items: DocItem[];
  status: 'PENDING' | 'RUNNING' | 'COMPLETED' | 'FAILED' | 'CANCELLED';
  cancelled: boolean;
  createdAt: string;
  finishedAt?: string;
}

export interface ConvertItem {
  audioPath: string;
  audioName: string;
  outputPath: string;
  status: 'PENDING' | 'CONVERTING' | 'DONE' | 'ERROR' | 'CANCELLED';
  progress: number;
  durationSec: number;
  error?: string;
}

export interface ConvertJob {
  id: string;
  imagePath: string;
  status: 'RUNNING' | 'DONE' | 'CANCELLED';
  items: ConvertItem[];
  overallProgress: number;
}

export interface VideoFileInfo {
  fileName: string;
  filePath: string;
  fileSize: number;
  previousStatus?: 'SUCCESS' | 'FAILED' | null;
  previousVideoId?: string;
  previousError?: string;
  previousUploadAt?: string;
}

export interface ScanFolderResponse {
  files: VideoFileInfo[];
  total: number;
  folder: string;
  hasPreviousUpload: boolean;
  previousFailedCount: number;
  previousSuccessCount: number;
}

