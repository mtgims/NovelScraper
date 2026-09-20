import type {
  AppSettings,
  AuthConfig,
  Book,
  Chapter,
  ChapterListItem,
  Collection,
  Invite,
  Job,
  JobCreate,
  ProgressUpdate,
  ReadingProgress,
  Site,
  Stats,
  TtsManifest,
  TtsVoices,
  User,
} from "./types";

// Empty = same-origin: all /api/* requests hit the frontend, which proxies them
// to the backend (see next.config.mjs). This keeps the app host-agnostic (works
// over localhost / LAN / Tailscale / a tunnel with no config or CORS). Set
// NEXT_PUBLIC_API_BASE only to point the browser straight at a backend origin.
export const API_BASE = process.env.NEXT_PUBLIC_API_BASE ?? "";

class ApiError extends Error {
  constructor(public status: number, message: string) {
    super(message);
  }
}

// Invoked whenever a request comes back 401 (no/expired session). The app
// registers a handler (see AuthGate) that drops the cached session so the UI
// falls back to the login screen. Kept as a hook so this module stays
// framework-agnostic and free of React/router imports.
let onUnauthorized: (() => void) | null = null;
export function setUnauthorizedHandler(fn: (() => void) | null) {
  onUnauthorized = fn;
}

// Throw an ApiError carrying the backend's `detail` message (or the status
// text) for any non-2xx response. A 401 also notifies the auth handler.
async function ensureOk(res: Response): Promise<Response> {
  if (res.ok) return res;
  if (res.status === 401) onUnauthorized?.();
  let detail = res.statusText;
  try {
    const body = await res.json();
    detail = body.detail ?? detail;
  } catch {
    /* non-JSON error body */
  }
  throw new ApiError(res.status, detail);
}

async function req<T>(path: string, init?: RequestInit): Promise<T> {
  const res = await ensureOk(
    await fetch(`${API_BASE}${path}`, {
      headers: { "Content-Type": "application/json" },
      // Send the session cookie (redundant same-origin, required if the browser
      // is pointed at a cross-origin backend via NEXT_PUBLIC_API_BASE).
      credentials: "include",
      ...init,
    })
  );
  if (res.status === 204) return undefined as T;
  return res.json() as Promise<T>;
}

// Multipart upload — must NOT set Content-Type (the browser adds the boundary).
async function upload<T>(path: string, files: File[]): Promise<T> {
  const form = new FormData();
  for (const f of files) form.append("files", f);
  const res = await ensureOk(
    await fetch(`${API_BASE}${path}`, {
      method: "POST",
      body: form,
      credentials: "include",
    })
  );
  return res.json() as Promise<T>;
}

export const api = {
  // Auth
  login: (username: string, password: string) =>
    req<User>("/api/auth/login", {
      method: "POST",
      body: JSON.stringify({ username, password }),
    }),
  logout: () => req<void>("/api/auth/logout", { method: "POST" }),
  me: () => req<User>("/api/auth/me"),
  register: (body: { username: string; password: string; invite_code?: string }) =>
    req<User>("/api/auth/register", { method: "POST", body: JSON.stringify(body) }),
  authConfig: () => req<AuthConfig>("/api/auth/config"),

  // Admin account management
  createInvite: () => req<Invite>("/api/auth/invites", { method: "POST" }),
  listInvites: () => req<Invite[]>("/api/auth/invites"),
  deleteInvite: (code: string) =>
    req<void>(`/api/auth/invites/${encodeURIComponent(code)}`, { method: "DELETE" }),
  clearSpentInvites: () =>
    req<{ deleted: number }>("/api/auth/invites", { method: "DELETE" }),
  listUsers: () => req<User[]>("/api/auth/users"),
  updateUser: (id: number, body: { disabled?: boolean; is_admin?: boolean }) =>
    req<User>(`/api/auth/users/${id}`, { method: "PATCH", body: JSON.stringify(body) }),
  deleteUser: (id: number) => req<void>(`/api/auth/users/${id}`, { method: "DELETE" }),

  getSites: () => req<Site[]>("/api/sites"),
  getStats: () => req<Stats>("/api/stats"),
  getVoices: () => req<TtsVoices>("/api/tts/voices"),
  ttsManifest: (bookId: number, position: number, voice: string, speed: number) =>
    req<TtsManifest>(
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
  importEpubs: (files: File[]) => upload<Book>("/api/import", files),
  addEpubs: (id: number, files: File[]) =>
    upload<Book>(`/api/books/${id}/import`, files),
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

// Widths the backend will render a cover at (app/services/images.py
// THUMB_WIDTHS). Asking for anything else silently serves the original, which
// can be >1MB — so callers pick from here.
export type CoverWidth = 200 | 400 | 800;

/** URL for a book's cover. Pass the width it will be *drawn* at (x2 for DPR) to
 *  get a WebP rendition instead of the source image; omit it for the original. */
export function coverUrl(bookId: number, width?: CoverWidth): string {
  const q = width ? `?w=${width}` : "";
  return `${API_BASE}/api/books/${bookId}/cover${q}`;
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
