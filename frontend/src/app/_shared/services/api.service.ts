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

