import { Component, OnDestroy, OnInit, ViewChild, ElementRef, AfterViewChecked } from '@angular/core';
import { FormBuilder, FormGroup, Validators } from '@angular/forms';
import { Subscription } from 'rxjs';
import { NzMessageService } from 'ng-zorro-antd/message';

import { ApiService } from '../../_shared/services/api.service';
import { SseService } from '../../_shared/services/sse.service';
import { DownloadJob, JobStatus } from '../../_shared/models/download-job.model';
import { VideoInfo } from '../../_shared/models/video-info.model';
import {
  DoneEvent,
  StatusEvent,
  ProgressEvent,
} from '../../_shared/models/download-request.model';

@Component({
  selector: 'app-downloader',
  templateUrl: './downloader.component.html',
  styleUrls: ['./downloader.component.scss'],
})
export class DownloaderComponent implements OnInit, OnDestroy, AfterViewChecked {
  @ViewChild('logContainer') logContainer!: ElementRef;

  form!: FormGroup;
  state: 'idle' | 'previewing' | 'downloading' | 'done' = 'idle';

  job: Partial<DownloadJob> = {
    status: 'PENDING',
    totalVideos: 0,
    completedVideos: 0,
    failedVideos: 0,
    progressPercent: 0,
    currentVideoProgress: 0,
    videos: [],
    logs: [],
  };

  resultData: DoneEvent | null = null;
  previewData: { total: number; videos: VideoInfo[] } | null = null;
  errorMsg: string = '';
  logs: string[] = [];
  shouldScrollLog = false;

  private subscriptions: Subscription[] = [];
  private eventSource: EventSource | null = null;

  constructor(
    private fb: FormBuilder,
    private api: ApiService,
    private sse: SseService,
    private message: NzMessageService
  ) {}

  ngOnInit(): void {
    this.form = this.fb.group({
      playlistUrl: ['', [Validators.required]],
      savePath: ['', [Validators.required]],
      folderName: ['', [Validators.required]],
    });
  }

  ngAfterViewChecked(): void {
    if (this.shouldScrollLog) {
      this.scrollLogToBottom();
      this.shouldScrollLog = false;
    }
  }

  ngOnDestroy(): void {
    this.cleanupSse();
  }

  // =====================
  // Getters / Helpers
  // =====================
  get isDownloading(): boolean {
    return this.state === 'downloading';
  }

  get pendingVideos(): number {
    return (this.job.totalVideos || 0) - (this.job.completedVideos || 0) - (this.job.failedVideos || 0);
  }

  getVideoStatusColor(status: string): string {
    const map: Record<string, string> = {
      PENDING: 'default',
      DOWNLOADING: 'processing',
      COMPLETED: 'success',
      FAILED: 'error',
      SKIPPED: 'warning',
    };
    return map[status] || 'default';
  }

  getJobStatusText(status: string): string {
    const map: Record<string, string> = {
      PENDING: '⏳ Chuẩn bị',
      FETCHING_INFO: '🔍 Đang lấy thông tin...',
      DOWNLOADING: '⬇ Đang tải',
      COMPLETED: '✅ Hoàn thành',
      FAILED: '❌ Thất bại',
      CANCELLED: '⛔ Đã hủy',
    };
    return map[status] || status;
  }

  getLogClass(log: string): string {
    if (log.includes('✅') || log.includes('Hoàn thành')) return 'log-success';
    if (log.includes('❌') || log.includes('Lỗi') || log.includes('ERROR')) return 'log-error';
    if (log.includes('⬇') || log.includes('🔍') || log.includes('ℹ')) return 'log-info';
    return '';
  }

  trackByVideoId(_index: number, video: VideoInfo): string {
    return video.id || String(_index);
  }

  trackByIndex(index: number): number {
    return index;
  }

  // =====================
  // Actions
  // =====================
  previewPlaylist(): void {
    if (!this.form.get('playlistUrl')?.value) {
      this.errorMsg = 'Vui lòng nhập URL playlist';
      return;
    }
    this.errorMsg = '';
    this.state = 'previewing';
    this.previewData = null;

    this.api.getPlaylistInfo(this.form.get('playlistUrl')!.value).subscribe({
      next: (res) => {
        this.previewData = { total: res.total, videos: res.videos };
        this.state = 'idle';

        // Auto-fill tên folder từ tên playlist nếu chưa nhập
        if (res.playlistTitle && !this.form.get('folderName')?.value) {
          this.form.get('folderName')?.setValue(res.playlistTitle);
        }

        this.message.success(`Tìm thấy ${res.total} video · Playlist: "${res.playlistTitle || 'N/A'}"`);
      },
      error: (err) => {
        this.state = 'idle';
        this.errorMsg = err?.error?.message || 'Không thể lấy thông tin playlist';
      },
    });
  }

