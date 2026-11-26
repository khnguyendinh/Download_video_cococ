import { Component, OnDestroy, OnInit } from '@angular/core';
import { Router } from '@angular/router';
import { NzMessageService } from 'ng-zorro-antd/message';
import { ApiService, SiteLesson, SiteStatus } from '../../_shared/services/api.service';
import { DownloadJob } from '../../_shared/models/download-job.model';

const SAVE_PATH_KEY = 'site-download.savePath';
const URL_KEY = 'site-download.url';

@Component({
  selector: 'app-site-download',
  templateUrl: './site-download.component.html',
  styleUrls: ['../upload/upload.component.scss', './site-download.component.scss'],
})
export class SiteDownloadComponent implements OnInit, OnDestroy {
  readonly Math = Math;

  url = '';
  savePath = '';
  folderName = '';

  status?: SiteStatus;
  opening = false;
  enqueuing = false;

  /** id bài đã tick */
  selected = new Set<string>();
  private scannedFor = '';

  jobs: DownloadJob[] = [];
  expanded = new Set<string>();

  private statusTimer?: ReturnType<typeof setInterval>;
  private jobsTimer?: ReturnType<typeof setInterval>;

  constructor(private api: ApiService, private message: NzMessageService, private router: Router) {}

  ngOnInit(): void {
    this.savePath = this.load(SAVE_PATH_KEY) || '';
    this.url = this.load(URL_KEY) || '';
    this.refreshStatus();
    this.refreshJobs();
    this.statusTimer = setInterval(() => {
      if (this.isBusy) this.refreshStatus();
    }, 1500);
    this.jobsTimer = setInterval(() => this.refreshJobs(), 2000);
  }

  ngOnDestroy(): void {
    clearInterval(this.statusTimer);
    clearInterval(this.jobsTimer);
  }

  // ─── Trình duyệt / đăng nhập ─────────────────────────

  get isBusy(): boolean {
    const s = this.status?.state;
    return s === 'OPENING' || s === 'WAITING_LOGIN' || s === 'SCANNING';
  }

  get lessons(): SiteLesson[] {
    return this.status?.state === 'READY' ? this.status.scan?.lessons || [] : [];
  }

  get downloadable(): SiteLesson[] {
    return this.lessons.filter((l) => !!l.url);
  }

  open(): void {
    const url = this.url.trim();
    if (!url) {
      this.message.warning('Dán link trang bài học vào trước nhé');
      return;
    }
    // Link trang tài liệu / Google Drive → chuyển sang tab Tài liệu
    if (/tai-lieu\.html|drive\.google\.com\/|docs\.google\.com\//i.test(url)) {
      this.message.info('Đây là link tài liệu → chuyển sang tab Tài liệu');
      this.router.navigate(['/docs'], { queryParams: { url } });
      return;
    }
    this.save(URL_KEY, url);
    this.opening = true;
    this.api.siteOpen(url).subscribe({
      next: (s) => {
        this.opening = false;
        this.status = s;
      },
      error: (err) => {
        this.opening = false;
        this.message.error(err?.error?.message || 'Không mở được trình duyệt');
      },
    });
  }

  closeBrowser(): void {
    this.api.siteClose().subscribe(() => setTimeout(() => this.refreshStatus(), 800));
  }

  private refreshStatus(): void {
    this.api.siteStatus().subscribe({
      next: (s) => {
        this.status = s;
        // Lần đầu có danh sách bài → tick hết + gợi ý tên folder
        if (s.state === 'READY' && s.scan && this.scannedFor !== s.scan.pageUrl + s.scan.lessons.length) {
          this.scannedFor = s.scan.pageUrl + s.scan.lessons.length;
          this.selected = new Set(s.scan.lessons.filter((l) => !!l.url).map((l) => l.id));
          this.folderName = 'Davinci - ' + (s.scan.levelName || s.scan.level);
        }
      },
      error: () => {},
    });
  }

  // ─── Chọn bài ────────────────────────────────────────

  toggle(l: SiteLesson): void {
    if (!l.url) return;
    if (this.selected.has(l.id)) this.selected.delete(l.id);
    else this.selected.add(l.id);
  }

  get allChecked(): boolean {
    return this.downloadable.length > 0 && this.downloadable.every((l) => this.selected.has(l.id));
  }

  toggleAll(): void {
    this.selected = this.allChecked ? new Set() : new Set(this.downloadable.map((l) => l.id));
  }

