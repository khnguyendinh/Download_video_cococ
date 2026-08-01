import { Component, OnDestroy, OnInit } from '@angular/core';
import { FormArray, FormBuilder, FormGroup, Validators } from '@angular/forms';
import { Subscription } from 'rxjs';
import { NzMessageService } from 'ng-zorro-antd/message';
import { NzModalService } from 'ng-zorro-antd/modal';

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
  savePath: string;
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
  // Thông tin thời gian
  startedAt?: string;
  finishedAt?: string;
  durationSeconds?: number;
  isHistorical?: boolean; // job từ lịch sử (load lại khi mở app)
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
  isLoadingHistory = false;

  constructor(
    private fb: FormBuilder,
    private api: ApiService,
    private sse: SseService,
    private message: NzMessageService,
    private modal: NzModalService
  ) {}

  ngOnInit(): void {
    this.form = this.fb.group({
      savePath: ['', Validators.required],
      items: this.fb.array([this.createItem()]),
    });

    // Load lịch sử jobs cũ từ backend
    this.loadHistory();
  }

  ngOnDestroy(): void {
    this.jobStates.forEach((j) => this.cleanupJob(j));
  }

  // ========================
  // Load lịch sử từ server
  // ========================
  loadHistory(): void {
    this.isLoadingHistory = true;
    this.api.getAllJobs().subscribe({
      next: (jobs: DownloadJob[]) => {
        this.isLoadingHistory = false;
        if (!jobs || jobs.length === 0) return;

        // Chuyển DownloadJob[] → JobState[]
        this.jobStates = jobs.map((j) => this.jobToState(j, true));

        // Job nào đang DOWNLOADING/FETCHING_INFO thì kết nối SSE (nếu server vẫn đang chạy)
        this.jobStates.forEach((js) => {
          if (js.status === 'DOWNLOADING' || js.status === 'FETCHING_INFO' || js.status === 'PENDING') {
            this.connectSse(js);
          }
        });

        // Xác định appState
        const hasActive = this.jobStates.some(
          (j) => j.status === 'DOWNLOADING' || j.status === 'FETCHING_INFO' || j.status === 'PENDING'
        );
        const hasAny = this.jobStates.length > 0;

        if (hasActive) {
          this.appState = 'running';
        } else if (hasAny) {
          this.appState = 'done';
        }
      },
      error: () => {
        this.isLoadingHistory = false;
      },
    });
  }

  /** Chuyển DownloadJob (từ API) sang JobState (UI) */
  private jobToState(j: DownloadJob, isHistorical = false): JobState {
    return {
      jobId: j.id,
      folderName: j.folderName,
      playlistUrl: j.playlistUrl,
      savePath: j.savePath || '',
      status: j.status as JobStatus,
      totalVideos: j.totalVideos || 0,
      completedVideos: j.completedVideos || 0,
      failedVideos: j.failedVideos || 0,
      progressPercent: j.progressPercent || 0,
      currentVideoTitle: j.currentVideoTitle || '',
      currentVideoProgress: j.currentVideoProgress || 0,
      videos: j.videos || [],
      logs: j.logs || [],
      expanded: false,
      startedAt: j.startedAt,
      finishedAt: j.finishedAt,
      durationSeconds: j.durationSeconds,
      isHistorical,
      subs: [],
    };
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

    this.api.batchDownload({ savePath, items: itemList }).subscribe({
      next: (res) => {
        if (!res.success) {
          this.message.error(res.message || 'Lỗi khởi động');
          this.appState = this.jobStates.length > 0 ? 'done' : 'input';
          return;
        }

        // Tạo jobState cho từng job trả về và THÊM VÀO CUỐI danh sách hiện tại
        const newStates: JobState[] = res.jobs.map((j: any) => ({
          jobId: j.jobId,
          folderName: j.folderName,
          playlistUrl: j.playlistUrl,
          savePath,
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
          isHistorical: false,
          subs: [],
        }));

        this.jobStates = [...this.jobStates, ...newStates];

        // Kết nối SSE từng job mới
        newStates.forEach((js) => this.connectSse(js));

        // Reset form
        this.form.reset();
        this.items.clear();
        this.items.push(this.createItem());

        this.message.success(`Đã khởi động ${res.jobs.length} playlist — chạy tuần tự`);
      },
      error: (err) => {
        this.appState = this.jobStates.length > 0 ? 'done' : 'input';
        this.message.error(err?.error?.message || 'Lỗi kết nối server');
      },
    });
  }

  // ========================
  // Xóa job
  // ========================
  confirmDeleteJob(js: JobState): void {
    this.modal.confirm({
      nzTitle: `Xóa job "${js.folderName}"?`,
      nzContent: 'Job sẽ bị xóa khỏi lịch sử và không thể khôi phục.',
      nzOkText: 'Xóa',
      nzOkDanger: true,
      nzCancelText: 'Hủy',
      nzOnOk: () => this.deleteJob(js),
    });
  }

  deleteJob(js: JobState): void {
    this.api.deleteJob(js.jobId).subscribe({
      next: () => {
        this.cleanupJob(js);
        this.jobStates = this.jobStates.filter((j) => j.jobId !== js.jobId);
        this.message.success(`Đã xóa job: ${js.folderName}`);

        if (this.jobStates.length === 0) {
          this.appState = 'input';
        }
      },
      error: () => {
        this.message.error('Không thể xóa job đang chạy');
      },
    });
  }

  // ========================
  // Retry job bị FAILED/CANCELLED
  // ========================
  retryJob(js: JobState): void {
    if (!js.savePath) {
      this.message.error('Không có đường dẫn lưu — không thể tải lại');
      return;
    }

    this.message.loading(`Đang khởi động lại: ${js.folderName}...`, { nzDuration: 1500 });

    this.api.batchDownload({
      savePath: js.savePath,
      items: [{ playlistUrl: js.playlistUrl, folderName: js.folderName }],
    }).subscribe({
      next: (res) => {
        if (!res.success || !res.jobs?.length) {
          this.message.error(res.message || 'Lỗi khi tạo lại job');
          return;
        }

        const newJob = res.jobs[0];
        const newState: JobState = {
          jobId: newJob.jobId,
          folderName: newJob.folderName,
          playlistUrl: newJob.playlistUrl || js.playlistUrl,
          savePath: js.savePath,
          status: 'PENDING',
          totalVideos: 0,
          completedVideos: 0,
          failedVideos: 0,
          progressPercent: 0,
          currentVideoTitle: '',
          currentVideoProgress: 0,
          videos: [],
          logs: [`🔄 Tải lại từ job FAILED: ${js.folderName}`],
          expanded: true,
          isHistorical: false,
          subs: [],
        };

        // Thay thế job cũ bằng job mới tại cùng vị trí
        const idx = this.jobStates.findIndex((j) => j.jobId === js.jobId);
        if (idx >= 0) {
          this.cleanupJob(this.jobStates[idx]);
          this.jobStates[idx] = newState;
        } else {
          this.jobStates.push(newState);
        }

        this.appState = 'running';
        this.connectSse(newState);
        this.message.success(`✅ Đã khởi động lại: ${js.folderName}`);
      },
      error: (err) => {
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
      // Reload lại thông tin job để lấy finishedAt, durationSeconds
      this.api.getJobStatus(js.jobId).subscribe({
        next: (job) => {
          js.finishedAt = job.finishedAt;
          js.startedAt = job.startedAt;
          js.durationSeconds = job.durationSeconds;
        },
      });
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

  formatDuration(seconds: number | undefined): string {
    if (!seconds) return '--';
    const h = Math.floor(seconds / 3600);
    const m = Math.floor((seconds % 3600) / 60);
    const s = seconds % 60;
    if (h > 0) return `${h}g ${m}p ${s}s`;
    if (m > 0) return `${m}p ${s}s`;
    return `${s}s`;
  }

  formatDateTime(dt: string | undefined): string {
    if (!dt) return '--';
    try {
      const d = new Date(dt);
      return d.toLocaleString('vi-VN', {
        day: '2-digit', month: '2-digit', year: 'numeric',
        hour: '2-digit', minute: '2-digit', second: '2-digit'
      });
    } catch { return dt; }
  }

  get totalCompleted(): number { return this.jobStates.filter(j => j.status === 'COMPLETED').length; }
  get totalFailed(): number { return this.jobStates.filter(j => j.status === 'FAILED').length; }
  get totalRunning(): number { return this.jobStates.filter(j => j.status === 'DOWNLOADING' || j.status === 'FETCHING_INFO').length; }
  get totalPending(): number { return this.jobStates.filter(j => j.status === 'PENDING').length; }

  /** Tổng số video bị lỗi trong tất cả jobs (để hiện nút Retry Failed) */
  get totalFailedVideosCount(): number {
    return this.jobStates.reduce((sum, j) => sum + (j.failedVideos || 0), 0);
  }

  /** Retry tất cả video FAILED trong các jobs đã xong */
  retryAllFailedVideos(): void {
    const jobsWithFailed = this.jobStates.filter(j => (j.failedVideos || 0) > 0);
    if (!jobsWithFailed.length) {
      this.message.warning('Không có video lỗi nào cần retry');
      return;
    }

    const jobIds = jobsWithFailed.map(j => j.jobId);
    const totalFailed = jobsWithFailed.reduce((sum, j) => sum + j.failedVideos, 0);

    this.message.loading(`Đang tạo retry jobs cho ${totalFailed} video lỗi...`, { nzDuration: 2000 });

    this.api.retryFailedBatch(jobIds).subscribe({
      next: (res) => {
        if (!res.success || !res.jobs?.length) {
          this.message.error(res.message || 'Lỗi khi tạo retry jobs');
          return;
        }

        // Reset failedVideos của job gốc về 0 trước khi thay thế
        jobsWithFailed.forEach(j => { j.failedVideos = 0; });

        // Thay thế job gốc tại cùng vị trí (không thêm dòng mới)
        res.jobs.forEach((j: any) => {
          // Tìm job gốc ứng với retry này (so khớp theo folderName, bỏ qua suffix [RETRY])
          const origJob = jobsWithFailed.find(orig =>
            orig.folderName.replace(/ \[RETRY\]/g, '') === j.folderName
          );
          if (!origJob) return;

          const origIdx = this.jobStates.findIndex(s => s.jobId === origJob.jobId);
          if (origIdx < 0) return;

          // Cleanup SSE cũ để tránh rỏ kết nối
          this.cleanupJob(this.jobStates[origIdx]);

          const newState: JobState = {
            jobId: j.jobId,
            folderName: j.folderName,           // Giữ nguyên tên gốc, không thêm [RETRY]
            playlistUrl: origJob.playlistUrl,
            savePath: origJob.savePath,
            status: 'PENDING' as JobStatus,
            totalVideos: j.totalRetryVideos || 0,
            completedVideos: 0,
            failedVideos: 0,
            progressPercent: 0,
            currentVideoTitle: '',
            currentVideoProgress: 0,
            videos: [],
            logs: [`🔄 Retry ${j.totalRetryVideos} video lỗi`],
            expanded: true,
            isHistorical: false,
            subs: [],
          };

          this.jobStates[origIdx] = newState;
          this.connectSse(newState);
        });

        this.jobStates = [...this.jobStates]; // Trigger change detection
        this.appState = 'running';
        this.message.success(`✅ Đã khởi động retry ${totalFailed} video lỗi từ ${res.jobs.length} playlist`);
      },
      error: (err) => {
        this.message.error(err?.error?.message || 'Lỗi kết nối server');
      },
    });
  }

  /** Sort video list: FAILED lên đầu, tiếp theo DOWNLOADING, rồi PENDING, cuối cùng COMPLETED/SKIPPED */
  getSortedVideos(videos: VideoInfo[]): VideoInfo[] {
    const order: Record<string, number> = {
      FAILED: 0,
      DOWNLOADING: 1,
      PENDING: 2,
      SKIPPED: 3,
      COMPLETED: 4,
    };
    return [...videos].sort((a, b) =>
      (order[a.status] ?? 5) - (order[b.status] ?? 5)
    );
  }
}
