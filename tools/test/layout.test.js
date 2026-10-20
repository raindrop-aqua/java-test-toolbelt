// 画面の配置のテスト（#38）。列や行が多い表をメインの領域でスクロールし、列名の行・行番号・テーブル名が
// 固定されること、移動先のセルが固定された部分の下に隠れないこと、サイドパネルの開閉を確かめる。
// Chrome DevTools Protocol で画面の大きさを決め、キーを送る。Chrome が無ければスキップする。WebSocket は Node 22 以降で使える
const test = require('node:test');
const assert = require('node:assert/strict');
const { pathToFileURL } = require('node:url');
const { EDITOR } = require('./load-core');
const { findChrome, openPage } = require('./chrome');

const chrome = findChrome();
const skip = !chrome ? 'Chrome が見つかりません（CHROME_PATH で指定できます）'
  : typeof WebSocket === 'undefined' ? 'WebSocket がありません（Node 22 以降が必要です）' : false;

// CI では、配置のテストが黙ってスキップされることのないようにする
test('CI では配置のテストを実行できる', { skip: !process.env.CI }, () => {
  assert.equal(skip, false, skip || '');
});

// 12 列 × 35 行の表と、小さな表
const COLS = Array.from({ length: 12 }, (_, i) => 'column_' + (i + 1));
const FIXTURE = [
  '[wide]',
  `{#|${COLS.join('|')}}`,
  ...Array.from({ length: 35 }, (_, r) => `${r % 3 + 1}|${COLS.map((c, i) => `v${r + 1}_${i + 1}`).join('|')}`),
  '',
  '[small]',
  '{a|b}',
  '1|2',
  '',
].join('\n');

