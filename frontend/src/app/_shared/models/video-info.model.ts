export interface VideoInfo {
  id: string;
  title: string;
  url: string;
  duration: string;
  thumbnail?: string;
  index: number;
  status: VideoStatus;
  progress: number;
  savedFilename?: string;
  errorMessage?: string;
}

export type VideoStatus = 'PENDING' | 'DOWNLOADING' | 'COMPLETED' | 'FAILED' | 'SKIPPED';
