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
<title>BB-PixWall Remote Pro</title>

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
  <h1>BB-PixWall</h1>
  <div class="spacer"></div>
  <span class="chip" id="modeChip">Standard</span>
  <span class="chip good" id="lanChip">LAN</span>
</header>

<div class="hero">
  <div class="mood-title" id="heroMood">
    Waiting for Mood Engine
  </div>
  <div class="muted" id="heroMoodSummary">
    Environment profile will appear here.
  </div>

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
      <small>Cache</small>
      <strong id="cacheMetric">0</strong>
    </div>
    <div class="metric">
      <small>Resource policy</small>
      <strong id="resourcePolicy">-</strong>
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

  <div class="field">
    <label class="title">Source priority</label>
    <select id="sourcePriorityMode">
      ${
            options(
                SourcePriorityMode.entries.map {
                    it.name to it.label
                }
            )
        }
    </select>
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
                WallpaperOrder.entries.map {
                    it.name to it.label
                }
            )
        }
    </select>
  </div>

  <label class="toggle">
    <div>
      <b>Decision Engine</b>
      <div class="help">Smart ranking for Random Shuffle / Surprise.</div>
    </div>
    <input id="decisionEngineEnabled" type="checkbox">
  </label>
</div>
</details>

<details>
<summary>Network & cache</summary>
<div class="section">
  <div class="two">
    <label class="toggle"><div><b>Data Saver</b></div><input id="dataSaverEnabled" type="checkbox"></label>
    <label class="toggle"><div><b>Wi-Fi only</b></div><input id="wifiOnly" type="checkbox"></label>
    <label class="toggle"><div><b>Allow mobile data</b></div><input id="mobileDataAllowed" type="checkbox"></label>
    <label class="toggle"><div><b>Lean storage</b></div><input id="leanStorageMode" type="checkbox"></label>
    <label class="toggle"><div><b>Smart Home/Lock pairing</b></div><input id="smartPairingEnabled" type="checkbox"></label>
    <label class="toggle"><div><b>Adaptive Resource Protection</b></div><input id="adaptiveResourceProtectionEnabled" type="checkbox"></label>
  </div>

  <div class="two">
    <div class="field">
      <label class="title">Prefetch target</label>
      <input id="cacheTarget" type="number" min="4" max="36">
    </div>
    <div class="field">
      <label class="title">Cache cap MB</label>
      <input id="cacheMaxMb" type="number" min="128" max="2048" step="64">
    </div>
    <div class="field">
      <label class="title">Low storage reserve MB</label>
      <input id="lowStorageReserveMb" type="number" min="256" max="8192">
    </div>
  </div>

  <div class="three">
    <div class="metric"><small>Cache pools</small><strong id="cacheBreakdown">-</strong></div>
    <div class="metric"><small>Cycle</small><strong id="cycleProgress">-</strong></div>
    <div class="metric"><small>Blocked purge</small><strong id="blockedCachePurge">0</strong></div>
  </div>

  <div class="actions" style="margin-top:12px">
    <button class="secondary" onclick="act('/api/prepare')">Prepare / Cache</button>
    <button class="secondary" onclick="act('/api/purge-blocked')">Purge blocked</button>
    <button class="danger" onclick="act('/api/cache-clear')">Clear Cache</button>
  </div>
</div>
</details>

