// Kokoro voice ids encode language + gender in a two-letter prefix, e.g.
// "af_heart" = American Female "Heart", "bm_george" = British Male "George".
// This turns the flat id list into groups for the voice picker: one group per
// (language, gender), with clean display names (no "af"/"am" prefix).

const LANG_LABELS: Record<string, string> = {
  a: "American English",
  b: "British English",
  e: "Spanish",
  f: "French",
  h: "Hindi",
  i: "Italian",
  j: "Japanese",
  p: "Brazilian Portuguese",
  z: "Mandarin Chinese",
};
// Display order of languages (unknown ones sort last).
const LANG_ORDER = Object.keys(LANG_LABELS);

export type VoiceGroup = {
  label: string; // e.g. "American English — Female"
  voices: { id: string; name: string }[];
};

function titleCase(raw: string): string {
  return raw
    .split("_")
    .map((w) => (w ? w[0].toUpperCase() + w.slice(1) : w))
    .join(" ");
}

/** Group voice ids by language + gender, with clean display names. Ids that
 *  don't match the Kokoro pattern fall into an "Other" group, unchanged. */
export function groupVoices(ids: string[]): VoiceGroup[] {
  const buckets = new Map<string, { id: string; name: string }[]>();
  for (const id of ids) {
    const m = /^([a-z])([fm])_(.+)$/.exec(id);
    const key = m ? `${m[1]}${m[2]}` : "~other";
    const name = m ? titleCase(m[3]) : id;
    (buckets.get(key) ?? buckets.set(key, []).get(key)!).push({ id, name });
  }

  const langRank = (lang: string) => {
    const i = LANG_ORDER.indexOf(lang);
    return i < 0 ? LANG_ORDER.length : i;
  };

  return [...buckets.keys()]
    .sort((a, b) => langRank(a[0]) - langRank(b[0]) || a.localeCompare(b)) // lang, then f<m
    .map((key) => {
      const lang = LANG_LABELS[key[0]];
      const label = lang
        ? `${lang} — ${key[1] === "f" ? "Female" : "Male"}`
        : "Other";
      return {
        label,
        voices: buckets.get(key)!.sort((x, y) => x.name.localeCompare(y.name)),
      };
    });
}
