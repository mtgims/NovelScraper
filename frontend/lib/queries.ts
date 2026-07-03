"use client";

import {
  useMutation,
  useQuery,
  useQueryClient,
} from "@tanstack/react-query";
import { useEffect, useState } from "react";

import { api, jobEventsUrl } from "./api";
import type { Job, JobCreate, JobProgress } from "./types";

export function useSites() {
  return useQuery({ queryKey: ["sites"], queryFn: api.getSites });
}

export function useStats() {
  return useQuery({ queryKey: ["stats"], queryFn: api.getStats });
}

export function useVoices() {
  return useQuery({
    queryKey: ["tts-voices"],
    queryFn: api.getVoices,
    staleTime: Infinity,
  });
}

export function useBooks() {
  return useQuery({ queryKey: ["books"], queryFn: api.getBooks });
}

export function useBook(id: number) {
  return useQuery({
    queryKey: ["book", id],
    queryFn: () => api.getBook(id),
    enabled: Number.isFinite(id),
  });
}

export function useChapters(bookId: number) {
  return useQuery({
    queryKey: ["chapters", bookId],
    queryFn: () => api.getChapters(bookId),
    enabled: Number.isFinite(bookId),
  });
}

export function useChapter(bookId: number, position: number) {
  return useQuery({
    queryKey: ["chapter", bookId, position],
    queryFn: () => api.getChapter(bookId, position),
    enabled: Number.isFinite(bookId) && Number.isFinite(position),
  });
}

export function useProgress(bookId: number) {
  return useQuery({
    queryKey: ["progress", bookId],
    queryFn: () => api.getProgress(bookId),
    enabled: Number.isFinite(bookId),
    // The reader keeps this cache authoritative via setQueryData on every scroll
    // save, and mutations invalidate it explicitly. Don't auto-refetch on mount/
    // focus: a refetch racing the just-fired save PUT can read the old scroll
    // (0) and clobber the cache, so re-entering a chapter would start at the top.
    refetchOnMount: false,
    refetchOnWindowFocus: false,
    staleTime: Infinity,
  });
}

export function useUpdateProgress(bookId: number) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: import("./types").ProgressUpdate) =>
      api.updateProgress(bookId, body),
    onSuccess: (data) => {
      qc.setQueryData(["progress", bookId], data);
      qc.invalidateQueries({ queryKey: ["stats"] });
    },
  });
}

export function useJobs() {
  return useQuery({
    queryKey: ["jobs"],
    queryFn: api.getJobs,
    // Poll while the list view is open so finished jobs settle without SSE.
    refetchInterval: 3000,
  });
}

export function useCreateJob() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (data: JobCreate) => api.createJob(data),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["jobs"] }),
  });
}

export function useCancelJob() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (id: string) => api.cancelJob(id),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["jobs"] }),
  });
}

export function useDeleteJob() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (id: string) => api.deleteJob(id),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["jobs"] }),
  });
}

export function useClearJobs() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: () => api.clearFinishedJobs(),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["jobs"] }),
  });
}

export function useDeleteBook() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (id: number) => api.deleteBook(id),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["books"] }),
  });
}

// --- Library collections (Mihon-style categories) ---

export function useCollections() {
  return useQuery({ queryKey: ["collections"], queryFn: api.getCollections });
}

export function useCreateCollection() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (name: string) => api.createCollection(name),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["collections"] }),
  });
}

export function useUpdateCollection() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ id, ...body }: { id: number; name?: string; sort_order?: number }) =>
      api.updateCollection(id, body),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["collections"] }),
  });
}

export function useDeleteCollection() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (id: number) => api.deleteCollection(id),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["collections"] });
      qc.invalidateQueries({ queryKey: ["books"] }); // memberships changed
    },
  });
}

export function useSetBookCollections() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ id, collectionIds }: { id: number; collectionIds: number[] }) =>
      api.setBookCollections(id, collectionIds),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["books"] }),
  });
}

export function useSetRating(bookId: number) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (rating: number) => api.setRating(bookId, rating),
    onSuccess: (book) => {
      qc.setQueryData(["book", bookId], book);
      qc.invalidateQueries({ queryKey: ["books"] });
    },
  });
}

export function useUpdateBookChapters(bookId: number) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: () => api.updateBookChapters(bookId),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["jobs"] }),
  });
}

export function useImportEpubs() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (files: File[]) => api.importEpubs(files),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["books"] }),
  });
}

export function useAddEpubs(bookId: number) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (files: File[]) => api.addEpubs(bookId, files),
    onSuccess: (book) => {
      qc.setQueryData(["book", bookId], book);
      qc.invalidateQueries({ queryKey: ["books"] });
      qc.invalidateQueries({ queryKey: ["chapters", bookId] });
      qc.invalidateQueries({ queryKey: ["progress", bookId] });
    },
  });
}

export function useSettings() {
  return useQuery({ queryKey: ["settings"], queryFn: api.getSettings });
}

export function useUpdateSettings() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: Partial<import("./types").AppSettings>) =>
      api.updateSettings(body),
    onSuccess: (data) => qc.setQueryData(["settings"], data),
  });
}

export function useReorderBooks() {
  // No cache invalidation: the library applies the new order optimistically and
  // the backend is now the source of truth. Refetching here would re-render the
  // grid and re-register every sortable card, making the next drag feel laggy.
  return useMutation({
    mutationFn: (orderedIds: number[]) => api.reorderBooks(orderedIds),
  });
}

const TERMINAL = new Set(["completed", "failed", "cancelled"]);

/**
 * Subscribe to a job's live SSE progress stream. Returns the latest payload,
 * or null until the first event. Closes automatically on terminal events.
 */
export function useJobStream(jobId: string | undefined, enabled = true) {
  const [progress, setProgress] = useState<JobProgress | null>(null);

  useEffect(() => {
    if (!jobId || !enabled || typeof window === "undefined") return;
    const es = new EventSource(jobEventsUrl(jobId));

    const handle = (e: MessageEvent) => {
      try {
        setProgress(JSON.parse(e.data) as JobProgress);
      } catch {
        /* ignore malformed event */
      }
    };
    es.addEventListener("snapshot", handle);
    es.addEventListener("progress", handle);
    for (const ev of TERMINAL) {
      es.addEventListener(ev, (e) => {
        handle(e as MessageEvent);
        es.close();
      });
    }
    es.onerror = () => es.close();

    return () => es.close();
  }, [jobId, enabled]);

  return progress;
}

export type { Job };
