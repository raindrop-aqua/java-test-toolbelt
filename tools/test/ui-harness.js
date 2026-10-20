// ui.test.js がエディタのページに埋め込んで実行する、ブラウザ側の操作。
// 結果は JSON にして、id が result の pre 要素に書き出す
(() => {
  const result = { errors: [], steps: {} };
  window.addEventListener('error', e => result.errors.push(`${e.message} (${e.lineno})`));
  const $ = id => document.getElementById(id);
  const q = sel => {
    const el = document.querySelector(sel);
    if (!el) throw new Error('要素がありません: ' + sel);
    return el;
  };
  const fire = (el, type) => el.dispatchEvent(new Event(type, { bubbles: true }));
  // 利用者の入力と同じく、focusin → input → change の順にイベントを起こす
  const type = (sel, value) => {
    const el = q(sel);
    el.dispatchEvent(new FocusEvent('focusin', { bubbles: true }));
    el.value = value;
    fire(el, 'input');
    fire(el, 'change');
  };
  const cell = (s, r, f) => `input.cell[data-s="${s}"][data-r="${r}"][data-f="${f}"]`;
  const text = () => {
    $('btnText').click();
    const value = $('text').value;
    $('btnGrid').click();
    return value;
  };
  const status = () => $('status').textContent;
  // テーブルの一覧（番号・名前・エラーと警告・行数）
  const tableList = () => [...document.querySelectorAll('#tables button')]
    .map(b => [...b.children].map(c => c.textContent));

  try {
    // テキストで読み込んで表に切り替える
    $('btnText').click();
    $('text').value = [
      '// 顧客',
      '[customer]',
      '{#|customer_id|customer_name|customer_rank}',
      '1|C001|山田 太郎|GOLD',
      '2|C001|山田 太郎|SILVER',
      '',
      '[product]',
      '{product_code|stock}',
      'P001|10',
      '',
    ].join('\n');
    fire($('text'), 'input');
    $('btnGrid').click();
    result.steps.loaded = { sections: document.querySelectorAll('section.sec').length, status: status() };
    result.steps.tables = tableList();

    // 行・列の操作ボタンの表示の切り替え（行番号は常に表示する）
    const shown = () => ({
      checked: $('chkTools').checked,
      rowTools: getComputedStyle(q('.rtools')).display,
      colTools: getComputedStyle(q('.coltools')).display,
      lineNumber: getComputedStyle(q('tbody .rh .ln')).display,
    });
    const toggleTools = () => {
      $('chkTools').click();
      return shown();
    };
    result.steps.tools = { initial: shown(), off: toggleTools(), on: toggleTools() };

    // セルの編集と行の追加
    type(cell(1, 0, 1), '99');
    q('section[data-s="1"] [data-act="sec-addrow"]').click();
    type(cell(1, 1, 0), 'P002');
    type(cell(1, 1, 1), '5');

    // Alt+N で NULL にする
    q(cell(0, 1, 3)).dispatchEvent(new KeyboardEvent('keydown', { bubbles: true, altKey: true, code: 'KeyN', key: 'n' }));
    result.steps.altN = q(cell(0, 1, 3)).value;

    // 列を追加すると、列名が空なのでエラーになる。名前の末尾の記号は記号の選択に移る
    q('section[data-s="1"] [data-act="sec-addcol"]').click();
    result.steps.addColumn = status();
    type('input.cname[data-s="1"][data-f="2"]', 'updated_at@');
    result.steps.columnMark = {
      name: q('input.cname[data-s="1"][data-f="2"]').value,
      mark: q('select.mark[data-s="1"][data-f="2"]').value,
    };

    result.steps.edited = text();

    // Excel からの貼り付け（2行目から上書きし、足りない行は追加する）
    const data = new DataTransfer();
    data.setData('text/plain', '3\tC002\t鈴木\tREGULAR\n1,2\tC003\t佐藤\tGOLD\n');
    q(cell(0, 1, 0)).dispatchEvent(new ClipboardEvent('paste', { bubbles: true, cancelable: true, clipboardData: data }));
    result.steps.pasted = text();

    // パターンで絞り込む
    $('selFilter').value = '2';
    fire($('selFilter'), 'change');
    result.steps.filter = {
      options: [...$('selFilter').options].map(o => o.value),
      dimRows: document.querySelectorAll('tr.dim').length,
      tables: tableList(),
    };
    $('selFilter').value = '';
    fire($('selFilter'), 'change');

    // 行をコメントにし、複製し、2回元に戻すと複製だけが取り消される
    q('section[data-s="0"] tr[data-r="0"] [data-act="row-toggle"]').click();
    q('section[data-s="1"] tr[data-r="0"] [data-act="row-dup"]').click();
    result.steps.beforeUndo = text();
    $('btnUndo').click();
    $('btnUndo').click();
    result.steps.afterUndo = text();
    $('btnRedo').click();
    result.steps.afterRedo = text();

    // 列数が合わない行と、修正ボタン
    $('btnText').click();
    $('text').value = '[t]\n{a|b|c}\n1\n';
    fire($('text'), 'input');
    $('btnGrid').click();
    result.steps.mismatch = { status: status(), missingCells: document.querySelectorAll('input.missing').length, tables: tableList() };
    q('#issues [data-fix]').click();
    result.steps.fixed = { status: status(), text: text() };

    // パターン番号の名前で絞り込む（大文字と小文字は区別しない）。絞り込み中に追加した行には、そのパターンが入る
    $('btnText').click();
    $('text').value = '[t]\n{#|a}\nGold|1\ngold,SILVER|2\n|3\n02|4\n';
    fire($('text'), 'input');
    $('btnGrid').click();
    $('selFilter').value = 'gold';
    fire($('selFilter'), 'change');
    result.steps.namedFilter = {
      options: [...$('selFilter').options].map(o => [o.value, o.textContent]),
      dimRows: document.querySelectorAll('tr.dim').length,
    };
    q('section[data-s="0"] .add-row').click();
    result.steps.namedAddRow = text();
    $('selFilter').value = '';
    fire($('selFilter'), 'change');

    // テキスト表示では、一覧のクリックでテーブル名の行を選択する
    $('btnText').click();
    $('text').value = '[a]\n{x}\n1\n\n[b]\n{y}\n2\n';
    // 入力のチェックは遅れて行われるので、整形でその場でチェックして一覧を作り直す
    $('btnFormat').click();
    q('#tables button[data-s="1"]').click();
    const ta = $('text');
    result.steps.textJump = ta.value.slice(ta.selectionStart, ta.selectionEnd);
    $('btnGrid').click();

    // DDL を貼り付けて登録し、スキーマタブで入力中のテーブルの定義を見る（#29）
    $('btnSchemaPaste').click();
    $('schemaText').value = [
      'CREATE TABLE customer (customer_id VARCHAR(10) PRIMARY KEY, customer_name VARCHAR(50) NOT NULL);',
      'CREATE TABLE orders (',
      '  order_no VARCHAR(10) PRIMARY KEY,',
      '  customer_id VARCHAR(10) NOT NULL REFERENCES customer (customer_id),',
      '  status VARCHAR(10) NOT NULL,',
      '  note VARCHAR(100),',
      '  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL',
      ');',
      'CREATE INDEX ix_orders ON orders (status);',
    ].join('\n');
    $('btnSchemaPasteOk').click();
    result.steps.schemaLoaded = { summary: $('schemaSummary').textContent, counts: $('schemaCounts').textContent };
    $('btnText').click();
    $('text').value = '[orders]\n{order_no|status|memo}\nA1|NEW|x\nA2|NEW|y\n\n[customer]\n{customer_id}\nC1\n';
    fire($('text'), 'input');
    $('btnGrid').click();
    const focusCell = sel => q(sel).dispatchEvent(new FocusEvent('focusin', { bubbles: true }));
    const schemaView = () => ({
      head: q('#schemaView .tbl').textContent,
      fk: [...document.querySelectorAll('#schemaView .fk')].map(e => e.textContent),
      cols: [...document.querySelectorAll('#schemaView .schema-cols li')].map(li => `${li.querySelector('.st').textContent} ${li.dataset.col} ${li.querySelector('.ty').textContent}`),
      notes: [...document.querySelectorAll('#schemaView .schema-note')].map(e => e.textContent),
    });
    focusCell(cell(0, 1, 1));
    result.steps.schemaView = schemaView();
    // スキーマとのチェック（スキーマに無い列、必須の列）
    result.steps.schemaIssues = [...document.querySelectorAll('#issues li')].map(li => li.textContent);
    // パネルの列をクリックすると、表のその列（入力中の行）へ移動する
    q('#schemaView li[data-col="order_no"]').click();
    result.steps.schemaJump = { s: document.activeElement.dataset.s, r: document.activeElement.dataset.r, f: document.activeElement.dataset.f };
    // 外部キーの親をクリックすると、その定義を表示する
    q('#schemaView [data-show="customer"]').click();
    result.steps.schemaParent = q('#schemaView .tbl').textContent;
    // 列名でも検索できる
    $('schemaSearch').value = 'customer_id';
    fire($('schemaSearch'), 'input');
    result.steps.schemaSearch = [...document.querySelectorAll('#schemaView .schema-results li')].map(li => li.textContent);
    $('schemaSearch').value = '';
    fire($('schemaSearch'), 'input');
    // 追従を止めると、別のテーブルにフォーカスしても表示は変わらない
    focusCell(cell(0, 0, 0));
    q('#schemaView [data-act="follow"]').click();
    focusCell(cell(1, 0, 0));
    result.steps.schemaPinned = q('#schemaView .tbl').textContent;
    q('#schemaView [data-act="follow"]').click();
    result.steps.schemaFollow = q('#schemaView .tbl').textContent;
    // スキーマに無いテーブル名は、近い名前を示す
    type('input.tname[data-s="1"]', 'custmer');
    result.steps.schemaUnknown = q('#schemaView .schema-note').textContent;
    type('input.tname[data-s="1"]', 'customer');

    // テーブルを追加して名前を入力すると候補が出て、選ぶと列の展開のしかたを選べる
    q('[data-act="sec-add"]').click();
    type('input.tname[data-s="2"]', 'ord');
    const tname = q('input.tname[data-s="2"]');
    result.steps.suggest = [...document.querySelectorAll('#suggest .opt')].map(o => o.textContent);
    tname.dispatchEvent(new KeyboardEvent('keydown', { bubbles: true, key: 'Enter' }));
    result.steps.expandChoices = [...document.querySelectorAll('#suggest [data-how]')].map(b => b.textContent);
    q('#suggest [data-how="required"]').click();
    result.steps.expanded = text();

    // 列名の下に型を表示し、チェックボックスで切り替える
    const types = () => [...document.querySelectorAll('section[data-s="2"] .coltype')].map(e => e.textContent);
    const typesShown = () => getComputedStyle(q('section[data-s="2"] .coltype')).display;
    result.steps.types = { texts: types(), shown: typesShown() };
    $('chkTypes').click();
    result.steps.types.hidden = typesShown();
    $('chkTypes').click();

    // 列名の候補は、まだ書いていない列だけを、型とともに出す。選ぶと型の行も変わる
    q('section[data-s="2"] [data-act="sec-addcol"]').click();
    type('input.cname[data-s="2"][data-f="3"]', '');
    result.steps.columnSuggest = [...document.querySelectorAll('#suggest .opt')].map(o => o.textContent);
    type('input.cname[data-s="2"][data-f="3"]', 'cr');
    q('input.cname[data-s="2"][data-f="3"]').dispatchEvent(new KeyboardEvent('keydown', { bubbles: true, key: 'Enter' }));
    result.steps.columnChosen = { name: q('input.cname[data-s="2"][data-f="3"]').value, types: types() };

    // 値が列の型に合わなければ、セルを警告の色にする（表示を切り替えてチェックをすぐに行う）
    type(cell(2, 0, 3), '2024-13-01');
    text();
    result.steps.valueType = {
      cell: q(cell(2, 0, 3)).classList.contains('warn-cell'),
      title: q(cell(2, 0, 3)).title,
    };

    // 書式の誤りがあると表に切り替えられない
    $('btnText').click();
    $('text').value = '[t]\n1\n';
    fire($('text'), 'input');
    $('btnGrid').click();
    result.steps.fatal = {
      mode: $('textView').style.display === 'none' ? 'grid' : 'text',
      issues: $('issues').textContent,
      tables: $('tables').textContent,
    };
  } catch (e) {
    result.errors.push(e.stack || String(e));
  }

  // 遅延して実行するチェック（setTimeout）を待ってから書き出す
  setTimeout(() => {
    const pre = document.createElement('pre');
    pre.id = 'result';
    pre.textContent = JSON.stringify(result);
    document.body.appendChild(pre);
  }, 500);
})();
