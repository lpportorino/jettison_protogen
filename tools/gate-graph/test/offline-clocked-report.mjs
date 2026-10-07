// Exercise a real clocked module report with the matching viewer bundle.
import assert from 'node:assert/strict';
import { pathToFileURL } from 'node:url';
import { readFile } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { dirname, join } from 'node:path';

const [modulePath, reportPath, screenshotPath, testsText] = process.argv.slice(2);
assert.ok(modulePath && reportPath && screenshotPath && testsText);
const tests = Number(testsText);
assert.ok(Number.isSafeInteger(tests) && tests > 0 && tests < 10000);
const { chromium } = await import(pathToFileURL(modulePath).href);
const browser = await chromium.launch({ headless: true });
const errors = [], requests = [];
try {
  const context = await browser.newContext({ offline: true, viewport: { width: 1440, height: 1000 } });
  const page = await context.newPage();
  page.on('pageerror', error => errors.push(error.message));
  page.on('console', message => { if (message.type() === 'error') errors.push(message.text()); });
  page.on('request', request => { if (!request.url().startsWith('file:')) requests.push(request.url()); });
  await page.goto(pathToFileURL(reportPath).href);
  await page.waitForFunction(() => !document.getElementById('status').textContent.startsWith('Loading'), { timeout: 15000 });
  assert.equal(await page.locator('#status').innerText(), `Complete capture · ${tests + 3} tasks · ${tests * 5} measurements`);
  // Run -> adapter -> suite -> test invocations, each in its own timeline.
  for (let depth = 0; depth < 3; depth++) {
    assert.equal(await page.locator('#timeline .row').count(), 1);
    await page.locator('#timeline .row').getByRole('button', { name: /Children/ }).click();
  }
  assert.equal(await page.locator('#timeline .row').count(), Math.min(200, tests));
  const first = page.locator('#timeline .row').first();
  await first.locator('button').first().click();
  const detail = await page.locator('#details').innerText();
  const canonical = (await readFile(join(dirname(reportPath), 'graph.edn'), 'utf8')).trimEnd();
  const digest = createHash('sha256').update(canonical).digest('hex');
  assert.ok(detail.includes(`:artifact "${digest}"`), 'CLJS/JVM artifact identity must match');
  for (const quantity of [':tests', ':assertions', ':assertions-passed', ':assertions-failed', ':assertions-errored']) {
    assert.ok(detail.includes(`:quantity ${quantity} `));
  }
  assert.ok(detail.includes(':record :execution') && detail.includes(':kind :test') && detail.includes(':outcome :passed'));
  assert.ok(detail.includes(':accounting :exclusive') && detail.includes(':status :measured'));
  let visited = 0, pages = 0;
  while (visited < tests) {
    const rows = await page.locator('#timeline .row').count();
    assert.equal(rows, Math.min(200, tests - visited));
    const geometry = await page.locator('.bar').evaluateAll(bars => bars.map(bar => ({
      left: parseFloat(bar.style.left), width: parseFloat(bar.style.width), title: bar.title
    })));
    assert.equal(geometry.length, rows);
    for (const bar of geometry) {
      assert.ok(bar.left >= 0 && bar.width >= 0 && bar.left + bar.width <= 100.000001);
      assert.ok(bar.title.includes('passed') && bar.title.includes('ns'));
    }
    visited += rows; pages++;
    const next = page.getByRole('button', { name: 'Next tasks', exact: true });
    assert.equal(await next.count(), visited < tests ? 1 : 0);
    if (visited < tests) await next.click();
  }
  await page.screenshot({ path: screenshotPath, fullPage: true });
  await page.getByRole('button', { name: '↑ Parent', exact: true }).click();
  assert.equal(await page.locator('#timeline .row').count(), 1);
  await page.getByRole('button', { name: 'Run overview', exact: true }).click();
  assert.equal(await page.locator('#timeline .row').count(), 1);
  assert.deepEqual(errors, []); assert.deepEqual(requests, []);
  console.log(JSON.stringify({ status: 'passed', browser: browser.version(), offline: true,
    tests, measurements: tests * 5, pages, externalRequests: requests.length, errors: errors.length, artifact: digest }));
} finally {
  await browser.close();
}
