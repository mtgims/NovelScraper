// NovelScraper source plugin (LNReader plugin format): novel-bin.com.
// Listing rows and novel details come from the site's pages. The novel page lists
// only the first 30 chapters, but chapter addresses are numbered in order
// (chapter-1 ... chapter-N) and the page states N, so the rest are filled in.
const { fetchApi } = require('@libs/fetch');
const { load } = require('cheerio');
const { NovelStatus } = require('@libs/novelStatus');

const site = 'https://novel-bin.com/';

const absolute = u => (!u ? undefined : u.startsWith('http') ? u : site + u.replace(/^\//, ''));
const STATUS = { ongoing: NovelStatus.Ongoing, completed: NovelStatus.Completed, full: NovelStatus.Completed };

async function page(url) {
  const res = await fetchApi(url, { headers: { Referer: site } });
  if (res.status === 429) throw new Error('novel-bin.com is refusing requests right now (too many); try again later');
  if (!res.ok) throw new Error(`novel-bin.com answered ${res.status}`);
  return load(await res.text());
}

function rows($) {
  return $('a.almanac-book-row').map((_, el) => {
    const a = $(el);
    return {
      name: (a.attr('title') || a.find('h3').first().text()).trim(),
      path: a.attr('href').replace(/^\//, ''),
      cover: absolute(a.find('img').first().attr('src')),
    };
  }).get().filter(n => n.name && n.path.startsWith('novel-bin/'));
}

class NovelBin {
  id = 'novelscraper.novelbin';
  name = 'Novel Bin (novel-bin.com)';
  site = site;
  version = '1.0.0';
  icon = '';

  async popularNovels(pageNo, { showLatestNovels }) {
    return rows(await page(`${site}${showLatestNovels ? 'dayvisit' : 'allvisit'}/?page=${pageNo}`));
  }

  async searchNovels(searchTerm, pageNo) {
    return rows(await page(`${site}search?keyword=${encodeURIComponent(searchTerm)}&page=${pageNo}`));
  }

  async parseNovel(novelPath) {
    const path = novelPath.replace(/^\//, '').replace(/\/?$/, '/');
    const $ = await page(site + path);
    const meta = p => $(`meta[property="${p}"]`).attr('content');
    const named = new Map();
    $('.almanac-chapter-list a').each((_, el) => {
      const href = $(el).attr('href') || '';
      const n = Number((href.match(/chapter-(\d+)$/) || [])[1]);
      if (n) named.set(n, $(el).find('strong').first().text().trim());
    });
    let total = 0;
    $('dt').each((_, el) => {
      if ($(el).text().trim() === 'Chapters') total = Number($(el).next('dd').text().replace(/[^0-9]/g, '')) || 0;
    });
    const latest = Number(((meta('og:novel:lastest_chapter_url') || '').match(/chapter-(\d+)/) || [])[1]) || 0;
    const count = Math.max(total, latest, ...named.keys(), 0);
    const chapters = [];
    for (let i = 1; i <= count; i++) {
      chapters.push({ name: named.get(i) || `Chapter ${i}`, path: `${path}chapter-${i}`, chapterNumber: i });
    }
    const status = (meta('og:novel:status') || '').toLowerCase();
    return {
      name: meta('og:novel:novel_name') || $('h1').first().text().trim(),
      path,
      cover: absolute(meta('og:image')),
      author: meta('og:novel:author'),
      genres: meta('og:novel:genre'),
      summary: $('[class*="synopsis"]').first().text().trim() || meta('og:description'),
      status: STATUS[status] || NovelStatus.Unknown,
      chapters,
    };
  }

  async parseChapter(chapterPath) {
    const $ = await page(site + chapterPath.replace(/^\//, ''));
    for (const sel of ['#chr-content', '.chapter-content', '[class*="reader-content"]', '[class*="chapter-body"]', 'article']) {
      const body = $(sel).first();
      if (body.length && body.text().trim().length > 200) {
        body.find('script, style, ins, .ads, [class*="ad-"]').remove();
        return body.html();
      }
    }
    throw new Error('No chapter text on the page');
  }

  resolveUrl = (path, isNovel) => site + path.replace(/^\//, '');
}

exports.default = new NovelBin();
