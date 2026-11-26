import { Component, OnDestroy, OnInit } from '@angular/core';
import { ActivatedRoute } from '@angular/router';
import { NzMessageService } from 'ng-zorro-antd/message';
import { ApiService, DocInfo, DocJob, DocScan } from '../../_shared/services/api.service';

const SAVE_PATH_KEY = 'doc-download.savePath';
const URL_KEY = 'doc-download.url';
const FORMAT_KEY = 'doc-download.sheetFormat';

@Component({
  selector: 'app-doc-download',
  templateUrl: './doc-download.component.html',
  styleUrls: [
    '../upload/upload.component.scss',
    '../site-download/site-download.component.scss',
    './doc-download.component.scss',
  ],
})
export class DocDownloadComponent implements OnInit, OnDestroy {
  url = 'https://davincihcm-hocdautu.com/tai-lieu.html';
  savePath = '';
  folderName = '';
  sheetFormat: 'office' | 'pdf' = 'office';

  scan?: DocScan;
  scanning = false;
  enqueuing = false;

  /** Lọc theo loại (PDF / Sheet / Slide...) */
  typeFilter = '';
  query = '';

  selected = new Set<string>();

  jobs: DocJob[] = [];
  expanded = new Set<string>();
  private jobsTimer?: ReturnType<typeof setInterval>;

  constructor(private api: ApiService, private message: NzMessageService, private route: ActivatedRoute) {}

  ngOnInit(): void {
    this.savePath = this.load(SAVE_PATH_KEY) || '';
    this.url = this.load(URL_KEY) || this.url;
    this.sheetFormat = this.load(FORMAT_KEY) === 'pdf' ? 'pdf' : 'office';
    // Được chuyển từ tab khác kèm ?url=... → điền link và tự lấy danh sách
    const fromQuery = this.route.snapshot.queryParamMap.get('url');
    if (fromQuery) {
      this.url = fromQuery;
      this.doScan();
    }
    this.refreshJobs();
    this.jobsTimer = setInterval(() => this.refreshJobs(), 1500);
  }

  ngOnDestroy(): void {
    clearInterval(this.jobsTimer);
  }

  // ─── Quét link ───────────────────────────────────────

  doScan(): void {
    const url = this.url.trim();
    if (!url) {
      this.message.warning('Dán link trang tài liệu vào trước nhé');
      return;
    }
    this.save(URL_KEY, url);
    this.scanning = true;
    this.api.docsScan(url).subscribe({
      next: (s) => {
        this.scanning = false;
        this.scan = s;
        this.typeFilter = '';
        this.query = '';
        this.selected = new Set(s.docs.filter((d) => d.downloadable).map((d) => d.id));
        if (!this.folderName.trim()) this.folderName = s.docs.length > 1 ? 'Tài liệu' : '';
        this.message.success(`Tìm thấy ${s.docs.length} tài liệu`);
      },
      error: (err) => {
        this.scanning = false;
        this.message.error(err?.error?.message || 'Không đọc được trang');
      },
    });
  }

  get docs(): DocInfo[] {
    return this.scan?.docs || [];
  }

  get types(): string[] {
    const t: string[] = [];
    this.docs.forEach((d) => {
      const x = (d.type || '').trim();
      if (x && !t.includes(x)) t.push(x);
    });
    return t;
  }

  get hasGoogleDocs(): boolean {
    return this.docs.some((d) => d.kind === 'sheet' || d.kind === 'doc' || d.kind === 'slide');
  }

  get visible(): DocInfo[] {
    const q = this.norm(this.query);
    return this.docs.filter(
      (d) =>
        (!this.typeFilter || (d.type || '').trim() === this.typeFilter) &&
        (!q || this.norm(`${d.title} ${d.desc} ${d.type} ${d.level}`).includes(q)),
    );
  }

  countType(t: string): number {
    return this.docs.filter((d) => (d.type || '').trim() === t).length;
  }

  // ─── Chọn ────────────────────────────────────────────

  toggle(d: DocInfo): void {
    if (!d.downloadable) return;
    if (this.selected.has(d.id)) this.selected.delete(d.id);
    else this.selected.add(d.id);
  }

  get allChecked(): boolean {
    const v = this.visible.filter((d) => d.downloadable);
    return v.length > 0 && v.every((d) => this.selected.has(d.id));
  }

  /** Chọn / bỏ chọn các tài liệu đang hiển thị (theo bộ lọc) */
  toggleAll(): void {
    const v = this.visible.filter((d) => d.downloadable);
    if (this.allChecked) v.forEach((d) => this.selected.delete(d.id));
    else v.forEach((d) => this.selected.add(d.id));
  }

  get canDownload(): boolean {
    return this.selected.size > 0 && !!this.savePath.trim();
  }

