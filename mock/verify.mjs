import { chromium } from 'playwright-core';
import { fileURLToPath } from 'url';
import path from 'path';

const dir = path.dirname(fileURLToPath(import.meta.url));
const results = [];
const check = (name, ok, detail='') => { results.push([ok?'PASS':'FAIL', name, detail]); };

const browser = await chromium.launch();
const page = await browser.newPage({ viewport: { width: 1200, height: 900 } });
const errors = [];
page.on('console', m => { if (m.type() === 'error') errors.push('console: ' + m.text()); });
page.on('pageerror', e => errors.push('pageerror: ' + e.message));

await page.goto('file://' + path.join(dir, 'index.html'));
await page.waitForTimeout(600);

// -- home screen basics
check('clock renders', (await page.textContent('#clock')).length > 3);
check('18 list rows', await page.locator('#appList .row').count() === 18);
check('rail letters rendered', await page.locator('#rail .rl').count() >= 8);
check('calendar widget present', await page.locator('#wCal').count() === 1);
check('weather widget present', await page.locator('#wWx').count() === 1);
await page.screenshot({ path: path.join(dir, 'shots', '1-home-dark.png') });

// -- swipe down opens search
const home = await page.locator('#home').boundingBox();
await page.mouse.move(home.x + 200, home.y + 300);
await page.mouse.down();
for (let i = 1; i <= 8; i++) await page.mouse.move(home.x + 200, home.y + 300 + i * 12);
await page.mouse.up();
await page.waitForTimeout(250);
check('swipe-down opens search', await page.locator('#searchOverlay.open').count() === 1);

// -- calculator
await page.fill('#searchInput', '12*7+2');
await page.waitForTimeout(120);
const calcTxt = await page.textContent('#searchBody');
check('calculator evaluates 12*7+2=86', calcTxt.includes('= 86'), '');
await page.screenshot({ path: path.join(dir, 'shots', '2-search.png') });

// app search + escape
await page.fill('#searchInput', 'set');
await page.waitForTimeout(120);
check('search finds Settings', (await page.textContent('#searchBody')).includes('Settings'));
await page.keyboard.press('Escape');
await page.waitForTimeout(120);
check('Escape closes search', await page.locator('#searchOverlay.open').count() === 0);

// -- rail drag shows magnifier
const rail = await page.locator('#rail').boundingBox();
await page.mouse.move(rail.x + 20, rail.y + rail.height * 0.5);
await page.mouse.down();
await page.mouse.move(rail.x + 20, rail.y + rail.height * 0.7, { steps: 4 });
await page.waitForTimeout(150);
const magVisible = await page.locator('#magnifier').evaluate(el => el.style.display === 'block' && getComputedStyle(el).display !== 'none');
check('letter-rail drag shows magnifier', magVisible);
await page.screenshot({ path: path.join(dir, 'shots', '3-rail-drag.png') });
await page.mouse.up();
await page.waitForTimeout(150);
check('magnifier hides on release', await page.locator('#magnifier').evaluate(el => el.style.display === 'none'));

// -- wave letters rotate after scroll
await page.locator('#home').evaluate(el => el.scrollTo(0, el.scrollHeight));
await page.waitForTimeout(350);
const passedCount = await page.locator('#rail .rl.passed').count();
check('wave letters undock (passed class) after scroll', passedCount >= 3, `count=${passedCount}`);
const tilted = await page.locator('#rail .rl.passed').first().evaluate(el => getComputedStyle(el).transform !== 'none');
check('passed letters are transformed (tilted)', tilted);
await page.screenshot({ path: path.join(dir, 'shots', '4-scrolled-wave.png') });
await page.locator('#home').evaluate(el => el.scrollTo(0, 0));
await page.waitForTimeout(300);

// -- folder popup
await page.evaluate(() => Mock.openFolder('Work'));
await page.waitForTimeout(250);
check('folder popup opens', await page.locator('#folderPop.open').count() === 1);
check('folder lists 4 apps', await page.locator('#fpList .srow').count() === 4);
await page.screenshot({ path: path.join(dir, 'shots', '5-folder.png') });
await page.keyboard.press('Escape');

