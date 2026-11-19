// Headless-Chrome checks for assets/page-sources.js.  Run: NODE_PATH=/opt/npm-tools/node_modules node check-page-sources.js
const http = require('http'), fs = require('fs'), puppeteer = require('puppeteer');
const js = fs.readFileSync(__dirname + '/../../../main/assets/page-sources.js', 'utf8');
const srv = http.createServer((q, r) => {
  try { r.setHeader('content-type', 'text/html'); r.end(fs.readFileSync(__dirname + q.url.split('?')[0].split('#')[0])); } catch (e) { r.statusCode = 404; r.end(); }
}).listen(8098, async () => {
  const br = await puppeteer.launch({ args: ['--no-sandbox'] });
  const p = await br.newPage();
  await p.setViewport({ width: 1280, height: 720 });
  let fail = 0;
  const eq = (n, a, b) => { const ok = JSON.stringify(a) === JSON.stringify(b); if (!ok) { fail++; console.log('FAIL', n, 'got', JSON.stringify(a), 'want', JSON.stringify(b)); } else console.log('ok  ', n); };
  const call = (mode) => p.evaluate('(' + js + ')(' + JSON.stringify(mode) + ')').then(JSON.parse);
  const open = async (f) => { await p.goto('http://localhost:8098/' + f); await new Promise(r => setTimeout(r, 400)); };
  // The app starts the script, waits a moment, then reads (asking again until it says done).
  const read = async () => {
    await call('start');
    for (let i = 0; i < 20; i++) { await new Promise(r => setTimeout(r, 100)); const r = await call('read'); if (r.done) return r; }
    return { done: false };
  };

  await open('ps-select.html');
  let before = await p.evaluate(() => location.href);
  let r = await read();
  eq('select that rewrites ?source=N: 2 other addresses', r.urls.map(u => u.url.replace(/^.*\//, '')), ['ps-select.html?source=1', 'ps-select.html?source=2']);
  eq('select: labels and indexes', r.urls.map(u => [u.label, u.index]), [['Source 2 (beta)', 1], ['Source 3 (gamma)', 2]]);
  eq('select: address restored', await p.evaluate(() => location.href), before);
  eq('select: first option showing again', await p.evaluate(() => document.getElementById('sourceSelect').selectedIndex), 0);
  eq('select: player swapped back', await p.evaluate(() => document.getElementById('p').getAttribute('src')), 'frame1.html');

  await open('ps-select.html?source=1');
  before = await p.evaluate(() => location.href);
  await p.evaluate(() => { document.getElementById('sourceSelect').selectedIndex = 1; });
  r = await read();
  eq('select with source=1 showing: never the page itself', r.urls.map(u => u.url.replace(/^.*\//, '')), ['ps-select.html?source=0', 'ps-select.html?source=2']);
  eq('select: address restored (2)', await p.evaluate(() => location.href), before);

  await open('ps-select-quiet.html');
  r = await read();
  eq('select whose change does not touch the address: nothing', r.urls, []);

  await open('ps-links.html');
  r = await read();
  eq('link row ?srv=1..3 (nav, other path, other param ignored)', r.urls.map(u => [u.label, u.url.replace(/^.*\//, '')]),
    [['Alpha', 'ps-links.html?srv=1'], ['Beta', 'ps-links.html?srv=2'], ['Gamma', 'ps-links.html?srv=3']]);
  eq('links: address untouched', await p.evaluate(() => location.pathname + location.search), '/ps-links.html');

  await open('ps-nav.html');
  r = await read();
  eq('nav links and a select with no address change: nothing', r.urls, []);

  console.log(fail ? 'FAILED' : 'all passed');
  await br.close(); srv.close(); process.exit(fail ? 1 : 0);
});
