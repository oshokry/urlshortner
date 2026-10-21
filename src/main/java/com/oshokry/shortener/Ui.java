package com.oshokry.shortener;

final class Ui {
    private Ui() { }
    static final String HTML = """
        <!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
        <title>Shortly · URL Shortener</title><link rel="stylesheet" href="/style.css"><script src="/app.js" defer></script></head>
        <body><main><header><span class="brand">↗ shortly</span><span class="badge">JAVA 17 · GREENFIELD DEMO</span></header>
        <section class="intro"><p class="eyebrow">LESS LINK. MORE SIGNAL.</p><h1>Long URLs.<br>Short stories.</h1>
        <p>Create a memorable link, set its lifetime, and watch the clicks come in.</p></section>
        <div class="grid"><section class="card"><h2>Create a short link</h2><form id="create">
        <label for="url">Destination URL</label><input id="url" name="url" type="url" placeholder="https://example.com/a-very-long-story" required maxlength="2048">
        <div class="row"><div><label for="alias">Custom alias <small>optional</small></label><input id="alias" name="alias" placeholder="my-demo" pattern="[A-Za-z0-9_-]{4,32}" maxlength="32"></div>
        <div><label for="ttlSeconds">Expires after</label><select id="ttlSeconds" name="ttlSeconds"><option value="">Never</option><option value="60">1 minute · demo</option><option value="3600">1 hour</option><option value="86400">1 day</option><option value="604800">1 week</option></select></div></div>
        <button id="submit" type="submit">Shorten link ↗</button></form>
        <p id="message" role="status" aria-live="polite"></p>
        <div id="result" hidden><span class="eyebrow">YOUR SHORT LINK</span><a id="short" target="_blank" rel="noopener noreferrer"></a><button id="copy" class="secondary">Copy link</button></div></section>
        <section class="card"><h2>Link analytics</h2><form id="lookup"><label for="code">Short code</label><div class="lookup"><input id="code" placeholder="Paste the code here" required pattern="[A-Za-z0-9_-]{4,32}"><button type="submit" class="secondary">Refresh</button></div></form>
        <div class="metrics"><div><strong id="clicks">—</strong><span>Total redirects</span></div><div><strong id="state" class="state">—</strong><span>Link status</span></div></div>
        <dl><dt>Destination</dt><dd id="destination">Create or look up a link to get started.</dd><dt>Expires</dt><dd id="expires">—</dd><dt>Last clicked</dt><dd id="last">—</dd></dl>
        <p id="stats-message" role="status" aria-live="polite"></p></section></div>
        <footer>Persistent links & clicks · HTTP 302 redirects · No tracking cookies<br><span>Local interview prototype. Analytics count redirect requests, not unique people.</span></footer>
        </main></body></html>
        """;
    static final String CSS = """
        :root{color-scheme:light;font-family:system-ui,-apple-system,Segoe UI,sans-serif;color:#182c36;background:#f1f5f2}*{box-sizing:border-box}body{margin:0}main{max-width:1100px;margin:auto;padding:36px 28px}header{display:flex;justify-content:space-between;align-items:center;gap:15px}.brand{font-size:27px;font-weight:800;letter-spacing:-1px}.badge{font-size:11px;letter-spacing:1.5px;border:1px solid #cad8cf;border-radius:30px;padding:9px 13px}.intro{margin:52px 0 32px}.eyebrow{font-size:11px;font-weight:700;letter-spacing:2px;color:#32735a}h1{font-size:clamp(42px,6vw,68px);letter-spacing:-3px;line-height:1.06;margin:16px 0}p{color:#52666c;line-height:1.6}.grid{display:grid;grid-template-columns:1.15fr 1fr;gap:22px}.card{background:white;border:1px solid #dce5df;border-radius:20px;padding:28px;box-shadow:0 8px 28px #243d2f05}h2{font-size:20px;margin:0 0 24px}label{display:block;font-size:13px;font-weight:650;margin:0 0 9px}small{color:#71827b;font-weight:400}input,select{width:100%;border:1px solid #c8d5ce;border-radius:9px;padding:13px;font:inherit;font-size:14px;background:#fcfdfc;min-width:0}input:focus,select:focus{outline:2px solid #73b89a;outline-offset:2px}.row{display:grid;grid-template-columns:1fr 1fr;gap:15px;margin:22px 0}button{cursor:pointer;border:0;border-radius:9px;background:#176446;color:white;font:inherit;font-size:14px;font-weight:650;padding:13px 18px}button:hover{filter:brightness(.92)}button:disabled{opacity:.6;cursor:wait}#submit{width:100%}.secondary{background:#e9f1ec;color:#24523e}.lookup{display:flex;gap:10px}.metrics{display:grid;grid-template-columns:1fr 1fr;gap:12px;margin:25px 0}.metrics>div{padding:17px;background:#f2f6f3;border-radius:12px}.metrics strong{display:block;font-size:38px;line-height:1.3}.metrics .state{font-size:23px;line-height:2.15}.metrics span{font-size:12px;color:#64756d}dt{font-size:11px;letter-spacing:1px;text-transform:uppercase;color:#76877c;margin-top:17px}dd{font-size:13px;margin:6px 0;overflow-wrap:anywhere}#result{border-top:1px solid #e1e8e3;padding-top:20px}#short{display:block;color:#176446;font-size:18px;margin:12px 0;overflow-wrap:anywhere}footer{text-align:center;color:#53695d;font-size:12px;line-height:1.8;margin:30px 0}footer span{color:#809087}.error{color:#ae3333}#message,#stats-message{font-size:13px}@media(max-width:760px){.grid{grid-template-columns:1fr}.badge{font-size:9px}.intro{margin-top:35px}main{padding:24px 18px}.card{padding:23px}.row{grid-template-columns:1fr}}
        """;
    static final String JS = """
        'use strict';
        const el = id => document.getElementById(id);
        const message = (id,text,error=false) => { el(id).textContent=text; el(id).className=error?'error':''; };
        const date = v => v ? new Date(v).toLocaleString() : 'Never';
        async function request(url,options) {
          const response=await fetch(url,options); const data=await response.json();
          if(!response.ok) throw new Error(data.error||'Request failed'); return data;
        }
        function showStats(data) {
          el('clicks').textContent=data.clicks; el('state').textContent=data.expired?'Expired':'Active';
          el('destination').textContent=data.url; el('expires').textContent=date(data.expiresAt);
          el('last').textContent=data.lastClickedAt?date(data.lastClickedAt):'No clicks yet';
          message('stats-message','Updated just now. Open your short link, then refresh.');
        }
        el('create').addEventListener('submit',async event=>{
          event.preventDefault(); el('submit').disabled=true; message('message','Creating…'); el('result').hidden=true;
          try { const data=await request('/api/links',{method:'POST',body:new URLSearchParams(new FormData(event.target))});
            el('short').href=data.shortUrl; el('short').textContent=data.shortUrl; el('result').hidden=false;
            el('code').value=data.code; showStats(data); message('message','Your link is ready.');
          } catch(error) { message('message',error.message,true); } finally { el('submit').disabled=false; }
        });
        el('lookup').addEventListener('submit',async event=>{
          event.preventDefault(); try { showStats(await request('/api/links/'+encodeURIComponent(el('code').value.trim()))); }
          catch(error) { message('stats-message',error.message,true); }
        });
        el('copy').addEventListener('click',async()=>{
          try { await navigator.clipboard.writeText(el('short').href); message('message','Copied to clipboard.'); }
          catch { message('message','Select and copy the short link above.'); }
        });
        """;
}
