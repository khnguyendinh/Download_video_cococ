import { Component, OnDestroy } from '@angular/core';
import { NzMessageService } from 'ng-zorro-antd/message';
import { ApiService, ConvertItem, ConvertJob } from '../../_shared/services/api.service';
import { environment } from '../../../environments/environment';

@Component({
  selector: 'app-convert',
  templateUrl: './convert.component.html',
  styleUrls: ['../upload/upload.component.scss', './convert.component.scss'],
})
export class ConvertComponent implements OnDestroy {

  readonly Math = Math;

  audioPaths: string[] = [];
  imagePath = '';

  state: 'idle' | 'running' | 'done' = 'idle';
  job?: ConvertJob;
  starting = false;

  private pollTimer?: ReturnType<typeof setInterval>;

  constructor(private api: ApiService, private message: NzMessageService) {}

  ngOnDestroy(): void {
    this.stopPolling();
  }

  // ─── Chọn file ──────────────────────────────────────

  pickAudio(): void {
    this.api.pickFiles('audio').subscribe({
      next: (res) => {
        const added = res.files.filter((f) => !this.audioPaths.includes(f));
        this.audioPaths = [...this.audioPaths, ...added];
        if (added.length) this.message.success(`Đã thêm ${added.length} file audio`);
      },
      error: (err) => {
        this.message.error(err?.error?.error || 'Không mở được hộp thoại chọn file');
      },
    });
  }

  pickImage(): void {
    this.api.pickFiles('image').subscribe({
      next: (res) => {
        if (res.files.length) this.imagePath = res.files[0];
      },
      error: (err) => {
        this.message.error(err?.error?.error || 'Không mở được hộp thoại chọn file');
      },
    });
  }

  removeAudio(i: number): void {
    this.audioPaths = this.audioPaths.filter((_, idx) => idx !== i);
  }

  clearAudio(): void {
    this.audioPaths = [];
  }

  get previewUrl(): string {
    return this.imagePath
      ? `${environment.apiUrl}/api/convert/preview?path=${encodeURIComponent(this.imagePath)}`
      : '';
  }

  get canStart(): boolean {
    return this.audioPaths.length > 0 && !!this.imagePath && !this.starting;
  }

  fileName(path: string): string {
    return path.split(/[\\/]/).pop() || path;
  }

  folderOf(path: string): string {
    const i = Math.max(path.lastIndexOf('\\'), path.lastIndexOf('/'));
    return i > 0 ? path.substring(0, i) : path;
  }

  ext(path: string): string {
    return (path.split('.').pop() || '').toUpperCase();
  }

  // ─── Convert ────────────────────────────────────────

  start(): void {
    if (!this.canStart) return;
    this.starting = true;
    this.api.startConvert({ audioPaths: this.audioPaths, imagePath: this.imagePath }).subscribe({
      next: (job) => {
        this.starting = false;
        this.job = job;
        this.state = 'running';
        this.startPolling();
      },
      error: (err) => {
        this.starting = false;
        this.message.error(err?.error?.error || 'Không bắt đầu convert được');
      },
    });
  }

  cancel(): void {
    if (!this.job) return;
    this.api.cancelConvert(this.job.id).subscribe();
  }

  reset(): void {
    this.stopPolling();
    this.job = undefined;
    this.state = 'idle';
    this.audioPaths = [];
  }

  private startPolling(): void {
    this.stopPolling();
    this.pollTimer = setInterval(() => {
      if (!this.job) return;
      this.api.getConvertStatus(this.job.id).subscribe({
        next: (job) => {
          this.job = job;
          if (job.status !== 'RUNNING') {
            this.state = 'done';
            this.stopPolling();
            const err = this.count('ERROR');
            if (job.status === 'CANCELLED') this.message.warning('Đã huỷ convert');
            else if (err > 0) this.message.warning(`Xong, có ${err} file lỗi`);
            else this.message.success('🎉 Convert xong tất cả file!');
          }
        },
        error: () => {},
      });
    }, 700);
  }

  private stopPolling(): void {
    if (this.pollTimer) clearInterval(this.pollTimer);
    this.pollTimer = undefined;
  }

  count(status: ConvertItem['status']): number {
    return this.job?.items.filter((i) => i.status === status).length ?? 0;
  }

  formatDuration(sec: number): string {
    if (!sec) return '';
    const s = Math.round(sec);
    const h = Math.floor(s / 3600);
    const m = Math.floor((s % 3600) / 60);
    const ss = String(s % 60).padStart(2, '0');
    return h ? `${h}:${String(m).padStart(2, '0')}:${ss}` : `${m}:${ss}`;
  }
}