<details open>
<summary>Mood Engine</summary>
<div class="section">

  <label class="toggle">
    <div><b>Adaptive Mood Wallpapers</b></div>
    <input id="moodEngineEnabled" type="checkbox">
  </label>

  <div class="subcard">
    <div class="help">Current Mood</div>
    <div class="mood-title" id="moodProfileLabel">Waiting</div>
    <div class="muted" id="moodProfileSummary">Waiting</div>
    <p class="status" id="adaptiveMoodContext">Waiting</p>

    <div class="two">
      <div class="metric"><small>Last influence</small><strong id="moodLastFactors">-</strong></div>
      <div class="metric"><small>Auto-react</small><strong id="moodAutoReactStatus">-</strong></div>
    </div>
  </div>

  <label class="toggle">
    <div>
      <b>Auto-react to environment</b>
      <div class="help">Cached wallpaper only, cooldown protected.</div>
    </div>
    <input id="moodAutoReactEnabled" type="checkbox">
  </label>

  <div class="field">
    <label class="title">Auto-react cooldown</label>
    <input id="moodAutoReactCooldownMinutes" type="range" min="15" max="180" step="15">
    <div class="help"><span id="moodAutoReactCooldownMinutesVal">60</span> min</div>
  </div>

  <label class="toggle">
    <div><b>Ambient Light</b></div>
    <input id="moodAmbientLightEnabled" type="checkbox">
  </label>

  <div class="two">
    <div class="field">
      <label class="title">Dark room ≤ lux</label>
      <input id="moodDarkLuxThreshold" type="number" min="1" max="200">
    </div>
    <div class="field">
      <label class="title">Bright environment ≥ lux</label>
      <input id="moodBrightLuxThreshold" type="number" min="100" max="10000">
    </div>
  </div>

  <div class="three">
    <div class="metric"><small>Lux</small><strong id="ambientLux">-</strong></div>
    <div class="metric"><small>Day phase</small><strong id="dayPhase">-</strong></div>
    <div class="metric"><small>Thermal</small><strong id="thermalStatus">-</strong></div>
  </div>

  <div class="two">
    <label class="toggle"><div><b>Time Mood</b></div><input id="moodTimeEnabled" type="checkbox"></label>
    <label class="toggle"><div><b>Dark Mode Sync</b></div><input id="moodDarkModeEnabled" type="checkbox"></label>
    <label class="toggle"><div><b>Battery Context</b></div><input id="moodBatteryContextEnabled" type="checkbox"></label>
    <label class="toggle"><div><b>Thermal Protection</b></div><input id="moodThermalProtectionEnabled" type="checkbox"></label>
  </div>

  <div class="field">
    <label class="title">Mood strength</label>
    <input id="moodStrength" type="range" min="0" max="100" step="5">
    <div class="help"><span id="moodStrengthVal">60</span>%</div>
  </div>

  <div class="subcard">
    <h3>Weather Mood</h3>

    <label class="toggle"><div><b>Weather Mood</b></div><input id="moodWeatherEnabled" type="checkbox"></label>
    <label class="toggle">
      <div>
        <b>Use Device Location</b>
        <div class="help">Approximate location is enough.</div>
      </div>
      <input id="moodUseDeviceLocation" type="checkbox">
    </label>

    <div class="two">
      <div class="metric"><small>Permission</small><strong id="locationPermission">-</strong></div>
      <div class="metric"><small>Current location</small><strong id="locationStatus">-</strong></div>
    </div>

    <div class="field">
      <label class="title">Manual fallback city</label>
      <input id="moodWeatherCity" type="text" placeholder="Miraj, Maharashtra">
    </div>

    <p class="status" id="weatherStatus">Waiting</p>

    <div class="actions">
      <button class="secondary" onclick="act('/api/weather-refresh')">
        Refresh location & weather
      </button>
    </div>

    <div class="field">
      <label class="title">Weather influence</label>
      <input id="moodWeatherInfluence" type="range" min="0" max="100" step="5">
      <div class="help"><span id="moodWeatherInfluenceVal">60</span>%</div>
    </div>

    <label class="toggle"><div><b>Outdoor Temperature</b></div><input id="moodOutdoorTemperatureEnabled" type="checkbox"></label>

    <div class="two">
      <div class="field">
        <label class="title">Cold ≤ °C</label>
        <input id="moodColdTemperatureC" type="number" min="-10" max="30">
      </div>
      <div class="field">
        <label class="title">Hot ≥ °C</label>
        <input id="moodHotTemperatureC" type="number" min="20" max="50">
      </div>
    </div>
  </div>

</div>
</details>