// -- notifications + inline reply
await page.evaluate(() => Mock.openNotifs('Messages'));
await page.waitForTimeout(250);
check('notification sheet opens', await page.locator('#notifSheet.open').count() === 1);
check('2 notification cards', await page.locator('#notifCard .notif-card').count() === 2);
await page.locator('#notifCard .notif-card .n-acts button').first().click();
await page.waitForTimeout(120);
await page.fill('.reply-box.open input', 'See you at noon');
await page.press('.reply-box.open input', 'Enter');
await page.waitForTimeout(250);
const toastTxt = await page.textContent('#toast');
check('inline reply sends + toast', toastTxt.includes('Replied to Lena'), toastTxt);
await page.screenshot({ path: path.join(dir, 'shots', '6-notif-reply.png') });
await page.keyboard.press('Escape');

// -- customize: theme + accent + sizes persist
await page.evaluate(() => Mock.openCustomize());
await page.waitForTimeout(200);
await page.locator('#segTheme button[data-v="light"]').click();
await page.waitForTimeout(300);
check('light theme applies', await page.locator('#screen[data-theme="light"]').count() === 1);
await page.locator('.swatch[data-v="violet"]').click();
await page.waitForTimeout(200);
const accent = await page.evaluate(() => getComputedStyle(document.documentElement).getPropertyValue('--accent').trim());
check('accent switch to violet', accent.includes('295'), accent);
await page.locator('#rClock').evaluate(el => { el.value = 40; el.dispatchEvent(new Event('input')); });
await page.waitForTimeout(200);
const clockSize = await page.evaluate(() => getComputedStyle(document.getElementById('clock')).fontSize);
check('clock size slider works (40px)', clockSize === '40px', clockSize);
await page.screenshot({ path: path.join(dir, 'shots', '7-customize-light.png') });

// -- persistence across reload
await page.reload();
await page.waitForTimeout(500);
const persisted = await page.evaluate(() => Mock.state());
check('settings persist after reload (light, violet, clock 40)',
  persisted.theme === 'light' && persisted.accent === 'violet' && persisted.clockSize === 40,
  JSON.stringify(persisted));
check('light theme survives reload', await page.locator('#screen[data-theme="light"]').count() === 1);
await page.screenshot({ path: path.join(dir, 'shots', '8-light-reloaded.png') });

// reset back to dark for the final gallery
await page.evaluate(() => Mock.reset());
await page.waitForTimeout(200);
check('reset restores defaults', (await page.evaluate(() => Mock.state())).theme === 'dark');

// -- drawer
await page.click('#corner');
await page.waitForTimeout(250);
check('app drawer opens from corner', await page.locator('#drawer.open').count() === 1);
check('drawer grid has 24 apps', await page.locator('#drawerGrid .dapp').count() === 24);
await page.fill('#drawerSearch', 'git');
await page.waitForTimeout(120);
check('drawer search filters', await page.locator('#drawerGrid .dapp:visible').count() === 1);
await page.screenshot({ path: path.join(dir, 'shots', '9-drawer.png') });

// -- launch animation path
await page.keyboard.press('Escape');
await page.waitForTimeout(150);
await page.evaluate(() => Mock.closeOverlays());
await page.locator('#appList .row').first().click();
await page.waitForTimeout(600);
const launchToast = await page.textContent('#toast');
check('row tap fires launch toast', launchToast.includes('Would launch'), launchToast);

console.log('\n===== RESULTS =====');
for (const [s, n, d] of results) console.log(s.padEnd(5), n, d ? `(${d})` : '');
const fails = results.filter(r => r[0] === 'FAIL').length;
console.log(`\n${results.length - fails}/${results.length} passed`);
console.log('JS errors:', errors.length ? errors.join(' | ') : 'none');
await browser.close();
process.exit(fails || errors.length ? 1 : 0);
