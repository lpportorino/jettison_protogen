// Run in the pinned browser runtime; paths are supplied by the caller.
// No application server or network access is needed to open the report.
import assert from 'node:assert/strict';
import { pathToFileURL } from 'node:url';
import { readFile } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { dirname, join } from 'node:path';

const [modulePath, reportPath, screenshotPath] = process.argv.slice(2);
assert.ok(modulePath && reportPath && screenshotPath, 'expected PLAYWRIGHT-MODULE REPORT SCREENSHOT');
const { chromium } = await import(pathToFileURL(modulePath).href);
const browser = await chromium.launch({ headless: true });
const failures = [];
const requests = [];
try {
  const context = await browser.newContext({ offline: true, viewport: { width: 1440, height: 1000 } });
  const page = await context.newPage();
  page.on('pageerror', error => failures.push(error.message));
  page.on('console', message => { if (message.type() === 'error') failures.push(message.text()); });
  page.on('request', request => { if (!request.url().startsWith('file:')) requests.push(request.url()); });
  await page.goto(pathToFileURL(reportPath).href);
  await page.waitForFunction(() => !document.getElementById('status').textContent.startsWith('Loading'), { timeout: 15000 });
  assert.match(await page.locator('#status').innerText(), /^Complete capture · 3 tasks · 6 measurements$/);
  assert.equal(await page.locator('#timeline .row').count(), 1);
  await page.locator('#timeline .row').getByRole('button', { name: /Children/ }).click();
  assert.equal(await page.locator('#timeline .row').count(), 2);
  await page.locator('#timeline').getByRole('button', { name: 'graph-tests', exact: true }).click();
  const detail = await page.locator('#details').innerText();
  assert.ok(detail.includes(':cpu-user-ns') && detail.includes(':rss-peak-bytes'));
  assert.ok(detail.includes(':accounting :inclusive') && detail.includes(':status :measured'));
  assert.ok(detail.includes(':outcome :passed'));
  const canonicalEdn = (await readFile(join(dirname(reportPath), 'graph.edn'), 'utf8')).trimEnd();
  const digest = createHash('sha256').update(canonicalEdn).digest('hex');
  assert.ok(detail.includes(`:artifact "${digest}"`), 'CLJS canonical digest must equal the JVM artifact digest');
  const geometry = await page.locator('.bar').evaluateAll(bars => bars.map(bar => ({
    left: parseFloat(bar.style.left), width: parseFloat(bar.style.width), title: bar.title
  })));
  assert.equal(geometry.length, 2);
  for (const bar of geometry) {
    assert.ok(bar.left >= 0 && bar.width > 0 && bar.left + bar.width <= 100.000001);
    assert.ok(bar.title.includes('passed'));
  }
  assert.ok(Math.max(...geometry.map(bar => bar.left)) < Math.min(...geometry.map(bar => bar.left + bar.width)),
    'captured independent gates must visibly overlap');
  await page.screenshot({ path: screenshotPath, fullPage: true });
  await page.getByRole('button', { name: 'Run overview', exact: true }).click();
  assert.equal(await page.locator('#timeline .row').count(), 1);
  assert.deepEqual(failures, []);
  assert.deepEqual(requests, []);
  console.log(JSON.stringify({ status: 'passed', browser: browser.version(), offline: true,
    gates: 2, measurements: 6, externalRequests: requests.length, pageErrors: failures.length }));
} finally {
  await browser.close();
}
