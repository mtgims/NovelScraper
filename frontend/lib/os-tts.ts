// Native OS text-to-speech via the Web Speech API (`window.speechSynthesis`).
// Unlike the Kokoro engines (server/WebGPU, see browser-tts.ts) this synthesizes
// on the *operating system* using its installed voices — no PC, no GPU. It speaks
// one sentence per utterance so the reader's existing sentence-highlight model
// works: `onSentence(i)` fires as each sentence starts, `onDone` when the chapter
// ends.
//
// Trade-off (inherent to the API, not a bug): there is no audio buffer and no
// MediaSession transport, and browsers generally stop speech when the screen
// locks / tab backgrounds. So OS voices are a *screen-on* option; the Server
// engine remains the one for reliable locked-screen narration.

import type { OsVoice } from "./voices";

/** Whether the Web Speech API is present. */
export function osTtsUsable(): boolean {
  return (
    typeof window !== "undefined" &&
    "speechSynthesis" in window &&
    "SpeechSynthesisUtterance" in window
  );
}

/** The device's installed voices. The list is often empty on the first call
 *  (Chrome loads it async), so wait for `voiceschanged` once if needed. */
export function osVoices(): Promise<OsVoice[]> {
  if (!osTtsUsable()) return Promise.resolve([]);
  const read = (): OsVoice[] =>
    window.speechSynthesis
      .getVoices()
      .map((v) => ({ voiceURI: v.voiceURI, name: v.name, lang: v.lang }));

  const now = read();
  if (now.length > 0) return Promise.resolve(now);

  return new Promise((resolve) => {
    let done = false;
    const finish = () => {
      if (done) return;
      done = true;
      window.speechSynthesis.removeEventListener("voiceschanged", finish);
      resolve(read());
    };
    window.speechSynthesis.addEventListener("voiceschanged", finish);
    // Fallback: some browsers never fire the event but do populate the list.
    setTimeout(finish, 1000);
  });
}

type SpeakOptions = {
  voiceURI?: string;
  rate: number;
  onSentence: (globalIndex: number) => void;
  onDone: () => void;
  onError?: () => void;
};

/**
 * Speaks a flat array of sentences, one utterance at a time, starting at
 * `fromIndex`. A single speaker owns the global `speechSynthesis` queue; calling
 * `speak()` again (or `cancel()`) supersedes any in-progress run via a generation
 * guard, so voice/speed changes and seeks can't leave a stale utterance chained.
 */
export class OsTtsSpeaker {
  private gen = 0;
  private index = 0;
  private paused = false;
  private keepAlive: ReturnType<typeof setInterval> | null = null;

  /** The sentence index currently being (or about to be) spoken. */
  get currentIndex(): number {
    return this.index;
  }

  get isPaused(): boolean {
    return this.paused;
  }

  speak(sentences: string[], fromIndex: number, opts: SpeakOptions): void {
    if (!osTtsUsable()) {
      opts.onError?.();
      return;
    }
    const gen = ++this.gen;
    this.index = Math.max(0, Math.min(fromIndex, sentences.length));
    this.paused = false;
    window.speechSynthesis.cancel(); // clear anything queued from a prior run

    const voice =
      opts.voiceURI != null
        ? window.speechSynthesis.getVoices().find((v) => v.voiceURI === opts.voiceURI)
        : undefined;

    const speakNext = () => {
      if (gen !== this.gen) return; // superseded by a newer speak()/cancel()
      if (this.index >= sentences.length) {
        this.stopKeepAlive();
        opts.onDone();
        return;
      }
      const i = this.index;
      const u = new SpeechSynthesisUtterance(sentences[i]);
      if (voice) u.voice = voice;
      // Web Speech rate is 0.1–10; our slider is 0.5–2, so pass it through.
      u.rate = Math.max(0.1, Math.min(10, opts.rate));
      u.onstart = () => {
        if (gen === this.gen) opts.onSentence(i);
      };
      u.onend = () => {
        if (gen !== this.gen) return;
        this.index = i + 1;
        speakNext();
      };
      u.onerror = (e) => {
        if (gen !== this.gen) return;
        // "interrupted"/"canceled" are our own cancel() — not real failures.
        if (e.error === "interrupted" || e.error === "canceled") return;
        this.index = i + 1; // skip the offending sentence, keep going
        speakNext();
      };
      window.speechSynthesis.speak(u);
    };

    this.startKeepAlive();
    speakNext();
  }

  pause(): void {
    if (!osTtsUsable()) return;
    this.paused = true;
    this.stopKeepAlive();
    try {
      window.speechSynthesis.pause();
    } catch {
      /* ignore */
    }
  }

  resume(): void {
    if (!osTtsUsable()) return;
    this.paused = false;
    this.startKeepAlive();
    try {
      window.speechSynthesis.resume();
    } catch {
      /* ignore */
    }
  }

  cancel(): void {
    this.gen++; // supersede any pending onend chain
    this.paused = false;
    this.stopKeepAlive();
    if (osTtsUsable()) {
      try {
        window.speechSynthesis.cancel();
      } catch {
        /* ignore */
      }
    }
  }

  // Chrome silently pauses utterances that run past ~15s; a periodic
  // pause()+resume() keeps long sentences flowing. No-op elsewhere.
  private startKeepAlive(): void {
    if (this.keepAlive || !osTtsUsable()) return;
    this.keepAlive = setInterval(() => {
      if (this.paused) return;
      const ss = window.speechSynthesis;
      if (ss.speaking && !ss.paused) {
        ss.pause();
        ss.resume();
      }
    }, 10000);
  }

  private stopKeepAlive(): void {
    if (this.keepAlive) {
      clearInterval(this.keepAlive);
      this.keepAlive = null;
    }
  }
}
