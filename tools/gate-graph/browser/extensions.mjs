import assert from 'node:assert/strict';
import { chromium, webkit } from 'playwright';
import { mkdir, writeFile, readFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { pathToFileURL } from 'node:url';

const [fixtures, output, engine = 'chromium'] = process.argv.slice(2);
assert.ok(fixtures && output && ['chromium', 'webkit'].includes(engine));
await mkdir(output);
const file = resolve(fixtures, 'extensions/extensions.html');
const original = await readFile(file, 'utf8');
let browser;
let failure;
const records = [];
let assertions = 0;
async function tally(check) { await check(); assertions += 1; }
try {
  browser = await ({ chromium, webkit })[engine].launch({ headless: true });
  for (const width of [1280, 390]) {
    const context = await browser.newContext({ viewport: { width, height: 900 } });
    const page = await context.newPage();
    assertions = 0;
    const errors = [];
    const requests = [];
    page.on('pageerror', error => errors.push(error.message));
    await page.route('**/*', route => {
      if (route.request().url().startsWith('file:')) return route.continue();
      requests.push(route.request().url());
      return route.abort();
    });
    await page.goto(pathToFileURL(file).href);
    await page.waitForFunction(() => !document.getElementById('status').textContent.startsWith('Loading'));
    await tally(async () => assert.match(await page.locator('#archive-status').innerText(), /Archive: incomplete/));
    await tally(async () => assert.equal(await page.locator('.row').count(), 1, 'the core graph remains interactive'));
    await tally(async () => assert.equal(await page.locator('.extension-observation').count(), 1));
    await page.locator('.extension-observation summary').click();
    await tally(async () => assert.match(await page.locator('#report-extensions').innerText(), /example.cache\/observation v1/));
    await tally(async () => assert.match(await page.locator('#report-extensions').innerText(), /900719925474099312345 byte/));
    await tally(async () => assert.match(await page.locator('.extension-payload').innerText(), /window.extensionAttack=true/));
    await tally(async () => assert.equal(await page.evaluate(() => window.extensionAttack), undefined, 'hostile payload never executes'));
    await tally(async () => assert.match(await page.locator('#extension-identity').innerText(), /^[a-f0-9]{64}$/));
    await tally(async () => assert.match(await page.locator('#extension-core-identity').innerText(), /^[a-f0-9]{64}$/));
    await tally(async () => assert.match(await page.locator('#report-extensions').innerText(), /caller-declared/));
    await tally(async () => assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true));
    await tally(async () => assert.deepEqual(errors, []));
    await tally(async () => assert.deepEqual(requests, []));
    await page.locator('#report-extensions').scrollIntoViewIfNeeded();
    await page.screenshot({ path: resolve(output, `extension-${width}.png`), fullPage: true });
    records.push({ width, passed: true, assertions, externalRequests: requests.length, errors });
    await context.close();
  }
  assert.equal(await readFile(file, 'utf8'), original, 'inspection leaves the source report unchanged');
} catch (error) {
  failure = { kind: error instanceof assert.AssertionError ? 'FAIL' : 'ERROR', message: error.message };
  process.exitCode = failure.kind === 'FAIL' ? 1 : 2;
} finally {
  await writeFile(resolve(output, 'manifest.json'), JSON.stringify({ engine, completed: !failure,
    failure, records, sourceUnchanged: !failure, visualInspection: 'pending; captures are not reviews' }, null, 2));
  await browser?.close();
}
