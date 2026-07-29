import { Component, OnDestroy, OnInit } from '@angular/core';
import { FormArray, FormBuilder, FormGroup, Validators } from '@angular/forms';
import { Subscription } from 'rxjs';
import { NzMessageService } from 'ng-zorro-antd/message';

import { ApiService } from '../../_shared/services/api.service';
import { SseService } from '../../_shared/services/sse.service';
import { VideoInfo } from '../../_shared/models/video-info.model';
import { DownloadJob, JobStatus } from '../../_shared/models/download-job.model';
import { DoneEvent, StatusEvent, ProgressEvent } from '../../_shared/models/download-request.model';

// Trạng thái từng job trên UI
export interface JobState {
  jobId: string;
  folderName: string;
  playlistUrl: string;
  status: JobStatus;
  totalVideos: number;
  completedVideos: number;
  failedVideos: number;
  progressPercent: number;
  currentVideoTitle: string;
  currentVideoProgress: number;
  videos: VideoInfo[];
  logs: string[];
  expanded: boolean;
  doneData?: DoneEvent;
  eventSource?: EventSource;
  subs: Subscription[];
}

@Component({
  selector: 'app-downloader',
  templateUrl: './downloader.component.html',
  styleUrls: ['./downloader.component.scss'],
})
export class DownloaderComponent implements OnInit, OnDestroy {
  form!: FormGroup;
  appState: 'input' | 'running' | 'done' = 'input';
  jobStates: JobState[] = [];

  constructor(
    private fb: FormBuilder,
    private api: ApiService,
    private sse: SseService,
    private message: NzMessageService
  ) {}

  ngOnInit(): void {
    this.form = this.fb.group({
      savePath: ['', Validators.required],
      items: this.fb.array([this.createItem()]),
    });
  }

  ngOnDestroy(): void {
    this.jobStates.forEach((j) => this.cleanupJob(j));
  }

  // ========================
  // Form helpers
  // ========================
  get items(): FormArray {
    return this.form.get('items') as FormArray;
  }

  createItem(): FormGroup {
    return this.fb.group({
      playlistUrl: ['', Validators.required],
      folderName: ['', Validators.required],
      previewState: ['idle'], // idle | loading | done | error
      previewTotal: [0],
    });
  }

  addItem(): void {
    this.items.push(this.createItem());
  }

  removeItem(i: number): void {
    if (this.items.length > 1) this.items.removeAt(i);
  }

  trackByIndex(i: number): number { return i; }
  trackByJobId(_: number, j: JobState): string { return j.jobId; }
  trackByVideoId(_: number, v: VideoInfo): string { return v.id; }

  // ========================
  // Preview per row
  // ========================
  previewItem(i: number): void {
    const ctrl = this.items.at(i) as FormGroup;
    const url = ctrl.get('playlistUrl')?.value;
    if (!url) { this.message.warning('Vui lòng nhập URL'); return; }

    ctrl.get('previewState')?.setValue('loading');

    this.api.getPlaylistInfo(url).subscribe({
      next: (res) => {
        ctrl.get('previewState')?.setValue('done');
        ctrl.get('previewTotal')?.setValue(res.total);
        // Auto-fill folder name từ tên playlist nếu chưa nhập
        if (res.playlistTitle && !ctrl.get('folderName')?.value) {
          ctrl.get('folderName')?.setValue(res.playlistTitle);
        }
        this.message.success(`[Dòng ${i + 1}] Tìm thấy ${res.total} video · "${res.playlistTitle}"`);
      },
      error: () => {
        ctrl.get('previewState')?.setValue('error');
        this.message.error(`[Dòng ${i + 1}] Không lấy được thông tin playlist`);
      },
    });
  }

  // ========================
  // Start batch download
  // ========================
  startAll(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      this.items.controls.forEach((c) => c.markAllAsTouched());
      this.message.error('Vui lòng điền đầy đủ thông tin');
      return;
    }

    const savePath = this.form.get('savePath')?.value;
    const itemList = this.items.value.map((it: any) => ({
      playlistUrl: it.playlistUrl,
      folderName: it.folderName,
    }));

    this.appState = 'running';
    this.jobStates = [];

