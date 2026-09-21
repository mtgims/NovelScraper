// NovelScraper source plugin (LNReader plugin format): openquill.net.
// Lists and search come from the site's own search API; a story's details from
// its page (schema.org data and the chapter list), chapter text from the reader.
const { fetchApi } = require('@libs/fetch');
const { load } = require('cheerio');
const { NovelStatus } = require('@libs/novelStatus');

const site = 'https://openquill.net/';

const absolute = u => (!u ? undefined : u.startsWith('http') ? u : site + u.replace(/^\//, ''));
const STATUS = { ONGOING: NovelStatus.Ongoing, COMPLETED: NovelStatus.Completed, HIATUS: NovelStatus.OnHiatus, CANCELLED: NovelStatus.Cancelled };

async function search(params) {
  const res = await fetchApi(`${site}api/stories/search?${params}`);
  if (!res.ok) throw new Error(`openquill.net answered ${res.status}`);
  const data = await res.json();
  return (data.stories || []).map(s => ({ name: s.title, path: `stories/${s.slug}`, cover: absolute(s.coverImageUrl) }));
}

async function storyPage(path, page) {
  const res = await fetchApi(site + path + (page > 1 ? `?chapterPage=${page}` : ''));
  if (!res.ok) throw new Error(`openquill.net answered ${res.status}`);
  return load(await res.text());
}

// The chapter links on a story page: "12: The Title" plus a date.
function chaptersOf($, path) {
  const seen = new Set();
  const out = [];
  $(`a[href^="/${path}/chapter/"]`).each((_, el) => {
    const href = $(el).attr('href');
    const span = $(el).find('span').first();
    // The span's own text, without badges (e.g. an "Images" marker) nested in it.
    const label = span.clone().children().remove().end().text().trim();
    if (!label || seen.has(href)) return;  // the "Read" button repeats chapter 1
    seen.add(href);
    const n = Number((href.match(/\/chapter\/(\d+)/) || [])[1]);
    out.push({
      name: label.replace(/^\d+\s*:\s*/, '') || label,
      path: href.replace(/^\//, ''),
      releaseTime: $(el).find('small').first().text().trim() || undefined,
      chapterNumber: Number.isFinite(n) ? n : undefined,
    });
  });
  return out.sort((a, b) => (a.chapterNumber || 0) - (b.chapterNumber || 0));
}

class OpenQuill {
  id = 'novelscraper.openquill';
  name = 'OpenQuill';
  site = site;
  version = '1.0.0';
  icon = '';

  async popularNovels(page, { showLatestNovels }) {
    // The site sorts by trending or by views (no "recently updated" order).
    return search(`sortBy=${showLatestNovels ? 'trending' : 'pageviews'}&page=${page}`);
  }

  async searchNovels(searchTerm, page) {
    return search(`q=${encodeURIComponent(searchTerm)}&page=${page}`);
  }

  async parseNovel(novelPath) {
    const path = novelPath.replace(/^\//, '').replace(/\/$/, '').replace(/\?.*$/, '');
    const $ = await storyPage(path, 1);
    let book = {};
    $('script[type="application/ld+json"]').each((_, el) => {
      try {
        const d = JSON.parse($(el).text());
        if (d['@type'] === 'Book') book = d;
      } catch (e) { /* not ours */ }
    });
    const genre = Array.isArray(book.genre) ? [...new Set(book.genre)].join(', ') : book.genre;
    const statusWord = ($('body').text().match(/\b(ONGOING|COMPLETED|HIATUS|CANCELLED)\b/i) || [])[1];
    // The chapter list is paged ("Page 1 of 6"); later pages come from parsePage.
    const pages = Number(($('body').text().match(/Page\s+1\s+of\s+(\d+)/) || [])[1]) || 1;
    return {
      name: book.name || $('h1').first().text().trim(),
      path,
      cover: absolute($('meta[property="og:image"]').attr('content')),
      author: book.author ? book.author.name : undefined,
      genres: genre,
      summary: book.description,
      status: statusWord ? STATUS[statusWord.toUpperCase()] : NovelStatus.Unknown,
      chapters: chaptersOf($, path),
      totalPages: pages > 1 ? pages : undefined,
    };
  }

  async parsePage(novelPath, page) {
    const path = novelPath.replace(/^\//, '').replace(/\/$/, '').replace(/\?.*$/, '');
    return { chapters: chaptersOf(await storyPage(path, Number(page)), path) };
  }

  async parseChapter(chapterPath) {
    const res = await fetchApi(site + chapterPath.replace(/^\//, ''));
    if (!res.ok) throw new Error(`openquill.net answered ${res.status}`);
    const html = await res.text();
    const $ = load(html);
    const body = $('article.chapterReaderBody');
    if (!body.length) {
      if (/Chapter locked/i.test($('body').text())) throw new Error('This chapter is locked by its author');
      throw new Error('No chapter text on the page');
    }
    body.find('script, style, ins, .ads').remove();
    return body.html();
  }

  resolveUrl = (path, isNovel) => site + path.replace(/^\//, '');
}

exports.default = new OpenQuill();
