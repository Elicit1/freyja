/** Inspect the encoded atlas, and render light/dark motion previews from its cells. */
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const { chromium } = require('../logs/browser-tools/node_modules/playwright-core');
const root = path.resolve(__dirname, '..');
const docs = path.join(root, 'docs/agent-avatar');
const atlasPath = path.join(root, 'front/public/agent/freyja/spritesheet-local-motion-v4.png');
const previousPath = path.join(root, 'front/public/agent/freyja/spritesheet-local-motion-v3.png');
const url = file => 'data:image/png;base64,' + fs.readFileSync(file).toString('base64');
const labels = ['待机', '向右移动', '向左移动', '招手', '完成', '遇到问题', '等待处理', '工作', '思考'];

(async () => {
  const browser = await chromium.launch({ headless: true, executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe' });
  try {
    const page = await browser.newPage();
    const result = await page.evaluate(async ({ current, previous, labels }) => {
      const load = async url => { const image = new Image(); image.src = url; await image.decode(); return image; };
      const canvas = (width, height) => { const c = document.createElement('canvas'); c.width = width; c.height = height; return c; };
      const image = await load(current), old = await load(previous);
      if (image.width !== 3072 || image.height !== 1872) throw new Error('Unexpected encoded atlas dimensions');
      const atlas = canvas(image.width, image.height), ctx = atlas.getContext('2d', { willReadFrequently: true });
      ctx.drawImage(image, 0, 0);
      const report = [];
      for (let row = 0; row < 9; row++) {
        const frames = [];
        for (let frame = 0; frame < 16; frame++) {
          const pixels = ctx.getImageData(frame * 192, row * 208, 192, 208).data;
          let occupied = 0, edge = 0;
          for (let p = 0; p < 192 * 208; p++) if (pixels[p * 4 + 3]) {
            occupied++;
            const x = p % 192, y = Math.floor(p / 192);
            if (x === 0 || x === 191 || y === 0 || y === 207) edge++;
          }
          if (!occupied || edge) throw new Error(`Encoded frame ${row}/${frame}: occupied=${occupied}, edge=${edge}`);
          frames.push({ frame, occupied, edge });
        }
        report.push({ row, label: labels[row], frames });
      }
      // The straight-leg pose exposed the retained sliver of the previous shin.
      const beforeAfter = canvas(768, 960), bc = beforeAfter.getContext('2d');
      bc.fillStyle = '#252638'; bc.fillRect(0, 0, 768, 480);
      bc.fillStyle = '#f8f5ff'; bc.fillRect(0, 480, 768, 480);
      for (let background = 0; background < 2; background++) for (let column = 0; column < 2; column++) {
        bc.drawImage(column ? image : old, 4 * 192, 208, 192, 208, column * 384, background * 480, 384, 416);
        bc.fillStyle = background ? '#61438e' : '#ffffff'; bc.font = '20px sans-serif';
        bc.fillText(column ? '修正后：旧脚轮廓已替换' : '修正前：旧脚轮廓残留', column * 384 + 12, background * 480 + 450);
      }
      const handContact = canvas(8 * 144, 6 * 174), hc = handContact.getContext('2d');
      for (let group = 0; group < 6; group++) {
        const row = [3, 4, 7][group % 3], dark = group < 3;
        hc.fillStyle = dark ? '#252638' : '#f8f5ff'; hc.fillRect(0, group * 174, handContact.width, 174);
        for (let frame = 0; frame < 8; frame++) hc.drawImage(image, frame * 2 * 192 + 48, row * 208 + 76, 96, 96, frame * 144, group * 174, 144, 144);
        hc.fillStyle = dark ? '#ffffff' : '#61438e'; hc.font = '15px sans-serif'; hc.fillText(labels[row], 8, group * 174 + 165);
      }
      return { dimensions: [image.width, image.height], report, beforeAfter: beforeAfter.toDataURL(), handContact: handContact.toDataURL() };
    }, { current: url(atlasPath), previous: url(previousPath), labels });
    for (const [key, name] of [['beforeAfter', 'crop-repair-before-after.png'], ['handContact', 'crop-repair-hands.png']]) {
      fs.writeFileSync(path.join(docs, name), Buffer.from(result[key].split(',')[1], 'base64'));
      delete result[key];
    }
    result.sha256 = crypto.createHash('sha256').update(fs.readFileSync(atlasPath)).digest('hex');
    result.totalFrames = 144;
    fs.writeFileSync(path.join(docs, 'crop-repair-validation.json'), JSON.stringify(result, null, 2) + '\n');
    // The browser preview uses exact cell geometry, both backgrounds and existing timing.
    const html = `<!doctype html><html lang="zh"><meta charset="utf-8"><title>FREYJA 动作裁切检查</title>
<style>body{margin:24px;font:15px system-ui;background:#eceaf2}main{display:grid;grid-template-columns:repeat(3,1fr);gap:16px}.card{padding:16px;border-radius:12px;background:#fff}.pair{display:flex;gap:12px}.stage{width:192px;height:208px;background:#f8f5ff}.stage.dark{background:#252638}.sprite{width:192px;height:208px;background-image:url(${url(atlasPath)});background-size:1600% 900%;background-repeat:no-repeat}h1{font-size:22px}h2{font-size:16px}</style>
<h1>FREYJA 九种动作 · 深浅背景逐帧预览</h1><p>每格 192 × 208。点击任意动作暂停或继续。</p><main>${labels.map((label, row) => `<section class="card" data-row="${row}"><h2>${label}</h2><div class="pair"><div class="stage"><div class="sprite"></div></div><div class="stage dark"><div class="sprite"></div></div></div></section>`).join('')}</main>
<script>const cycles=[10240,6784,6784,4480,5376,7808,6464,5248,6592],start=performance.now();for(const c of document.querySelectorAll('.card'))c.onclick=()=>c.dataset.paused=c.dataset.paused==='yes'?'no':'yes';function tick(t){for(const c of document.querySelectorAll('.card')){if(c.dataset.paused==='yes')continue;const row=+c.dataset.row,frames=Array.from({length:16},(_,i)=>i);if(row!==1&&row!==2)frames.push(...Array.from({length:14},(_,i)=>14-i));const pause=row===0?5120:0,active=cycles[row]-pause,elapsed=(t-start)%cycles[row],frame=elapsed>=active?frames.at(-1):frames[Math.min(frames.length-1,Math.floor(elapsed/active*frames.length))];for(const s of c.querySelectorAll('.sprite'))s.style.backgroundPosition=(frame/15*100)+'% '+(row/8*100)+'%'}requestAnimationFrame(tick)}requestAnimationFrame(tick)</script></html>`;
    fs.writeFileSync(path.join(docs, 'crop-repair-motion-preview.html'), html);
    console.log(JSON.stringify({ totalFrames: result.totalFrames, dimensions: result.dimensions, sha256: result.sha256, edgePixels: 0 }));
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exitCode = 1; });
