// Mirrors the backend Pydantic schemas (app/schemas.py).

export interface User {
  id: number;
  username: string;
  is_admin: boolean;
  disabled: boolean;
  created_at: string;
}

export interface Invite {
  code: string;
  created_by: number;
  used_by: number | null;
  expires_at: string;
  created_at: string;
}

export type JobStatus =
  | "queued"
  | "running"
  | "completed"
  | "failed"
  | "cancelled";

export interface Job {
  id: string;
  site: string;
  book_slug: string;
  source_url: string | null;
  status: JobStatus;
  phase: string;
  chapters_per_volume: number;
  total_chapters: number;
  fetched_chapters: number;
  skipped_chapters: number;
  error: string | null;
  book_id: number | null;
  created_at: string;
  started_at: string | null;
  finished_at: string | null;
}

export interface JobCreate {
  url: string;
  chapters_per_volume?: number;
  delay?: number;
  concurrency?: number;
}

export interface Volume {
  id: number;
  number: number;
  title: string;
  chapter_count: number;
  size_bytes: number;
}

export interface Book {
  id: number;
  slug: string;
  site: string;
  title: string;
  author: string;
  language: string;
  has_cover: boolean;
  created_at: string;
  updated_at: string | null;
  sort_order: number;
  rating: number | null;
  can_update: boolean;
  imported: boolean;
  collection_ids: number[];
  volumes: Volume[];
}

export interface Collection {
  id: number;
  name: string;
  sort_order: number;
}

export interface AppSettings {
  auto_update_hours: number;
  allow_open_signup: boolean;
}

export interface AuthConfig {
  allow_open_signup: boolean;
}

export interface Site {
  name: string;
  base_url: string;
  enumeration: string;
}

export interface ChapterListItem {
  position: number;
  number: string;
  title: string;
  volume: number; // volume_number, for grouping the TOC by volume
}

export interface Chapter {
  position: number;
  number: string;
  title: string;
  content: string;
  has_prev: boolean;
  has_next: boolean;
}

export interface ReadingProgress {
  last_position: number;
  scroll: number;
  read_positions: number[];
  total_chapters: number;
  read_count: number;
  chapters_left: number;
  percent_read: number;
  total_words: number;
  words_read: number;
  hours_total: number;
  hours_left: number;
}

export interface ProgressUpdate {
  last_position?: number;
  scroll?: number;
  mark_read?: number;
  unmark_read?: number;
  mark_positions?: number[];
  unmark_positions?: number[];
  mark_all?: boolean;
  reset?: boolean;
}

export interface BookStat {
  book_id: number;
  title: string;
  total_chapters: number;
  read_count: number;
  percent_read: number;
}

export interface TtsVoices {
  available: boolean;
  default: string;
  voices: string[];
  device?: string; // "cuda" | "cpu" | "unknown" (server-side engine)
}

// A chapter's narration plan: sentence-grouped chunks + the flat sentence texts.
// A read-along block: either a text paragraph (its sentences, which carry the
// audio) or an image (render-only — no audio, not part of the sentence stream).
export type TtsBlock =
  | { type: "text"; sentences: string[] }
  | { type: "image"; src: string; alt?: string };

export interface TtsManifest {
  chunks: number[][];
  blocks: TtsBlock[];
  voice: string;
  speed: number;
}

export interface Stats {
  total_books: number;
  books_started: number;
  books_finished: number;
  total_chapters: number;
  chapters_read: number;
  total_words: number;
  words_read: number;
  hours_read: number;
  hours_total: number;
  hours_remaining: number;
  percent_read: number;
  books: BookStat[];
}

// Live SSE progress payload (a superset of the snapshot / progress events).
export interface JobProgress extends Partial<Job> {
  event?: string;
  phase: string;
  total?: number;
  fetched?: number;
  skipped?: number;
  volume?: number;
}
