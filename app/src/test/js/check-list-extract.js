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

  await browser.close(); server.close(); server2.close();
  console.log(fails ? fails + ' FAILED' : 'all passed');
  process.exit(fails ? 1 : 0);
})();
