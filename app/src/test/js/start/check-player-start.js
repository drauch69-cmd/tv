const http=require('http'),fs=require('fs'),puppeteer=require('puppeteer');
const js=fs.readFileSync(__dirname+'/../../../main/assets/player-start.js','utf8');
const srv=http.createServer((q,r)=>{try{r.end(fs.readFileSync(''+__dirname+''+q.url.split('?')[0]));}catch(e){r.statusCode=404;r.end();}}).listen(8098,async()=>{
 const br=await puppeteer.launch({args:['--no-sandbox']});const p=await br.newPage();
 await p.setViewport({width:1280,height:720});await p.evaluateOnNewDocument(js);
 let fail=0;const eq=(n,a,b)=>{const ok=JSON.stringify(a)===JSON.stringify(b);if(!ok)fail++;console.log(ok?'ok  ':'FAIL',n,JSON.stringify(a));};
 await p.goto('http://localhost:8098/a.html');await new Promise(r=>setTimeout(r,4000));
 eq('top page: centre button pressed, not mute', await p.evaluate(()=>[window.started>0,!!window.muteClicked]),[true,false]);
 await p.goto('http://localhost:8098/b.html');await new Promise(r=>setTimeout(r,4000));
 const f=p.frames().find(x=>x.url().includes('frame.html'));
 eq('player frame: start pressed, not fullscreen', await f.evaluate(()=>[!!window.started,!!window.fsPressed]),[true,false]);
 await p.goto('http://localhost:8098/c.html');await new Promise(r=>setTimeout(r,4000));
 eq('article with no video: nothing pressed', await p.evaluate(()=>[!!window.adClicked, window.__slStartPressed||0]),[false,0]);
 console.log(fail?'FAILED':'all passed');await br.close();srv.close();});
