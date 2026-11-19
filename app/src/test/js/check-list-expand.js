// Local check of assets/list-expand.js (presses "show more" buttons) + list-extract.js on the result.
// Not part of CI. Run:  NODE_PATH=/opt/npm-tools/node_modules node check-list-expand.js
const fs = require('fs'), path = require('path'), http = require('http');
const puppeteer = require('puppeteer-core');
const expandJs = fs.readFileSync(path.join(__dirname, '../../main/assets/list-expand.js'), 'utf8');
const extractJs = fs.readFileSync(path.join(__dirname, '../../main/assets/list-extract.js'), 'utf8');
let fails = 0, base;
function eq(name, got, want) {
  const ok = JSON.stringify(got) === JSON.stringify(want);
  if (!ok) { fails++; console.log('FAIL', name, '\n  got ', JSON.stringify(got), '\n  want', JSON.stringify(want)); } else console.log('ok  ', name);
}
const expand = async (page) => JSON.parse(await page.evaluate(`(${expandJs})()`));
const extract = async (page) => JSON.parse(await page.evaluate(`(${extractJs})(null, null)`));
(async () => {
  const server = http.createServer((q, res) => {
    const f = path.join(__dirname, 'fixtures', path.basename(q.url.split('?')[0]));
    if (fs.existsSync(f)) { res.setHeader('content-type', 'text/html'); res.end(fs.readFileSync(f, 'utf8')); } else { res.statusCode = 404; res.end('x'); }
  });
  await new Promise(r => server.listen(0, '127.0.0.1', r));
  base = 'http://127.0.0.1:' + server.address().port;
  const browser = await puppeteer.launch({ executablePath: process.env.CHROME || '/usr/bin/google-chrome', args: ['--no-sandbox'] });
  const page = await browser.newPage();
  await page.setViewport({ width: 1280, height: 720 });

  // (a) + (b) sections with 12 rows and an expander; a category tab row and a nav "More" link on the same page
  await page.goto(base + '/expand-sections.html');
  let before = await extract(page);
  eq('sections: before expanding only 12 per section', before.items.length, 36);
  let r = await expand(page);
  eq('sections: three expanders pressed', r.pressed, 3);
  eq('sections: tab clicks never happen', await page.evaluate('window.tabClicked'), false);
  eq('sections: URL unchanged', page.url(), base + '/expand-sections.html');
  let after = await extract(page);
  eq('sections: all rows read after expanding (35+20+14)', after.items.length, 69);
  eq('sections: rows of the last section', after.items.filter(i => /\/watch\/C\//.test(i.url)).length, 14);
  eq('sections: new rows carry their links', after.items.some(i => i.url.endsWith('/watch/A/ppv-35')), true);
  await page.goto(base + '/expand-sections.html'); // fresh load: only one press per button per read
  r = await expand(page);
  eq('sections: fresh read presses each expander once', r.pressed, 3);
  eq('sections: candidates reported', r.candidates >= 3, true);

  // (c) pagination rows ("1 2 3 Next", buttons and href="#" links)
  await page.goto(base + '/expand-pager.html');
  r = await expand(page);
  eq('pager: nothing pressed', r.pressed, 0);
  eq('pager: no pager click happened', await page.evaluate('window.tabClicked'), false);

  // (d) a "More" link with a real href, and a header link
  await page.goto(base + '/expand-navlink.html');
  r = await expand(page);
  eq('real-href link: nothing pressed', r.pressed, 0);
  eq('real-href link: not clicked', await page.evaluate('window.navClicked'), false);

  // (e) no expander at all
  await page.goto(base + '/expand-none.html');
  r = await expand(page);
  eq('no expander: pressed 0', r.pressed, 0);

  // existing fixtures that have buttons near rows must not be touched either
  for (const f of ['cards-sections.html', 'table-rows.html', 'days.html', 'portal.html', 'list-sides.html']) {
    await page.goto(base + '/' + f);
    const url0 = page.url();
    r = await expand(page);
    eq(f + ': pressed 0', r.pressed, 0);
    eq(f + ': URL unchanged', page.url(), url0);
  }
  await browser.close(); server.close();
  console.log(fails ? fails + ' FAILED' : 'all passed');
  process.exit(fails ? 1 : 0);
})();
