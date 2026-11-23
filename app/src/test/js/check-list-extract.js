// Local check of assets/list-extract.js against fixture pages in a real headless Chrome.
// Not part of CI. Run:  NODE_PATH=/opt/npm-tools/node_modules node check-list-extract.js
const fs = require('fs'), path = require('path');
const puppeteer = require('puppeteer-core');
const http = require('http');
let server, base, server2, base2;
const clickJs = fs.readFileSync(path.join(__dirname, '../../main/assets/click-row.js'), 'utf8');
const js = fs.readFileSync(path.join(__dirname, '../../main/assets/list-extract.js'), 'utf8');
let fails = 0;
function eq(name, got, want) {
  const ok = JSON.stringify(got) === JSON.stringify(want);
  if (!ok) { fails++; console.log('FAIL', name, '\n  got ', JSON.stringify(got), '\n  want', JSON.stringify(want)); } else console.log('ok  ', name);
}
async function run(page, file, recipe, learned) {
  await page.goto(base + '/' + file);
  const out = await page.evaluate(`(${js})(${recipe ? JSON.stringify(JSON.stringify(recipe)) : 'null'}, ${learned ? JSON.stringify(JSON.stringify(learned)) : 'null'})`);
  return JSON.parse(out);
}
function handler(q, res) {
  const f = path.join(__dirname, 'fixtures', path.basename(q.url.split('?')[0]));
  if (fs.existsSync(f)) { res.setHeader('content-type', 'text/html'); res.end(fs.readFileSync(f, 'utf8').replace(/__OTHER__/g, base2)); } else { res.statusCode = 404; res.end('x'); }
}
const clickSpec = (url) => JSON.parse(decodeURIComponent(url.split('#sl-click=')[1]));
(async () => {
  // a second server on another port = a different origin (stands in for a cross-origin iframe)
  server2 = http.createServer(handler);
  await new Promise(r => server2.listen(0, '127.0.0.1', r));
  base2 = 'http://127.0.0.1:' + server2.address().port;
  server = http.createServer(handler);
  await new Promise(r => server.listen(0, '127.0.0.1', r));
  base = 'http://127.0.0.1:' + server.address().port;
  const browser = await puppeteer.launch({ executablePath: process.env.CHROME || '/usr/bin/google-chrome', args: ['--no-sandbox'] });
  const page = await browser.newPage();
  await page.setViewport({ width: 1280, height: 720 });
  let r = await run(page, 'portal.html');
  console.log(r.debug);
  eq('portal titles', r.items.map(i => i.title), ['Harbour Cleanup Morning', 'Pottery for Beginners', 'Open Mic Night']);
  eq('portal subtitles', r.items.map(i => i.subtitle), ['Friends of the Bay', 'Clay Studio', 'Corner Cafe']);
  eq('portal times', r.items.map(i => i.timeText), ['Sat, Nov 21 • 9:00 AM', 'Tue, Nov 24 • 6:30 PM', 'Fri, Nov 27 • 7:00 PM']);
  eq('portal datetime', r.items[0].datetime, '2026-11-21T09:00:00');
  eq('portal live', r.items.map(i => i.liveHint), [false, false, true]);
  eq('portal urls', r.items.map(i => i.url.replace(/^.*\/e\//, '')), ['101', '102', '103']);
  r = await run(page, 'days.html');
  console.log(r.debug);
  eq('days titles', r.items.map(i => i.title), ['Rovers vs Athletic', 'Harriers v City', 'Wanderers v Town', 'Lions v Wolves']);
  eq('days sections', r.items.map(i => i.section), ['Thursday, December 3, 2026', 'Friday, December 4, 2026', 'Friday, December 4, 2026', 'Saturday, December 5, 2026']);
  eq('days times', r.items.map(i => i.timeText), ['Kickoff 7:45 PM', '20:00 · League Cup', 'Begins 21:30', '12:15 PM']);
  eq('days urls', r.items.map(i => i.url.replace(/^.*\/match\//, '')), ['1', '2', '3', '4']);
  r = await run(page, 'flat.html');
  console.log(r.debug);
  eq('flat titles', r.items.map(i => i.title), ['Chess Club Final', 'Quiz Evening', 'Film Screening']);
  eq('flat sections', r.items.map(i => i.section), ['Mon 7 Dec 2026', 'Mon 7 Dec 2026', 'Tue 8 Dec 2026']);
  eq('flat subtitles', r.items.map(i => i.subtitle), ['Hall B', 'Main bar', 'Room 4']);
  r = await run(page, 'portal.html', { host: 'x', item: 'a.row', title: '.o', time: '.pill' });
  eq('recipe method', r.method, 'recipe');
  eq('recipe title', r.items.map(i => i.title), ['Friends of the Bay', 'Clay Studio', 'Corner Cafe']);
  r = await run(page, 'portal.html', { host: 'x', item: 'a.nothing' });
  eq('recipe fallback', r.method, 'automatic');
  r = await run(page, 'portal.html', { host: 'x', item: '((' });
  eq('bad selector fallback', r.items.length, 3);

  // ---- (a) rows with only JS click handlers: kept, with #sl-click URLs carrying the title
  r = await run(page, 'click.html');
  console.log(r.debug);
  eq('click titles', r.items.map(i => i.title), ['Rovers vs Athletic', 'Harriers v City', 'Lions v Wolves']);
  eq('click urls are absolute page+fragment', r.items.every(i => i.url.startsWith(base + '/click.html#sl-click=')), true);
  eq('click url titles', r.items.map(i => clickSpec(i.url).t), ['Rovers vs Athletic', 'Harriers v City', 'Lions v Wolves']);
  eq('click url indexes', r.items.map(i => clickSpec(i.url).i), [0, 1, 2]);
  eq('click url selector', clickSpec(r.items[0].url).s, 'div.ev');
  eq('click urls distinct', new Set(r.items.map(i => i.url)).size, 3);
  eq('click times kept', r.items.map(i => i.timeText), ['Thu, Dec 3 \u2022 7:45 PM', 'Fri, Dec 4 \u2022 8:00 PM', 'Sat, Dec 5 \u2022 12:15 PM']);
  eq('click rows counted', r.clickRows, 3);
  eq('click diag line', /click fallback used for 3 row/.test(r.debug) && /text \d+ chars/.test(r.debug) && /iframes 0/.test(r.debug), true);
  // replay (click-row.js) on a fresh load of the page
  const replay = async (spec) => JSON.parse(await page.evaluate(`(${clickJs})(${JSON.stringify(JSON.stringify(spec))})`));
  await page.goto(base + '/click.html');
  let rp = await replay(clickSpec(r.items[1].url));
  eq('replay by title ok', [rp.ok, rp.how], [true, 'title']);
  eq('replay clicked the right row', await page.evaluate('window.__clicked'), 'Harriers v City');
  eq('replay SPA url change seen', new URL(page.url()).pathname, '/watch/2');
  eq('replay reports row centre', rp.x > 0 && rp.y > 0 && rp.iw === 1280, true);
  await page.goto(base + '/click.html');
  rp = await replay({ t: 'Not there', i: 2, s: 'div.ev' });
  eq('replay falls back to index', [rp.ok, rp.how, await page.evaluate('window.__clicked')], [true, 'index', 'Lions v Wolves']);
  await page.goto(base + '/click.html');
  rp = await replay({ t: 'Lions v Wolves', i: -1, s: '' });
  eq('replay without selector finds text', [rp.ok, rp.how, await page.evaluate('window.__clicked')], [true, 'text', 'Lions v Wolves']);
  await page.goto(base + '/click.html');
  rp = await replay({ t: 'Nothing like it', i: 9, s: 'div.nope' });
  eq('replay reports not found', rp.ok, false);
  r = await run(page, 'mixed.html');
  console.log(r.debug);
  eq('mixed: unlinked rows with a time kept, other junk dropped', r.items.map(i => i.title), ['Alpha Cup', 'Beta Cup', 'Gamma Cup', 'Delta Cup']);
  eq('mixed: only the unlinked event is a click row', r.items.map(i => i.url.indexOf('#sl-click=') >= 0), [false, false, true, false]);
  eq('mixed replay clicks it', await (async () => {
    await page.goto(base + '/mixed.html');
    const rr = await replay(clickSpec(r.items[2].url));
    return [rr.ok, (await page.evaluate('window.__clicked')).indexOf('Gamma Cup') >= 0];
  })(), [true, true]);
  // recipe rows without links become click rows too
  r = await run(page, 'click.html', { host: 'x', item: 'div.ev', title: 'h3' });
  eq('recipe click rows', [r.method, r.items.length, clickSpec(r.items[1].url).i], ['recipe', 3, 1]);

  // ---- (b) the list is inside a same-origin iframe: read as an extra root
  r = await run(page, 'iframe-same.html');
  console.log(r.debug);
  eq('same-origin iframe titles', r.items.map(i => i.title), ['Spring Concert', 'Book Club', 'Night Market']);
  eq('same-origin iframe urls resolve against the frame', r.items.map(i => i.url), [base + '/ev/201', base + '/ev/202', base + '/ev/203']);
  eq('same-origin iframe reported', [r.frames.length, r.frames[0].same, r.frames[0].pct >= 35], [1, true, true]);
  r = await run(page, 'iframe-same-click.html');
  eq('same-origin iframe click rows', [r.items.length, r.items.map(i => clickSpec(i.url).t)], [3, ['Spring Concert', 'Book Club', 'Night Market']]);
  await page.goto(base + '/iframe-same-click.html');
  rp = await replay(clickSpec(r.items[1].url));
  const inner = page.frames().find(f => f.url().endsWith('iframe-inner-click.html'));
  eq('replay clicks inside the iframe', [rp.ok, await inner.evaluate('window.__clicked')], [true, 'Book Club']);
  eq('replay iframe row position is in top-window coordinates', rp.x > 0 && rp.x < 1280 && rp.y > 100, true);

  // ---- (c) the list is in a cross-origin iframe: nothing readable, but the big frame is reported
  r = await run(page, 'iframe-cross.html');
  console.log(r.debug);
  eq('cross-origin: no items', r.items.length, 0);
  eq('cross-origin: frame reported', [r.frames.length, r.frames[0].src, r.frames[0].same, r.frames[0].vis, r.frames[0].pct >= 35], [1, base2 + '/iframe-inner.html', false, true, true]);
  eq('cross-origin diag line', /iframes 1 \(largest 127\.0\.0\.1 \d+(\.\d)?% cross-origin\)/.test(r.debug), true);
  // following it: the frame's own address read as the index page
  r = await run(page, 'iframe-cross.html');
  await page.goto(r.frames[0].src);
  const followed = JSON.parse(await page.evaluate(`(${js})(null)`));
  eq('cross-origin: frame read directly', followed.items.map(i => i.title), ['Spring Concert', 'Book Club', 'Night Market']);
  eq('cross-origin: links resolve against the frame', followed.items[0].url, base2 + '/ev/201');

  // ---- (d) list rendered late (setTimeout 4s)
  r = await run(page, 'late.html');
  console.log(r.debug);
  eq('late: nothing at first', r.items.length, 0);
  eq('late: loading seen and text short', [r.loading, r.textLen < 1500], [true, true]);
  await new Promise(res => setTimeout(res, 4500));
  r = JSON.parse(await page.evaluate(`(${js})(null)`));
  eq('late: found after the wait', r.items.map(i => i.title), ['Late Night Jam', 'Sunday Brunch Show', 'Quiz Final']);

  // ---- (e) one event only, stragglers, learned shapes, nothing to find
  r = await run(page, 'single.html');
  console.log(r.debug);
  eq('single: lone event', r.items.map(i => [i.title, i.timeText, i.url.replace(/^.*\/w\//, '')]), [['Comets vs Rockets', 'Tue 9:30 PM', '77']]);
  r = await run(page, 'lead.html');
  console.log(r.debug);
  eq('lead: stragglers before the list', r.items.map(i => i.title), ['Owls vs Hawks', 'Bears vs Lions', 'Ducks vs Geese', 'Foxes vs Wolves', 'Seals vs Orcas']);
  eq('lead: shape reported', r.shape, 'ul.rest > li.it');
  r = await run(page, 'click.html');
  const learned = r.shape;
  console.log('click shape', learned);
  r = await run(page, 'single-click.html', null, ['div.grid > div.card.ev']);
  console.log(r.debug);
  eq('learned: single click row', [r.items.length, r.items[0] && r.items[0].title, r.items[0] && r.items[0].url.includes('#sl-click=')], [1, 'Scheduled Thing', true]);
  r = await run(page, 'single-click.html');
  eq('single click row without learning: nothing', r.items.length, 0);
  r = await run(page, 'empty.html');
  console.log(r.debug);
  eq('empty page stays empty', r.items.length, 0);
  r = await run(page, 'portal.html', null, ['div.nothing > a.x']);
  eq('learned shape ignored when a list is found', r.items.length, 3);

  // ---- (f) layouts seen on live index pages (invented names, same markup shapes)
  // cards where an emoji and a repeated league label used to beat the matchup
  r = await run(page, 'cards-badges.html');
  console.log(r.debug);
  eq('badges: titles are the matchups', r.items.map(i => i.title), ['Team A vs Team B', 'Team C vs Team D', 'Team E vs Team F', 'Team G vs Team H', 'Team I vs Team J', 'Team K vs Team L']);
  eq('badges: live and times', r.items.map(i => [i.liveHint, i.timeText]), [[true, ''], [true, ''], [false, '7:00 PM ET Nov 18'], [false, '7:30 PM ET Nov 18'], [false, '8:00 PM ET Nov 18'], [false, '10:00 PM ET Nov 18']]);
  eq('badges: sections on every row', r.items.map(i => i.section), ['Hot Games (2)', 'Hot Games (2)', 'Upcoming (4)', 'Upcoming (4)', 'Upcoming (4)', 'Upcoming (4)']);
  eq('badges: Watch is not the subtitle', r.items.every(i => i.subtitle !== 'Watch' && i.subtitle !== 'NBA'), true);
  // an article with links inside paragraphs: no events
  r = await run(page, 'article.html');
  console.log(r.debug);
  eq('article: no items', r.items.length, 0);
  // a junk row shape learned by an older reader must not bring the article links back
  for (const sel of [['a'], ['a in p.p2'], ['p.p2 > a'], ['p.p2'], ['nav > a']]) {
    r = await run(page, 'article.html', null, sel);
    eq('article with learned ' + sel[0] + ': still no items', r.items.length, 0);
  }
  // real rows plus a learned shape still work (nothing else scores, the learned shape finds them)
  r = await run(page, 'single-click.html', null, ['div.grid > div.card.ev']);
  eq('learned shape on real rows still returns them', r.items.length, 1);
  r = await run(page, 'lead.html');
  eq('lead: shape reports a time share', r.shapeTimed >= 0.5, true);
  // table rows: the row is the item, cells are separate lines
  r = await run(page, 'table-rows.html');
  console.log(r.debug);
  eq('table: titles', r.items.map(i => i.title), ['Team A vs Team B', 'Team C vs Team D', 'Team E vs Team F', 'Team G vs Team H', 'Team I vs Team J']);
  eq('table: times from the date cell', r.items.map(i => i.timeText), ['Nov 17, 2026, 6:30 PM', 'Nov 17, 2026, 7:00 PM', 'Nov 17, 2026, 8:00 PM', 'Nov 17, 2026, 9:30 PM', 'Nov 18, 2026, 7:00 PM']);
  eq('table: urls and live', r.items.map(i => [i.url.replace(/^.*\//, ''), i.liveHint]), [['nba-streams-1', true], ['nba-streams-2', false], ['nba-streams-3', false], ['nba-streams-4', false], ['nba-streams-5', false]]);
  eq('table: no click rows', r.items.some(i => i.url.indexOf('#sl-click=') >= 0), false);
  // two team names with VS / score / countdown between them
  for (const w of [1280, 800]) {
    await page.setViewport({ width: w, height: 720 });
    r = await run(page, 'cards-matchcard.html');
    console.log(r.debug);
    eq('matchcards ' + w + ': titles', r.items.map(i => i.title), ['Team One vs Team Two', 'Team Three vs Team Four', 'Team Five vs Team Six', 'Team Seven vs Team Eight', 'Team Nine vs Team Ten', 'Team Eleven vs Team Twelve']);
  }
  await page.setViewport({ width: 1280, height: 720 });
  eq('matchcards: countdown kept as the time', r.items.slice(0, 3).map(i => i.timeText), ['STARTS IN 1d 19h', 'STARTS IN 4d 12h', 'STARTS IN 2h 05m']);
  eq('matchcards: no score digits in titles or times', r.items.slice(3, 5).map(i => [/\d/.test(i.title), i.timeText]), [[false, ''], [false, '']]);
  eq('matchcards: urls', r.items.map(i => i.url.replace(/^.*\/watch\//, '')).slice(0, 2), ['team-two-at-team-one', 'team-four-at-team-three']);
  // names on both sides of a time, button text is not "live"
  r = await run(page, 'list-sides.html');
  console.log(r.debug);
  eq('sides: titles', r.items.map(i => i.title), ['Team A vs Team B', 'Team C vs Team D', 'Team E vs Team F', 'Team G vs Team H']);
  eq('sides: time', r.items.map(i => i.timeText), ['12:00 AM', '1:30 AM', '3:00 AM', '4:00 AM']);
  eq('sides: datetime passed through', r.items[0].datetime, '2026-11-19 00:00:00');
  eq('sides: button text does not make rows live', r.items.map(i => i.liveHint), [false, false, false, false]);
  eq('sides: no subtitle', r.items.map(i => i.subtitle), ['', '', '', '']);
  // cards in several sections, each card with an empty overlay link carrying aria-label
  r = await run(page, 'cards-matchcard-live.html');
  console.log(r.debug);
  eq('live matchcards: titles (league label and viewer count are not titles)', r.items.map(i => i.title), ['Red Hawks vs Blue Jays', 'North State Owls vs South Tech Foxes', 'Harbor Kings vs River Cats', 'Stone Bears vs Iron Wolves']);
  eq('live matchcards: countdowns', r.items.slice(2).map(i => i.timeText), ['STARTS IN 33m', 'STARTS IN 18h 3m']);
  r = await run(page, 'cards-sections.html');
  console.log(r.debug);
  eq('sections: all 19 cards (content-visibility:auto cards off screen still read)', r.items.length, 19);
  eq('sections: titles from the overlay link', [r.items[0].title, r.items[1].title, r.items[18].title], ['Alpha1 vs Beta1', 'Alpha2 vs Beta2', 'Alpha19 vs Beta19']);
  eq('sections: section = the heading text', [r.items[0].section, r.items[5].section, r.items[18].section], ['Basketball', 'Soccer', 'Other']);
  eq('sections: live cards', r.items.slice(0, 4).map(i => i.liveHint), [true, false, false, true]);
  eq('sections: epoch seconds become an instant', [r.items[0].datetime, r.items[1].datetime], ['2026-11-18T04:00:00.000Z', '2026-11-19T02:00:00.000Z']);
  eq('sections: upcoming time text', r.items[1].timeText, 'Tomorrow 6:00 PM');
  eq('sections: no header rows', r.items.some(i => /^(Basketball|Soccer|Other)$/.test(i.title)), false);
  // cards with title + "Start time:" subtitle under date headings (kept as is)
  r = await run(page, 'cards-subtitle.html');
  console.log(r.debug);
  eq('subtitle cards: titles', r.items.map(i => i.title), ['Team A vs Team B', 'Team C vs Team D', 'Team E vs Team F', 'Team G vs Team H']);
  eq('subtitle cards: sections', r.items.map(i => i.section), ['Tuesday, November 17, 2026', 'Tuesday, November 17, 2026', 'Wednesday, November 18, 2026', 'Wednesday, November 18, 2026']);
  eq('subtitle cards: time', r.items.map(i => i.timeText), ['Start time: 7:00 PM ET', 'Start time: 8:00 PM ET', 'Start time: 7:30 PM ET', 'Start time: 9:00 PM ET']);

  // arena cards: league label styled exactly like the team names; live card has a score, a game clock and a live dot
  r = await run(page, 'cards-arena.html');
  console.log(r.debug);
  eq('arena: titles (league label is not a team)', r.items.map(i => i.title), ['Alpha State Owls vs Beta Ducks', 'Delta Bills vs Gamma Fins', 'Zeta Bears vs Epsilon Saints']);
  eq('arena: live card is live (game clock / live dot), upcoming are not', r.items.map(i => i.liveHint), [true, false, false]);
  eq('arena: links', r.items.map(i => i.url.replace(/^.*\/watch\//, '')), ['alpha-owls-at-beta-ducks', 'gamma-fins-at-delta-bills', 'eps-saints-at-zeta-bears']);

  // a time-only schedule with no links next to a linked card row: the cards win (the schedule's rows open nothing)
  r = await run(page, 'schedule-vs-cards.html');
  console.log(r.debug);
  eq('schedule vs cards: the linked cards are read', r.items.map(i => i.url.replace(/^.*\/player\//, '')), ['basketball/owls-vs-ducks', 'basketball/bills-vs-fins', 'basketball/bears-vs-saints', 'basketball/hawks-vs-jays', 'basketball/kings-vs-cats']);
  eq('schedule vs cards: no click rows', r.clickRows, 0);
  // Fold-out game rows with date lines between them; three short footer links ("Live Now | Upcoming | Top") must not win
  r = await run(page, 'strikeout-nfl.html');
  console.log(r.debug);
  eq('fold-out rows: all games read', r.items.length, 12);
  eq('fold-out rows: no footer links', r.items.filter(i => /-sports$/.test(i.url)).length, 0);
  eq('fold-out rows: date lines are sections, not rows', r.items.slice(6).map(i => i.section),
    ['November 22, 2026 (Sunday)', 'November 22, 2026 (Sunday)', 'November 22, 2026 (Sunday)', 'November 23, 2026 (Monday)',
     'November 26, 2026 (Thursday)', 'November 26, 2026 (Thursday)']);
  eq('fold-out rows: no click rows', r.clickRows || 0, 0);

  await browser.close(); server.close(); server2.close();
  console.log(fails ? fails + ' FAILED' : 'all passed');
  process.exit(fails ? 1 : 0);
})();
