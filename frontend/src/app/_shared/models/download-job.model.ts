import { VideoInfo } from './video-info.model';

export interface DownloadJob {
  id: string;
  playlistUrl: string;
  folderName: string;
  savePath: string;
  /** Có giá trị = job tải khoá học (tab Khoá học), không hiện ở tab Tải xuống */
  referer?: string;
  status: JobStatus;
  totalVideos: number;
  completedVideos: number;
  failedVideos: number;
  currentVideoTitle?: string;
  currentVideoProgress: number;
  progressPercent: number;
  startedAt?: string;
  finishedAt?: string;
  durationSeconds?: number;
  videos: VideoInfo[];
  logs: string[];
}

export type JobStatus =
  | 'PENDING'
  | 'FETCHING_INFO'
  | 'DOWNLOADING'
  | 'COMPLETED'
  | 'FAILED'
  | 'CANCELLED';
