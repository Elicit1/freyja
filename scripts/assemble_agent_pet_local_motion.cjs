/** Offline packaging for imagegen artwork. Chrome Canvas preserves the shared
 * base pixels; only explicitly permitted motion regions are taken from edits.
 * No runtime dependency is added to the frontend.
 */
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const { execFileSync } = require('node:child_process');
const { chromium } = require('../logs/browser-tools/node_modules/playwright-core');
const ROOT = path.resolve(__dirname, '..');
const SOURCE = path.join(ROOT, 'docs/agent-avatar/source/local-motion');
const DOCS = path.join(ROOT, 'docs/agent-avatar');
const OUTPUT = path.join(ROOT, 'front/public/agent/freyja');
const names = ['idle', 'running-right', 'running-left', 'waving', 'jumping', 'failed', 'waiting', 'running', 'review'];
const save = (file, data) => fs.writeFileSync(file, Buffer.from(data.split(',')[1], 'base64'));
const dataUrl = file => `data:image/${path.extname(file) === '.webp' ? 'webp' : 'png'};base64,${fs.readFileSync(file).toString('base64')}`;

async function main() {
  fs.mkdirSync(SOURCE, { recursive: true });
  const browser = await chromium.launch({ headless: true, executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe' });
  try {
    const page = await browser.newPage();
    if (process.argv.includes('--prepare')) {
    const prepared = await page.evaluate(async ({ atlas, names }) => {
      const load = async url => { const img = new Image(); img.src = url; await img.decode(); return img; };
      const img = await load(atlas);
      const canvas = (w, h) => { const c = document.createElement('canvas'); c.width = w; c.height = h; return c; };
      const preview = canvas(192 * 3, 208 * 3);
      const outputs = [];
      for (let row = 0; row < 9; row++) {
        const base = canvas(192, 208);
        base.getContext('2d').drawImage(img, 0, row * 208, 192, 208, 0, 0, 192, 208);
        const template = canvas(1536, 1024);
        for (let i = 0; i < 8; i++) template.getContext('2d').drawImage(base, (i % 4) * 384, Math.floor(i / 4) * 512 + 48, 384, 416);
        const detail = canvas(576, 624);
        detail.getContext('2d').drawImage(base, 0, 0, 576, 624);
        preview.getContext('2d').drawImage(base, row % 3 * 192, Math.floor(row / 3) * 208);
        outputs.push({ name: names[row], base: base.toDataURL(), detail: detail.toDataURL(), template: template.toDataURL() });
      }
      return { outputs, preview: preview.toDataURL() };
    }, { atlas: dataUrl(path.join(OUTPUT, 'spritesheet-smooth-v2.webp')), names });
    for (const item of prepared.outputs) {
      for (const key of ['base', 'detail', 'template']) save(path.join(SOURCE, `${item.name}-${key}.png`), item[key]);
    }
    save(path.join(DOCS, 'local-motion-bases.png'), prepared.preview);
    console.log(JSON.stringify({ prepared: names, source: SOURCE }));
    return;
    }
    const config = JSON.parse(fs.readFileSync(path.join(DOCS, 'local-motion-regions.json'), 'utf8'));
    const inputs = config.actions.map(action => ({ ...action,
      base: dataUrl(path.join(SOURCE, `${action.name}-base.png`)),
      generated: action.mirror ? null : dataUrl(path.join(SOURCE, `${action.name}-generated.png`))
    }));
    const packaged = await page.evaluate(async ({ inputs, feather }) => {
      const W = 192, H = 208;
      const canvas = (w = W, h = H) => { const c = document.createElement('canvas'); c.width = w; c.height = h; return c; };
      const load = async url => { const img = new Image(); img.src = url; await img.decode(); return img; };
      const pixels = c => c.getContext('2d', { willReadFrequently: true }).getImageData(0, 0, c.width, c.height);
      const drawPixels = data => { const c = canvas(); c.getContext('2d').putImageData(new ImageData(new Uint8ClampedArray(data), W, H), 0, 0); return c; };
      function regionMask(regions) {
        const mask = canvas(), ctx = mask.getContext('2d'); ctx.fillStyle = 'white';
        for (const region of regions) {
          ctx.beginPath();
          if (region.ellipse) { const [x, y, rx, ry] = region.ellipse; ctx.ellipse(x, y, rx, ry, 0, 0, Math.PI * 2); }
          else { region.polygon.forEach(([x,y], i) => i ? ctx.lineTo(x,y) : ctx.moveTo(x,y)); ctx.closePath(); }
          ctx.fill();
        }
        const hard = pixels(mask).data;
        const weights = new Float32Array(W * H);
        for (let y = 0; y < H; y++) for (let x = 0; x < W; x++) {
          const p = y * W + x; if (!hard[p * 4 + 3]) continue;
          let distance = feather;
          for (let dy = -feather; dy <= feather; dy++) for (let dx = -feather; dx <= feather; dx++) {
            if (x+dx < 0 || y+dy < 0 || x+dx >= W || y+dy >= H || !hard[((y+dy)*W+x+dx)*4+3]) distance = Math.min(distance, Math.hypot(dx,dy));
          }
          weights[p] = hard[p*4+3] / 255 * Math.min(1, distance / feather);
        }
        return weights;
      }
      // Fit regenerated patches to the fixed head, never realign the master drawing.
      function registration(reference, source, weights) {
        const points = [];
        for (let y = 20; y < 100; y += 2) for (let x = 36; x < 150; x += 2) {
          const p = y*W+x; if (reference[p*4+3] >= 245 && !weights[p]) points.push([x,y,p*4]);
        }
        function sample(x,y,k) {
          const ix = Math.floor(x), iy = Math.floor(y), fx = x-ix, fy=y-iy;
          if (ix < 0 || iy < 0 || ix+1 >= W || iy+1 >= H) return 0;
          const p = (iy*W+ix)*4+k;
          return source[p]*(1-fx)*(1-fy)+source[p+4]*fx*(1-fy)+source[p+W*4]*(1-fx)*fy+source[p+W*4+4]*fx*fy;
        }
        let best = { score: Infinity, dx: 0, dy: 0, scale: 1 };
        for (const step of [1, .25]) {
          const center = { ...best };
          const scales = step === 1 ? [.96,.98,1,1.02,1.04] : [center.scale-.01,center.scale,center.scale+.01];
          for (const scale of scales) for (let dy = -2; dy <= 2; dy += step) for (let dx = -2; dx <= 2; dx += step) {
            const tx = (step === 1 ? 0 : center.dx) + dx, ty = (step === 1 ? 0 : center.dy) + dy;
            let score = 0;
            for (const [x,y,p] of points) for (let k = 0; k < 3; k++) score += Math.abs(reference[p+k] - sample((x-96-tx)/scale+96,(y-64-ty)/scale+64,k));
            score /= points.length * 3;
            if (score < best.score) best = { score, dx: tx, dy: ty, scale };
          }
        }
        return best;
      }
      function fit(source, transform) {
        const c = canvas(), ctx = c.getContext('2d');
        ctx.translate(96 + transform.dx, 64 + transform.dy); ctx.scale(transform.scale, transform.scale); ctx.translate(-96,-64);
        ctx.drawImage(source, 0, 0); return pixels(c).data;
      }
      // Premultiplied RGBA interpolation for tiny in-between gestures; base pixels
      // are copied byte-for-byte outside the explicit mask.
      function mix(a,b,t) {
        const result = new Uint8ClampedArray(a.length);
        for (let p = 0; p < a.length; p += 4) {
          const alpha = a[p+3]*(1-t)+b[p+3]*t; result[p+3] = alpha;
          for (let k = 0; k < 3; k++) result[p+k] = alpha ? (a[p+k]*a[p+3]*(1-t)+b[p+k]*b[p+3]*t)/alpha : 0;
        }
        return result;
      }
      function inspectFrame(data, name, frame) {
        const visited = new Uint8Array(W * H), components = [];
        for (let p = 0; p < W * H; p++) {
          if (data[p * 4 + 3] && (p % W === 0 || p % W === W - 1 || p < W || p >= W * (H - 1)))
            throw new Error(`${name}/${frame}: artwork touches cell edge`);
          if (visited[p] || data[p * 4 + 3] < 32) continue;
          const queue = [p]; visited[p] = 1;
          for (let i = 0; i < queue.length; i++) {
            const q = queue[i], x = q % W, y = Math.floor(q / W);
            for (let dy = -1; dy <= 1; dy++) for (let dx = -1; dx <= 1; dx++) {
              const nx = x + dx, ny = y + dy, n = ny * W + nx;
              if (nx < 0 || ny < 0 || nx >= W || ny >= H || visited[n] || data[n * 4 + 3] < 32) continue;
              visited[n] = 1; queue.push(n);
            }
          }
          if (queue.length >= 3) components.push(queue.length);
        }
        if (components.length !== 1) throw new Error(`${name}/${frame}: detached artwork components ${components}`);
        return { connectedComponents: components.length, edgePixels: 0 };
      }
      const actions = [], byName = {};
      for (const input of inputs) {
        if (input.mirror) {
          const right = byName[input.mirror];
          const mirror = data => {
            const out = new Uint8ClampedArray(data.length);
            for (let y=0;y<H;y++) for (let x=0;x<W;x++) out.set(data.subarray((y*W+x)*4,(y*W+x)*4+4),(y*W+W-1-x)*4);
            return out;
          };
          const weights = new Float32Array(W*H);
          for(let y=0;y<H;y++) for(let x=0;x<W;x++) weights[y*W+W-1-x]=right.weights[y*W+x];
          const action = { ...input, basePixels: mirror(right.basePixels), weights, frames: right.frames.map(mirror), alignments: right.alignments };
          actions.push(action); byName[input.name]=action; continue;
        }
        const original = canvas(); original.getContext('2d').drawImage(await load(input.base),0,0);
        const basePixels = pixels(original).data, weights = regionMask(input.regions);
        const sheet = await load(input.generated);
        if (sheet.width !== 1536 || sheet.height !== 1024) throw new Error(`${input.name}: expected 1536x1024 edit, got ${sheet.width}x${sheet.height}`);
        const candidates = [], alignments=[];
        for (let i=0;i<8;i++) {
          const cell = canvas();
          cell.getContext('2d').drawImage(sheet,(i%4)*384,Math.floor(i/4)*512+48,384,416,0,0,W,H);
          const transform = registration(basePixels,pixels(cell).data,weights);
          alignments.push(transform); candidates.push(fit(cell,transform));
        }
        const keyframes = input.order.map(i => {
          const generated = candidates[i], frame = new Uint8ClampedArray(basePixels);
          for(let p=0;p<W*H;p++) {
            let t=weights[p]; if(!t) continue; const j=p*4;
            // Feather only inside opaque artwork. Across a silhouette, blending
            // alpha retains the old limb as a translucent sliver instead of erasing it.
            if (basePixels[j+3] < 245 || generated[j+3] < 245) t = 1;
            const alpha=basePixels[j+3]*(1-t)+generated[j+3]*t;
            frame[j+3]=alpha;
            for(let k=0;k<3;k++) frame[j+k]=alpha ? (basePixels[j+k]*basePixels[j+3]*(1-t)+generated[j+k]*generated[j+3]*t)/alpha : 0;
          }
          return frame;
        });
        const frames=[];
        for(let i=0;i<8;i++) {
          frames.push(keyframes[i]);
          // Hold hand/leg drawings. Crossfading different silhouettes produces
          // two hands or feet; eye motion inside the opaque face can interpolate.
          frames.push(input.interpolate === false ? keyframes[i] : mix(keyframes[i],keyframes[(i+1)%8],.5));
        }
        const action = { ...input,basePixels,weights,frames,alignments };
        actions.push(action); byName[input.name]=action;
      }
      const dense=canvas(W*16,H*9),portable=canvas(W*8,H*9),preview=canvas(8*192,9*234),maskPreview=canvas(192*3,234*3);
      const pc=preview.getContext('2d'),mc=maskPreview.getContext('2d'); pc.fillStyle='#f8f5ff';pc.fillRect(0,0,preview.width,preview.height);mc.fillStyle='#f8f5ff';mc.fillRect(0,0,maskPreview.width,maskPreview.height);
      const counts=[6,8,8,4,5,8,6,6,6],report=[],saved=[];
      for(let row=0;row<9;row++) {
        const action=actions[row]; let changedOutside=0,maxChanged=0; const structure=[];
        for(let col=0;col<16;col++) {
          const data=action.frames[col]; let changed=0;
          structure.push(inspectFrame(data, action.name, col));
          for(let p=0;p<W*H;p++) {
            const j=p*4; const different=[0,1,2,3].some(k=>data[j+k]!==action.basePixels[j+k]);
            if(different){changed++;if(!action.weights[p])changedOutside++;}
          }
          maxChanged=Math.max(maxChanged,changed);
          const frame=drawPixels(data);dense.getContext('2d').drawImage(frame,col*W,row*H);
          if(col%2===0)pc.drawImage(frame,col/2*192,row*234);
        }
        if(changedOutside)throw new Error(`${action.name}: ${changedOutside} changed pixels outside motion region`);
        if(!maxChanged)throw new Error(`${action.name}: no animation`);
        const selected=Array.from({length:counts[row]},(_,i)=>Math.floor(i*16/counts[row]));
        for(let col=0;col<counts[row];col++)portable.getContext('2d').drawImage(drawPixels(action.frames[selected[col]]),col*W,row*H);
        pc.fillStyle='#61438e';pc.font='14px sans-serif';pc.fillText(action.label,6,row*234+225);
        const mask=canvas(),ctx=mask.getContext('2d');ctx.drawImage(drawPixels(action.basePixels),0,0);
        const overlay=new Uint8ClampedArray(W*H*4);
        for(let p=0;p<W*H;p++)if(action.weights[p]){overlay[p*4]=249;overlay[p*4+1]=75;overlay[p*4+2]=128;overlay[p*4+3]=110;}
        ctx.drawImage(drawPixels(overlay),0,0);mc.drawImage(mask,row%3*192,Math.floor(row/3)*234);mc.fillStyle='#61438e';mc.font='14px sans-serif';mc.fillText(action.label,row%3*192+6,Math.floor(row/3)*234+225);
        report.push({name:action.name,motionRegions:action.regions||{mirror:action.mirror},interpolation:action.interpolate===false||action.mirror?'hold':'opaque-eye-crossfade',changedPixelsOutsideRegions:changedOutside,maxChangedPixels:maxChanged,structure,alignments:action.alignments,portableSelected:selected});
        saved.push({name:action.name,frames:action.frames.map(data=>drawPixels(data).toDataURL()),mask:mask.toDataURL()});
      }
      // Decode the saved atlas and verify static pixels again after PNG encoding.
      const denseUrl=dense.toDataURL();const decoded=canvas(W*16,H*9);decoded.getContext('2d').drawImage(await load(denseUrl),0,0);const decodedPixels=pixels(decoded).data;
      for(let row=0;row<9;row++)for(let col=1;col<16;col++)for(let y=0;y<H;y++)for(let x=0;x<W;x++) {
        if(actions[row].weights[y*W+x])continue;
        const a=((row*H+y)*W*16+x)*4,b=a+col*W*4;
        for(let k=0;k<4;k++)if(decodedPixels[a+k]!==decodedPixels[b+k])throw new Error(`Decoded static pixel mismatch: ${row}/${col}/${x}/${y}`);
      }
      return {dense:denseUrl,portable:portable.toDataURL(),preview:preview.toDataURL(),masks:maskPreview.toDataURL(),report,saved};
    }, { inputs, feather: config.featherPixels });
    save(path.join(OUTPUT, 'spritesheet-local-motion-v4.png'), packaged.dense);
    save(path.join(OUTPUT, 'spritesheet-local-motion-codex.png'), packaged.portable);
    save(path.join(DOCS, 'local-motion-frames-preview.png'), packaged.preview);
    save(path.join(DOCS, 'local-motion-regions-preview.png'), packaged.masks);
    const framesPath = path.join(SOURCE, 'frames-clean');fs.mkdirSync(framesPath,{recursive:true});
    for(const item of packaged.saved)for(let i=0;i<item.frames.length;i++)save(path.join(framesPath, `${item.name}-${String(i).padStart(2,'0')}.png`),item.frames[i]);
    const report={tool:'built-in image_gen.imagegen',frameSize:[192,208],atlasSize:[3072,1872],format:'lossless PNG',pixelLockPassed:true,rows:packaged.report};
    for(const file of ['spritesheet-local-motion-v4.png','spritesheet-local-motion-codex.png']) {
      const data=fs.readFileSync(path.join(OUTPUT,file));report[file]={bytes:data.length,sha256:crypto.createHash('sha256').update(data).digest('hex')};
    }
    const manifest = { id: 'freyja', displayName: 'FREYJA', description: '银白发、紫金花饰的 Q 版创作助手', spritesheetPath: 'spritesheet-local-motion-codex.png', spriteVersionNumber: 1 };
    fs.writeFileSync(path.join(OUTPUT,'pet-local-motion.json'),JSON.stringify(manifest,null,2)+'\n');
    const zip=path.join(OUTPUT,'freyja-local-motion.codex-pet.zip');
    // Python's standard library is used only as a ZIP writer, never for artwork.
    execFileSync('python',['-c','import sys, zipfile; archive = zipfile.ZipFile(sys.argv[3], "w", zipfile.ZIP_DEFLATED); archive.write(sys.argv[1], "pet.json"); archive.write(sys.argv[2], "spritesheet-local-motion-codex.png"); archive.close()',path.join(OUTPUT,'pet-local-motion.json'),path.join(OUTPUT,manifest.spritesheetPath),zip]);
    const bytes=fs.readFileSync(zip);report['freyja-local-motion.codex-pet.zip']={bytes:bytes.length,sha256:crypto.createHash('sha256').update(bytes).digest('hex')};
    fs.writeFileSync(path.join(DOCS,'local-motion-validation.json'),JSON.stringify(report,null,2)+'\n');
    console.log(JSON.stringify({pixelLockPassed:true,rows:report.rows.map(row=>({name:row.name,changedPixelsOutsideRegions:row.changedPixelsOutsideRegions,maxChangedPixels:row.maxChangedPixels})),atlas:report['spritesheet-local-motion-v4.png']}));
  } finally { await browser.close(); }
}
main().catch(error => { console.error(error); process.exitCode = 1; });
