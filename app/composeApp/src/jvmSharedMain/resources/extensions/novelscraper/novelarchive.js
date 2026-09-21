// NovelScraper source plugin (LNReader plugin format): novelarchive.cc.
// The site is a JavaScript app over a JSON API, which this uses directly.
const { fetchApi } = require('@libs/fetch');
const { NovelStatus } = require('@libs/novelStatus');

const site = 'https://novelarchive.cc/';

const escape = s => String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
const absolute = u => (!u ? undefined : u.startsWith('http') ? u : site + u.replace(/^\//, ''));

async function api(path) {
  const res = await fetchApi(site + path);
  if (!res.ok) throw new Error(`novelarchive.cc answered ${res.status}`);
  return res.json();
}

const toItem = n => ({ name: n.title, path: `novel?id=${n.id}`, cover: absolute(n.cover_url || n.image_url) });
const idOf = path => (path.match(/[?&](?:id|novel)=([0-9a-fA-F]+)/) || [])[1];

class NovelArchive {
  id = 'novelscraper.novelarchive';
  name = 'Novel Archive';
  site = site;
  version = '1.0.0';
  icon = '';

  async popularNovels(page, { showLatestNovels }) {
    const data = await api(`api/novels?sort=${showLatestNovels ? 'recent' : 'popular'}&page=${page}`);
    return data.novels.map(toItem);
  }

  async searchNovels(searchTerm, page) {
    const data = await api(`api/novels?search=${encodeURIComponent(searchTerm)}&page=${page}`);
    return data.novels.map(toItem);
  }

  async parseNovel(novelPath) {
    const id = idOf(novelPath);
    const { novel } = await api(`api/novels/${id}`);
    return {
      name: novel.title,
      path: novelPath,
      cover: absolute(novel.cover_url || novel.image_url),
      author: novel.author,
      genres: novel.genres,
      summary: novel.description,
      status: NovelStatus.Unknown,
      // The site numbers chapters from 1, in this order.
      chapters: (novel.chapter_names || []).map((name, i) => ({
        name,
        path: `reader?novel=${id}&chapter=${i + 1}`,
        chapterNumber: i + 1,
      })),
    };
  }

  async parseChapter(chapterPath) {
    const id = idOf(chapterPath);
    const n = (chapterPath.match(/[?&]chapter=(\d+)/) || [])[1];
    const { chapter } = await api(`api/novels/${id}/chapters/${n}`);
    // Plain text, one paragraph per line.
    return String(chapter.content || '')
      .split(/\r?\n/)
      .map(l => l.trim())
      .filter(Boolean)
      .map(l => `<p>${escape(l)}</p>`)
      .join('\n');
  }

  resolveUrl = (path, isNovel) => site + path;
}

exports.default = new NovelArchive();