  get canEnqueue(): boolean {
    return this.selected.size > 0 && !!this.savePath.trim() && !!this.folderName.trim();
  }

  enqueue(): void {
    if (!this.canEnqueue) return;
    this.save(SAVE_PATH_KEY, this.savePath.trim());
    this.enqueuing = true;
    const ids = this.lessons.filter((l) => this.selected.has(l.id)).map((l) => l.id);
    this.api
      .siteEnqueue({ savePath: this.savePath.trim(), folderName: this.folderName.trim(), lessonIds: ids })
      .subscribe({
        next: (r) => {
          this.enqueuing = false;
          this.message.success(`Đã thêm ${r.total} video vào hàng đợi`);
          this.refreshJobs();
        },
        error: (err) => {
          this.enqueuing = false;
          this.message.error(err?.error?.message || 'Không thêm được vào hàng đợi');
        },
      });
  }

  // ─── Hàng đợi ────────────────────────────────────────

  private refreshJobs(): void {
    this.api.getAllJobs().subscribe({
      next: (all) => {
        const rank = (j: DownloadJob) =>
          j.status === 'DOWNLOADING' || j.status === 'FETCHING_INFO' ? 0 : j.status === 'PENDING' ? 1 : 2;
        this.jobs = (all || [])
          .filter((j) => !!j.referer)
          .sort((a, b) => {
            const r = rank(a) - rank(b);
            if (r !== 0) return r;
            // Đang chờ: job thêm trước đứng trước. Đã xong: mới nhất lên đầu
            const ta = a.startedAt || '';
            const tb = b.startedAt || '';
            return rank(a) === 2 ? (tb > ta ? 1 : -1) : ta > tb ? 1 : -1;
          });
      },
      error: () => {},
    });
  }

  get activeCount(): number {
    return this.jobs.filter((j) => j.status === 'PENDING' || j.status === 'DOWNLOADING').length;
  }

  jobPercent(j: DownloadJob): number {
    if (!j.totalVideos) return 0;
    const running = j.status === 'DOWNLOADING' ? (j.currentVideoProgress || 0) / 100 : 0;
    const done = j.completedVideos + j.failedVideos;
    return Math.min(100, Math.floor(((done + (done < j.totalVideos ? running : 0)) * 100) / j.totalVideos));
  }

  unfinished(j: DownloadJob): number {
    return j.videos.filter((v) => v.status !== 'COMPLETED' && v.status !== 'SKIPPED').length;
  }

  canRetry(j: DownloadJob): boolean {
    return (j.status === 'COMPLETED' || j.status === 'FAILED' || j.status === 'CANCELLED') && this.unfinished(j) > 0;
  }

  cancel(j: DownloadJob): void {
    this.api.cancelJob(j.id).subscribe({
      next: () => {
        this.message.info(j.status === 'PENDING' ? 'Đã bỏ khỏi hàng đợi' : 'Sẽ dừng sau video đang tải');
        this.refreshJobs();
      },
      error: (err) => this.message.error(err?.error?.message || 'Không huỷ được'),
    });
  }

  retry(j: DownloadJob): void {
    this.api.siteRetry(j.id).subscribe({
      next: (r) => {
        this.message.success(`Đã xếp ${r.total} video vào cuối hàng đợi`);
        this.refreshJobs();
      },
      error: (err) => this.message.error(err?.error?.message || 'Không retry được'),
    });
  }

  remove(j: DownloadJob): void {
    this.api.deleteJob(j.id).subscribe({
      next: () => this.refreshJobs(),
      error: (err) => this.message.error(err?.error?.message || 'Không xoá được'),
    });
  }

  toggleExpand(j: DownloadJob): void {
    if (this.expanded.has(j.id)) this.expanded.delete(j.id);
    else this.expanded.add(j.id);
  }

  statusLabel(s: string): string {
    return (
      {
        PENDING: '🕒 Đang chờ',
        FETCHING_INFO: '🔍 Đang lấy thông tin',
        DOWNLOADING: '⬇ Đang tải',
        COMPLETED: '✅ Xong',
        FAILED: '❌ Lỗi',
        CANCELLED: '⛔ Đã huỷ',
      } as Record<string, string>
    )[s] || s;
  }

  // ─── localStorage (bọc try/catch) ────────────────────

  private load(key: string): string | null {
    try {
      return localStorage.getItem(key);
    } catch {
      return null;
    }
  }

  private save(key: string, value: string): void {
    try {
      localStorage.setItem(key, value);
    } catch {}
  }
}
