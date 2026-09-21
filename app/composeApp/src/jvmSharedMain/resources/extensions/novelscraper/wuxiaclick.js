// NovelScraper source plugin (LNReader plugin format): wuxia.click, a site of
// the WuxiaWorld.eu family. Lists come from its JSON API; chapter text from the
// chapter page (its paragraphs are marked data-tts-paragraph).
const { fetchApi } = require('@libs/fetch');
const { load } = require('cheerio');
const { NovelStatus } = require('@libs/novelStatus');

const site = 'https://wuxia.click/';
const PAGE = 18;

async function api(path) {
  const res = await fetchApi(site + 'api/' + path);
  if (!res.ok) throw new Error(`wuxia.click answered ${res.status}`);
  return res.json();
}

const toItem = n => ({ name: n.name, path: `novel/${n.slug}`, cover: n.image || undefined });
const slugOf = path => path.replace(/^\/?novel\//, '').replace(/\/$/, '');

// The site's status codes (e.g. "CD" for completed).
const STATUS = { CD: NovelStatus.Completed, OG: NovelStatus.Ongoing, ON: NovelStatus.Ongoing, HI: NovelStatus.OnHiatus };

class WuxiaClick {
  id = 'novelscraper.wuxiaclick';
  name = 'Wuxia Click';
  site = site;
  version = '1.0.0';
  icon = '';

  async popularNovels(page, { showLatestNovels }) {
    const order = showLatestNovels ? '-created_at' : '-total_views';
    const data = await api(`novels/?limit=${PAGE}&offset=${(page - 1) * PAGE}&order=${order}`);
    return data.results.map(toItem);
  }

  async searchNovels(searchTerm, page) {
    const data = await api(`search/?search=${encodeURIComponent(searchTerm)}&limit=${PAGE}&offset=${(page - 1) * PAGE}`);
    return data.results.map(toItem);
  }

  async parseNovel(novelPath) {
    const slug = slugOf(novelPath);
    const novel = await api(`novels/${slug}/`);
    const chapters = await api(`chapters/${slug}/`);
    return {
      name: novel.name,
      path: `novel/${slug}`,
      cover: novel.image || novel.original_image || undefined,
      author: novel.author ? novel.author.name : undefined,
      genres: (novel.categories || []).map(c => c.name).join(', '),
      summary: novel.description,
      status: STATUS[novel.status] || NovelStatus.Unknown,
      chapters: chapters
        .slice()
        .sort((a, b) => a.index - b.index)
        .map(c => ({
          name: c.title,
          path: `chapter/${c.novSlugChapSlug}`,
          releaseTime: c.timeAdded,
          chapterNumber: c.index,
        })),
    };
  }

  async parseChapter(chapterPath) {
    const res = await fetchApi(site + chapterPath.replace(/^\//, ''));
    if (!res.ok) throw new Error(`wuxia.click answered ${res.status}`);
    const $ = load(await res.text());
    const paragraphs = $('[data-tts-paragraph]').map((_, el) => `<p>${$(el).html()}</p>`).get();
    if (!paragraphs.length) throw new Error('No chapter text on the page');
    return paragraphs.join('\n');
  }

  resolveUrl = (path, isNovel) => site + path.replace(/^\//, '');
}

exports.default = new WuxiaClick();
