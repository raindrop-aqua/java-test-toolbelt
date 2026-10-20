// エディタの画面をヘッドレスの Chrome で操作するテスト。Chrome が無ければスキップする。
// Chrome の場所は環境変数 CHROME_PATH でも指定できる
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { execFileSync } = require('node:child_process');
const { EDITOR } = require('./load-core');
const { findChrome, chromeArgs } = require('./chrome');

// ハーネスを埋め込んだページを開き、操作の結果を受け取る
function runHarness(chrome) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'fixture-editor-'));
  try {
    const harness = fs.readFileSync(path.join(__dirname, 'ui-harness.js'), 'utf8');
    const html = fs.readFileSync(EDITOR, 'utf8').replace('</body>', `<script>${harness}</script></body>`);
    const page = path.join(dir, 'page.html');
    fs.writeFileSync(page, html);
    const args = chromeArgs(path.join(dir, 'profile'), '--virtual-time-budget=3000', '--dump-dom', 'file://' + page);
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

  test('テーブルの一覧を、ファイルの順に行数とともに表示する', () => {
    assert.deepEqual(result.steps.tables, [['1.', 'customer', '', '2 行'], ['2.', 'product', '', '1 行']]);
  });

  test('行・列の操作ボタンの表示を切り替える（既定は表示。行番号は常に表示）', () => {
    const visible = { checked: true, rowTools: 'inline-flex', colTools: 'flex', lineNumber: 'inline-block' };
    assert.deepEqual(result.steps.tools, {
      initial: visible,
      off: { checked: false, rowTools: 'none', colTools: 'none', lineNumber: 'inline-block' },
      on: visible,
    });
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
    assert.deepEqual(result.steps.filter, {
      options: ['', '1', '2', '3'],
      dimRows: 2,
      // 絞り込み中は、そのパターンで使う行（共通の行を含む）の数 / すべての行の数
      tables: [['1.', 'customer', '', '1 / 3 行'], ['2.', 'product', '', '2 / 2 行']],
    });
  });

  test('元に戻す・やり直す', () => {
    assert.match(result.steps.beforeUndo, /\n\/\/ 1\|C001\|山田 太郎\|GOLD\n/);
    assert.match(result.steps.beforeUndo, / P001 +\|99 +\|\n P001 +\|99 +\|\n/);
    assert.equal(result.steps.afterUndo, result.steps.pasted, '2回でコメント化と複製が取り消される');
    assert.doesNotMatch(result.steps.afterRedo, /\n P001 +\|99 +\|\n P001/, 'やり直すとコメント化だけが戻る');
    assert.match(result.steps.afterRedo, /\n\/\/ 1\|C001/);
  });

  test('列数が合わない行を修正する', () => {
    assert.deepEqual(result.steps.mismatch, {
      status: 'エラー 1 / 警告 0',
      missingCells: 2,
      tables: [['1.', 't', 'エラー 1', '1 行']],
    });
    assert.deepEqual(result.steps.fixed, { status: '✓ 問題なし', text: '[t]\n{a|b|c}\n 1| |\n' });
  });

  test('パターン番号の名前で絞り込む', () => {
    assert.deepEqual(result.steps.namedFilter, {
      options: [['', 'すべて'], ['2', '02'], ['gold', 'Gold'], ['silver', 'SILVER']],
      dimRows: 1,
    });
    assert.equal(result.steps.namedAddRow, '[t]\n{#          |a}\n Gold       |1\n gold,SILVER|2\n            |3\n 02         |4\n Gold       |\n');
  });

  test('列名の下に型を表示し、チェックボックスで切り替える', () => {
    assert.deepEqual(result.steps.types, { texts: ['VARCHAR(10) PK', 'VARCHAR(10) NN', 'VARCHAR(10) NN'], shown: 'block', hidden: 'none' });
  });

  test('列名の候補は、まだ書いていない列を型とともに出し、選ぶと型の行も変わる', () => {
    assert.deepEqual(result.steps.columnSuggest, ['created_atTIMESTAMP NN', 'noteVARCHAR(100)']);
    assert.deepEqual(result.steps.columnChosen, {
      name: 'created_at',
      types: ['VARCHAR(10) PK', 'VARCHAR(10) NN', 'VARCHAR(10) NN', 'TIMESTAMP NN'],
    });
  });

  test('値が列の型に合わなければ、セルを警告の色にする', () => {
    assert.deepEqual(result.steps.valueType, { cell: true, title: '列 created_at（TIMESTAMP）：存在しない日付です' });
  });

  test('テキスト表示では、一覧のクリックでテーブル名の行を選択する', () => {
    assert.equal(result.steps.textJump, '[b]');
  });

  test('DDL を貼り付けて登録する（#29）', () => {
    assert.deepEqual(result.steps.schemaLoaded, { summary: '貼り付け', counts: '2 テーブル・無視した文 1 件' });
  });

  test('スキーマタブ：入力中のテーブルの列を、ファイルにあるか・必須か・今の列かとともに表示する', () => {
    assert.deepEqual(result.steps.schemaView, {
      head: 'orders',
      // 親の customer がファイルで後ろにある
      fk: ['FK (customer_id) → customer（先に投入）'],
      cols: [
        '✓ order_no VARCHAR(10) PK',
        '⚠ customer_id VARCHAR(10) NN',
        '▶ status VARCHAR(10) NN',
        '· note VARCHAR(100)',
        // 既定値があるので必須ではない
        '· created_at TIMESTAMP NN',
      ],
      notes: ['列 memo はスキーマにありません。'],
    });
  });

  test('スキーマと照らし合わせて、投入の順序、スキーマに無い列、無い必須の列を警告する', () => {
    assert.deepEqual(result.steps.schemaIssues, [
      '警告L1 [orders] 外部キーの親 customer がこのテーブルより後にあります（投入すると失敗します。検証用のファイルなら問題ありません）',
      '警告L2 [orders] 列 memo は orders のスキーマにありません',
      '警告L2 [orders] 必須の列 customer_id がありません（投入すると失敗します。検証用のファイルなら問題ありません）',
      '警告L7 [customer] 必須の列 customer_name がありません（投入すると失敗します。検証用のファイルなら問題ありません）',
    ]);
  });

  test('スキーマタブ：列のクリックで表のその列へ、外部キーのクリックで親の定義へ移動する', () => {
    assert.deepEqual(result.steps.schemaJump, { s: '0', r: '1', f: '0' });
    assert.equal(result.steps.schemaParent, 'customer');
  });

  test('スキーマタブ：列名でも検索でき、追従を止められ、無いテーブル名には近い名前を示す', () => {
    assert.deepEqual(result.steps.schemaSearch, ['customercustomer_id', 'orderscustomer_id']);
    assert.equal(result.steps.schemaPinned, 'orders', '追従を止めると表示は変わらない');
    assert.equal(result.steps.schemaFollow, 'customer', '追従を再開すると入力中のテーブルを表示する');
    assert.equal(result.steps.schemaUnknown, 'スキーマにありません。近い名前：customer');
  });

  test('テーブル名の候補を選び、必須の列だけを展開する', () => {
    assert.deepEqual(result.steps.suggest, ['orders5 列']);
    assert.deepEqual(result.steps.expandChoices, ['全列 5', '必須の列だけ 3', '主キーだけ 1', '展開しない']);
    assert.match(result.steps.expanded, /\n\n\[orders\]\n\{order_no\|customer_id\|status\}\n {9}\| {11}\|\n$/);
  });

  test('書式の誤りがあると、テキストのまま誤りを表示する', () => {
    assert.equal(result.steps.fatal.mode, 'text');
    assert.match(result.steps.fatal.issues, /データの前に \{列名1\|列名2\} を記述してください/);
    assert.equal(result.steps.fatal.tables, '書式の誤りがあるため、表示できません');
  });
});
