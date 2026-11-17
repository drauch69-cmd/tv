// Headless-Chrome checks for assets/source-switch.js.  Run: NODE_PATH=/opt/npm-tools/node_modules node check-source-switch.js
const http = require('http'), fs = require('fs'), puppeteer = require('puppeteer');
const js = fs.readFileSync(__dirname + '/../../../main/assets/source-switch.js', 'utf8');
const srv = http.createServer((q, r) => {
  try { r.setHeader('content-type', 'text/html'); r.end(fs.readFileSync(__dirname + q.url.split('?')[0].split('#')[0])); } catch (e) { r.statusCode = 404; r.end(); }
}).listen(8099, async () => {
  const br = await puppeteer.launch({ args: ['--no-sandbox'] });
  const p = await br.newPage();
  await p.setViewport({ width: 1280, height: 720 });
  let fail = 0;
  const eq = (n, a, b) => { const ok = JSON.stringify(a) === JSON.stringify(b); if (!ok) { fail++; console.log('FAIL', n, 'got', JSON.stringify(a), 'want', JSON.stringify(b)); } else console.log('ok  ', n); };
  const run = (mode) => p.evaluate('(' + js + ')(' + JSON.stringify(mode) + ')').then(JSON.parse);
  const open = async (f) => { await p.goto('http://localhost:8099/' + f); await new Promise(r => setTimeout(r, 400)); };

  await open('a.html');
  let r = await run('find');
  eq('a: 4 buttons above the iframe', [r.found, r.count, r.active], [true, 4, 1]);
  eq('a: labels', r.labels, ['Alpha-1 X', 'Alpha-2 X', 'Alpha-3 X', 'Alpha-4 X']);
  eq('a: positions are inside the window', r.xs.every(x => x > 0 && x < r.iw) && r.ys.every(y => y > 0 && y < 720), true);
  eq('a: click 2 ok', (await run('click 2')).ok, true);
  await new Promise(r => setTimeout(r, 300));
  eq('a: iframe src swapped', await p.evaluate(() => document.getElementById('p').getAttribute('src')), 'frame3.html');
  r = await run('find');
  eq('a: after the swap the active one moved', [r.count, r.active], [4, 2]);
  eq('a: click out of range', (await run('click 9')).ok, false);

  await open('b.html');
  r = await run('find');
  eq('b: nav links at the top, player far below', r.found, false);
  await open('b2.html');
  r = await run('find');
  eq('b2: nav menu right above the player is still chrome', r.found, false);

  await open('c.html');
  r = await run('find');
  eq('c: external-domain ad links under the player', r.found, false);

  await open('d.html');
  r = await run('find');
  eq('d: list items below the player', [r.found, r.count, r.active], [true, 3, 1]);

  await open('e.html');
  r = await run('find');
  eq('e: no player, nothing', r.found, false);

  await open('f.html');
  r = await run('find');
  eq('f: unrelated buttons under the player (share/like/report)', r.found, false);

  await open('g.html');
  r = await run('find');
  eq('g: numbered pointer-cursor spans, active not shown', [r.found, r.count, r.active], [true, 3, -1]);

  // Expanded chat widget pinned to the viewport: it must not be taken for the player.
  await p.setViewport({ width: 960, height: 540, deviceScaleFactor: 2 });
  await open('h.html');
  r = await run('find');
  eq('h: pinned chat widget is the largest frame, still finds the selector', [r.found, r.count, r.active], [true, 4, 0]);
  await p.setViewport({ width: 1280, height: 720 });

  console.log(fail ? 'FAILED' : 'all passed');
  await br.close(); srv.close(); process.exit(fail ? 1 : 0);
});