    this.api.batchDownload({ savePath, items: itemList }).subscribe({
      next: (res) => {
        if (!res.success) {
          this.message.error(res.message || 'Lỗi khởi động');
          this.appState = 'input';
          return;
        }

        // Tạo jobState cho từng job trả về
        this.jobStates = res.jobs.map((j: any) => ({
          jobId: j.jobId,
          folderName: j.folderName,
          playlistUrl: j.playlistUrl,
          status: 'PENDING' as JobStatus,
          totalVideos: 0,
          completedVideos: 0,
          failedVideos: 0,
          progressPercent: 0,
          currentVideoTitle: '',
          currentVideoProgress: 0,
          videos: [],
          logs: [`🚀 Job đã tạo cho folder: ${j.folderName}`],
          expanded: false,
          subs: [],
        }));

        // Kết nối SSE từng job
        this.jobStates.forEach((js) => this.connectSse(js));

        this.message.success(`Đã khởi động ${res.jobs.length} playlist — chạy tuần tự`);
      },
      error: (err) => {
        this.appState = 'input';
        this.message.error(err?.error?.message || 'Lỗi kết nối server');
      },
    });
  }

  reset(): void {
    this.jobStates.forEach((j) => this.cleanupJob(j));
    this.jobStates = [];
    this.appState = 'input';
    this.form.reset();
    this.items.clear();
    this.items.push(this.createItem());
  }

  // ========================
  // SSE per job
  // ========================
  private connectSse(js: JobState): void {
    const es = new EventSource(`/api/download/progress/${js.jobId}`);
    js.eventSource = es;

    const onStatus = (e: MessageEvent) => {
      const d: StatusEvent = JSON.parse(e.data);
      js.status = d.status as JobStatus;
      js.totalVideos = d.totalVideos;
      js.completedVideos = d.completedVideos;
      js.failedVideos = d.failedVideos;
      js.progressPercent = d.progressPercent;
      js.currentVideoTitle = d.currentVideoTitle;
      js.currentVideoProgress = d.currentVideoProgress;
      if (d.logs?.length) js.logs = [...d.logs];
    };

    const onPlaylist = (e: MessageEvent) => {
      const d = JSON.parse(e.data);
      js.totalVideos = d.total;
      js.videos = [...d.videos];
    };

    const onVideoStart = (e: MessageEvent) => {
      const d = JSON.parse(e.data);
      this.updateVideo(js, d.video);
    };

    const onProgress = (e: MessageEvent) => {
      const d: ProgressEvent = JSON.parse(e.data);
      js.currentVideoProgress = d.percent;
      this.updateVideoProgress(js, d.videoId, d.percent);
    };

    const onVideoDone = (e: MessageEvent) => {
      const d = JSON.parse(e.data);
      this.updateVideo(js, d.video);
    };

    const onDone = (e: MessageEvent) => {
      const d: DoneEvent = JSON.parse(e.data);
      js.doneData = d;
      js.status = d.success ? 'COMPLETED' : 'FAILED';
      js.progressPercent = 100;
      es.close();
      this.checkAllDone();
    };

    es.addEventListener('status', onStatus as EventListener);
    es.addEventListener('playlist_info', onPlaylist as EventListener);
    es.addEventListener('video_start', onVideoStart as EventListener);
    es.addEventListener('progress', onProgress as EventListener);
    es.addEventListener('video_done', onVideoDone as EventListener);
    es.addEventListener('done', onDone as EventListener);
  }

  private checkAllDone(): void {
    const allFinished = this.jobStates.every(
      (j) => j.status === 'COMPLETED' || j.status === 'FAILED' || j.status === 'CANCELLED'
    );
    if (allFinished) {
      this.appState = 'done';
      this.message.success('Tất cả playlist đã xử lý xong!');
    }
  }

  private updateVideo(js: JobState, video: VideoInfo): void {
    const idx = js.videos.findIndex((v) => v.id === video.id);
    if (idx >= 0) js.videos[idx] = { ...js.videos[idx], ...video };
    else js.videos.push(video);
    js.videos = [...js.videos];
  }

  private updateVideoProgress(js: JobState, videoId: string, pct: number): void {
    const idx = js.videos.findIndex((v) => v.id === videoId);
    if (idx >= 0) {
      js.videos[idx] = { ...js.videos[idx], progress: pct };
      js.videos = [...js.videos];
    }
  }

  private cleanupJob(js: JobState): void {
    js.subs.forEach((s) => s.unsubscribe());
    js.eventSource?.close();
  }

  // ========================
  // UI helpers
  // ========================
  getStatusColor(s: string): string {
    return ({ PENDING: 'default', FETCHING_INFO: 'processing', DOWNLOADING: 'processing',
      COMPLETED: 'success', FAILED: 'error', CANCELLED: 'warning' } as any)[s] || 'default';
  }

  getStatusText(s: string): string {
    return ({ PENDING: '⏳ Chờ', FETCHING_INFO: '🔍 Lấy info...', DOWNLOADING: '⬇ Đang tải',
      COMPLETED: '✅ Xong', FAILED: '❌ Thất bại', CANCELLED: '⛔ Hủy' } as any)[s] || s;
  }

  getVideoStatusColor(s: string): string {
    return ({ PENDING: 'default', DOWNLOADING: 'processing', COMPLETED: 'success',
      FAILED: 'error', SKIPPED: 'warning' } as any)[s] || 'default';
  }

  get totalCompleted(): number { return this.jobStates.filter(j => j.status === 'COMPLETED').length; }
  get totalFailed(): number { return this.jobStates.filter(j => j.status === 'FAILED').length; }
  get totalRunning(): number { return this.jobStates.filter(j => j.status === 'DOWNLOADING' || j.status === 'FETCHING_INFO').length; }
  get totalPending(): number { return this.jobStates.filter(j => j.status === 'PENDING').length; }
}