  download(): void {
    if (!this.canDownload) return;
    this.save(SAVE_PATH_KEY, this.savePath.trim());
    this.save(FORMAT_KEY, this.sheetFormat);
    this.enqueuing = true;
    // Giữ thứ tự như danh sách
    const ids = this.docs.filter((d) => this.selected.has(d.id)).map((d) => d.id);
    this.api
      .docsDownload({ savePath: this.savePath.trim(), folderName: this.folderName.trim(), ids, sheetFormat: this.sheetFormat })
      .subscribe({
        next: (r) => {
          this.enqueuing = false;
          this.message.success(`Đã thêm ${r.total} tài liệu vào hàng đợi`);
          this.expanded.add(r.jobId);
          this.refreshJobs();
        },
        error: (err) => {
          this.enqueuing = false;
          this.message.error(err?.error?.message || 'Không bắt đầu tải được');
        },
      });
  }

  // ─── Hàng đợi ────────────────────────────────────────

  private refreshJobs(): void {
    this.api.docsJobs().subscribe({
      next: (j) => (this.jobs = j || []),
      error: () => {},
    });
  }

  get activeCount(): number {
    return this.jobs.filter((j) => j.status === 'PENDING' || j.status === 'RUNNING').length;
  }

  done(j: DocJob): number {
    return j.items.filter((i) => i.status === 'DONE' || i.status === 'SKIPPED').length;
  }

  failed(j: DocJob): number {
    return j.items.filter((i) => i.status === 'FAILED').length;
  }

  unfinished(j: DocJob): number {
    return j.items.filter((i) => i.status !== 'DONE' && i.status !== 'SKIPPED').length;
  }

  jobPercent(j: DocJob): number {
    if (!j.items.length) return 0;
    const cur = j.items.find((i) => i.status === 'DOWNLOADING');
    const finished = j.items.filter((i) => ['DONE', 'SKIPPED', 'FAILED', 'CANCELLED'].includes(i.status)).length;
    return Math.min(100, Math.floor(((finished + (cur ? cur.progress / 100 : 0)) * 100) / j.items.length));
  }

  current(j: DocJob) {
    return j.items.find((i) => i.status === 'DOWNLOADING');
  }

  canRetry(j: DocJob): boolean {
    return j.status !== 'PENDING' && j.status !== 'RUNNING' && this.unfinished(j) > 0;
  }

  cancel(j: DocJob): void {
    this.api.docsCancel(j.id).subscribe({
      next: () => {
        this.message.info('Sẽ dừng sau file đang tải');
        this.refreshJobs();
      },
      error: (err) => this.message.error(err?.error?.message || 'Không huỷ được'),
    });
  }

  retry(j: DocJob): void {
    this.api.docsRetry(j.id).subscribe({
      next: () => {
        this.message.success('Đang tải lại các file chưa xong');
        this.refreshJobs();
      },
      error: (err) => this.message.error(err?.error?.message || 'Không tải lại được'),
    });
  }

  toggleExpand(j: DocJob): void {
    if (this.expanded.has(j.id)) this.expanded.delete(j.id);
    else this.expanded.add(j.id);
  }

  statusLabel(s: string): string {
    return (
      ({
        PENDING: '🕒 Đang chờ',
        RUNNING: '⬇ Đang tải',
        COMPLETED: '✅ Xong',
        FAILED: '❌ Có lỗi',
        CANCELLED: '⛔ Đã huỷ',
      } as Record<string, string>)[s] || s
    );
  }

  itemIcon(s: string): string {
    return (
      ({ DONE: '✅', SKIPPED: '⏭', FAILED: '❌', DOWNLOADING: '⬇', CANCELLED: '⛔' } as Record<string, string>)[s] || '⏸'
    );
  }

  kindIcon(d: DocInfo): string {
    const t = (d.type || '').toLowerCase();
    if (d.kind === 'sheet' || t.includes('sheet')) return '📊';
    if (d.kind === 'slide' || t.includes('slide') || t.includes('ppt')) return '📽';
    if (d.kind === 'doc') return '📝';
    if (d.kind === 'text') return '📃';
    if (t.includes('video')) return '🎬';
    return '📕';
  }

  kindHint(d: DocInfo): string {
    switch (d.kind) {
      case 'sheet':
        return this.sheetFormat === 'pdf' ? 'Google Sheet → .pdf' : 'Google Sheet → .xlsx';
      case 'slide':
        return this.sheetFormat === 'pdf' ? 'Google Slides → .pdf' : 'Google Slides → .pptx';
      case 'doc':
        return this.sheetFormat === 'pdf' ? 'Google Docs → .pdf' : 'Google Docs → .docx';
      case 'drive':
        return 'Google Drive';
      case 'text':
        return 'Bài viết → .txt';
      case 'url':
        return 'Link trực tiếp';
      default:
        return 'Không có link tải';
    }
  }

  fmtSize(b: number): string {
    if (!b || b < 0) return '';
    if (b < 1024) return `${b} B`;
    if (b < 1024 * 1024) return `${(b / 1024).toFixed(0)} KB`;
    return `${(b / 1024 / 1024).toFixed(1)} MB`;
  }

  fmtDate(d: string): string {
    const p = (d || '').split('-');
    return p.length === 3 ? `${p[2]}/${p[1]}/${p[0]}` : d || '';
  }

  private norm(s: string): string {
    return (s || '')
      .normalize('NFD')
      .replace(/[̀-ͯ]/g, '')
      .replace(/đ/gi, 'd')
      .toLowerCase()
      .trim();
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
