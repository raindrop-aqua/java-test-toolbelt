// エディタの画面をヘッドレスの Chrome で操作するテスト。Chrome が無ければスキップする。
// Chrome の場所は環境変数 CHROME_PATH でも指定できる
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { execFileSync } = require('node:child_process');
const { EDITOR } = require('./load-core');

function findChrome() {
  const candidates = [
    process.env.CHROME_PATH,
    '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
    '/usr/bin/google-chrome',
    '/usr/bin/google-chrome-stable',
    '/usr/bin/chromium',
    '/usr/bin/chromium-browser',
    'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe',
  ];
  return candidates.find(p => p && fs.existsSync(p));
}

// ハーネスを埋め込んだページを開き、操作の結果を受け取る
function runHarness(chrome) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'fixture-editor-'));
  try {
    const harness = fs.readFileSync(path.join(__dirname, 'ui-harness.js'), 'utf8');
    const html = fs.readFileSync(EDITOR, 'utf8').replace('</body>', `<script>${harness}</script></body>`);
    const page = path.join(dir, 'page.html');
    fs.writeFileSync(page, html);
    const args = [
      '--headless=new', '--disable-gpu', '--no-first-run', `--user-data-dir=${path.join(dir, 'profile')}`,
      '--virtual-time-budget=3000', '--dump-dom', 'file://' + page,
    ];
    if (process.platform === 'linux') args.unshift('--no-sandbox');
    const dom = execFileSync(chrome, args, { encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'], timeout: 60000 });
    // ページに埋め込んだスクリプトの文字列にも一致しないよう、最後に追加された要素を使う
    const match = [...dom.matchAll(/<pre id="result">([\s\S]*?)<\/pre>/g)].pop();
    if (!match) throw new Error('結果が出力されませんでした');
    const json = match[1].replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&quot;/g, '"').replace(/&amp;/g, '&');
    return JSON.parse(json);
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
}

const chrome = findChrome();

// CI では、Chrome が無いために画面のテストが黙ってスキップされることのないようにする
test('CI では Chrome がある', { skip: !process.env.CI }, () => {
  assert.ok(chrome, 'Chrome が見つかりません（CHROME_PATH で指定できます）');
});

test.describe('画面の操作', { skip: chrome ? false : 'Chrome が見つかりません（CHROME_PATH で指定できます）' }, () => {
  let result;
  test.before(() => {
    result = runHarness(chrome);
  });

  test('JavaScript のエラーが無い', () => assert.deepEqual(result.errors, []));

  test('テキストから読み込んで表で表示する', () => {
    assert.deepEqual(result.steps.loaded, { sections: 2, status: '✓ 問題なし' });
  });

  test('編集・行の追加・Alt+N・列の追加', () => {
    assert.equal(result.steps.altN, '<null>');
    assert.equal(result.steps.addColumn, 'エラー 1 / 警告 0');
    assert.deepEqual(result.steps.columnMark, { name: 'updated_at', mark: '@' });
    assert.equal(result.steps.edited, [
      '// 顧客',
      '[customer]',
      '{#|customer_id|customer_name|customer_rank}',
      ' 1|C001       |山田 太郎    |GOLD',
      ' 2|C001       |山田 太郎    |<null>',
      '',
      '[product]',
      '{product_code|stock|updated_at@}',
      ' P001        |99   |',
      ' P002        |5    |',
      '',
    ].join('\n'));
  });

  test('Excel からの貼り付け', () => {
    assert.equal(result.steps.pasted, [
      '// 顧客',
      '[customer]',
      '{#  |customer_id|customer_name|customer_rank}',
      ' 1  |C001       |山田 太郎    |GOLD',
      ' 3  |C002       |鈴木         |REGULAR',
      ' 1,2|C003       |佐藤         |GOLD',
      '',
      '[product]',
      '{product_code|stock|updated_at@}',
      ' P001        |99   |',
      ' P002        |5    |',
      '',
    ].join('\n'));
  });

  test('パターンで絞り込む', () => {
    assert.deepEqual(result.steps.filter, { options: ['', '1', '2', '3'], dimRows: 2 });
  });

  test('元に戻す・やり直す', () => {
    assert.match(result.steps.beforeUndo, /\n\/\/ 1\|C001\|山田 太郎\|GOLD\n/);
    assert.match(result.steps.beforeUndo, / P001 +\|99 +\|\n P001 +\|99 +\|\n/);
    assert.equal(result.steps.afterUndo, result.steps.pasted, '2回でコメント化と複製が取り消される');
    assert.doesNotMatch(result.steps.afterRedo, /\n P001 +\|99 +\|\n P001/, 'やり直すとコメント化だけが戻る');
    assert.match(result.steps.afterRedo, /\n\/\/ 1\|C001/);
  });

  test('列数が合わない行を修正する', () => {
    assert.deepEqual(result.steps.mismatch, { status: 'エラー 1 / 警告 0', missingCells: 2 });
    assert.deepEqual(result.steps.fixed, { status: '✓ 問題なし', text: '[t]\n{a|b|c}\n 1| |\n' });
  });

  test('書式の誤りがあると、テキストのまま誤りを表示する', () => {
    assert.equal(result.steps.fatal.mode, 'text');
    assert.match(result.steps.fatal.issues, /データの前に \{列名1\|列名2\} を記述してください/);
  });
});
