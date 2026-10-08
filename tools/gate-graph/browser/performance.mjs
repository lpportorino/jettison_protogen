import assert from 'node:assert/strict';
import { chromium } from 'playwright';
import { mkdir, copyFile, readFile, writeFile } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { createRequire } from 'node:module';
import { resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import os from 'node:os';

const [fixtures, output, repeatsText = '3'] = process.argv.slice(2);
const repeats = Number(repeatsText);
assert.ok(fixtures && output && Number.isInteger(repeats) && repeats >= 1 && repeats <= 10,
  'usage: node performance.mjs FIXTURE_ROOT NEW_OUTPUT_ROOT [REPEATS_1_TO_10]');
await mkdir(output);
const sha256 = bytes => createHash('sha256').update(bytes).digest('hex');
const workloads = { dense: { tasks: 3005, edges: 2964, measurements: 0, resources: 2 },
  signals: { tasks: 5, edges: 4, measurements: 2470, resources: 36, logicalCores: 32, measurementSeries: 38 } };
const browser = await chromium.launch({ headless: true });
const result = {
  completed: false, recordedAt: new Date().toISOString(), repeats,
  environment: { browser: browser.version(), playwright: createRequire(import.meta.url)('playwright/package.json').version,
    node: process.version, platform: os.platform(), release: os.release(), architecture: os.arch(),
    cpuModel: os.cpus()[0]?.model, visibleLogicalCPUs: os.cpus().length, visibleMemoryBytes: os.totalmem(),
    containerImage: process.env.GATE_BENCH_CONTAINER_IMAGE || null, viewport: { width: 1440, height: 900 } },
  method: { load: 'Browser navigation time origin to admitted nonempty timeline plus two requestAnimationFrame callbacks.',
    interactions: 'Native DOM click/change dispatch through synchronous handlers plus two requestAnimationFrame callbacks; excludes Playwright actionability delays.',
    isolation: 'Fresh context per sample; shared browser process and host filesystem caches. Offline context; every non-file request aborted.',
    dom: 'Maximum sampled after load and each settled interaction, not a transient allocation or memory peak.',
    interpretation: 'Finite synthetic measurements, not an SLA, physical-device result, cold-cache claim or statistical performance guarantee.' },
  fixtures: {}, samples: [],
};
async function snapshot(page, state) {
  return page.evaluate(state => ({ state, rows: document.querySelectorAll('.row').length,
    tracks: document.querySelectorAll('.resource-row').length, elements: document.querySelectorAll('*').length }), state);
}
async function action(page, selector, text, resourceLabels) {
  return page.evaluate(async ({ selector, text, resourceLabels }) => {
    const start = performance.now();
    if (resourceLabels) {
      for (const label of resourceLabels) {
        const select = document.querySelector('select');
        const option = [...select.options].find(option => option.textContent === label);
        if (!option) throw Error(`Missing resource option: ${label}`);
        select.value = option.value;
        select.dispatchEvent(new Event('change', { bubbles: true }));
      }
    } else {
      const button = [...document.querySelectorAll(selector)].find(button => !text || button.textContent === text);
      if (!button) throw Error(`Missing action: ${selector} ${text}`);
      button.click();
    }
    await new Promise(requestAnimationFrame);
    await new Promise(requestAnimationFrame);
    return performance.now() - start;
  }, { selector, text, resourceLabels });
}
try {
  for (const [fixture, workload] of Object.entries(workloads)) {
    const isolated = resolve(output, fixture);
    await mkdir(isolated);
    await copyFile(resolve(fixtures, fixture, 'index.html'), resolve(isolated, 'index.html'));
    const html = await readFile(resolve(isolated, 'index.html'));
    const javascript = html.toString().match(/<script>([\s\S]*?)<\/script>/)?.[1];
    assert.ok(javascript, 'embedded executable bundle is present');
    result.fixtures[fixture] = { ...workload, htmlBytes: html.length, htmlSHA256: sha256(html),
      embeddedBundleBytes: Buffer.byteLength(javascript), embeddedBundleSHA256: sha256(javascript) };
    for (let repeat = 1; repeat <= repeats; repeat++) {
      const context = await browser.newContext({ viewport: result.environment.viewport, offline: true });
      context.setDefaultTimeout(15000);
      await context.route('**/*', route => route.request().url().startsWith('file:') ? route.continue() : route.abort());
      await context.addInitScript(() => {
        window.__gateBenchmark = {};
        const observer = new MutationObserver(() => {
          const status = document.querySelector('#status');
          if (!status || status.textContent.startsWith('Loading') || !document.querySelector('.row')) return;
          observer.disconnect();
          requestAnimationFrame(() => requestAnimationFrame(() => { window.__gateBenchmark.loadToReadyMs = performance.now(); }));
        });
        observer.observe(document, { childList: true, subtree: true, characterData: true });
      });
      const page = await context.newPage(), errors = [], requests = [];
      page.on('pageerror', error => errors.push(error.message));
      page.on('console', message => { if (message.type() === 'error') errors.push(message.text()); });
      page.on('request', request => { if (!request.url().startsWith('file:')) requests.push(request.url()); });
      await page.goto(pathToFileURL(resolve(isolated, 'index.html')).href);
      await page.waitForFunction(() => Number.isFinite(window.__gateBenchmark?.loadToReadyMs));
      assert.match(await page.locator('#status').innerText(), new RegExp(`${workload.tasks} tasks.*${workload.measurements} measurements`));
      const sample = { fixture, repeat, loadToReadyMs: await page.evaluate(() => window.__gateBenchmark.loadToReadyMs),
        interactionsMs: {}, states: [await snapshot(page, 'overview')] };
      if (fixture === 'dense') {
        sample.interactionsMs.openChildren = await action(page, 'button', 'Open 3000 children');
        assert.ok(await page.locator('.fold-row').count() > 0);
        sample.states.push(await snapshot(page, 'folds'));
        sample.interactionsMs.openFold = await action(page, '.fold-row .row-head button', '128 grouped tasks · 1 need attention');
        assert.equal(await page.locator('.fold-row').count(), 0);
        sample.states.push(await snapshot(page, 'companion'));
        sample.interactionsMs.openTaskDetail = await action(page, '.row .row-head button');
      } else {
        sample.interactionsMs.addFiveResourceTracks = await action(page, null, null,
          ['GPU 0 · gpu-utilization-percent', 'CPU core 31 · cpu-user-ns', 'Disk 0 · bytes-read', 'GPU 0 · memory-current-bytes', 'CPU core 0 · cpu-user-ns']);
        assert.equal(await page.locator('.resource-row').count(), 8);
        sample.states.push(await snapshot(page, 'eight-resource-tracks'));
        sample.interactionsMs.openResourceDetail = await action(page, '.resource-row button', 'Inspect track evidence');
      }
      assert.ok(await page.locator('dialog[open]').count() === 1);
      sample.states.push(await snapshot(page, 'detail'));
      sample.maxSampledRows = Math.max(...sample.states.map(state => state.rows));
      sample.maxSampledTracks = Math.max(...sample.states.map(state => state.tracks));
      sample.maxSampledElements = Math.max(...sample.states.map(state => state.elements));
      assert.ok(sample.maxSampledRows <= 24 && sample.maxSampledTracks <= 8);
      assert.deepEqual(errors, []); assert.deepEqual(requests, []);
      Object.assign(sample, { errors, externalRequests: requests.length });
      result.samples.push(sample);
      await context.close();
    }
  }
  result.completed = true;
} finally {
  await browser.close();
  await writeFile(resolve(output, 'performance.json'), JSON.stringify(result, null, 2) + '\n');
}
