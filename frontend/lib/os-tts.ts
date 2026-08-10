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
  private sentences: string[] = [];
  private voice: SpeechSynthesisVoice | undefined;
  private rate = 1;
  private onSentence: (i: number) => void = () => {};
  private onDone: () => void = () => {};
  private onError: (() => void) | undefined;
  private awaiting = -1; // index whose utterance is currently active (-1 = none)
  private ended = false; // did onend/onerror already advance past `awaiting`?
  private stalls = 0; // consecutive watchdog ticks the engine has been idle
  private watchdog: ReturnType<typeof setInterval> | null = null;

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
    this.sentences = sentences;
    this.index = Math.max(0, Math.min(fromIndex, sentences.length));
    this.paused = false;
    // Idle until speakNext arms a sentence, so the watchdog can't advance during
    // the deferred-start window below.
    this.awaiting = -1;
    this.ended = true;
    this.stalls = 0;
    this.rate = Math.max(0.1, Math.min(10, opts.rate));
    this.onSentence = opts.onSentence;
    this.onDone = opts.onDone;
    this.onError = opts.onError;
    this.voice =
      opts.voiceURI != null
        ? window.speechSynthesis.getVoices().find((v) => v.voiceURI === opts.voiceURI)
        : undefined;

    const ss = window.speechSynthesis;
    const begin = () => {
      if (gen === this.gen) this.speakNext(gen);
    };
    if (ss.speaking || ss.pending) {
      // Chrome drops an utterance queued in the same tick as cancel(); let it
      // settle before starting the new run.
      ss.cancel();
      setTimeout(begin, 130);
    } else {
      begin();
    }
    this.startWatchdog();
  }

  private speakNext(gen: number): void {
    if (gen !== this.gen) return; // superseded by a newer speak()/cancel()
    if (this.index >= this.sentences.length) {
      this.finishRun();
      this.onDone();
      return;
    }
    const i = this.index;
    const u = new SpeechSynthesisUtterance(this.sentences[i]);
    if (this.voice) u.voice = this.voice;
    u.rate = this.rate; // Web Speech rate 0.1–10; our slider (0.5–2) passes through
    this.awaiting = i;
    this.ended = false;
    this.stalls = 0;
    u.onstart = () => {
      if (gen === this.gen) this.onSentence(i);
    };
    u.onend = () => this.advance(gen, i);
    u.onerror = (e) => {
      // "interrupted"/"canceled" are our own cancel() — not real failures.
      if (e.error === "interrupted" || e.error === "canceled") return;
      this.advance(gen, i);
    };
    window.speechSynthesis.speak(u);
  }

  // Move to the next sentence exactly once per utterance (guarded so a real
  // onend and the watchdog can't both advance).
  private advance(gen: number, i: number): void {
    if (gen !== this.gen || this.ended || this.awaiting !== i) return;
    this.ended = true;
    this.index = i + 1;
    this.speakNext(gen);
  }

  pause(): void {
    if (!osTtsUsable()) return;
    this.paused = true;
    try {
      window.speechSynthesis.pause();
    } catch {
      /* ignore */
    }
  }

  resume(): void {
    if (!osTtsUsable()) return;
    this.paused = false;
    try {
      window.speechSynthesis.resume();
    } catch {
      /* ignore */
    }
  }

  cancel(): void {
    this.gen++; // supersede any pending onend chain
    this.paused = false;
    this.finishRun();
    if (osTtsUsable()) {
      try {
        window.speechSynthesis.cancel();
      } catch {
        /* ignore */
      }
    }
  }

  // Android/Chrome frequently stop speaking WITHOUT firing `onend`, which would
  // otherwise strand narration after one sentence (~5s). Poll for the engine
  // going idle mid-run and treat it as the current sentence having finished.
  private startWatchdog(): void {
    if (this.watchdog) return;
    this.watchdog = setInterval(() => {
      if (this.paused || !osTtsUsable()) return;
      const ss = window.speechSynthesis;
      if (ss.speaking || ss.pending) {
        this.stalls = 0;
        return;
      }
      if (this.ended || this.awaiting < 0) return; // already between sentences
      this.stalls += 1;
      if (this.stalls >= 2) {
        // ~1.4s idle while a sentence was in flight → onend was dropped; advance.
        this.stalls = 0;
        this.advance(this.gen, this.awaiting);
      }
    }, 700);
  }

  private finishRun(): void {
    this.awaiting = -1;
    this.ended = true;
    this.stalls = 0;
    if (this.watchdog) {
      clearInterval(this.watchdog);
      this.watchdog = null;
    }
  }
}