<details>
<summary>Background reliability</summary>
<div class="section">
  <label class="toggle"><div><b>Background guard</b></div><input id="backgroundGuardEnabled" type="checkbox"></label>
  <label class="toggle"><div><b>Charging only</b></div><input id="chargingOnly" type="checkbox"></label>
  <label class="toggle"><div><b>Pause Battery Saver</b></div><input id="pauseBatterySaver" type="checkbox"></label>
  <label class="toggle"><div><b>Pause low battery</b></div><input id="pauseLowBattery" type="checkbox"></label>

  <div class="field">
    <label class="title">Low battery threshold</label>
    <input id="lowBatteryThreshold" type="number" min="5" max="50">
  </div>

  <label class="toggle"><div><b>Quiet hours</b></div><input id="quietHoursEnabled" type="checkbox"></label>

  <div class="two">
    <div class="field"><label class="title">Start hour</label><input id="quietStartHour" type="number" min="0" max="23"></div>
    <div class="field"><label class="title">End hour</label><input id="quietEndHour" type="number" min="0" max="23"></div>
  </div>
</div>
</details>

<details open>
<summary>Autonomous Intelligence Core</summary>
<div class="section">

  <div class="four">
    <div class="metric">
      <small>Engine grade</small>
      <strong id="autonomousGrade">Waiting</strong>
    </div>

    <div class="metric">
      <small>Watchdog</small>
      <strong id="watchdogState">Waiting</strong>
    </div>

    <div class="metric">
      <small>Storage</small>
      <strong id="storagePressure">Waiting</strong>
    </div>

    <div class="metric">
      <small>Adaptive cache</small>
      <strong id="adaptiveCacheTargetV3">Waiting</strong>
    </div>
  </div>

  <div class="subcard">
    <h3>Self-Heal Engine</h3>
    <div class="muted" id="autonomousHealth">Waiting</div>
    <div class="status" id="selfHealLast">Waiting</div>

    <div class="actions" style="margin-top:10px">
      <button
        class="secondary"
        onclick="act('/api/autonomy-audit')"
      >
        Run autonomous audit
      </button>
    </div>
  </div>

  <div class="subcard">
    <h3>Wallpaper DNA</h3>

    <div class="three">
      <div class="metric">
        <small>DNA</small>
        <strong id="wallpaperDna">Waiting</strong>
      </div>

      <div class="metric">
        <small>Visual family</small>
        <strong id="wallpaperFamily">Waiting</strong>
      </div>

      <div class="metric">
        <small>Entropy</small>
        <strong id="wallpaperEntropy">Waiting</strong>
      </div>
    </div>

    <p class="muted">
      Fatigue:
      <span id="familyFatigue">Learning</span>
    </p>

    <p class="muted">
      Pair director:
      <span id="pairStory">Waiting</span>
    </p>
  </div>

  <div class="subcard">
    <h3>Learning Brain</h3>

    <div class="three">
      <div class="metric">
        <small>Taste confidence</small>
        <strong id="tasteConfidenceV2">Learning</strong>
      </div>

      <div class="metric">
        <small>Learning mode</small>
        <strong id="learningMode">Explore</strong>
      </div>

      <div class="metric">
        <small>Diversity budget</small>
        <strong id="diversityBudget">Waiting</strong>
      </div>
    </div>
  </div>

  <div class="subcard">
    <h3>Context Resolver</h3>

    <div class="three">
      <div class="metric">
        <small>Confidence</small>
        <strong id="contextConfidence">Waiting</strong>
      </div>

      <div class="metric">
        <small>Smoothed light</small>
        <strong id="contextLuxSmoothed">Waiting</strong>
      </div>

      <div class="metric">
        <small>Weather stability</small>
        <strong id="contextWeatherStable">Waiting</strong>
      </div>
    </div>

    <p class="muted" id="contextConflict">Waiting</p>
  </div>

  <div class="subcard">
    <h3>Source Resilience</h3>

    <div class="two">
      <div class="metric">
        <small>Google Photos trust</small>
        <strong id="sourcePhotosTrust">Learning</strong>
      </div>

      <div class="metric">
        <small>Google Drive trust</small>
        <strong id="sourceDriveTrust">Learning</strong>
      </div>
    </div>

    <p class="muted" id="sourceResilienceLast">Waiting</p>
  </div>

  <div class="subcard">
    <h3>Decision Trace</h3>

    <div class="two">
      <div class="metric">
        <small>Trace ID</small>
        <strong id="decisionTraceId">-</strong>
      </div>

      <div class="metric">
        <small>Confidence</small>
        <strong id="decisionConfidenceV2">Waiting</strong>
      </div>
    </div>

    <p>
      <b>Why V2:</b>
      <span id="decisionWhyV2">Waiting</span>
    </p>

    <p class="muted">
      <b>Why not runner-up:</b>
      <span id="decisionWhyNotRunner">Waiting</span>
    </p>

    <p class="muted">
      <b>Score trace:</b>
      <span id="decisionBreakdownV2">Waiting</span>
    </p>

    <p class="muted">
      <b>Shadow ranking:</b>
      <span id="shadowRank">Waiting</span>
    </p>
  </div>

  <div class="subcard">
    <h3>Recovery / Quality</h3>

    <div class="muted">
      Quality guard:
      <span id="qualityGuardLast">No rejection</span>
    </div>

    <div class="muted">
      Apply journal:
      <span id="applyJournal">Idle</span>
    </div>

    <div class="muted">
      Crash recovery:
      <span id="crashRecovery">None</span>
    </div>
  </div>

