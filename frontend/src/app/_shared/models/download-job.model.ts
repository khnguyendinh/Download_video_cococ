import { VideoInfo } from './video-info.model';

export interface DownloadJob {
  id: string;
  playlistUrl: string;
  folderName: string;
  savePath: string;
  status: JobStatus;
  totalVideos: number;
  completedVideos: number;
  failedVideos: number;
  currentVideoTitle?: string;
  currentVideoProgress: number;
  progressPercent: number;
  startedAt?: string;
  finishedAt?: string;
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
