import type {
  AppSettings,
  Book,
  Chapter,
  ChapterListItem,
  Collection,
  Job,
  JobCreate,
  ProgressUpdate,
  ReadingProgress,
  Site,
  Stats,
  TtsVoices,
} from "./types";

export const API_BASE =
  process.env.NEXT_PUBLIC_API_BASE ?? "http://127.0.0.1:8000";

class ApiError extends Error {
  constructor(public status: number, message: string) {
    super(message);
  }
}

async function req<T>(path: string, init?: RequestInit): Promise<T> {
  const res = await fetch(`${API_BASE}${path}`, {
    headers: { "Content-Type": "application/json" },
    ...init,
  });
  if (!res.ok) {
    let detail = res.statusText;
    try {
      const body = await res.json();
      detail = body.detail ?? detail;
    } catch {
      /* non-JSON error body */
    }
    throw new ApiError(res.status, detail);
  }
  if (res.status === 204) return undefined as T;
  return res.json() as Promise<T>;
}

export const api = {
  getSites: () => req<Site[]>("/api/sites"),
  getStats: () => req<Stats>("/api/stats"),
  getVoices: () => req<TtsVoices>("/api/tts/voices"),
  ttsManifest: (bookId: number, position: number, voice: string, speed: number) =>
    req<{
      chunks: number[][];
      paragraphs: string[][];
      voice: string;
      speed: number;
    }>(
      `/api/books/${bookId}/chapters/${position}/audio/manifest?voice=${encodeURIComponent(voice)}&speed=${speed}`
    ),
  getBooks: () => req<Book[]>("/api/books"),
  getBook: (id: number) => req<Book>(`/api/books/${id}`),
  deleteBook: (id: number) =>
    req<void>(`/api/books/${id}`, { method: "DELETE" }),
  setRating: (id: number, rating: number) =>
    req<Book>(`/api/books/${id}`, {
      method: "PATCH",
      body: JSON.stringify({ rating }),
    }),
  updateBookChapters: (id: number) =>
    req<Job>(`/api/books/${id}/update`, { method: "POST" }),
  setBookCollections: (id: number, collectionIds: number[]) =>
    req<Book>(`/api/books/${id}/collections`, {
      method: "PUT",
      body: JSON.stringify({ collection_ids: collectionIds }),
    }),
  reorderBooks: (orderedIds: number[]) =>
    req<void>("/api/books/reorder", {
      method: "POST",
      body: JSON.stringify({ ordered_ids: orderedIds }),
    }),
  getCollections: () => req<Collection[]>("/api/collections"),
  createCollection: (name: string) =>
    req<Collection>("/api/collections", {
      method: "POST",
      body: JSON.stringify({ name }),
    }),
  updateCollection: (id: number, body: { name?: string; sort_order?: number }) =>
    req<Collection>(`/api/collections/${id}`, {
      method: "PATCH",
      body: JSON.stringify(body),
    }),
  deleteCollection: (id: number) =>
    req<void>(`/api/collections/${id}`, { method: "DELETE" }),
  getSettings: () => req<AppSettings>("/api/settings"),
  updateSettings: (body: Partial<AppSettings>) =>
    req<AppSettings>("/api/settings", {
      method: "PUT",
      body: JSON.stringify(body),
    }),
  getChapters: (bookId: number) =>
    req<ChapterListItem[]>(`/api/books/${bookId}/chapters`),
  getChapter: (bookId: number, position: number) =>
    req<Chapter>(`/api/books/${bookId}/chapters/${position}`),
  getProgress: (bookId: number) =>
    req<ReadingProgress>(`/api/books/${bookId}/progress`),
  updateProgress: (bookId: number, body: ProgressUpdate) =>
    req<ReadingProgress>(`/api/books/${bookId}/progress`, {
      method: "PUT",
      body: JSON.stringify(body),
    }),
  getJobs: () => req<Job[]>("/api/jobs"),
  getJob: (id: string) => req<Job>(`/api/jobs/${id}`),
  createJob: (data: JobCreate) =>
    req<Job>("/api/jobs", { method: "POST", body: JSON.stringify(data) }),
  cancelJob: (id: string) =>
    req<{ cancelled: boolean }>(`/api/jobs/${id}/cancel`, { method: "POST" }),
  deleteJob: (id: string) =>
    req<void>(`/api/jobs/${id}`, { method: "DELETE" }),
  clearFinishedJobs: () =>
    req<{ deleted: number }>("/api/jobs", { method: "DELETE" }),
};

export function downloadUrl(bookId: number, volume: number): string {
  return `${API_BASE}/api/books/${bookId}/download?volume=${volume}`;
}

export function downloadAllUrl(bookId: number): string {
  return `${API_BASE}/api/books/${bookId}/download-all`;
}

export function coverUrl(bookId: number): string {
  return `${API_BASE}/api/books/${bookId}/cover`;
}

export function audioChunkUrl(
  bookId: number,
  position: number,
  chunk: number,
  voice: string,
  speed: number
): string {
  return `${API_BASE}/api/books/${bookId}/chapters/${position}/audio/${chunk}?voice=${encodeURIComponent(voice)}&speed=${speed}`;
}

export function jobEventsUrl(jobId: string): string {
  return `${API_BASE}/api/jobs/${jobId}/events`;
}

export { ApiError };