  startDownload(): void {
    if (this.form.invalid) {
      Object.values(this.form.controls).forEach((c) => c.markAsDirty());
      return;
    }

    this.errorMsg = '';
    this.resultData = null;
    this.logs = [];
    this.state = 'downloading';
    this.job = {
      status: 'PENDING',
      totalVideos: 0,
      completedVideos: 0,
      failedVideos: 0,
      progressPercent: 0,
      currentVideoProgress: 0,
      videos: [],
      logs: [],
    };

    this.api.startDownload(this.form.value).subscribe({
      next: (res) => {
        if (res.success) {
          this.addLog(`🚀 Job ID: ${res.jobId}`);
          this.connectSse(res.jobId);
        } else {
          this.state = 'idle';
          this.errorMsg = res.message;
        }
      },
      error: (err) => {
        this.state = 'idle';
        this.errorMsg = err?.error?.message || 'Lỗi khởi động download';
      },
    });
  }

  cancelJob(): void {
    if (this.job.id) {
      this.api.cancelJob(this.job.id).subscribe({
        next: () => this.message.warning('Đã gửi lệnh hủy'),
        error: () => this.message.error('Không thể hủy job'),
      });
    }
  }

  reset(): void {
    this.cleanupSse();
    this.state = 'idle';
    this.job = { status: 'PENDING', totalVideos: 0, completedVideos: 0, failedVideos: 0, progressPercent: 0, currentVideoProgress: 0, videos: [], logs: [] };
    this.resultData = null;
    this.previewData = null;
    this.logs = [];
    this.errorMsg = '';
    this.form.reset();
  }

  // =====================
  // SSE
  // =====================
  private connectSse(jobId: string): void {
    this.cleanupSse();
    const es = this.sse.connect(jobId);
    this.eventSource = es;

    // status event
    const statusSub = this.sse.listenOn<StatusEvent>(es, 'status').subscribe((data) => {
      this.job.status = data.status as JobStatus;
      this.job.totalVideos = data.totalVideos;
      this.job.completedVideos = data.completedVideos;
      this.job.failedVideos = data.failedVideos;
      this.job.progressPercent = data.progressPercent;
      this.job.currentVideoTitle = data.currentVideoTitle;
      this.job.currentVideoProgress = data.currentVideoProgress;
      if (data.logs?.length) {
        this.logs = [...data.logs];
        this.shouldScrollLog = true;
      }
    });

    // playlist_info event
    const playlistSub = this.sse.listenOn<{ videos: VideoInfo[]; total: number }>(es, 'playlist_info').subscribe((data) => {
      this.job.totalVideos = data.total;
      this.job.videos = [...data.videos];
    });

    // video_start event
    const videoStartSub = this.sse.listenOn<{ video: VideoInfo }>(es, 'video_start').subscribe((data) => {
      this.updateVideo(data.video);
      this.shouldScrollLog = true;
    });

    // progress event
    const progressSub = this.sse.listenOn<ProgressEvent>(es, 'progress').subscribe((data) => {
      this.updateVideoProgress(data.videoId, data.percent);
      this.job.currentVideoProgress = data.percent;
    });

    // video_done event
    const videoDoneSub = this.sse.listenOn<{ video: VideoInfo }>(es, 'video_done').subscribe((data) => {
      this.updateVideo(data.video);
    });

    // done event
    const doneSub = this.sse.listenOn<DoneEvent>(es, 'done').subscribe((data) => {
      this.resultData = data;
      this.state = 'done';
      this.cleanupSse();
      if (data.success) {
        this.message.success('Tải xuống hoàn thành!');
      } else {
        this.message.error(data.message || 'Có lỗi xảy ra');
      }
    });

    this.subscriptions.push(statusSub, playlistSub, videoStartSub, progressSub, videoDoneSub, doneSub);
  }

  private updateVideo(video: VideoInfo): void {
    if (!this.job.videos) this.job.videos = [];
    const idx = this.job.videos.findIndex((v) => v.id === video.id);
    if (idx >= 0) {
      this.job.videos[idx] = { ...this.job.videos[idx], ...video };
    } else {
      this.job.videos.push(video);
    }
    // Trigger change detection
    this.job.videos = [...this.job.videos];
  }

  private updateVideoProgress(videoId: string, percent: number): void {
    if (!this.job.videos) return;
    const idx = this.job.videos.findIndex((v) => v.id === videoId);
    if (idx >= 0) {
      this.job.videos[idx] = { ...this.job.videos[idx], progress: percent };
      this.job.videos = [...this.job.videos];
    }
  }

  private addLog(msg: string): void {
    const now = new Date();
    const ts = [now.getHours(), now.getMinutes(), now.getSeconds()]
      .map((n) => n.toString().padStart(2, '0'))
      .join(':');
    this.logs.push(`[${ts}] ${msg}`);
    this.shouldScrollLog = true;
  }

  private scrollLogToBottom(): void {
    if (this.logContainer?.nativeElement) {
      this.logContainer.nativeElement.scrollTop = this.logContainer.nativeElement.scrollHeight;
    }
  }

  private cleanupSse(): void {
    this.subscriptions.forEach((s) => s.unsubscribe());
    this.subscriptions = [];
    this.sse.disconnect();
    this.eventSource = null;
  }
}
