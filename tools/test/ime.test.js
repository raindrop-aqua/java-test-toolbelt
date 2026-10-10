// IME（日本語入力）のテスト（#27）。Chrome DevTools Protocol で、変換中の入力とキー操作を送る。
// Chrome が無ければスキップする。WebSocket は Node 22 以降で使える
const test = require('node:test');
const assert = require('node:assert/strict');
const { pathToFileURL } = require('node:url');
const { EDITOR } = require('./load-core');
const { findChrome, openPage } = require('./chrome');

const chrome = findChrome();
const skip = !chrome ? 'Chrome が見つかりません（CHROME_PATH で指定できます）'
  : typeof WebSocket === 'undefined' ? 'WebSocket がありません（Node 22 以降が必要です）' : false;

// CI では、IME のテストが黙ってスキップされることのないようにする
test('CI では IME のテストを実行できる', { skip: !process.env.CI }, () => {
  assert.equal(skip, false, skip || '');
});

test.describe('IME での入力', { skip }, () => {
  let page;
  test.beforeEach(async () => {
    page = await openPage(chrome, pathToFileURL(EDITOR).href);
  });
  test.afterEach(() => page.close());

  const cell = (r, f) => `document.querySelector('input.cell[data-s="0"][data-r="${r}"][data-f="${f}"]')`;
  const focus = (r, f) => page.evaluate(`${cell(r, f)}.focus()`);
  // 保存されるテキスト（テキスト表示に切り替えて読み、表に戻す）。桁揃えは既定で有効
  const text = () => page.evaluate(`(() => {
    document.getElementById('btnText').click();
    const value = document.getElementById('text').value;
    document.getElementById('btnGrid').click();
    return value;
  })()`);
  const focused = () => page.evaluate(`(() => {
    const el = document.activeElement;
    return { r: el.dataset.r, f: el.dataset.f, value: el.value };
  })()`);

  // 変換中の文字を compose まで入力し、キー（Enter など）で確定する。
  // Chrome は変換中のキーを keyCode 229 で送る。keyCode には、ほかのブラウザで起こり得る値も指定できるようにする
  async function composeAndCommit(compose, key, keyCode = 229) {
    for (let i = 1; i <= compose.length; i++) {
      const partial = compose.slice(0, i);
      await page.send('Input.imeSetComposition', { text: partial, selectionStart: i, selectionEnd: i });
    }
    const keyEvent = { key, code: key, windowsVirtualKeyCode: keyCode, nativeVirtualKeyCode: keyCode };
    await page.send('Input.dispatchKeyEvent', { type: 'rawKeyDown', ...keyEvent });
    await page.send('Input.insertText', { text: compose });
    await page.send('Input.dispatchKeyEvent', { type: 'keyUp', ...keyEvent });
  }

  // 変換中でない、ふつうのキー入力
  const press = (key, keyCode) => page.send('Input.dispatchKeyEvent', {
    type: 'rawKeyDown', key, code: key, windowsVirtualKeyCode: keyCode, nativeVirtualKeyCode: keyCode,
  });

  test('変換を確定する Enter では、行を移動・追加しない', async () => {
    await focus(0, 0);
    await composeAndCommit('ああ', 'Enter');
    assert.equal(await text(), '[table_name]\n{col1|col2}\n ああ|\n');
  });

  test('変換中の Enter の keyCode が 229 でなくても、行を移動・追加しない', async () => {
    await focus(0, 0);
    await composeAndCommit('ああ', 'Enter', 13);
    assert.equal(await text(), '[table_name]\n{col1|col2}\n ああ|\n');
  });

  test('確定した後の Enter では、今までどおり行を追加して移動する', async () => {
    await focus(0, 1);
    await composeAndCommit('いい', 'Enter');
    await press('Enter', 13);
    assert.deepEqual(await focused(), { r: '1', f: '1', value: '' });
    assert.equal(await text(), '[table_name]\n{col1|col2}\n     |いい\n     |\n');
  });

  test('変換中の ↓ では、下の行に移動しない', async () => {
    await page.evaluate(`document.querySelector('[data-act="sec-addrow"]').click()`);
    await focus(0, 0);
    await page.send('Input.imeSetComposition', { text: 'かん', selectionStart: 2, selectionEnd: 2 });
    await page.send('Input.dispatchKeyEvent', {
      type: 'rawKeyDown', key: 'ArrowDown', code: 'ArrowDown', windowsVirtualKeyCode: 229, nativeVirtualKeyCode: 229,
    });
    await page.send('Input.insertText', { text: '漢' });
    assert.deepEqual(await focused(), { r: '0', f: '0', value: '漢' });
    assert.equal(await text(), '[table_name]\n{col1|col2}\n 漢  |\n     |\n');
  });

  test('列名の欄でも、変換を確定する Enter ではフォーカスを外さない', async () => {
    await page.evaluate(`document.querySelector('input.cname[data-f="0"]').select()`);
    await composeAndCommit('しめい', 'Enter');
    const active = await page.evaluate('document.activeElement.className');
    assert.equal(active, 'cname');
    assert.equal(await text(), '[table_name]\n{しめい|col2}\n       |\n');
  });
});
