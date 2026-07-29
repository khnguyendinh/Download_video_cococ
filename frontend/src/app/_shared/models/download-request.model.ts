export interface DownloadRequest {
  playlistUrl: string;
  folderName: string;
  savePath: string;
}

export interface StartDownloadResponse {
  success: boolean;
  jobId: string;
  message: string;
}

export interface PlaylistInfoResponse {
  success: boolean;
  total: number;
  videos: any[];
  message?: string;
}

export interface DoneEvent {
  success: boolean;
  message: string;
  completed?: number;
  failed?: number;
  outputDir?: string;
}

export interface StatusEvent {
  jobId: string;
  status: string;
  totalVideos: number;
  completedVideos: number;
  failedVideos: number;
  progressPercent: number;
  currentVideoTitle: string;
  currentVideoProgress: number;
  logs: string[];
}

export interface ProgressEvent {
  videoId: string;
  videoIndex: number;
  percent: number;
  size: string;
  eta: string;
}
