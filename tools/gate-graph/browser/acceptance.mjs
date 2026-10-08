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
let browser;
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
// What a VISION reviewer must confirm in each captured state. The harness asserts
// behaviour; these are the things only looking can judge. Every sidecar also gets
// VLM_GENERIC. A state without an entry is an ERROR, never an unguided image.
const VLM_GENERIC = [
  'No text is clipped, overlapping another element, or cut off at the viewport edge.',
  'Every label is legible against its background in this theme; data bars, markers and the cursor are distinguishable from their track.',
  'Nothing claims more than the evidence: no invented durations, dependencies, causes or zero-filled telemetry.',
];
const VLM_EXPECT = {
  overview: ['The status line (capture completeness, elapsed time, task/measurement counts) is the first readable fact after the title.',
    'The "Where to investigate" panel and its first action are visible above the timeline and read as the primary next step.',
    'IF the timeline is in view (dom.marksInViewport > 0): bars are proportional to elapsed time and sit under one shared ruler. A first screen that ends above the timeline is a framing fact, not a finding; the geometry is asserted mechanically.'],
  detail: ['A modal details dialog for the selected task shows its name, outcome and elapsed time at the top.',
    'Typed dependency edges are listed as actionable buttons; raw evidence stays behind collapsed disclosures.'],
  'canonical-task': ['The canonical task EDN is expanded and readable, wrapping rather than overflowing the dialog.'],
  'rotated-detail': ['After rotation the details dialog still fits the viewport and keeps its close action reachable.'],
  'rotated-search': ['After rotation the active search keeps its query; IF the matching row is in view it is the only lane. A frame ending above the lane is not a finding — the single-row result is asserted mechanically.'],
  light: ['The light theme keeps every element legible; IF bars and edges are in view (dom.marksInViewport > 0) they remain distinguishable from their tracks.'],
  refused: ['The page states plainly that the report was refused, and shows no timeline, controls or fabricated data.'],
  attention: ['Only the task that needs attention is listed, and its failed outcome is unmistakable.'],
  folds: ['Dense children are folded into grouped rows; the group containing a failure is visually distinct.',
    'Each group shows envelope, occupied and summed durations as separate quantities.'],
  companion: ['The opened group shows its members as individual lanes on the shared time axis.'],
  'candidate-list': ['Activity-change candidates are listed with kind, quantity, resource and exact time window.',
    'The panel states that candidates are not bottleneck verdicts or speedup estimates.'],
  'candidate-detail': ['The candidate dialog shows its exact window, supporting measurement IDs and a concrete next experiment.'],
  'candidate-tasks': ['Tasks coincident with the candidate window are listed as actionable buttons within the viewport.'],
  'window-resources': ['Task lanes and resource tracks share one time window and one cursor position.'],
  gpu: ['The GPU utilisation track is visible with its scale caption and its uncertain/unavailable count.'],
  'resource-evidence': ['The track-evidence dialog identifies the resource, quantity and sampling method.'],
  'resource-evidence-expanded': ['The exact core query and its evidence rows are expanded and readable.'],
  'light-resources': ['In the light theme every resource track, scale caption and sample mark is legible.'],
  'decision-detail': ['A decision-only task is shown as a decision, with no invented duration, and says it has no recorded dependency edges.'],
  'unfinished-detail': ['An unfinished task says its final duration is unknown rather than showing a completed duration.'],
};
const vlmIndex = [];
async function domSummary(page) {
  return page.evaluate(() => {
    const text = e => e?.textContent.replace(/\s+/g, ' ').trim() ?? null;
    const visible = e => e.checkVisibility();
    const dialog = document.querySelector('dialog[open]');
    return {
      status: text(document.getElementById('status')),
      breadcrumbs: [...document.querySelectorAll('#breadcrumbs button,#breadcrumbs .crumb')].map(text),
      investigate: [...document.querySelectorAll('#opportunities button')].filter(visible).map(text).slice(0, 12),
      rows: [...document.querySelectorAll('#timeline .row')].slice(0, 24).map(row => ({
        node: row.dataset.node ?? null, members: row.dataset.members ? row.dataset.members.split(' ').length : null,
        label: text(row.querySelector('button')),
        marks: [...row.querySelectorAll('.bar,.decision')].slice(0, 8).map(m => ({
          class: m.className, left: m.style.left || null, width: m.style.width || null })),
      })),
      resources: [...document.querySelectorAll('.resource-row')].map(r => text(r.querySelector('.track-label') ?? r).slice(0, 120)),
      dialog: dialog ? { heading: text(dialog.querySelector('h2')), text: text(dialog).slice(0, 2000) } : null,
      focused: (() => { const e = document.activeElement;
        if (!e || e === document.body) return null;
        return { tag: e.tagName.toLowerCase(), id: e.id || null, role: e.getAttribute('role'),
          text: text(e)?.slice(0, 80) ?? null, focusVisible: e.matches(':focus-visible') }; })(),
      selectedEdges: [...document.querySelectorAll('.edge.selected-edge')].map(e => e.dataset.edge),
      scroll: { x: scrollX, y: scrollY },
      viewport: { width: innerWidth, height: innerHeight },
      cursor: getComputedStyle(document.querySelector('.track') ?? document.body).getPropertyValue('--cursor').trim() || null,
      marksInViewport: [...document.querySelectorAll('.bar,.decision,.sample')].filter(m => {
        const b = m.getBoundingClientRect(); return b.bottom > 0 && b.top < innerHeight && b.right > 0 && b.left < innerWidth; }).length,
    };
  });
}
async function capture(page, name, state, fullPage = false) {
  // Park the pointer so a stale hover border never reads as a second selection.
  await page.mouse.move(0, 0);
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
  const expect = VLM_EXPECT[state];
  if (!expect) throw new Error(`no VLM expectations declared for captured state "${state}"`);
  // A tall full-page capture is also cut into overlapping viewport-width tiles of at most 1600 CSS px,
  // so a reviewer never has to judge text that a downscaled giant image has made illegible.
  const tiles = [];
  if (fullPage && metadata.document.height > 1600) {
    // 200 px overlap: nothing is cut on a seam, and no trailing tile is only that overlap.
    for (let y = 0, i = 0; y === 0 || y + 200 < metadata.document.height; y += 1400, i++) {
      const tile = `${file.replace(/\.png$/, '')}.tile-${i}.png`;
      await page.screenshot({ path: resolve(output, tile), fullPage: true, scale: 'css',
        clip: { x: 0, y, width: metadata.document.width, height: Math.min(1600, metadata.document.height - y) } });
      tiles.push(tile);
    }
  }
  const sidecar = `${file}.json`;
  await writeFile(resolve(output, sidecar), JSON.stringify({
    image: file, tiles, engine, ...activeCase, state, viewport: page.viewportSize(), fullPage,
    theme: metadata.theme, fragment: metadata.fragment, expect: [...expect, ...VLM_GENERIC],
    dom: await domSummary(page),
  }, null, 2));
  vlmIndex.push({ image: file, sidecar, state, ...activeCase });
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
async function focusReturnsTo(page, node) {
  await page.waitForFunction(id => document.activeElement?.closest('[data-node]')?.dataset.node === id, node, { timeout: 2000 })
    .catch(() => {});
  const actual = await page.evaluate(() => document.activeElement?.closest('[data-node]')?.dataset.node ?? document.activeElement?.id ?? null);
  assert.equal(actual, node, `closing details returns focus to the task that opened them (expected ${node}, focus is on ${actual})`);
}
async function focusIsOn(page, text, message) {
  const actual = await page.evaluate(() => document.activeElement?.textContent.trim() ?? null);
  assert.equal(actual, text, message);
}
async function dialogHeading(page) {
  return page.getByRole('dialog').locator('#details h2').first().innerText();
}
async function waitReady(page, refused = false) {
  await page.waitForFunction(() => !document.querySelector('#status').textContent.startsWith('Loading'));
  const status = await page.locator('#status').innerText();
  assert.equal(status.includes('refused'), refused, status);
}
// Shared WCAG relative-luminance arithmetic, injected into the page as source.
const CONTRAST_JS = `
  const color = text => text.match(/[\\d.]+/g)?.map(Number);
  const luminance = rgb => rgb.slice(0, 3).map(v => v / 255).map(v => v <= .04045 ? v / 12.92 : ((v + .055) / 1.055) ** 2.4)
    .reduce((sum, v, i) => sum + v * [.2126, .7152, .0722][i], 0);
  const ratio = (a, b) => { const x = luminance(a), y = luminance(b); return (Math.max(x, y) + .05) / (Math.min(x, y) + .05); };
  const opaqueBackground = start => {
    for (let node = start; node; node = node.parentElement) {
      const candidate = color(getComputedStyle(node).backgroundColor);
      if (candidate && (candidate.length < 4 || candidate[3] === 1)) return candidate;
    }
  };`;
// TEXT: every visible rendered text element, the raw-evidence <pre> blocks and an
// empty input's placeholder, each >= 4.5:1. Returns {checked, min}; `checked` is a
// COUNT of elements, `min` the lowest ratio measured — never confuse the two.
async function themeContrast(page, scope = 'document') {
  const result = await page.evaluate(({ js, scope }) => new Function('scope', js + `
    const root = scope === 'dialog' ? document.querySelector('dialog[open]') : document;
    const failures = []; let checked = 0, min = Infinity;
    const judge = (element, fg, label) => {
      const background = opaqueBackground(element);
      if (!fg || !background) throw new Error('Unmeasured text color: ' + label);
      const r = ratio(fg, background); checked++; min = Math.min(min, r);
      if (r < 4.5) failures.push({ text: label.slice(0, 80), ratio: +r.toFixed(2) });
    };
    for (const element of root.querySelectorAll('button,p,h1,h2,h3,label,summary,pre,.ruler span,.crumb')) {
      if (!element.checkVisibility() || !element.textContent.trim()) continue;
      judge(element, color(getComputedStyle(element).color), element.textContent);
    }
    for (const input of root.querySelectorAll('input[placeholder]')) {
      if (!input.checkVisibility() || input.value) continue;
      judge(input, color(getComputedStyle(input, '::placeholder').color), 'placeholder: ' + input.placeholder);
    }
    return { checked, min: +min.toFixed(2), failures };`)(scope), { js: CONTRAST_JS, scope });
  assert.ok(result.checked > (scope === 'dialog' ? 3 : 10), 'contrast checks exercise rendered text');
  assert.deepEqual(result.failures, [], `normal text contrast >= 4.5:1; failing: ${JSON.stringify(result.failures).slice(0, 600)}`);
  return { checked: result.checked, min: result.min };
}
// NON-TEXT (WCAG 1.4.11): every solid data mark against its own track, the shared
// cursor against its track, and the focus ring against the page, each >= 3:1.
// Patterned fills (`.running`) have no single colour and are counted as skipped.
async function nonTextContrast(page) {
  const result = await page.evaluate(js => new Function(js + `
    const failures = []; let checked = 0, skipped = 0, min = Infinity;
    const judge = (label, fg, bg) => {
      const r = ratio(fg, bg); checked++; min = Math.min(min, r);
      if (r < 3) failures.push({ label, ratio: +r.toFixed(2) });
    };
    for (const mark of document.querySelectorAll('.bar,.decision,.sample')) {
      if (!mark.checkVisibility()) continue;
      const fill = color(getComputedStyle(mark).backgroundColor);
      if (!fill || (fill.length > 3 && fill[3] !== 1)) { skipped++; continue; }
      judge('mark ' + mark.className, fill, opaqueBackground(mark.parentElement));
    }
    const page = opaqueBackground(document.body);
    for (const track of document.querySelectorAll('.track,.resource-track')) {
      if (!track.checkVisibility()) continue;
      const after = getComputedStyle(track, '::after');
      const cursor = color(after.borderLeftColor);
      judge('cursor', cursor, opaqueBackground(track));
      const halo = color(after.boxShadow.match(/rgba?\\([^)]*\\)/)?.[0] ?? '');
      if (!halo) failures.push({ label: 'cursor halo missing', ratio: 0 });
      else judge('cursor halo', cursor, halo);
      break;
    }
    // A cursor or an edge crossing a bar is separated from it by its page-colour
    // casing, so every solid mark must also stand off that casing colour.
    for (const mark of document.querySelectorAll('.bar,.decision,.sample')) {
      if (!mark.checkVisibility()) continue;
      const fill = color(getComputedStyle(mark).backgroundColor);
      if (fill && !(fill.length > 3 && fill[3] !== 1)) judge('mark vs casing', fill, page);
    }
    for (const edge of document.querySelectorAll('.edge')) {
      const st = getComputedStyle(edge), stroke = color(st.stroke), a = Number(st.opacity);
      const blended = stroke.slice(0, 3).map((v, i) => v * a + page[i] * (1 - a));
      const casing = document.querySelector('.edge-casing[data-casing-for="' + CSS.escape(edge.dataset.edge) + '"]');
      if (!casing) { failures.push({ label: 'edge without casing', ratio: 0 }); continue; }
      // Every casing sits in a layer BELOW every edge, so one edge's casing never notches another.
      if (casing.closest('g')?.nextElementSibling !== edge.closest('g')) failures.push({ label: 'casing not below the edge layer', ratio: 0 });
      judge('edge vs casing', blended, color(getComputedStyle(casing).stroke));
    }
    const focus = getComputedStyle(document.documentElement).getPropertyValue('--focus').trim();
    const probe = document.createElement('i'); probe.style.color = focus; document.body.append(probe);
    judge('focus ring', color(getComputedStyle(probe).color), opaqueBackground(document.body)); probe.remove();
    return { checked, skipped, min: +min.toFixed(2), failures };`)(), CONTRAST_JS);
  assert.ok(result.checked >= 2, 'non-text contrast judged at least the cursor and the focus ring');
  assert.deepEqual(result.failures, [], `non-text contrast (marks, cursor, focus ring) >= 3:1; failing: ${JSON.stringify(result.failures).slice(0, 600)}`);
  return { checked: result.checked, skipped: result.skipped, min: result.min };
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
async function rulerAligned(page) {
  const result = await page.evaluate(() => {
    const track = [...document.querySelectorAll('#timeline .track')].find(t => t.checkVisibility());
    const ruler = document.querySelector('#timeline .ruler');
    if (!track || !ruler) return null;
    const t = track.getBoundingClientRect();
    return [...ruler.children].map((tick, i, all) => {
      const b = tick.getBoundingClientRect(), x = t.left + t.width * i / (all.length - 1);
      const anchor = i === 0 ? b.left : i === all.length - 1 ? b.right : (b.left + b.right) / 2;
      return { label: tick.textContent, off: +(anchor - x).toFixed(2) };
    });
  });
  if (result === null) return;
  assert.ok(result.length >= 3, 'the ruler has interior ticks');
  assert.ok(result.every(r => Math.abs(r.off) <= 2), 'each ruler label sits at the time it names: ' + JSON.stringify(result));
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
  browser = await ({ chromium, webkit }[engine]).launch({ headless: true });
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
      const options = { ...descriptor };
      delete options.defaultBrowserType;
      const context = await browser.newContext({ ...options, offline: emulatedOffline });
      await context.route('**/*', route => route.request().url().startsWith('file:') ? route.continue() : route.abort());
      context.setDefaultTimeout(10000);
      await context.addInitScript(() => document.addEventListener('DOMContentLoaded', () => {
        const style = document.createElement('style');
        style.textContent = '*{-webkit-tap-highlight-color:transparent!important}';
        document.head.append(style);
      }));
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
        const deadControls = await page.evaluate(() => [
          ...['search', 'attention-controls'].map(id => [id, document.getElementById(id)]),
          ['Timeline controls', [...document.querySelectorAll('summary')].find(s => s.textContent.trim() === 'Timeline controls')]]
          .filter(([, element]) => element?.checkVisibility()).map(([name]) => name));
        assert.deepEqual(deadControls, [], `a refused report shows no search box or timeline controls, which could only do nothing (visible: ${deadControls})`);
        await capture(page,name,'refused');
        assert.deepEqual(errors,[]); assert.deepEqual(requests,[]);
        records.push({fixture,device:deviceName,elapsedMs,errors,externalRequests:requests.length,passed:true});
        await context.close();
        continue;
      }
      assert.ok(await page.locator('#search').isVisible()
        && await page.locator('summary').filter({hasText:/^Timeline controls$/}).isVisible(),
        'an admitted report reveals its search box and timeline controls (they start hidden until admission)');
      await geometry(page);
      await rulerAligned(page);
      if (fixture === 'branch' && ['iphone-portrait','portrait-full'].includes(deviceName)) {
        records.push({fixture,device:deviceName,firstScreenTimeline:await firstScreenTimeline(page)});
      }
      if (!['leaf','open-signals'].includes(fixture) || deviceName === 'iphone-portrait') {
        await capture(page,name,'overview');
      }
      await openTimelineControls(page);
      const textContrast = { dark: await themeContrast(page) };
      const markContrast = { dark: await nonTextContrast(page) };
      await timelineControl(page,'Light / dark');
      textContrast.light = await themeContrast(page);
      markContrast.light = await nonTextContrast(page);
      await timelineControl(page,'Light / dark');
      if(fixture==='branch') {
        assert.equal(await page.locator('#opportunities button').first().innerText(),'Longest gate: C-check · 140 ms · inspect',
          'the investigation call to action names the planted longest gate and its exact duration');
        assert.equal(await page.locator('.row').count(),4);
        assert.equal(await page.locator('.edge').count(),4);
        const bars=await page.locator('.row .bar').evaluateAll(es=>es.map(e=>parseFloat(e.style.width)));
        assert.ok(Math.abs(bars[0]-100/260*100)<0.001);
        const initialEdges = await page.locator('.edge').evaluateAll(es => es.map(e => [e.dataset.edge,e.dataset.from,e.dataset.to,e.getAttribute('d')]).sort((a,b)=>a[0].localeCompare(b[0])));
        assert.equal(new Set(initialEdges.map(e => e[0])).size, 4, 'four distinct dependency identities');
        await activate(page, page.getByRole('button',{name:'B-check · passed',exact:true}), touch);
        assert.ok(await page.getByRole('dialog').isVisible());
        assert.match(await page.getByRole('dialog').innerText(), /B-check/);
        assert.equal(await page.getByRole('dialog').getByRole('button',{name:'Open children',exact:true}).count(),0,
          'a task without children offers no Open children');
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
        await focusReturnsTo(page, 'B-check');
        // A dialog's `close` event is QUEUED. Close and re-open in ONE task, so the stale event
        // lands after the new open: it must not consume the new opener or pull focus out.
        await activate(page, page.getByRole('button',{name:'B-check · passed',exact:true}), touch);
        await page.evaluate(() => { document.getElementById('close-details').click();
          [...document.querySelectorAll('button')].find(b => b.textContent.trim() === 'B-check · passed').click(); });
        await page.getByRole('dialog').getByRole('button',{name:'B-check → D-package · requires · observed',exact:true}).click();
        await closeDialog(page, touch);
        await page.waitForFunction(() => document.activeElement?.closest('[data-node]')?.dataset.node === 'B-check', null, { timeout: 2000 }).catch(() => {});
        const afterStaleClose = await page.evaluate(() => document.activeElement?.closest('[data-node]')?.dataset.node ?? document.activeElement?.id ?? null);
        assert.equal(afterStaleClose, 'B-check', `a stale close event from before a re-open must not take the new opener (focus is on ${afterStaleClose})`);
        // Safari does not focus the button it activates, so close() hands focus back to whatever held
        // it before the dialog opened — here the search box. Close must still return to the opener.
        await page.getByRole('searchbox').focus();
        await page.evaluate(() => [...document.querySelectorAll('button')].find(b => b.textContent.trim() === 'B-check · passed').click());
        await page.keyboard.press('Escape');
        const afterHeldFocus = await page.evaluate(() => document.activeElement?.closest('[data-node]')?.dataset.node ?? document.activeElement?.id ?? null);
        assert.equal(afterHeldFocus, 'B-check', `closing returns to the opener even when another control held focus before it opened (focus is on ${afterHeldFocus})`);
        await activate(page, page.getByRole('button',{name:'B-check · passed',exact:true}), touch);
        if (deviceName === 'desktop') {
          const hit = await page.evaluate(() => { const b = document.getElementById('search').getBoundingClientRect();
            return !!document.elementFromPoint(b.left + b.width / 2, b.top + b.height / 2)?.closest('dialog'); });
          assert.ok(hit, 'an open details dialog is modal: the controls behind it are not hit-testable');
        }
        await page.getByRole('dialog').getByRole('button',{name:'B-check → D-package · requires · observed',exact:true}).click();
        assert.equal(await dialogHeading(page),'D-package','a dependency edge in the details opens its other endpoint');
        await closeDialog(page, touch);
        await focusReturnsTo(page, 'B-check');  // the control that OPENED the dialog, not the endpoint browsed to
        await activate(page, page.getByRole('button',{name:'Longest gate: C-check · 140 ms · inspect',exact:true}), touch);
        assert.equal(await dialogHeading(page),'C-check','the investigation call to action opens the gate it names');
        assert.match(await page.getByRole('dialog').innerText(),/140 ms elapsed/);
        await closeDialog(page, touch);
        await focusIsOn(page,'Longest gate: C-check · 140 ms · inspect','closing returns focus to the call to action that opened the dialog, though it was re-rendered');
        await timelineControl(page,'Zoom in');
        await checkWindow(page, [.25,.75]);
        await timelineControl(page,'Pan →');
        await checkWindow(page, [.5,1]);
        await timelineControl(page,'← Pan');
        await checkWindow(page, [.25,.75]);
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
          await page.goto(page.url().split('#')[0] + '#');
          await page.reload(); await waitReady(page);
          await page.evaluate(() => document.activeElement?.blur());
          await page.keyboard.press('Tab');
          assert.equal(await page.evaluate(() => document.activeElement?.textContent.trim()),'Longest gate: C-check · 140 ms · inspect',
            'the investigation action is the first keyboard stop');
          await page.keyboard.press('Enter');
          assert.equal(await dialogHeading(page),'C-check','keyboard Enter opens the named gate');
          await page.keyboard.press('Escape');
          await focusIsOn(page,'Longest gate: C-check · 140 ms · inspect','Escape returns keyboard focus to the call to action');
        }
      }
      if(fixture==='dense') {
        const cta = page.locator('#opportunities button').first();
        assert.match(await cta.innerText(),/^1\s+task needs attention · show$/,'failures lead the investigate panel');
        await cta.click();
        assert.equal(await page.locator('.row').count(),1,'the attention action shows exactly the failing task');
        assert.equal(await page.evaluate(() => document.activeElement?.id),'timeline-heading','the attention action moves focus to the filtered timeline, not <body>');
        assert.equal(await page.locator('#attention-toggle').getAttribute('aria-pressed'),'true');
        await page.getByRole('button',{name:'Needs attention',exact:true}).click();
        assert.equal(await page.locator('.row').count(),4);
        await page.getByRole('button',{name:'Needs attention',exact:true}).click();
        assert.equal(await page.locator('.row').count(),1,'whole-run attention finds a failure below the current hierarchy');
        assert.match(await page.locator('.row').innerText(), /child-1500.*failed/);
        await page.locator('.row').scrollIntoViewIfNeeded();
        await capture(page,name,'attention');
        await page.reload(); await waitReady(page);
        assert.equal(await page.locator('.row').count(),1,'the attention filter survives reload from the fragment');
        assert.equal(await page.locator('#attention-toggle').getAttribute('aria-pressed'),'true','the attention toggle reports its restored state');
        await page.getByRole('button',{name:'Needs attention',exact:true}).click();
        await page.getByRole('button',{name:'Open 3000 children',exact:true}).click();
        assert.ok(await page.locator('.fold-row').count()<=24);
        assert.ok(await page.locator('.fold-row').count()>0,'dense children are actually folded');
        const foldMembers = await page.locator('.fold-row').evaluateAll(es=>es.map(e=>e.dataset.members));
        const foldedEdges = await page.locator('.edge').evaluateAll(es=>es.map(e=>[e.dataset.from,e.dataset.to]));
        const memberIds = new Set(foldMembers.flatMap(members=>members.split(' ')));
        assert.ok(foldedEdges.length>0 && foldedEdges.length<=120,'dense cross-fold dependencies render within the edge budget');
        assert.ok(foldedEdges.every(edge=>edge.every(id=>memberIds.has(id))),'folded edge endpoints retain visible original member identities');
        const failedFold = page.locator('.fold-row').filter({hasText:/1\s+needs attention/});
        assert.equal(await failedFold.count(),1,'failure remains visible in a folded summary');
        await page.locator('#timeline').evaluate(element=>element.scrollIntoView({block:'start'}));
        await capture(page,name,'folds');
        const failedMembers = (await failedFold.getAttribute('data-members')).split(' ');
        assert.ok(failedMembers.includes('child-1500'),'the failing child sits in the fold that reports it');
        await failedFold.getByRole('button',{name:'Group evidence',exact:true}).click();
        assert.equal(await dialogHeading(page),'Exact group membership and boundary edges');
        await page.getByRole('dialog').locator('details').evaluateAll(ds => ds.forEach(d => { d.open = true; }));
        assert.ok((await page.getByRole('dialog').innerText()).includes('"child-1500"'),'group evidence names the failing member exactly');
        await closeDialog(page, touch);
        assert.equal(await page.evaluate(() => document.activeElement?.textContent.trim()),'Group evidence',
          'closing group evidence returns focus to the control that opened it');
        // The dialog's `close` event is ASYNC: it must never take focus back from a
        // control the user already moved to. Close and move focus in ONE task, so the
        // handler is guaranteed to run afterwards, then require focus to stay put.
        await failedFold.getByRole('button',{name:'Group evidence',exact:true}).click();
        await page.evaluate(() => { document.getElementById('detail-dialog').close();
          document.querySelector('#opportunities button').focus(); });
        await settle(page);
        assert.equal(await page.evaluate(() => document.activeElement?.closest('#opportunities') !== null),true,
          'a late dialog close never steals focus from where the user moved it');
        await activate(page, failedFold.locator('button').first(), touch);
        assert.ok(await page.locator('.row').count()<=24);
        assert.ok(new URLSearchParams(new URL(page.url()).hash.slice(1)).get('fold'),'group identity is linkable');
        await page.locator('#timeline').evaluate(element=>scrollTo(0,element.getBoundingClientRect().top+scrollY));
        await capture(page,name,'companion');
        const starts = await page.locator('#timeline .row .bar').evaluateAll(es => es.map(e => parseFloat(e.style.left)));
        assert.ok(starts.length > 1 && starts.every((v, i) => i === 0 || v >= starts[i - 1]),'lanes are in chronological order');
        await page.getByRole('button',{name:'← Back',exact:true}).click();
        assert.deepEqual(await page.locator('.fold-row').evaluateAll(es=>es.map(e=>e.dataset.members)),foldMembers,'back restores exact folded membership');
        await page.getByRole('searchbox').fill('failed');
        assert.equal(await page.locator('.row').count(),1);
        assert.equal(await page.evaluate(()=>window.injected), undefined);
        await page.getByRole('searchbox').fill('Literal');
        assert.equal(await page.locator('.row').count(),1);
        assert.match(await page.locator('.row').innerText(), /<\/script><script>window.injected=true<\/script>/,'hostile label remains literal text');
        await page.getByRole('searchbox').fill('');
        await page.getByRole('button',{name:'Run overview',exact:true}).click();
        await activate(page, page.getByRole('button',{name:'B-check · passed',exact:true}), touch);
        await page.getByRole('dialog').getByRole('button',{name:'Open children',exact:true}).click();
        assert.ok(await page.locator('.fold-row').count()>0,'Open children in the details opens the folded child level');
        assert.equal(await page.evaluate(() => document.activeElement?.id),'timeline-heading','navigation moves focus to the new level, not <body>');
        await page.locator('#breadcrumbs').getByRole('button',{name:'↑ Parent',exact:true}).click();
        assert.equal(await page.locator('.row').count(),4,'↑ Parent returns to the level above');
      }
      if(fixture==='signals') {
        await page.locator('#opportunities summary').click();
        await page.locator('#opportunities').evaluate(element=>scrollTo(0,element.getBoundingClientRect().top+scrollY));
        assert.match(await page.locator('#opportunities').innerText(),/Not a bottleneck verdict or speedup estimate/);
        await capture(page,name,'candidate-list');
        assert.equal(await page.locator('#opportunities').getByRole('button',{name:'activity-burst · cpu-user-ns · host · 180 ms–192 ms',exact:true}).count(),1,
          'the planted CPU burst is surfaced as a candidate');
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
        const candidateFocus = await page.evaluate(() => { const a = document.activeElement;
          return { tag: a?.tagName ?? 'none', text: (a?.textContent ?? '').trim().slice(0, 60), visible: !!a?.checkVisibility?.(),
            panel: !!a?.closest('#opportunities') }; });
        assert.ok(candidateFocus.tag === 'SUMMARY' && candidateFocus.visible && candidateFocus.panel,
          `closing a candidate whose opener was re-rendered into a closed panel focuses that panel's summary, not the page body or a fallback (focus is on ${JSON.stringify(candidateFocus)})`);
        const exactWindow = new URLSearchParams(new URL(page.url()).hash.slice(1)).get('exact');
        await page.reload(); await waitReady(page);
        assert.equal(new URLSearchParams(new URL(page.url()).hash.slice(1)).get('exact'),exactWindow,'the exact evidence window survives reload');
        const reloadedBounds = await page.locator('.ruler').evaluateAll(es=>es.map(e=>[e.firstElementChild.textContent,e.lastElementChild.textContent]));
        assert.ok(reloadedBounds.every(([start,end])=>start==='100 ms' && end==='112 ms'),'restored rulers show the exact window');
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
        assert.match(await gpu.innerText(),/1 uncertain\/unavailable/,'the planted unavailable GPU sample is counted, not zero-filled');
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
        assert.match(await page.evaluate(() => document.activeElement?.closest('.resource-row')?.querySelector('.track-label')?.textContent ?? ''),
          /^GPU 0 · gpu-utilization-percent/, 'closing returns focus to the track that opened the dialog, not the first identical button');
        // Safari and iOS do not focus a button on click, so the dialog opens with focus on the body and
        // close() has nothing to return to. Reproduce that exactly: every track's button reads the same.
        await gpu.getByRole('button',{name:'Inspect track evidence',exact:true}).evaluate(button => { document.activeElement?.blur(); button.click(); });
        assert.ok(await page.getByRole('dialog').isVisible());
        await page.keyboard.press('Escape');
        const safariFocus = await page.evaluate(() => { const a = document.activeElement;
          return { track: a?.closest('.resource-row')?.querySelector('.track-label')?.textContent ?? '',
            element: `${a?.tagName ?? 'none'} ${(a?.textContent ?? '').trim().slice(0, 60)}`, open: document.getElementById('detail-dialog').open }; });
        assert.match(safariFocus.track, /^GPU 0 · gpu-utilization-percent/,
          `with no focus on click (Safari), closing still returns to the track that opened the dialog; focus is on ${JSON.stringify(safariFocus)}`);
        // A close that skips `cancel` — close() called directly — is left to the queued `close` fallback.
        await gpu.getByRole('button',{name:'Inspect track evidence',exact:true}).evaluate(button => { document.activeElement?.blur(); button.click(); });
        await page.evaluate(() => document.getElementById('detail-dialog').close());
        await page.waitForFunction(() => !!document.activeElement?.closest('.resource-row'), null, { timeout: 2000 }).catch(() => {});
        const fallbackFocus = await page.evaluate(() => document.activeElement?.closest('.resource-row')?.querySelector('.track-label')?.textContent ?? `${document.activeElement?.tagName}`);
        assert.match(fallbackFocus, /^GPU 0 · gpu-utilization-percent/,
          `a close that skipped cancel still returns to the opener through the close fallback (focus is on ${fallbackFocus})`);
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
      records.push({fixture,device:deviceName,elapsedMs,errors,externalRequests:requests.length,textContrast,markContrast,passed:true});
      await context.close();
    }
  }
  completed = true;
} catch (error) {
  // FAIL (exit 1) is a verdict: an assertion about the viewer did not hold.
  // ERROR (exit 2) is anything else — a launch failure, a missing fixture, a
  // timeout — and is NOT a verdict either way. A locator timeout can be the
  // viewer's fault (a control removed or renamed), so read the message before
  // blaming the harness; never report an ERROR as a pass. Never merge the two.
  const kind = error instanceof assert.AssertionError ? 'FAIL' : 'ERROR';
  failure = { kind, ...activeCase, message: error.message, errors: activeErrors, externalRequests: activeRequests };
  console.error(`[acceptance] ${kind} ${JSON.stringify(activeCase ?? {})}: ${error.stack ?? error.message}`);
  process.exitCode = kind === 'FAIL' ? 1 : 2;
} finally {
  await writeFile(resolve(output,'vlm-index.json'),JSON.stringify(vlmIndex,null,2));
  await writeFile(resolve(output,'manifest.json'),JSON.stringify({engine,browser:browser?.version() ?? null,playwright:playwrightVersion,
    completed,failure,physicalDevice:false,fixedViewports,skipped,emulatedOffline,externalRequestPolicy:'abort and fail on every non-file request',
    visualInspection:'pending; captures are not reviews',artifacts,records},null,2));
  await browser?.close();
}
