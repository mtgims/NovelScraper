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

// --- Native OS (Web Speech API) voices -----------------------------------

export type OsVoice = { voiceURI: string; name: string; lang: string };

// A readable name for a BCP-47 primary subtag. Falls back to the raw code for
// languages we don't have a label for (so nothing is ever hidden).
const OS_LANG_LABELS: Record<string, string> = {
  en: "English", es: "Spanish", fr: "French", de: "German", it: "Italian",
  pt: "Portuguese", nl: "Dutch", ru: "Russian", pl: "Polish", tr: "Turkish",
  ar: "Arabic", hi: "Hindi", ja: "Japanese", ko: "Korean", zh: "Chinese",
  sv: "Swedish", da: "Danish", fi: "Finnish", nb: "Norwegian", no: "Norwegian",
  cs: "Czech", el: "Greek", he: "Hebrew", th: "Thai", id: "Indonesian",
  vi: "Vietnamese", ro: "Romanian", hu: "Hungarian", uk: "Ukrainian",
};

// Region subtag → short suffix, so "English (US)" and "English (UK)" stay apart.
const OS_REGION_LABELS: Record<string, string> = {
  US: "US", GB: "UK", AU: "AU", CA: "CA", IN: "IN", IE: "IE", ZA: "ZA",
  NZ: "NZ", ES: "Spain", MX: "Mexico", BR: "Brazil", PT: "Portugal",
  FR: "France", CN: "Mainland", TW: "Taiwan", HK: "Hong Kong",
};

/** Human label for a BCP-47 tag, e.g. "en-US" → "English (US)". */
function osLangLabel(tag: string): string {
  const [primaryRaw, regionRaw] = tag.split("-");
  const primary = (primaryRaw || tag).toLowerCase();
  const base = OS_LANG_LABELS[primary] ?? (primaryRaw || tag).toUpperCase();
  const region = regionRaw ? OS_REGION_LABELS[regionRaw.toUpperCase()] : undefined;
  return region ? `${base} (${region})` : base;
}

// The OS never reports gender, so guess it from the voice name: explicit words,
// then a small list of common gendered engine/system voice names. Unknown → null
// (that voice stays under a language-only group rather than a wrong one).
const FEMALE_NAMES =
  /\b(female|woman|girl|samantha|victoria|karen|moira|tessa|fiona|amelie|anna|ellen|joana|luciana|paulina|zosia|milena|alva|sara|zuzana|kyoko|mei|ting|yuna|nora|catherine|serena|zira|susan|hazel|linda|heather|zoe|allison|ava)\b/i;
const MALE_NAMES =
  /\b(male|man|boy|alex|daniel|fred|tom|thomas|oliver|aaron|arthur|diego|jorge|juan|carlos|luca|maged|otoya|david|mark|george|james|ryan|paul|rishi|reed)\b/i;

function osGender(name: string): "f" | "m" | null {
  if (FEMALE_NAMES.test(name)) return "f";
  if (MALE_NAMES.test(name)) return "m";
  return null;
}

/** Group OS voices by language (then gender when derivable from the name) into
 *  the same `VoiceGroup` shape as `groupVoices`, so the picker renders alike.
 *  `uiLang` (e.g. navigator.language) floats the matching language to the top. */
export function groupOsVoices(voices: OsVoice[], uiLang = ""): VoiceGroup[] {
  const uiPrimary = uiLang.split("-")[0].toLowerCase();
  const buckets = new Map<string, { id: string; name: string }[]>();
  const langOf = new Map<string, string>(); // group label → primary subtag (sorting)

  for (const v of voices) {
    const primary = (v.lang.split("-")[0] || "zz").toLowerCase();
    const g = osGender(v.name);
    const label = g
      ? `${osLangLabel(v.lang)} — ${g === "f" ? "Female" : "Male"}`
      : osLangLabel(v.lang);
    langOf.set(label, primary);
    (buckets.get(label) ?? buckets.set(label, []).get(label)!).push({
      id: v.voiceURI,
      name: v.name,
    });
  }

  return [...buckets.keys()]
    .sort((a, b) => {
      // UI language first, then alphabetical by label.
      const ua = langOf.get(a) === uiPrimary ? 0 : 1;
      const ub = langOf.get(b) === uiPrimary ? 0 : 1;
      return ua - ub || a.localeCompare(b);
    })
    .map((label) => ({
      label,
      voices: buckets.get(label)!.sort((x, y) => x.name.localeCompare(y.name)),
    }));
}
