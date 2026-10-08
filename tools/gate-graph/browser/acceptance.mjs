import assert from 'node:assert/strict';
import { chromium, webkit, devices } from 'playwright';
import { mkdir, copyFile, readdir, readFile, writeFile } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { createRequire } from 'node:module';
import { resolve } from 'node:path';
import { pathToFileURL } from 'node:url';

const [fixtures, output, engine = 'chromium'] = process.argv.slice(2);
const fixedViewports = process.argv.slice(5).includes('--fixed-viewports');
assert.ok(fixtures && output, 'usage: node acceptance.mjs FIXTURE_ROOT NEW_EVIDENCE_ROOT [chromium|webkit] [--fixed-viewports]');
assert.ok(['chromium', 'webkit'].includes(engine), 'supported browser engine');
assert.ok(!fixedViewports || engine === 'webkit', 'fixed viewport mode is restricted to WebKit');
await mkdir(output);
const browser = await ({ chromium, webkit }[engine]).launch({ headless: true });
const playwrightVersion = createRequire(import.meta.url)('playwright/package.json').version;
// WebKit's emulated offline mode rejects even a minimal file:// document.
// Abort external requests explicitly; the acceptance container also has no network.
const emulatedOffline = engine !== 'webkit';
const cases = [
  ['desktop', { viewport: { width: 1440, height: 900 } }],
  ['narrow', { viewport: { width: 1024, height: 768 } }],
  ['iphone-portrait', devices['iPhone 13']],
  ['iphone-landscape', devices['iPhone 13 landscape']],
  ['portrait-full', { ...devices['iPhone 13'], viewport: { width: 390, height: 844 } }],
  ['landscape-full', { ...devices['iPhone 13 landscape'], viewport: { width: 844, height: 390 } }],
];
const records = [];
const artifacts = {};
const skipped = [];
let completed = false;
let activeCase;
let activeErrors = [];
let activeRequests = [];
let failure;
async function settle(page) {
  await page.evaluate(async () => {
    await new Promise(requestAnimationFrame);
    await new Promise(requestAnimationFrame);
  });
}
async function capture(page, name, state, fullPage = false) {
  await settle(page);
  const file = `${name}-${state}${fullPage ? '-full' : ''}.png`;
  await page.screenshot({ path: resolve(output, file), fullPage, scale: 'css' });
  const metadata = await page.evaluate(() => ({
    theme: document.documentElement.hasAttribute('data-light') ? 'light' : 'dark',
    fragment: location.hash,
    scroll: { x: scrollX, y: scrollY },
    document: { width: document.documentElement.scrollWidth, height: document.documentElement.scrollHeight },
    dialogOpen: !!document.querySelector('dialog[open]'),
    visibleLanes: document.querySelectorAll('.row').length,
    resourceTracks: document.querySelectorAll('.resource-row').length,
    openDisclosures: [...document.querySelectorAll('details[open] > summary')].map(e => e.textContent),
  }));
  records.push({ file, state, viewport: page.viewportSize(), fullPage, ...metadata, inspected: false });
}
async function activate(page, locator, touch) {
  if (touch) await locator.tap();
  else { await locator.focus(); await page.keyboard.press('Enter'); }
}
async function openTimelineControls(page) {
  const summary = page.locator('summary').filter({ hasText: /^Timeline controls$/ });
  if (!(await summary.evaluate(element => element.parentElement.open))) await summary.click();
}
async function timelineControl(page, name) {
  await openTimelineControls(page);
  await page.getByRole('button', { name, exact: true }).click();
}
async function firstScreenTimeline(page) {
  const rect = await page.locator('.row[data-node="A-build"] .bar').evaluate(element => {
    const b = element.getBoundingClientRect();
    return { top: b.top, bottom: b.bottom, width: b.width, height: b.height, viewport: innerHeight, scroll: scrollY };
  });
  assert.equal(rect.scroll,0,'first-screen assertion is made before scrolling');
  assert.ok(rect.width>0 && rect.height>0,'first chronological interval has real geometry');
  assert.ok(rect.top>=0 && rect.bottom<=rect.viewport,'initial portrait view exposes the first complete duration bar: '+JSON.stringify(rect));
  return rect;
}
async function closeDialog(page, touch) {
  if (touch) await page.getByRole('button', { name: 'Close details', exact: true }).tap();
  else await page.keyboard.press('Escape');
  assert.equal(await page.getByRole('dialog').count(), 0, 'dialog is dismissible');
}
async function waitReady(page, refused = false) {
  await page.waitForFunction(() => !document.querySelector('#status').textContent.startsWith('Loading'));
  const status = await page.locator('#status').innerText();
  assert.equal(status.includes('refused'), refused, status);
}
async function themeContrast(page) {
  const result = await page.evaluate(() => {
    const color = text => text.match(/[\d.]+/g)?.map(Number);
    const luminance = rgb => rgb.slice(0, 3).map(v => v / 255).map(v => v <= .04045 ? v / 12.92 : ((v + .055) / 1.055) ** 2.4)
      .reduce((sum, v, i) => sum + v * [.2126, .7152, .0722][i], 0);
    const failures = []; let checked = 0;
    for (const element of document.querySelectorAll('button,p,h1,h2,h3,label,summary,.ruler span,.crumb')) {
      if (!element.checkVisibility() || !element.textContent.trim()) continue;
      const foreground = color(getComputedStyle(element).color);
      let node = element, background;
      while (node) {
        const candidate = color(getComputedStyle(node).backgroundColor);
        if (candidate && (candidate.length < 4 || candidate[3] === 1)) { background = candidate; break; }
        node = node.parentElement;
      }
      if (!foreground || !background) throw new Error('Unmeasured text color');
      const a = luminance(foreground), b = luminance(background);
      const ratio = (Math.max(a, b) + .05) / (Math.min(a, b) + .05);
      checked++;
      if (ratio < 4.5) failures.push({ text: element.textContent.slice(0, 80), ratio });
    }
    return { checked, failures };
  });
  assert.ok(result.checked > 10, 'contrast checks exercise rendered text');
  assert.deepEqual(result.failures, [], 'normal text contrast >= 4.5:1');
  return result.checked;
}
async function checkWindow(page, expected) {
  const actual = await page.evaluate(() => ({
    fragment: new URLSearchParams(location.hash.slice(1)).get('window').split(',').map(Number),
    controls: ['window-start', 'window-end'].map(id => Number(document.getElementById(id).value) / 100),
    rulers: [...document.querySelectorAll('.ruler')].map(e => e.textContent),
  }));
  assert.deepEqual(actual.fragment, expected, 'time window is reflected in the fragment');
  assert.deepEqual(actual.controls, expected, 'time controls reflect the active window');
  assert.ok(actual.rulers.length > 0);
  assert.ok(actual.rulers.every(value => value === actual.rulers[0]), 'all rulers share time coordinates');
}
async function setRange(page, selector, value, event = 'change') {
  await page.locator(selector).evaluate((element, { value, event }) => {
    element.value = value;
    element.dispatchEvent(new Event(event, { bubbles: true }));
  }, { value, event });
}
async function checkAlignment(page) {
  const geometry = await page.locator('.track,.resource-track').evaluateAll(elements => elements.map(element => {
    const b = element.getBoundingClientRect();
    const cursor = parseFloat(getComputedStyle(element, '::after').left);
    return { left: b.left, width: b.width, cursor: cursor / b.width };
  }));
  assert.ok(geometry.length >= 7, 'task and resource tracks exercised');
  for (const item of geometry) {
    assert.ok(Math.abs(item.left - geometry[0].left) <= 1, 'track starts align');
    assert.ok(Math.abs(item.width - geometry[0].width) <= 1, 'track widths align');
    assert.ok(Math.abs(item.cursor - .37) < .005, 'shared cursor is at 37% on every track');
  }
}
async function geometry(page) {
  const result = await page.evaluate(() => {
    const visible = element => element.getClientRects().length > 0;
    const controls = [...document.querySelectorAll('button,input,select,summary')].filter(visible);
    return {
      overflow: document.documentElement.scrollWidth - document.documentElement.clientWidth,
      controls: controls.length,
      small: controls.filter(e => {const b=e.getBoundingClientRect();return b.width < 44 || b.height < 44;}).map(e=>e.textContent),
      outside: controls.filter(e => {const b=e.getBoundingClientRect();return b.left < -1 || b.right > innerWidth + 1;}).map(e=>e.textContent),
    };
  });
  assert.ok(result.controls >= 6, 'nonempty rendered controls, including collapsed-toolbar surfaces');
  assert.ok(result.overflow <= 1, JSON.stringify(result));
  assert.deepEqual(result.small, [], '44 px native controls');
  assert.deepEqual(result.outside, [], 'controls within page width');
  return result;
}
try {
  for (const fixture of ['branch','dense','signals','incomplete','malformed','leaf','open-signals']) {
    const isolated = resolve(output, `isolated-${fixture}`);
    await mkdir(isolated);
    await copyFile(resolve(fixtures,fixture,'index.html'), resolve(isolated,'index.html'));
    assert.deepEqual(await readdir(isolated), ['index.html']);
    artifacts[fixture] = createHash('sha256').update(await readFile(resolve(isolated, 'index.html'))).digest('hex');
    for (const [deviceName, descriptor] of cases) {
      if (fixture !== 'branch' && !['desktop','iphone-portrait','iphone-landscape'].includes(deviceName)) continue;
      activeCase = { fixture, device: deviceName };
      if (fixedViewports && fixture === 'branch' && deviceName.startsWith('iphone')) {
        for (const state of ['rotated-detail','rotated-search']) skipped.push({fixture,device:deviceName,state,
          reason:'WebKit mobile resize leaves the layout viewport at the previous width; fixed-viewport mode does not accept rotation.'});
      }
      const { defaultBrowserType: ignored, ...options } = descriptor;
      const context = await browser.newContext({ ...options, offline: emulatedOffline });
      await context.route('**/*', route => route.request().url().startsWith('file:') ? route.continue() : route.abort());
      context.setDefaultTimeout(10000);
      const page = await context.newPage();
      const touch = !!options.hasTouch;
      const errors = [], requests = [];
      activeErrors = errors;
      activeRequests = requests;
      page.on('pageerror', e => errors.push(e.message));
      page.on('console', e => {if(e.type()==='error')errors.push(e.text());});
      page.on('request', r=>{if(!r.url().startsWith('file:')) requests.push(r.url());});
      const started = performance.now();
      await page.goto(pathToFileURL(resolve(isolated,'index.html')).href);
      await waitReady(page, fixture === 'malformed');
      const elapsedMs = performance.now()-started;
      const name = `${engine}-${fixture}-${deviceName}`;
      if (fixture === 'malformed') {
        assert.match(await page.locator('#status').innerText(), /^Report refused:/);
        assert.equal(await page.locator('#view-controls button,#attention-controls button,#breadcrumbs button,.row,.resource-row').count(),0,
          'refused data never initializes navigation, controls, tasks or telemetry');
        await capture(page,name,'refused');
        assert.deepEqual(errors,[]); assert.deepEqual(requests,[]);
        records.push({fixture,device:deviceName,elapsedMs,errors,externalRequests:requests.length,passed:true});
        await context.close();
        continue;
      }
      await geometry(page);
      if (fixture === 'branch' && ['iphone-portrait','portrait-full'].includes(deviceName)) {
        records.push({fixture,device:deviceName,firstScreenTimeline:await firstScreenTimeline(page)});
      }
      if (!['leaf','open-signals'].includes(fixture) || deviceName === 'iphone-portrait') {
        await capture(page,name,'overview');
      }
      await openTimelineControls(page);
      const darkContrast = await themeContrast(page);
      await timelineControl(page,'Light / dark');
      const lightContrast = await themeContrast(page);
      await timelineControl(page,'Light / dark');
      if(fixture==='branch') {
        assert.equal(await page.locator('.row').count(),4);
        assert.equal(await page.locator('.edge').count(),4);
        const bars=await page.locator('.row .bar').evaluateAll(es=>es.map(e=>parseFloat(e.style.width)));
        assert.ok(Math.abs(bars[0]-100/260*100)<0.001);
        const initialEdges = await page.locator('.edge').evaluateAll(es => es.map(e => [e.dataset.edge,e.dataset.from,e.dataset.to,e.getAttribute('d')]).sort((a,b)=>a[0].localeCompare(b[0])));
        assert.equal(new Set(initialEdges.map(e => e[0])).size, 4, 'four distinct dependency identities');
        await activate(page, page.getByRole('button',{name:'B-check · passed',exact:true}), touch);
        assert.ok(await page.getByRole('dialog').isVisible());
        assert.match(await page.getByRole('dialog').innerText(), /B-check/);
        await geometry(page);
        await capture(page,name,'detail');
        if (['desktop','iphone-portrait'].includes(deviceName)) {
          const canonical = page.getByRole('dialog').locator('summary').filter({hasText:/^Canonical task$/});
          await canonical.click();
          assert.match(await page.getByRole('dialog').innerText(),/:id "B-check"/);
          await capture(page,name,'canonical-task');
          await canonical.click();
        }
        if (deviceName.startsWith('iphone') && !fixedViewports) {
          await page.setViewportSize(deviceName==='iphone-portrait'?{width:844,height:390}:{width:390,height:844});
          assert.ok(await page.getByRole('dialog').isVisible());
          assert.match(await page.getByRole('dialog').innerText(), /B-check/);
          await geometry(page);
          await capture(page,name,'rotated-detail');
          await page.setViewportSize(options.viewport);
        }
        await closeDialog(page, touch);
        await timelineControl(page,'Zoom in');
        await checkWindow(page, [.25,.75]);
        await timelineControl(page,'Pan →');
        await checkWindow(page, [.5,1]);
        await timelineControl(page,'Reset time');
        await checkWindow(page, [0,1]);
        const restoredEdges = await page.locator('.edge').evaluateAll(es => es.map(e => [e.dataset.edge,e.dataset.from,e.dataset.to,e.getAttribute('d')]).sort((a,b)=>a[0].localeCompare(b[0])));
        assert.deepEqual(restoredEdges, initialEdges, 'reset restores deterministic dependency geometry');
        await timelineControl(page,'Light / dark');
        await capture(page,name,'light');
        await page.getByRole('searchbox').fill('C-check');
        assert.equal(await page.locator('.row').count(),1);
        await timelineControl(page,'Zoom in');
        const fragment = new URL(page.url()).hash;
        await page.reload(); await waitReady(page);
        assert.equal(new URL(page.url()).hash, fragment, 'search/window fragment survives reload');
        assert.equal(await page.getByRole('searchbox').inputValue(),'C-check');
        assert.equal(await page.locator('.row').count(),1);
        await checkWindow(page,[.25,.75]);
        if(deviceName.startsWith('iphone') && !fixedViewports) {
          await page.setViewportSize(deviceName==='iphone-portrait'?{width:844,height:390}:{width:390,height:844});
          assert.equal(await page.getByRole('searchbox').inputValue(),'C-check');
          await geometry(page);
          await capture(page,name,'rotated-search');
        }
        if (deviceName === 'desktop') {
          await page.goto(page.url().split('#')[0] + '#task=missing&window=NaN,Infinity&fold=missing');
          await page.reload(); await waitReady(page);
          await checkWindow(page,[0,1]);
          assert.equal(await page.locator('.row').count(),4,'invalid fragment cannot hide the overview');
          await page.goto(page.url().split('#')[0] + '#task=root&fold=A-build,A-build');
          await page.reload(); await waitReady(page);
          assert.equal(await page.locator('.row').count(),4,'duplicate fold members are refused without hiding the overview');
          assert.equal(new URLSearchParams(new URL(page.url()).hash.slice(1)).get('fold'),'','duplicate fold hint is discarded');
          await page.goto(page.url().split('#')[0] + '#task=root&q=C-check&page=128000');
          await page.reload(); await waitReady(page);
          assert.equal(await page.locator('.row').count(),1,'excessive page cannot hide a matching task');
          assert.match(await page.locator('.row').innerText(),/C-check/);
          assert.equal(new URLSearchParams(new URL(page.url()).hash.slice(1)).get('page'),'0','page clamps to the matching result extent');
        }
      }
      if(fixture==='dense') {
        await page.getByRole('button',{name:'Needs attention',exact:true}).click();
        assert.equal(await page.locator('.row').count(),1,'whole-run attention finds a failure below the current hierarchy');
        assert.match(await page.locator('.row').innerText(), /child-1500.*failed/);
        await page.locator('.row').scrollIntoViewIfNeeded();
        await capture(page,name,'attention');
        await page.getByRole('button',{name:'Needs attention',exact:true}).click();
        await page.getByRole('button',{name:'Open 3000 children',exact:true}).click();
        assert.ok(await page.locator('.fold-row').count()<=24);
        assert.ok(await page.locator('.fold-row').count()>0,'dense children are actually folded');
        const foldMembers = await page.locator('.fold-row').evaluateAll(es=>es.map(e=>e.dataset.members));
        const foldedEdges = await page.locator('.edge').evaluateAll(es=>es.map(e=>[e.dataset.from,e.dataset.to]));
        const memberIds = new Set(foldMembers.flatMap(members=>members.split(' ')));
        assert.ok(foldedEdges.length>0 && foldedEdges.length<=120,'dense cross-fold dependencies render within the edge budget');
        assert.ok(foldedEdges.every(edge=>edge.every(id=>memberIds.has(id))),'folded edge endpoints retain visible original member identities');
        const failedFold = page.locator('.fold-row').filter({hasText:'1 need attention'});
        assert.equal(await failedFold.count(),1,'failure remains visible in a folded summary');
        await page.locator('#timeline').evaluate(element=>element.scrollIntoView({block:'start'}));
        await capture(page,name,'folds');
        await activate(page, failedFold.locator('button').first(), touch);
        assert.ok(await page.locator('.row').count()<=24);
        assert.ok(new URLSearchParams(new URL(page.url()).hash.slice(1)).get('fold'),'group identity is linkable');
        await page.locator('#timeline').evaluate(element=>scrollTo(0,element.getBoundingClientRect().top+scrollY));
        await capture(page,name,'companion');
        await page.getByRole('button',{name:'← Back',exact:true}).click();
        assert.deepEqual(await page.locator('.fold-row').evaluateAll(es=>es.map(e=>e.dataset.members)),foldMembers,'back restores exact folded membership');
        await page.getByRole('searchbox').fill('failed');
        assert.equal(await page.locator('.row').count(),1);
        assert.equal(await page.evaluate(()=>window.injected), undefined);
        await page.getByRole('searchbox').fill('Literal');
        assert.equal(await page.locator('.row').count(),1);
        assert.match(await page.locator('.row').innerText(), /<\/script><script>window.injected=true<\/script>/,'hostile label remains literal text');
      }
      if(fixture==='signals') {
        await page.locator('#opportunities summary').click();
        await page.locator('#opportunities').evaluate(element=>scrollTo(0,element.getBoundingClientRect().top+scrollY));
        assert.match(await page.locator('#opportunities').innerText(),/Not a bottleneck verdict or speedup estimate/);
        await capture(page,name,'candidate-list');
        const cpuDip = page.locator('#opportunities').getByRole('button',{name:/^activity-dip · cpu-user-ns · host ·/}).first();
        await activate(page,cpuDip,touch);
        assert.match(await page.getByRole('dialog').innerText(),/Next experiment:.*independent gates/);
        assert.match(await page.getByRole('dialog').innerText(),/Compare full-run elapsed time/);
        await page.getByRole('dialog').locator('summary').filter({hasText:/^Candidate policy and exact measurement IDs$/}).click();
        const candidateText = await page.getByRole('dialog').innerText();
        assert.ok(candidateText.includes(':policy :activity-change-v1'));
        for (const index of [5,24,25,26,27]) assert.ok(candidateText.includes(`"host-cpu-user-ns-${index}"`),'candidate retains exact supporting sample ID');
        assert.equal(new URLSearchParams(new URL(page.url()).hash.slice(1)).get('exact'),'100000000,112000000','candidate preserves exact nanosecond evidence window');
        const candidateBounds = await page.locator('.ruler').evaluateAll(es=>es.map(e=>[e.firstElementChild.textContent,e.lastElementChild.textContent]));
        assert.ok(candidateBounds.length>=2 && candidateBounds.every(([start,end])=>start==='100 ms' && end==='112 ms'));
        await geometry(page);
        await capture(page,name,'candidate-detail');
        await page.getByRole('dialog').locator('summary').filter({hasText:/^Candidate policy and exact measurement IDs$/}).click();
        await page.getByRole('dialog').getByRole('heading',{name:'Investigate this interval',exact:true}).evaluate(element=>element.scrollIntoView({block:'start'}));
        for (const task of ['B-check','C-check']) {
          const button = page.getByRole('dialog').getByRole('button',{name:`${task} · passed`,exact:true});
          assert.ok(await button.isVisible(),'coincident task remains actionable');
          const bounds = await button.boundingBox();
          assert.ok(bounds.y>=0 && bounds.y+bounds.height<=page.viewportSize().height,'coincident task button is within captured viewport');
        }
        await capture(page,name,'candidate-tasks');
        await closeDialog(page,touch);
        await timelineControl(page,'Reset time');
        assert.ok(await page.locator('select option').count()>=39);
        const coreLabels = await page.locator('select option').allTextContents();
        assert.equal(coreLabels.filter(text=>/^CPU core \d+ · cpu-user-ns$/.test(text)).length,32,'all 32 core tracks discoverable');
        await page.getByLabel('Add a resource track').selectOption({label:'GPU 0 · gpu-utilization-percent'});
        assert.ok(await page.locator('.resource-row').count()>=4);
        await page.getByLabel('Add a resource track').selectOption({label:'CPU core 31 · cpu-user-ns'});
        await page.getByLabel('Add a resource track').selectOption({label:'Disk 0 · bytes-read'});
        await page.getByText('Time window and shared cursor',{exact:true}).click();
        await setRange(page,'#time-cursor','37','input');
        await checkAlignment(page);
        await setRange(page,'#window-start','20');
        await setRange(page,'#window-end','80');
        await checkWindow(page,[.2,.8]);
        await checkAlignment(page);
        await page.locator('#resources').scrollIntoViewIfNeeded();
        await capture(page,name,'window-resources');
        await timelineControl(page,'Reset time');
        await checkWindow(page,[0,1]);
        const gpu = page.locator('.resource-row').filter({hasText:'GPU 0 · gpu-utilization-percent'});
        assert.equal(await gpu.count(),1);
        await gpu.scrollIntoViewIfNeeded();
        await capture(page,name,'gpu');
        await activate(page,gpu.getByRole('button',{name:'Inspect track evidence',exact:true}),touch);
        assert.ok(await page.getByRole('dialog').isVisible());
        assert.match(await page.getByRole('dialog').innerText(),/gpu-utilization-percent/);
        const raw = await page.getByRole('dialog').textContent();
        assert.ok(raw.includes('gpu-gpu-utilization-percent-0'),'resource dialog retains exact sample identity');
        await geometry(page);
        await capture(page,name,'resource-evidence');
        if (['desktop','iphone-portrait'].includes(deviceName)) {
          await page.getByRole('dialog').locator('summary').filter({hasText:/^Exact core query and evidence$/}).click();
          assert.match(await page.getByRole('dialog').innerText(),/gpu-gpu-utilization-percent-0/);
          await capture(page,name,'resource-evidence-expanded');
        }
        await closeDialog(page,touch);
        await timelineControl(page,'Light / dark');
        await page.locator('#resources').scrollIntoViewIfNeeded();
        await capture(page,name,'light-resources',true);
        await page.getByRole('button',{name:'Remove CPU core 31 · cpu-user-ns',exact:true}).click();
        assert.equal(await page.locator('.resource-row').filter({hasText:'CPU core 31 · cpu-user-ns'}).count(),0,'track removal updates the resource surface');
      }
      if (fixture === 'incomplete') {
        assert.match(await page.locator('#status').innerText(), /^Incomplete capture/);
        assert.equal(await page.locator('.row').count(),4);
        assert.equal(await page.locator('.edge,.resource-track,.sample').count(),0,'absent evidence produces no causal or resource marks');
        assert.match(await page.locator('#resources').innerText(), /not collected.*unavailable/);
        for (const label of ['A-build · cached','B-check · cancelled','C-check · blocked','D-package · deselected']) {
          assert.ok(await page.getByRole('button',{name:label,exact:true}).isVisible(),label);
        }
        const decisionWidths = await page.locator('.row .decision').evaluateAll(es=>es.map(e=>e.getBoundingClientRect().width));
        assert.equal(decisionWidths.length,3,'cache, blocked and deselected are decision markers');
        assert.ok(decisionWidths.every(width=>Math.abs(width-2)<.01),'decisions have no invented duration');
        const paintedDecisions = await page.locator('.row .decision').evaluateAll(es=>es.map(element=>{
          const b=element.getBoundingClientRect(),track=element.parentElement.getBoundingClientRect();
          return Math.max(0,Math.min(b.right,track.right)-Math.max(b.left,track.left));
        }));
        assert.ok(paintedDecisions.every(width=>width>=1),'decisions at window boundaries remain visibly inside their clipped tracks');
        await activate(page,page.getByRole('button',{name:'A-build · cached',exact:true}),touch);
        assert.match(await page.getByRole('dialog').innerText(),/Decision only/);
        assert.match(await page.getByRole('dialog').innerText(),/No recorded dependency edges/);
        await capture(page,name,'decision-detail');
        await closeDialog(page,touch);
        await activate(page,page.locator('#breadcrumbs').getByRole('button',{name:'root',exact:true}),touch);
        assert.match(await page.getByRole('dialog').innerText(),/Unfinished · final duration unknown/);
        await geometry(page);
        await capture(page,name,'unfinished-detail');
        await closeDialog(page,touch);
      }
      if (fixture === 'leaf') {
        assert.equal(await page.locator('.row').count(),1,'a sole root leaf is visible in the initial overview');
        const row = page.locator('.row');
        const identity = await row.getAttribute('data-node');
        assert.ok(identity,'leaf retains its graph identity');
        await activate(page,row.locator('button').first(),touch);
        assert.ok((await page.getByRole('dialog').innerText()).includes(identity),'sole leaf details remain reachable');
        await closeDialog(page,touch);
        await page.getByRole('button',{name:'Run overview',exact:true}).click();
        assert.equal(await page.locator('.row').count(),1,'overview reset preserves the sole leaf');
        assert.equal(await page.locator('.row').getAttribute('data-node'),identity);
      }
      if (fixture === 'open-signals') {
        assert.match(await page.locator('#status').innerText(),/^Incomplete capture/);
        assert.match(await page.locator('#status').innerText(),/260 ms/,'late unowned telemetry extends the observed capture extent');
        assert.ok(await page.locator('.resource-row').count()>0);
        const ends = await page.locator('.ruler').evaluateAll(es=>es.map(e=>e.lastElementChild.textContent));
        assert.ok(ends.length>=2 && ends.every(end=>end==='260 ms'),'task/resource axes include the final telemetry interval');
        const cancelledWidth = await page.locator('.row[data-node="B-check"] .bar').evaluate(e=>parseFloat(e.style.width));
        assert.ok(Math.abs(cancelledWidth-60/260*100)<.001,'cancelled duration is scaled against telemetry-extended extent');
        await activate(page,page.locator('#breadcrumbs').getByRole('button',{name:'root',exact:true}),touch);
        assert.match(await page.getByRole('dialog').innerText(),/Unfinished · final duration unknown/,'late telemetry does not invent root completion');
        await closeDialog(page,touch);
      }
      await geometry(page);
      assert.deepEqual(errors,[]);assert.deepEqual(requests,[]);
      records.push({fixture,device:deviceName,elapsedMs,errors,externalRequests:requests.length,darkContrast,lightContrast,passed:true});
      await context.close();
    }
  }
  completed = true;
} catch (error) {
  failure = { ...activeCase, message: error.message, errors: activeErrors, externalRequests: activeRequests };
  throw error;
} finally {
  await writeFile(resolve(output,'manifest.json'),JSON.stringify({engine,browser:browser.version(),playwright:playwrightVersion,
    completed,failure,physicalDevice:false,fixedViewports,skipped,emulatedOffline,externalRequestPolicy:'abort and fail on every non-file request',
    visualInspection:'pending; captures are not reviews',artifacts,records},null,2));
  await browser.close();
}
