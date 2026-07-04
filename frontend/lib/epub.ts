// EPUB file-input helpers, shared by the import card (new scrape) and the
// "Add EPUB" button (book page) so the accept string and extension filter can't
// drift apart.

/** `accept` value for EPUB <input type="file">. */
export const EPUB_ACCEPT = ".epub,application/epub+zip";

/** Keep only .epub files from a picker's FileList (browsers don't enforce
 *  `accept`, and some report an empty MIME type). */
export function pickEpubs(files: FileList | File[] | null): File[] {
  return Array.from(files ?? []).filter((f) =>
    f.name.toLowerCase().endsWith(".epub")
  );
}
