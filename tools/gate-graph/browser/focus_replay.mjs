// focus_replay.mjs — replay the Group-evidence close-then-navigate sequence N
// times and print, per trial, where keyboard focus ended and every focus event
// with its timestamp. It measures the dialog focus-restoration race (a late,
// async restore taking focus back after the user moved on); it states no
// expected result. Run in the pinned Playwright image, network removed:
//   docker run --rm --network none -v "$PWD:/w" -w /w/tools/gate-graph/browser \
//     <playwright-image> node focus_replay.mjs /w/<fixture-root>
import { chromium } from 'playwright';
import { resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
const fixture = resolve(process.argv[2], 'dense', 'index.html');
const browser = await chromium.launch({ headless: true });
for (let trial = 0; trial < 10; trial++) {
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 }, offline: true });
  const page = await ctx.newPage();
  await page.addInitScript(() => {
    window.__focus = [];
    document.addEventListener('focusin', e => window.__focus.push([Math.round(performance.now()), 'focusin', e.target.id || e.target.textContent.trim().slice(0, 30)]), true);
    document.addEventListener('close', () => window.__focus.push([Math.round(performance.now()), 'dialog-close', '']), true);
  });
  await page.goto(pathToFileURL(fixture).href);
  await page.waitForFunction(() => !document.querySelector('#status').textContent.startsWith('Loading'));
  await page.getByRole('button', { name: 'Open 3000 children', exact: true }).click();
  const failedFold = page.locator('.fold-row').filter({ hasText: /1\s+needs attention/ });
  await failedFold.getByRole('button', { name: 'Group evidence', exact: true }).click();
  await page.keyboard.press('Escape');
  await page.evaluate(() => { window.__focus.push([Math.round(performance.now()), 'test: after Escape', document.activeElement?.textContent.trim().slice(0, 30)]); return 0; });
  const btn = failedFold.locator('button').first(); await btn.focus(); await page.keyboard.press('Enter');
  await page.evaluate(() => new Promise(r => requestAnimationFrame(() => requestAnimationFrame(r))));
  const end = await page.evaluate(() => ({ active: document.activeElement?.id || document.activeElement?.tagName, scroll: scrollY, log: window.__focus }));
  console.log(`trial ${trial}: active=${end.active} scroll=${end.scroll}  ${JSON.stringify(end.log)}`);
  await ctx.close();
}
await browser.close();
