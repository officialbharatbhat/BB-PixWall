package bb.pix.wall.network

import bb.pix.wall.settings.*
import bb.pix.wall.ui.theme.ThemeProfile

object WebRemoteDashboard {

    private fun esc(
        value: String,
    ): String =
        value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("'", "&#39;")

    private fun options(
        items: List<Pair<String, String>>,
    ): String =
        items.joinToString("") {
            "<option value='${esc(it.first)}'>" +
                "${esc(it.second)}</option>"
        }

    fun render(
        token: String,
    ): String =
        """
<!doctype html>
<html>
<head>
<meta charset="utf-8">
<meta
  name="viewport"
  content="width=device-width,initial-scale=1,viewport-fit=cover"
>
<meta name="theme-color" content="#080b10">
<title>BB-PixWall Lite Remote Pro</title>

<style>
:root{
  color-scheme:dark;
  --bg:#080a0f;
  --card:#11151c;
  --card2:#171c25;
  --card3:#202733;
  --line:#2c3544;
  --text:#f4f6fb;
  --muted:#aab3c3;
  --accent:#7ce6ff;
  --accent2:#a98cff;
  --good:#75e39e;
  --danger:#ff7b86;
  --r:21px;
}
*{box-sizing:border-box}
html{scroll-behavior:smooth}
body{
  margin:0;
  font-family:system-ui,-apple-system,sans-serif;
  color:var(--text);
  background:
    radial-gradient(circle at 18% 0%,#13202b 0,transparent 34%),
    var(--bg);
}
.shell{
  width:min(1240px,100%);
  margin:auto;
  padding:
    calc(16px + env(safe-area-inset-top))
    14px
    calc(40px + env(safe-area-inset-bottom));
}
header{
  position:sticky;
  top:0;
  z-index:30;
  display:flex;
  align-items:center;
  gap:10px;
  padding:12px 0;
  background:linear-gradient(
    180deg,
    rgba(8,10,15,.98),
    rgba(8,10,15,.88),
    transparent
  );
  backdrop-filter:blur(14px);
}
header h1{
  margin:0;
  font-size:clamp(23px,4vw,34px);
  letter-spacing:-.04em;
}
.spacer{flex:1}
.chip{
  border:1px solid var(--line);
  border-radius:999px;
  padding:7px 10px;
  background:var(--card2);
  color:var(--muted);
  font-size:12px;
  font-weight:800;
}
.chip.good{color:var(--good)}
.hero,.card,details{
  background:var(--card);
  border:1px solid var(--line);
  border-radius:var(--r);
}
.hero{
  padding:18px;
  margin:6px 0 14px;
  background:
    linear-gradient(
      135deg,
      rgba(124,230,255,.12),
      rgba(169,140,255,.08)
    ),
    var(--card);
}
.mood-title{
  color:var(--accent);
  font-size:25px;
  font-weight:900;
  margin:4px 0;
}
.muted,.help{
  color:var(--muted);
  font-size:13px;
  line-height:1.5;
}
.hero-grid,.two,.three,.four,.preview-grid,.history-grid{
  display:grid;
  gap:10px;
}
.hero-grid{grid-template-columns:repeat(4,minmax(0,1fr))}
.two{grid-template-columns:repeat(2,minmax(0,1fr))}
.three{grid-template-columns:repeat(3,minmax(0,1fr))}
.four{grid-template-columns:repeat(4,minmax(0,1fr))}
.metric{
  background:rgba(255,255,255,.035);
  border:1px solid var(--line);
  border-radius:16px;
  padding:12px;
}
.metric small{
  display:block;
  color:var(--muted);
  margin-bottom:5px;
}
.metric strong{
  display:block;
  overflow-wrap:anywhere;
}

.palette-swatches{
  display:flex;
  gap:8px;
  flex-wrap:wrap;
  margin-top:9px;
}
.palette-swatches span{
  width:34px;
  height:34px;
  border-radius:11px;
  border:1px solid rgba(255,255,255,.22);
  box-shadow:inset 0 0 0 1px rgba(0,0,0,.18);
}
.timeline{
  white-space:pre-line;
  font-family:ui-monospace,SFMono-Regular,Menlo,monospace;
  font-size:12px;
  line-height:1.55;
}
.preview-grid{
  grid-template-columns:repeat(4,minmax(0,1fr));
  margin:14px 0;
}
.wall{
  overflow:hidden;
  background:var(--card);
  border:1px solid var(--line);
  border-radius:20px;
}
.wall img{
  display:block;
  width:100%;
  aspect-ratio:9/13;
  object-fit:cover;
  background:#05070a;
}
.wall-title{
  padding:10px 11px 4px;
  font-weight:850;
}
.wall-meta{
  min-height:80px;
  padding:0 11px 10px;
  color:var(--muted);
  white-space:pre-line;
  font-size:12px;
  line-height:1.45;
}
.wall-actions,.actions{
  display:flex;
  gap:7px;
  flex-wrap:wrap;
}
.wall-actions{padding:0 9px 10px}
.card{
  padding:15px;
  margin:11px 0;
}
details{
  margin:11px 0;
  overflow:hidden;
}
summary{
  list-style:none;
  cursor:pointer;
  padding:17px 18px;
  font-size:18px;
  font-weight:900;
  display:flex;
  align-items:center;
}
summary::-webkit-details-marker{display:none}
summary:after{
  content:'⌄';
  margin-left:auto;
  color:var(--accent);
}
details[open] summary:after{transform:rotate(180deg)}
.section{
  border-top:1px solid var(--line);
  padding:16px;
}
.subcard{
  background:var(--card2);
  border:1px solid var(--line);
  border-radius:17px;
  padding:13px;
  margin:9px 0;
}
.field{margin:10px 0}
label.title{
  display:block;
  margin-bottom:6px;
  font-weight:750;
}
input[type=text],
input[type=number],
select{
  width:100%;
  border:1px solid #3a4454;
  border-radius:12px;
  padding:11px;
  background:#0d1117;
  color:var(--text);
  font:inherit;
}
input[type=range]{
  width:100%;
  accent-color:var(--accent);
}
.toggle{
  display:flex;
  align-items:flex-start;
  gap:10px;
  padding:9px 0;
}
.toggle input{
  margin-left:auto;
  width:22px;
  height:22px;
  accent-color:var(--accent);
}
button{
  border:0;
  border-radius:13px;
  padding:11px 13px;
  font:inherit;
  font-weight:850;
  cursor:pointer;
  color:#052029;
  background:var(--accent);
}
button.secondary{
  color:var(--text);
  background:var(--card3);
  border:1px solid var(--line);
}
button.danger{
  color:#ffadb4;
  background:#35161c;
  border:1px solid #6a2832;
}
.status{
  color:var(--accent);
  overflow-wrap:anywhere;
}
pre{
  max-height:330px;
  overflow:auto;
  white-space:pre-wrap;
  overflow-wrap:anywhere;
  border:1px solid var(--line);
  border-radius:13px;
  padding:11px;
  background:#090c11;
}
.history-grid{
  grid-template-columns:repeat(2,minmax(0,1fr));
}
.history{
  overflow:hidden;
  border:1px solid var(--line);
  border-radius:16px;
  background:var(--card2);
}
.history-img{
  display:grid;
  grid-template-columns:1fr 1fr;
}
.history-img img{
  width:100%;
  height:150px;
  object-fit:cover;
  background:#07090d;
}
.history-body{padding:11px}


.final-save{
  position:relative;
  inset:auto;
  z-index:auto;
  display:flex;
  align-items:center;
  gap:8px;
  width:100%;
  margin:16px 0 0;
  padding:12px;
  border:1px solid var(--line);
  border-radius:16px;
  background:var(--card);
  backdrop-filter:none;
}

@media(max-width:900px){
  .preview-grid{grid-template-columns:repeat(2,minmax(0,1fr))}
  .hero-grid,.four{grid-template-columns:repeat(2,minmax(0,1fr))}
}
@media(max-width:620px){
  .shell{padding-left:9px;padding-right:9px}
  .two,.three,.history-grid{grid-template-columns:1fr}
  header .chip:nth-of-type(2){display:none}
}
</style>
</head>

<body>
<div class="shell">

<header>
  <h1>BB-PixWall <span style="color:var(--accent);font-size:.62em">Lite</span></h1>
  <div class="spacer"></div>
  <span class="chip" id="modeChip">Standard</span>
  <span class="chip good" id="lanChip">LAN</span>
</header>

<div class="hero">
  <div class="mood-title">BB-PixWall Lite Remote</div>
  <div class="muted">Google Photos primary • Drive mirror • Local optional</div>
  <div class="hero-grid" style="margin-top:14px">
    <div class="metric">
      <small>Active source</small>
      <strong id="activeSource">-</strong>
    </div>
    <div class="metric">
      <small>Offline ready</small>
      <strong id="offlineReady">0</strong>
    </div>
    <div class="metric">
      <small>Last pipeline</small>
      <strong id="lastPipelineHero">Original stream</strong>
    </div>
    <div class="metric">
      <small>Last trigger</small>
      <strong id="lastTriggerHero">-</strong>
    </div>
  </div>
</div>

<div class="preview-grid">
  <div class="wall">
    <img id="imgCurrentHome" src="/img/current-home?t=1">
    <div class="wall-title">Current Home</div>
    <div class="wall-meta" id="currentHomeInfo">Waiting…</div>
    <div class="wall-actions">
      <button class="secondary" onclick="lib('favorite','current-home')">Favorite</button>
      <button class="secondary" onclick="lib('pin','current-home')">Pin</button>
      <button class="secondary" onclick="lib('save-original','current-home')">Save</button>
    </div>
  </div>

  <div class="wall">
    <img id="imgCurrentLock" src="/img/current-lock?t=1">
    <div class="wall-title">Current Lock</div>
    <div class="wall-meta" id="currentLockInfo">Waiting…</div>
    <div class="wall-actions">
      <button class="secondary" onclick="lib('favorite','current-lock')">Favorite</button>
      <button class="secondary" onclick="lib('pin','current-lock')">Pin</button>
      <button class="secondary" onclick="lib('save-original','current-lock')">Save</button>
    </div>
  </div>

  <div class="wall">
    <img id="imgNextHome" src="/img/next-home?t=1">
    <div class="wall-title">Next Home</div>
    <div class="wall-meta" id="nextHomeInfo">Waiting…</div>
    <div class="wall-actions">
      <button class="secondary" onclick="lib('favorite','next-home')">Favorite</button>
      <button class="danger" onclick="lib('never','next-home')">Never</button>
    </div>
  </div>

  <div class="wall">
    <img id="imgNextLock" src="/img/next-lock?t=1">
    <div class="wall-title">Next Lock</div>
    <div class="wall-meta" id="nextLockInfo">Waiting…</div>
    <div class="wall-actions">
      <button class="secondary" onclick="lib('favorite','next-lock')">Favorite</button>
      <button class="danger" onclick="lib('never','next-lock')">Never</button>
    </div>
  </div>
</div>

<div class="card">
  <div class="actions">
    <button onclick="act('/api/next')">Next Wall</button>
    <button class="secondary" onclick="act('/api/previous')">Previous</button>
    <button class="secondary" onclick="act('/api/refresh-next')">Refresh Next</button>
    <button class="secondary" onclick="act('/api/surprise')">Surprise Me</button>
    <button class="secondary" onclick="act('/api/save')">Save Current</button>
    <button class="secondary" onclick="act('/api/blur')">Toggle Blur</button>
  </div>
  <p class="status" id="status"></p>
</div>

<details open>
<summary>Sources</summary>
<div class="section">
  <div class="three">
    <div class="metric"><small>Photos</small><strong id="photosHealth">-</strong></div>
    <div class="metric"><small>Drive</small><strong id="driveHealth">-</strong></div>
    <div class="metric"><small>Local</small><strong id="localHealth">-</strong></div>
  </div>

  <div class="field">
    <label class="title">Google Photos public album</label>
    <input id="photosAlbumUrl" type="text">
  </div>

  <div class="field">
    <label class="title">Google Drive public mirror</label>
    <input id="driveFolderUrl" type="text">
  </div>
<div class="actions">
    <button class="secondary" onclick="act('/api/test-sources')">
      Test sources
    </button>
  </div>
</div>
</details>

<details>
<summary>Wallpaper order</summary>
<div class="section">
  <div class="field">
    <label class="title">Rotation strategy</label>
    <select id="wallpaperOrder">
      ${
            options(
                listOf(
                    WallpaperOrder.A_Z,
                    WallpaperOrder.SIZE_LOW_HIGH,
                    WallpaperOrder.SIZE_HIGH_LOW,
                    WallpaperOrder.RANDOM_SHUFFLE,
                    WallpaperOrder.SURPRISE,
                ).map {
                    it.name to it.label
                }
            )
        }
    </select>
  </div>
</div>
</details>











<details>
<summary>Wallpaper automation</summary>
<div class="section">
  <label class="toggle"><div><b>Automatic wallpaper change</b></div><input id="autoChange" type="checkbox"></label>

  <div class="two">
    <div class="field">
      <label class="title">Trigger</label>
      <select id="triggerMode">
        ${
            options(
                TriggerMode.entries.map {
                    it.name to it.label
                }
            )
        }
      </select>
    </div>

    <div class="field">
      <label class="title">Interval minutes</label>
      <input id="intervalMinutes" type="number" min="1" max="240">
    </div>
  </div>

  <div class="metric">
    <small>Next run</small>
    <strong id="nextRun">Event/manual</strong>
  </div>
</div>
</details>

<details>
<summary>Wallpaper target</summary>
<div class="section">
  <div class="field">
    <label class="title">Target</label>
    <select id="targetMode">
      ${
            options(
                WallpaperTargetMode.entries.map {
                    it.name to it.label
                }
            )
        }
    </select>
  </div>
</div>
</details>



<details>
<summary>Blur</summary>
<div class="section">
  <div class="two">
    <div class="subcard">
      <label class="toggle"><div><b>Home blur</b></div><input id="homeBlurEnabled" type="checkbox"></label>
      <input id="homeBlurRadius" type="range" min="0" max="64">
    </div>

    <div class="subcard">
      <label class="toggle"><div><b>Lock blur</b></div><input id="lockBlurEnabled" type="checkbox"></label>
      <input id="lockBlurRadius" type="range" min="0" max="64">
    </div>
  </div>
</div>
</details>

<details open>
<summary>History & Library</summary>
<div class="section">
  <div class="actions">
    <button class="secondary" onclick="loadHistory()">Refresh history</button>
  </div>
  <div id="historyList" class="history-grid" style="margin-top:10px"></div>
</div>
</details>

<details>
<summary>Appearance</summary>
<div class="section">
  <div class="two">
    <div class="field">
      <label class="title">Color mode</label>
      <select id="appearanceMode">
        ${
            options(
                AppearanceMode.entries.map {
                    it.name to it.label
                }
            )
        }
      </select>
    </div>

    <div class="field">
      <label class="title">Theme</label>
      <select id="theme">
        ${
            options(
                ThemeProfile.entries.map {
                    it.name to it.title
                }
            )
        }
      </select>
    </div>
  </div>
</div>
</details>

<details>
<summary>Diagnostics</summary>
<div class="section">
  <div class="four">
    <div class="metric"><small>Engine health</small><strong id="engineHealth">-</strong></div>
    <div class="metric"><small>Last trigger</small><strong id="lastTrigger">-</strong></div>
    <div class="metric"><small>Last error</small><strong id="lastError">None</strong></div>
    <div class="metric"><small>History</small><strong id="historyCount">0</strong></div>
  </div>

  <div class="actions" style="margin-top:10px">
    <button class="secondary" onclick="loadLogs()">Refresh logs</button>
    <button class="secondary" onclick="loadRoot()">Root diagnostics</button>
    <button class="secondary" onclick="act('/api/debug-report')">Export debug</button>
  </div>

  <pre id="rootDiag"></pre>
  <pre id="logs"></pre>
</div>
</details>


<details open>
<summary>Developer Information</summary>
<div class="section">

  <div class="subcard">
    <div class="help">DEVELOPER</div>
    <div class="mood-title" style="font-size:21px">
      Bharat Bhat
    </div>

    <div class="two">

      <div class="metric">
        <small>Email</small>
        <strong>
          <a
            href="mailto:bkbhatinfo@gmail.com"
            style="color:var(--accent);text-decoration:none"
          >
            bkbhatinfo@gmail.com
          </a>
        </strong>
      </div>

      <div class="metric">
        <small>Instagram</small>
        <strong>
          <a
            href="https://instagram.com/officialbharatbhat"
            target="_blank"
            rel="noopener noreferrer"
            style="color:var(--accent);text-decoration:none"
          >
            @officialbharatbhat
          </a>
        </strong>
      </div>

      <div class="metric">
        <small>Facebook</small>
        <strong>
          <a
            href="https://facebook.com/officialbharatbhat"
            target="_blank"
            rel="noopener noreferrer"
            style="color:var(--accent);text-decoration:none"
          >
            officialbharatbhat
          </a>
        </strong>
      </div>

      <div class="metric">
        <small>Telegram</small>
        <strong>
          <a
            href="https://t.me/BharatBhat"
            target="_blank"
            rel="noopener noreferrer"
            style="color:var(--accent);text-decoration:none"
          >
            BharatBhat
          </a>
        </strong>
      </div>

      <div class="metric">
        <small>YouTube</small>
        <strong>
          <a
            href="https://youtube.com/@sloverbofficial"
            target="_blank"
            rel="noopener noreferrer"
            style="color:var(--accent);text-decoration:none"
          >
            sloverbofficial
          </a>
        </strong>
      </div>

    </div>
  </div>

</div>
</details>

<div class="final-save">
  <span class="muted" id="dirtyState" style="margin-right:auto">
    Synced
  </span>
  <button class="secondary" onclick="load(true)">Reload</button>
  <button onclick="saveSettings()">Save all settings</button>
</div>

</div>

<script>
const token=${js(token)};

const settingIds=[
'photosAlbumUrl','driveFolderUrl','sourcePriorityMode',
'wallpaperOrder','decisionEngineEnabled',
'dataSaverEnabled','wifiOnly','mobileDataAllowed','leanStorageMode',
'smartPairingEnabled','adaptiveResourceProtectionEnabled',
'cacheTarget','cacheMaxMb','lowStorageReserveMb',
'moodEngineEnabled','moodAutoReactEnabled',
'moodAutoReactCooldownMinutes','moodAmbientLightEnabled',
'moodDarkLuxThreshold','moodBrightLuxThreshold','moodTimeEnabled',
'moodDarkModeEnabled','moodBatteryContextEnabled',
'moodThermalProtectionEnabled','moodStrength',
'moodWeatherEnabled','moodUseDeviceLocation','moodWeatherCity',
'moodWeatherInfluence','moodOutdoorTemperatureEnabled',
'moodColdTemperatureC','moodHotTemperatureC',
'backgroundGuardEnabled','chargingOnly','pauseBatterySaver',
'pauseLowBattery','lowBatteryThreshold','quietHoursEnabled',
'quietStartHour','quietEndHour',
'autoChange','triggerMode','intervalMinutes','targetMode',
'smartCropEnabled','aspectPreference','smartCropTolerancePct',
'perceptualDistance',
'homeBlurEnabled','homeBlurRadius','lockBlurEnabled','lockBlurRadius',
'appearanceMode','theme'
];

let dirty=false;
let loading=false;

function text(id,v){
  const e=document.getElementById(id);
  if(e)e.textContent=(v===undefined||v===null||v==='')?'-':String(v);
}

function paletteSwatches(id,value){
  const root=document.getElementById(id);
  if(!root)return;
  root.textContent='';
  const colors=String(value||'').match(/#[0-9a-fA-F]{6}/g)||[];
  colors.slice(0,6).forEach(c=>{
    const x=document.createElement('span');
    x.style.background=c;
    x.title=c;
    root.appendChild(x);
  });
}

function fmtBytes(n){
  n=Number(n||0);
  if(n>=1073741824)return(n/1073741824).toFixed(2)+' GB';
  if(n>=1048576)return(n/1048576).toFixed(1)+' MB';
  if(n>=1024)return(n/1024).toFixed(1)+' KB';
  return n+' B';
}

function wallInfo(v){
  if(!v||!v.exists)return'Unavailable';
  const blur=v.blur?('Blur ON • '+v.radius+'px'):'Blur OFF';
  return v.resolution+' • '+v.format+' • '+v.size+
    '\n'+v.source+' • '+blur+
    '\nQuality: '+v.quality;
}

function setForm(id,v){
  const e=document.getElementById(id);
  if(!e||v===undefined)return;
  if(e.type==='checkbox')e.checked=Boolean(v);
  else e.value=String(v);
}

function updateRangeLabels(){
  ['moodStrength','moodWeatherInfluence','moodAutoReactCooldownMinutes']
  .forEach(id=>{
    const e=document.getElementById(id);
    const o=document.getElementById(id+'Val');
    if(e&&o)o.textContent=e.value;
  });
}

function refreshImages(){
  const t=Date.now();
  document.getElementById('imgCurrentHome').src='/img/current-home?t='+t;
  document.getElementById('imgCurrentLock').src='/img/current-lock?t='+t;
  document.getElementById('imgNextHome').src='/img/next-home?t='+t;
  document.getElementById('imgNextLock').src='/img/next-lock?t='+t;
}

async function postForm(path,obj){
  const p=new URLSearchParams();
  Object.entries(obj).forEach(([k,v])=>p.set(k,String(v)));
  return fetch(path,{
    method:'POST',
    headers:{
      'X-BBPixWall-Token':token,
      'Content-Type':'application/x-www-form-urlencoded'
    },
    body:p
  });
}

async function act(path){
  text('status','Working…');
  try{
    const r=await fetch(path,{
      method:'POST',
      headers:{'X-BBPixWall-Token':token}
    });
    text('status',await r.text());
    setTimeout(()=>{
      load(false);
      refreshImages();
      loadHistory();
    },450);
  }catch(e){
    text('status','Network error: '+e);
  }
}

async function lib(action,slot){
  const r=await postForm('/api/library-action',{action,slot});
  text('status',await r.text());
  setTimeout(()=>{
    load(false);
    refreshImages();
  },350);
}

async function restoreHistory(id){
  const r=await postForm('/api/history-restore',{id});
  text('status',await r.text());
  setTimeout(()=>{
    load(false);
    refreshImages();
    loadHistory();
  },450);
}

async function loadHistory(){
  try{
    const list=await(
      await fetch('/api/history',{cache:'no-store'})
    ).json();

    const root=document.getElementById('historyList');
    root.textContent='';

    if(!list.length){
      const p=document.createElement('p');
      p.className='muted';
      p.textContent='No history yet.';
      root.appendChild(p);
      return;
    }

    list.forEach(item=>{
      const card=document.createElement('div');
      card.className='history';

      const imgs=document.createElement('div');
      imgs.className='history-img';

      ['home','lock'].forEach(side=>{
        if(item[side]){
          const img=document.createElement('img');
          img.src='/img/history/'+encodeURIComponent(item.id)+'/'+side+'?t='+Date.now();
          imgs.appendChild(img);
        }else{
          imgs.appendChild(document.createElement('div'));
        }
      });

      const body=document.createElement('div');
      body.className='history-body';

      const title=document.createElement('b');
      title.textContent=new Date(item.createdAt).toLocaleString();

      const btn=document.createElement('button');
      btn.className='secondary';
      btn.style.marginTop='8px';
      btn.textContent='Restore';
      btn.onclick=()=>restoreHistory(item.id);

      body.appendChild(title);
      body.appendChild(document.createElement('br'));
      body.appendChild(btn);

      card.appendChild(imgs);
      card.appendChild(body);
      root.appendChild(card);
    });
  }catch(e){
    text('status','History failed: '+e);
  }
}

function render(s){
  text('modeChip',s.engineMode==='ADVANCED'?'Advance':'Standard');
  text('lanChip',s.lanState||'LAN');

  text('heroMood',s.moodProfileLabel||'Adaptive');
  text('heroMoodSummary',s.moodProfileSummary);
  text('activeSource',s.activeSource);
  text('lastPipelineHero',s.lastPipeline||'Original stream');
  text('lastTriggerHero',s.lastTrigger||'-');
  text('offlineReady',s.offlineReady);
  text('cacheMetric',s.cache+' • '+fmtBytes(s.cacheBytes));
  text('resourcePolicy',s.resourcePolicy);

  text('currentHomeInfo',wallInfo(s.currentHomeInfo));
  text('currentLockInfo',wallInfo(s.currentLockInfo));
  text('nextHomeInfo',wallInfo(s.nextHomeInfo));
  text('nextLockInfo',wallInfo(s.nextLockInfo));

  text('photosHealth',s.photosHealth);
  text('driveHealth',s.driveHealth);
  text('localHealth',s.localHealth);

  text('cacheBreakdown',s.cacheBreakdown);
  text('cycleProgress',s.cycleProgress);
  text('blockedCachePurge',s.blockedCachePurge);

  text('moodProfileLabel',s.moodProfileLabel);
  text('moodProfileSummary',s.moodProfileSummary);
  text('moodLastFactors',s.moodLastFactors);
  text('moodAutoReactStatus',s.moodAutoReactStatus);
  text('adaptiveMoodContext',s.adaptiveMoodContext);
  text('ambientLux',(s.ambientLux||'-')+' lux');
  text('dayPhase',s.dayPhase);
  text('thermalStatus',s.thermalStatus);
  text('locationPermission',s.locationPermission);
  text('locationStatus',s.locationStatus);
  text('weatherStatus',s.weatherStatus);

  text(
    'nextRun',
    s.nextRun
      ?new Date(s.nextRun).toLocaleString()
      :'Event/manual'
  );

  text('lastPipeline',s.lastPipeline);
  text('lastAspect',s.lastAspect);
  text('selectionReason',s.selectionReason);
  text('autonomousGrade',s.autonomousGrade);
  text('autonomousHealth',s.autonomousHealth);
  text('selfHealLast',s.selfHealLast);
  text('watchdogState',s.watchdogState);
  text('storagePressure',s.storagePressure);
  text('adaptiveCacheTargetV3',s.adaptiveCacheTargetV3);
  text('sourcePhotosTrust',s.sourcePhotosTrust);
  text('sourceDriveTrust',s.sourceDriveTrust);
  text('sourceResilienceLast',s.sourceResilienceLast);
  text('wallpaperDna',s.wallpaperDna);
  text('wallpaperFamily',s.wallpaperFamily);
  text('wallpaperEntropy',s.wallpaperEntropy);
  text('familyFatigue',s.familyFatigue);
  text('pairStory',s.pairStory);
  text('tasteConfidenceV2',s.tasteConfidenceV2);
  text('learningMode',s.learningMode);
  text('diversityBudget',s.diversityBudget);
  text('contextConfidence',s.contextConfidence);
  text('contextConflict',s.contextConflict);
  text('contextLuxSmoothed',s.contextLuxSmoothed);
  text('contextWeatherStable',s.contextWeatherStable);
  text('decisionTraceId',s.decisionTraceId);
  text('decisionConfidenceV2',s.decisionConfidenceV2);
  text('decisionBreakdownV2',s.decisionBreakdownV2);
  text('decisionWhyV2',s.decisionWhyV2);
  text('decisionWhyNotRunner',s.decisionWhyNotRunner);
  text('shadowRank',s.shadowRank);
  text('qualityGuardLast',s.qualityGuardLast);
  text('applyJournal',s.applyJournal);
  text('crashRecovery',s.crashRecovery);

  text('premiumHomeDna',s.premiumHomeDna);
  text('premiumHomeFamily',s.premiumHomeFamily);
  text('premiumHomePalette',s.premiumHomePalette);
  text('premiumHomeVisual',s.premiumHomeVisual);
  text('premiumHomeRole',s.premiumHomeRole);
  text('premiumHomeQuality',s.premiumHomeQuality);
  text('premiumHomeAmoled',s.premiumHomeAmoled);
  text('premiumHomeReadability',s.premiumHomeReadability);
  text('premiumHomeCrop',s.premiumHomeCrop);
  text('premiumHomeEntropy',s.premiumHomeEntropy);
  paletteSwatches('premiumHomeSwatches',s.premiumHomePalette);

  text('premiumLockDna',s.premiumLockDna);
  text('premiumLockFamily',s.premiumLockFamily);
  text('premiumLockPalette',s.premiumLockPalette);
  text('premiumLockVisual',s.premiumLockVisual);
  text('premiumLockRole',s.premiumLockRole);
  text('premiumLockQuality',s.premiumLockQuality);
  text('premiumLockAmoled',s.premiumLockAmoled);
  text('premiumLockReadability',s.premiumLockReadability);
  text('premiumLockCrop',s.premiumLockCrop);
  text('premiumLockEntropy',s.premiumLockEntropy);
  paletteSwatches('premiumLockSwatches',s.premiumLockPalette);

  text('premiumPairSummary',s.premiumPairSummary);
  text('premiumPairHarmony',s.premiumPairHarmony);
  text('premiumPairBrightness',s.premiumPairBrightness);
  text('premiumPairPalette',s.premiumPairPalette);
  text('premiumPairStyle',s.premiumPairStyle);

  text('premiumCacheMap',s.premiumCacheMap);
  text('premiumCacheSize',s.premiumCacheSize);
  text('premiumCacheReadiness',s.premiumCacheReadiness);
  text('premiumCacheHealth',s.premiumCacheHealth);

  text('premiumSourcePhotos',s.premiumSourcePhotos);
  text('premiumSourceDrive',s.premiumSourceDrive);

  text('premiumWatchdogAge',s.premiumWatchdogAge);
  text('premiumLastChangeAge',s.premiumLastChangeAge);
  text('premiumApplyTiming',s.premiumApplyTiming);
  text('premiumDecision',s.premiumDecision);
  text('premiumLearning',s.premiumLearning);
  text('premiumContext',s.premiumContext);
  text('premiumLibrary',s.premiumLibrary);
  text('premiumRecovery',s.premiumRecovery);
  text('premiumEventTimeline',s.premiumEventTimeline);
  text('premiumExportPath',s.premiumExportPath);

  text('visualProfile',s.visualProfile);
  text('visualPalette',s.visualPalette);
  text('visualDetail',s.visualDetail);
  text('visualPairing',s.visualPairing);
  text('engineHealth',s.engineHealth);
  text('lastTrigger',s.lastTrigger);
  text('lastError',s.lastError||'None');
  text('historyCount',s.historyCount);

  document.getElementById('rootDiag').textContent=
    'Root: '+(s.rootState||'Unknown')+
    ' • '+(s.rootProvider||'')+
    '\n'+(s.rootCaps||'')+
    '\nPipeline: '+(s.lastPipeline||'-')+
    '\nAspect: '+(s.lastAspect||'-')+
    '\nWhy: '+(s.selectionReason||'-');

  updateRangeLabels();
}

async function load(force){
  if(loading)return;
  loading=true;
  try{
    const s=await(
      await fetch('/api/settings',{cache:'no-store'})
    ).json();

    if(force||!dirty){
      settingIds.forEach(id=>setForm(id,s[id]));
      dirty=false;
      text('dirtyState','Synced');
    }

    render(s);
  }catch(e){
    text('status','Status load failed: '+e);
  }finally{
    loading=false;
  }
}

async function saveSettings(){
  const p=new URLSearchParams();

  settingIds.forEach(id=>{
    const e=document.getElementById(id);
    if(!e)return;
    p.set(
      id,
      e.type==='checkbox'
        ?(e.checked?'true':'false')
        :e.value
    );
  });

  text('status','Saving…');

  const r=await fetch('/api/settings',{
    method:'POST',
    headers:{
      'X-BBPixWall-Token':token,
      'Content-Type':'application/x-www-form-urlencoded'
    },
    body:p
  });

  text('status',r.ok?'Settings saved':'Save failed');

  if(r.ok){
    dirty=false;
    text('dirtyState','Synced');
    await load(true);
  }
}

async function loadRoot(){
  document.getElementById('rootDiag').textContent=
    await(
      await fetch('/api/root-diagnostics',{cache:'no-store'})
    ).text();
}

async function loadLogs(){
  document.getElementById('logs').textContent=
    await(
      await fetch('/api/logs',{cache:'no-store'})
    ).text();
}

document.addEventListener('input',e=>{
  if(e.target&&settingIds.includes(e.target.id)){
    dirty=true;
    text('dirtyState','Unsaved changes');
    updateRangeLabels();
  }
});

load(true);
loadLogs();
loadHistory();
refreshImages();

setInterval(()=>load(false),2500);
setInterval(loadHistory,15000);
</script>

</body>
</html>
        """.trimIndent()

    private fun js(
        value: String,
    ): String =
        "\"" +
            value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"") +
            "\""
}
