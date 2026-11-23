export const POPULAR_PRIORITY_TITLES = [
  'Date A Live', 'Date A Live II', 'Date A Live III', 'Date A Live IV', 'Date A Live V',
  'Date A Live Movie: Mayuri Judgment', 'Date A Bullet: Dead or Bullet', 'Date A Bullet: Nightmare or Queen',
  'Solo Leveling', 'Solo Leveling Season 2',
  'Sousou no Frieren', 'Sousou no Frieren Season 2',
  'Spy x Family', 'Spy x Family Season 2', 'Spy x Family Season 3',
  'Oshi no Ko', 'Oshi no Ko Season 2', 'Oshi no Ko Season 3',
  'Jujutsu Kaisen', 'Jujutsu Kaisen Season 2',
  'Kimetsu no Yaiba', 'Kimetsu no Yaiba Season 2', 'Kimetsu no Yaiba Season 3', 'Kimetsu no Yaiba Season 4',
  'Shingeki no Kyojin', 'Shingeki no Kyojin Season 2', 'Shingeki no Kyojin Season 3',
  'Chainsaw Man', 'One Punch Man', 'One Punch Man Season 2',
  'Boku no Hero Academia', 'Boku no Hero Academia Season 2', 'Boku no Hero Academia Season 6', 'Boku no Hero Academia Season 7',
  'Dandadan', 'Kaiju No. 8', 'Mashle', 'Mashle Season 2',
  'Blue Lock', 'Blue Lock Season 2', 'Vinland Saga', 'Vinland Saga Season 2',
  'Violet Evergarden', 'Bocchi the Rock!', 'Horimiya',
  'Kaguya-sama: Love is War', 'Kaguya-sama: Love is War Season 2', 'Kaguya-sama: Love is War Season 3',
  'Re:Zero kara Hajimeru Isekai Seikatsu', 'Re:Zero kara Hajimeru Isekai Seikatsu Season 2', 'Re:Zero kara Hajimeru Isekai Seikatsu Season 3',
  'Mushoku Tensei', 'Mushoku Tensei Season 2',
  'Tensei shitara Slime Datta Ken', 'Tensei shitara Slime Datta Ken Season 2', 'Tensei shitara Slime Datta Ken Season 3',
  'Overlord', 'Overlord II', 'Overlord III', 'Overlord IV',
  'Kono Subarashii Sekai ni Shukufuku wo!', 'Kono Subarashii Sekai ni Shukufuku wo! 2', 'Kono Subarashii Sekai ni Shukufuku wo! 3',
  'Sword Art Online', 'Sword Art Online II',
  'Tokyo Ghoul', 'Tokyo Ghoul Season 2', 'Tokyo Ghoul Season 3',
  'Tokyo Revengers', 'Tokyo Revengers Season 2',
  'Steins;Gate', 'Steins;Gate 0', 'Death Note', 'Code Geass',
  'Dr. Stone', 'Dr. Stone Season 2', 'Dr. Stone Season 3',
  'Youkoso Jitsuryoku Shijou Shugi no Kyoushitsu e', 'Youkoso Jitsuryoku Shijou Shugi no Kyoushitsu e Season 2', 'Youkoso Jitsuryoku Shijou Shugi no Kyoushitsu e Season 3',
  'Kage no Jitsuryokusha ni Naritakute!', 'Kage no Jitsuryokusha ni Naritakute! Season 2',
  'Mob Psycho 100', 'Mob Psycho 100 II', 'Mob Psycho 100 III',
  'Cyberpunk: Edgerunners', '86', 'Yahari Ore no Seishun Love Comedy wa Machigatteiru.',
  'Kusuriya no Hitorigoto', 'Wind Breaker', 'Shangri-La Frontier', 'Sakamoto Days',
  'Jigokuraku', 'Lycoris Recoil', 'Ousama Ranking', 'Ao no Hako',
  'Tokidoki Bosotto Russia-go de Dereru Tonari no Alya-san', 'Make Heroine ga Oosugiru!',
  'Tsue to Tsurugi no Wistoria', 'Undead Unluck',
];

export function compactTitleKey(raw) {
  return String(raw || '')
    .normalize('NFKD')
    .replace(/[\u0300-\u036f]/g, '')
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '');
}

export function prioritizePopularCatalog(allCatalog, maxAnimes, popularOnly, parseTitleMeta, extractMatchableTitles) {
  const priorityRank = new Map();
  POPULAR_PRIORITY_TITLES.forEach((t, idx) => {
    const meta = parseTitleMeta(t);
    const k = `${compactTitleKey(meta.base)}::s${meta.season ?? 1}::${meta.formatKind}`;
    if (!priorityRank.has(k)) priorityRank.set(k, idx);
    const rawKey = compactTitleKey(t);
    if (!priorityRank.has(rawKey)) priorityRank.set(rawKey, idx);
  });

  const popularMatches = [];
  const restIncomplete = [];
  const restComplete = [];

  for (const anime of allCatalog) {
    const titles = extractMatchableTitles(anime);
    let bestRank = Infinity;
    for (const t of titles) {
      const meta = parseTitleMeta(t);
      const k = `${compactTitleKey(meta.base)}::s${meta.season ?? 1}::${meta.formatKind}`;
      const r1 = priorityRank.get(k);
      const r2 = priorityRank.get(compactTitleKey(t));
      const r = Math.min(r1 ?? Infinity, r2 ?? Infinity);
      if (r < bestRank) bestRank = r;
    }
    if (bestRank < Infinity) {
      popularMatches.push({ anime, rank: bestRank });
    } else if (!anime.episodes_total || Number(anime.episodes_total) <= 0) {
      restIncomplete.push(anime);
    } else {
      restComplete.push(anime);
    }
  }

  popularMatches.sort((a, b) => a.rank - b.rank || (Number(b.anime.popularity || 0) - Number(a.anime.popularity || 0)));
  const orderedPopular = popularMatches.map((x) => x.anime);
  if (popularOnly) {
    return orderedPopular.slice(0, maxAnimes);
  }
  return [...orderedPopular, ...restIncomplete, ...restComplete].slice(0, maxAnimes);
}

export function buildCuratedHostersPatchPayload(collectedEntries, version = 1) {
  let episodeCount = 0;
  let hosterLinks = 0;
  const entries = [];
  for (const item of collectedEntries) {
    const episodesObj = {};
    for (const epItem of item.episodes || []) {
      const list = (epItem.hosters || [])
        .filter((h) => h && typeof h.url === 'string' && h.url.startsWith('https://'))
        .slice(0, 6)
        .map((h) => ({
          q: h.quality || '720p',
          c: h.container || 'mp4',
          h: h.hoster || 'Hoster',
          u: h.url,
          p: h.provider || 'otakudesu',
          ...(h.referer ? { r: h.referer } : {}),
        }));
      if (list.length > 0) {
        episodesObj[String(epItem.ep)] = list;
        episodeCount++;
        hosterLinks += list.length;
      }
    }
    if (Object.keys(episodesObj).length > 0) {
      entries.push({
        id: item.anime_id,
        title: item.title,
        title_key: compactTitleKey(item.title),
        episodes: episodesObj,
      });
    }
  }
  return {
    version,
    source: 'curated-hosters-seed',
    generated_at: new Date().toISOString(),
    stats: {
      anime_count: entries.length,
      episode_count: episodeCount,
      hoster_links: hosterLinks,
    },
    entries,
  };
}
