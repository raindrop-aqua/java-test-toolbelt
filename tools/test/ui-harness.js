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
    result.steps.mismatch = { status: status(), missingCells: document.querySelectorAll('input.missing').length };
    q('#issues [data-fix]').click();
    result.steps.fixed = { status: status(), text: text() };

    // 書式の誤りがあると表に切り替えられない
    $('btnText').click();
    $('text').value = '[t]\n1\n';
    fire($('text'), 'input');
    $('btnGrid').click();
    result.steps.fatal = {
      mode: $('textView').style.display === 'block' ? 'text' : 'grid',
      issues: $('issues').textContent,
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