</div>
</details>

<details open>
<summary>Premium Intelligence Control Center</summary>
<div class="section">

  <div class="two">
    <div class="subcard">
      <h3>Home Intelligence</h3>
      <div class="metric">
        <small>DNA</small>
        <strong id="premiumHomeDna">Waiting</strong>
      </div>
      <p class="muted" id="premiumHomeVisual">Waiting</p>
      <p>
        Role <b id="premiumHomeRole">-</b>
        • Quality <b id="premiumHomeQuality">-</b>
        • AMOLED <b id="premiumHomeAmoled">-</b>
      </p>
      <p>
        Readability <b id="premiumHomeReadability">-</b>
        • Crop <b id="premiumHomeCrop">-</b>
        • Entropy <b id="premiumHomeEntropy">-</b>
      </p>
      <div class="muted" id="premiumHomePalette">Waiting</div>
      <div class="palette-swatches" id="premiumHomeSwatches"></div>
    </div>

    <div class="subcard">
      <h3>Lock Intelligence</h3>
      <div class="metric">
        <small>DNA</small>
        <strong id="premiumLockDna">Waiting</strong>
      </div>
      <p class="muted" id="premiumLockVisual">Waiting</p>
      <p>
        Role <b id="premiumLockRole">-</b>
        • Quality <b id="premiumLockQuality">-</b>
        • AMOLED <b id="premiumLockAmoled">-</b>
      </p>
      <p>
        Readability <b id="premiumLockReadability">-</b>
        • Crop <b id="premiumLockCrop">-</b>
        • Entropy <b id="premiumLockEntropy">-</b>
      </p>
      <div class="muted" id="premiumLockPalette">Waiting</div>
      <div class="palette-swatches" id="premiumLockSwatches"></div>
    </div>
  </div>

  <div class="subcard">
    <h3>Home / Lock Pair Director</h3>
    <p id="premiumPairSummary">Waiting</p>
    <div class="four">
      <div class="metric">
        <small>Harmony</small>
        <strong id="premiumPairHarmony">-</strong>
      </div>
      <div class="metric">
        <small>Brightness</small>
        <strong id="premiumPairBrightness">-</strong>
      </div>
      <div class="metric">
        <small>Palette</small>
        <strong id="premiumPairPalette">-</strong>
      </div>
      <div class="metric">
        <small>Style</small>
        <strong id="premiumPairStyle">-</strong>
      </div>
    </div>
  </div>

  <div class="subcard">
    <h3>Cache Intelligence</h3>
    <div class="four">
      <div class="metric">
        <small>Pools</small>
        <strong id="premiumCacheMap">Waiting</strong>
      </div>
      <div class="metric">
        <small>Size</small>
        <strong id="premiumCacheSize">-</strong>
      </div>
      <div class="metric">
        <small>Readiness</small>
        <strong id="premiumCacheReadiness">-</strong>
      </div>
      <div class="metric">
        <small>Health</small>
        <strong id="premiumCacheHealth">-</strong>
      </div>
    </div>
  </div>

  <div class="subcard">
    <h3>Source Telemetry</h3>
    <div class="two">
      <div class="metric">
        <small>Google Photos</small>
        <strong id="premiumSourcePhotos">Learning</strong>
      </div>
      <div class="metric">
        <small>Google Drive</small>
        <strong id="premiumSourceDrive">Learning</strong>
      </div>
    </div>

    <div class="actions" style="margin-top:10px">
      <button class="secondary" onclick="act('/api/reset-source-photos')">
        Reset Photos telemetry
      </button>
      <button class="secondary" onclick="act('/api/reset-source-drive')">
        Reset Drive telemetry
      </button>
    </div>
  </div>

  <div class="subcard">
    <h3>Runtime Telemetry</h3>
    <div class="four">
      <div class="metric">
        <small>Watchdog age</small>
        <strong id="premiumWatchdogAge">-</strong>
      </div>
      <div class="metric">
        <small>Last change</small>
        <strong id="premiumLastChangeAge">-</strong>
      </div>
      <div class="metric">
        <small>Apply timing</small>
        <strong id="premiumApplyTiming">-</strong>
      </div>
      <div class="metric">
        <small>Decision</small>
        <strong id="premiumDecision">-</strong>
      </div>
    </div>
    <p class="muted" id="premiumLearning">Waiting</p>
    <p class="muted" id="premiumContext">Waiting</p>
  </div>

  <div class="subcard">
    <h3>Library / Recovery</h3>
    <p id="premiumLibrary">Waiting</p>
    <p class="muted" id="premiumRecovery">Waiting</p>
    <p class="muted">
      Export:
      <span id="premiumExportPath">-</span>
    </p>
  </div>

  <div class="subcard">
    <h3>Intelligence Timeline</h3>
    <div class="timeline" id="premiumEventTimeline">
      No events yet
    </div>
  </div>

  <div class="actions">
    <button class="secondary" onclick="act('/api/premium-refresh')">
      Refresh intelligence
    </button>
    <button class="secondary" onclick="act('/api/reanalyze-current')">
      Reanalyze current
    </button>
    <button class="secondary" onclick="act('/api/rebuild-next')">
      Rebuild Next
    </button>
    <button class="secondary" onclick="act('/api/cache-integrity')">
      Cache integrity
    </button>
    <button class="secondary" onclick="act('/api/premium-self-heal')">
      Full Self-Heal
    </button>
    <button class="secondary" onclick="act('/api/export-intelligence')">
      Export report
    </button>
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
<summary>Quality & aspect</summary>
<div class="section">
  <label class="toggle"><div><b>Smart Crop</b></div><input id="smartCropEnabled" type="checkbox"></label>

  <div class="two">
    <div class="field">
      <label class="title">Aspect preference</label>
      <select id="aspectPreference">
        ${
            options(
                AspectPreference.entries.map {
                    it.name to it.label
                }
            )
        }
      </select>
    </div>

    <div class="field">
      <label class="title">Crop tolerance %</label>
      <input id="smartCropTolerancePct" type="number" min=".2" max="5" step=".1">
    </div>

    <div class="field">
      <label class="title">Duplicate distance</label>
      <input id="perceptualDistance" type="number" min="0" max="24">
    </div>
  </div>

  <div class="subcard">
    <h3>Visual Intelligence V2</h3>

    <div class="three">
      <div class="metric">
        <small>Visual profile</small>
        <strong id="visualProfile">Waiting</strong>
      </div>

      <div class="metric">
        <small>Dominant palette</small>
        <strong id="visualPalette">Waiting</strong>
      </div>

      <div class="metric">
        <small>Home / Lock pairing</small>
        <strong id="visualPairing">Waiting</strong>
      </div>
    </div>

    <p class="muted" id="visualDetail">
      Waiting for analyzed wallpaper.
    </p>

    <p class="help">
      Local-only analysis: quality, dominant palette,
      brightness, saturation, contrast, warmth, AMOLED
      suitability, lock-screen readability, crop safety,
      edge/detail density, cinematic, vibrant, pastel,
      monochrome and low-light classification.
    </p>
  </div>

  <div class="subcard">
    <div>Pipeline: <b id="lastPipeline">-</b></div>
    <div>Aspect: <b id="lastAspect">-</b></div>
    <div class="muted" style="margin-top:7px">
      Why: <span id="selectionReason">-</span>
    </div>
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
