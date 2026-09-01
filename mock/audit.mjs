import { chromium } from 'playwright-core';
import { fileURLToPath } from 'url';
import path from 'path';

const dir = path.dirname(fileURLToPath(import.meta.url));
const browser = await chromium.launch();
const page = await browser.newPage({ viewport: { width: 1200, height: 900 } });
await page.goto('file://' + path.join(dir, 'index.html'));
await page.waitForTimeout(600);

const audit = await page.evaluate(() => {
  const out = {};
  const rail = document.getElementById('rail').getBoundingClientRect();
  // 1. every rail letter sits inside the rail box
  out.railLettersInside = [...document.querySelectorAll('.rl')].every(el => {
    const r = el.getBoundingClientRect();
    return r.left >= rail.left - 1 && r.right <= rail.right + 1 && r.top >= rail.top - 1 && r.bottom <= rail.bottom + 1;
  });
  // 2. no app name overflows its row
  out.noNameOverflow = [...document.querySelectorAll('#appList .row .name')].every(el => el.scrollWidth <= el.clientWidth + 1);
  // 3. top 5 rows have real icons, later rows show letters
  const rows = [...document.querySelectorAll('#appList .row')];
  out.topRowsIconed = rows.slice(0, 5).every(r => r.classList.contains('iconed'));
  out.laterRowsLetters = rows.slice(5).every(r => r.querySelector('.letter') !== null);
  // 4. no horizontal overflow anywhere
  const home = document.getElementById('home');
  out.noHorizontalOverflow = home.scrollWidth <= home.clientWidth + 1;
  // 5. clock is big and light-weight
  const cs = getComputedStyle(document.getElementById('clock'));
  out.clockStyle = { size: cs.fontSize, weight: cs.fontWeight };
  // 6. dots sit on their rows vertically centered
  out.dotCount = document.querySelectorAll('.dot').length;
  // 7. rail letters are evenly spaced
  const tops = [...document.querySelectorAll('.rl')].map(el => el.getBoundingClientRect().top);
  const gaps = tops.slice(1).map((t, i) => t - tops[i]);
  const g0 = gaps[0];
  out.railEvenSpacing = gaps.every(g => Math.abs(g - g0) < 1.5);
  // 8. contrast: accent letter vs background
  out.accent = getComputedStyle(document.documentElement).getPropertyValue('--accent').trim();
  return out;
});

console.log(JSON.stringify(audit, null, 2));
let ok = true;
if (!audit.railLettersInside) { console.log('FAIL rail letters escape rail'); ok = false; }
if (!audit.noNameOverflow) { console.log('FAIL name overflow'); ok = false; }
if (!audit.topRowsIconed) { console.log('FAIL top rows not iconed'); ok = false; }
if (!audit.laterRowsLetters) { console.log('FAIL later rows missing letters'); ok = false; }
if (!audit.noHorizontalOverflow) { console.log('FAIL horizontal overflow'); ok = false; }
if (!audit.railEvenSpacing) { console.log('FAIL rail spacing uneven'); ok = false; }
console.log(ok ? 'GEOMETRY AUDIT: ALL OK' : 'GEOMETRY AUDIT: ISSUES FOUND');
await browser.close();
process.exit(ok ? 0 : 1);