test.describe('画面の配置', { skip }, () => {
  let page;

  async function open(width, height) {
    page = await openPage(chrome, pathToFileURL(EDITOR).href);
    await page.send('Emulation.setDeviceMetricsOverride', { width, height, deviceScaleFactor: 1, mobile: false });
    await page.evaluate(`(() => {
      document.getElementById('btnText').click();
      document.getElementById('text').value = ${JSON.stringify(FIXTURE)};
      document.getElementById('btnGrid').click();
    })()`);
  }

  test.afterEach(async () => {
    await page?.close();
    page = null;
  });

  // 要素の位置（画面の座標）
  const rect = sel => page.evaluate(`(() => {
    const r = document.querySelector(${JSON.stringify(sel)}).getBoundingClientRect();
    return { left: Math.round(r.left), top: Math.round(r.top), right: Math.round(r.right), bottom: Math.round(r.bottom) };
  })()`);
  const scroll = (top, left) => page.evaluate(`(() => {
    const m = document.getElementById('main');
    m.scrollTop = ${top};
    m.scrollLeft = ${left};
  })()`);
  const cell = (s, r, f) => `input.cell[data-s="${s}"][data-r="${r}"][data-f="${f}"]`;
  const press = (key, keyCode, modifiers = 0) => page.send('Input.dispatchKeyEvent', {
    type: 'rawKeyDown', key, code: key, windowsVirtualKeyCode: keyCode, nativeVirtualKeyCode: keyCode, modifiers,
  });
  const focused = () => page.evaluate(`(() => {
    const el = document.activeElement;
    return { r: el.dataset.r, f: el.dataset.f };
  })()`);

  test('表ごとではなく、メインの領域が縦横にスクロールする', async () => {
    await open(1200, 700);
    const size = await page.evaluate(`(() => {
      const m = document.getElementById('main');
      return { page: document.scrollingElement.scrollHeight <= innerHeight, x: m.scrollWidth > m.clientWidth, y: m.scrollHeight > m.clientHeight };
    })()`);
    assert.deepEqual(size, { page: true, x: true, y: true });
  });

  test('右下までスクロールしても、列名の行・行番号・テーブル名が見える', async () => {
    await open(1200, 700);
    await scroll(400, 9999);
    const main = await rect('#main');
    const head = await rect('section[data-s="0"] thead');
    const rh = await rect('section[data-s="0"] tr[data-r="20"] .rh');
    assert.equal(head.top, main.top, '列名の行が上に固定される');
    assert.equal(rh.left, main.left, '行番号が左に固定される');
    const name = await rect('section[data-s="1"] input.tname');
    assert.ok(name.left > main.left && name.right < main.left + 300, `小さな表のテーブル名が左に見える: ${JSON.stringify(name)}`);
  });

  test('↑ で移動した先のセルが、固定した列名の行の下に隠れない', async () => {
    await open(1200, 700);
    await page.evaluate(`document.querySelector('${cell(0, 20, 3)}').focus()`);
    // 移動先の行（19）が列名の行の下に来るまでスクロールする
    const top = await page.evaluate(`(() => {
      const m = document.getElementById('main');
      const row = document.querySelector('${cell(0, 19, 3)}');
      m.scrollTop += row.getBoundingClientRect().top - m.getBoundingClientRect().top;
      return m.scrollTop;
    })()`);
    assert.ok(top > 0);
    await press('ArrowUp', 38);
    assert.deepEqual(await focused(), { r: '19', f: '3' });
    const head = await rect('section[data-s="0"] thead');
    const target = await rect(cell(0, 19, 3));
    assert.ok(target.top >= head.bottom, `セル ${JSON.stringify(target)} が列名の行 ${JSON.stringify(head)} の下に隠れている`);
  });

  test('Shift+Tab で移動した先のセルが、固定した行番号の下に隠れない', async () => {
    await open(1200, 700);
    await page.evaluate(`document.querySelector('${cell(0, 5, 6)}').focus()`);
    // 移動先の列（5）が行番号の下に来るまでスクロールする
    await page.evaluate(`(() => {
      const m = document.getElementById('main');
      const target = document.querySelector('${cell(0, 5, 5)}');
      m.scrollLeft += target.getBoundingClientRect().left - m.getBoundingClientRect().left;
    })()`);
    await press('Tab', 9, 8);
    assert.deepEqual(await focused(), { r: '5', f: '5' });
    const rh = await rect('section[data-s="0"] tr[data-r="5"] .rh');
    const target = await rect(cell(0, 5, 5));
    assert.ok(target.left >= rh.right, `セル ${JSON.stringify(target)} が行番号 ${JSON.stringify(rh)} の下に隠れている`);
  });

  test('幅が広いとき、サイドパネルは横に並び、閉じるとメインの領域が広がる', async () => {
    await open(1200, 700);
    const before = await rect('#main');
    const panel = await rect('#sidePanel');
    assert.ok(panel.left >= before.right, 'サイドパネルはメインの領域に重ならない');
    await page.evaluate(`document.getElementById('btnSide').click()`);
    const closed = await page.evaluate(`({
      visible: getComputedStyle(document.getElementById('sidePanel')).display !== 'none',
      expanded: document.getElementById('btnSide').getAttribute('aria-expanded'),
    })`);
    assert.deepEqual(closed, { visible: false, expanded: 'false' });
    assert.ok((await rect('#main')).right > before.right);
    // 表示中のタブのハンドルでも開閉できる
    await page.evaluate(`document.getElementById('tabCheck').click()`);
    assert.equal(await page.evaluate(`document.getElementById('layout').classList.contains('side-open')`), true);
    await page.evaluate(`document.getElementById('tabCheck').click()`);
    assert.equal(await page.evaluate(`document.getElementById('layout').classList.contains('side-open')`), false);
  });

  test('幅が狭いとき、サイドパネルは表の上に重なるドロワーになる', async () => {
    await open(760, 640);
    assert.equal(await page.evaluate(`document.getElementById('layout').classList.contains('side-open')`), false, '最初は閉じている');
    const main = await rect('#main');
    await page.evaluate(`document.getElementById('tabCheck').click()`);
    const panel = await rect('#sidePanel');
    assert.ok(panel.left < main.right, 'メインの領域に重なる');
    assert.deepEqual(await rect('#main'), main, 'メインの領域の大きさは変わらない');
    await press('Escape', 27);
    assert.equal(await page.evaluate(`document.getElementById('layout').classList.contains('side-open')`), false, 'Esc で閉じる');
  });

  test('テキスト表示では、テキストの欄がメインの領域の高さいっぱいになる', async () => {
    await open(1200, 700);
    await page.evaluate(`document.getElementById('btnText').click()`);
    const main = await rect('#main');
    const text = await rect('#text');
    assert.ok(main.bottom - text.bottom <= 20 && text.bottom <= main.bottom, `テキストの欄 ${JSON.stringify(text)}、メインの領域 ${JSON.stringify(main)}`);
  });
});
