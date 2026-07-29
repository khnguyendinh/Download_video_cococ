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
}
