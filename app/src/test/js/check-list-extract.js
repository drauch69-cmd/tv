// Local check of assets/list-extract.js against fixture pages in a real headless Chrome.
// Not part of CI. Run:  NODE_PATH=/opt/npm-tools/node_modules node check-list-extract.js
const fs = require('fs'), path = require('path');
const puppeteer = require('puppeteer-core');
const http = require('http');
let server, base;
const js = fs.readFileSync(path.join(__dirname, '../../main/assets/list-extract.js'), 'utf8');
let fails = 0;
function eq(name, got, want) {
  const ok = JSON.stringify(got) === JSON.stringify(want);
  if (!ok) { fails++; console.log('FAIL', name, '\n  got ', JSON.stringify(got), '\n  want', JSON.stringify(want)); } else console.log('ok  ', name);
}
async function run(page, file, recipe) {
  await page.goto(base + '/' + file);
  const out = await page.evaluate(`(${js})(${recipe ? JSON.stringify(JSON.stringify(recipe)) : 'null'})`);
  return JSON.parse(out);
}
(async () => {
  server = http.createServer((q, res) => {
    const f = path.join(__dirname, 'fixtures', path.basename(q.url.split('?')[0]));
    if (fs.existsSync(f)) { res.setHeader('content-type', 'text/html'); res.end(fs.readFileSync(f)); } else { res.statusCode = 404; res.end('x'); }
  });
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
  await browser.close(); server.close();
  console.log(fails ? fails + ' FAILED' : 'all passed');
  process.exit(fails ? 1 : 0);
})();
