// FixtureCore（解析・書き出し・チェック）のテスト。実行: node --test tools/test/
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { ROOT, loadCore } = require('./load-core');

const C = loadCore();

// 行番号を除いたモデル（書き出し方で変わる行番号以外が同じかを比べる）
const model = doc => JSON.parse(JSON.stringify(doc, (k, v) => (k === 'line' || k === 'headerLine' ? undefined : v)));

const parseOk = text => {
  const p = C.parse(text);
  assert.deepEqual(p.fatal, [], '書式の誤りがあります');
  return p.doc;
};

const lintOf = text => C.lint(parseOk(text)).map(i => [i.level, i.r, i.f, i.msg]);

function fixtureFiles(dir) {
  return fs.readdirSync(dir, { withFileTypes: true }).flatMap(e => {
    const p = path.join(dir, e.name);
    return e.isDirectory() ? fixtureFiles(p) : p.endsWith('.txt') ? [p] : [];
  });
}

test.describe('リポジトリのフィクスチャ', () => {
  const files = fixtureFiles(path.join(ROOT, 'src', 'test', 'resources', 'data'));
  // 値の前後にわざと空白を入れてあるファイル。書き出すと空白が取れる
  const padded = new Set(['types.txt']);

  test('フィクスチャがある', () => assert.ok(files.length > 0));

  for (const file of files) {
    const name = path.relative(ROOT, file);
    const text = fs.readFileSync(file, 'utf8');

    test(`${name}: 読み込んで書き出すと元に戻る`, () => {
      const doc = parseOk(text);
      const plain = C.serialize(doc, {});
      if (padded.has(path.basename(file))) assert.deepEqual(model(parseOk(plain)), model(doc));
      else assert.equal(plain, text);
    });

    test(`${name}: 桁揃えしても読み込む内容は変わらない`, () => {
      const doc = parseOk(text);
      const aligned = C.serialize(doc, { align: true });
      assert.deepEqual(model(parseOk(aligned)), model(doc));
      assert.equal(C.serialize(parseOk(aligned), { align: true }), aligned, '桁揃えを2回しても同じになる');
    });

    test(`${name}: エラーも警告も無い`, () => {
      assert.deepEqual(C.lint(parseOk(text)).filter(i => i.level !== 'info'), []);
    });
  }
});

test.describe('解析', () => {
  test('Java の trim() と同じく、全角空白は取り除かない', () => {
    assert.equal(C.jtrim(' \t　a　 '), '　a　');
    const doc = parseOk('[t]\n{a}\n 　x \n');
    assert.deepEqual(doc.sections[0].rows[0].cells, ['　x']);
  });

  test('2文字の記号を先に判定する', () => {
    const doc = parseOk('[t]\n{a<=|b>=|c!=|d<|e>|f%|g@|h}\n1|2|3|4|5|6|7|8\n');
    assert.deepEqual(doc.sections[0].columns.map(c => c.mark), ['<=', '>=', '!=', '<', '>', '%', '@', '']);
  });

  test('パターン番号の列', () => {
    const doc = parseOk('[t]\n{#|a}\n1,2|x\n|y\n');
    const s = doc.sections[0];
    assert.equal(s.hasPattern, true);
    assert.deepEqual(s.rows.map(r => [r.pattern, r.cells]), [['1,2', ['x']], ['', ['y']]]);
    assert.deepEqual(C.allPatterns(doc), [1, 2]);
    assert.equal(C.inPattern(s, s.rows[0], 2), true);
    assert.equal(C.inPattern(s, s.rows[0], 3), false);
    assert.equal(C.inPattern(s, s.rows[1], 3), true, '共通の行はどのパターンにも含まれる');
  });

  test('末尾の空の項目を残す', () => {
    assert.deepEqual(parseOk('[t]\n{a|b}\nx|\n').sections[0].rows[0].cells, ['x', '']);
  });

  test('BOM と CRLF', () => {
    const text = '﻿[t]\r\n{a}\r\n1\r\n';
    assert.equal(C.serialize(parseOk(text), {}), '[t]\r\n{a}\r\n1\r\n');
  });

  test('コメントの置き場所：データの行の直後はそのテーブル、空行の後は次のテーブルの見出し', () => {
    const text = '// file\n\n// t\n[t]\n{a}\n1\n// 2\n\n// 3\n4\n\n// u\n[u]\n{b}\n5\n\n// tail\n';
    const doc = parseOk(text);
    const [t, u] = doc.sections;
    assert.deepEqual(t.comments, ['// file', '', '// t']);
    assert.deepEqual(t.rows.map(r => r.kind), ['data', 'comment', 'blank', 'comment', 'data']);
    assert.deepEqual(u.comments, ['// u']);
    assert.deepEqual(doc.trailing, ['// tail']);
    assert.equal(C.serialize(doc, {}), text);
  });

  test('同じテーブルに列名の行が2つあると、別のテーブルとして扱う（DbJigu と同じ）', () => {
    const doc = parseOk('[t]\n{a}\n1\n{b}\n2\n');
    assert.deepEqual(doc.sections.map(s => [s.name, s.columns[0].name]), [['t', 'a'], ['t', 'b']]);
  });

  test('DbJigu が例外にする構造の誤り', () => {
    const fatal = text => C.parse(text).fatal;
    assert.deepEqual(fatal('[t]\n1\n'), [
      { line: 2, msg: 'データの前に {列名1|列名2} を記述してください' },
      { line: 1, msg: '[t] の後に {列名1|列名2} を記述してください' },
    ]);
    assert.deepEqual(fatal('{a}\n'), [{ line: 1, msg: '列名の前に [テーブル名] を記述してください' }]);
    assert.deepEqual(fatal('[t\n'), [{ line: 1, msg: 'テーブル名は [テーブル名] の形式で記述してください' }]);
    assert.deepEqual(fatal('[ ]\n'), [{ line: 1, msg: 'テーブル名は [テーブル名] の形式で記述してください' }]);
    assert.deepEqual(fatal('[t]\n{a\n'), [{ line: 2, msg: '列名は {列名1|列名2} の形式で記述してください' }]);
    assert.deepEqual(fatal('[t]\n{a}\n1\n[u]\n'), [{ line: 4, msg: '[u] の後に {列名1|列名2} を記述してください' }]);
    // 誤りの後も読み進めるが、連鎖したエラーは出さない
    assert.deepEqual(fatal('[t\n{a}\n1\n'), [{ line: 1, msg: 'テーブル名は [テーブル名] の形式で記述してください' }]);
    assert.deepEqual(fatal('[t]\n{a|b\n1|2\n'), [{ line: 2, msg: '列名は {列名1|列名2} の形式で記述してください' }]);
    assert.equal(C.parse('[t]\n1\n').doc, null);
  });
});

test.describe('書き出し', () => {
  test('桁揃え：全角は幅2、データの行は先頭に空白を1つ入れ、最後の項目は詰めない', () => {
    // 空のパターン番号も、列の幅（# と 1 の幅 1）まで空白で埋める
    const doc = parseOk('[t]\n{#|name|b}\n1|山田|x\n|ab|<null>\n');
    assert.equal(C.serialize(doc, { align: true }), [
      '[t]',
      '{#|name|b}',
      ' 1|山田|x',
      '  |ab  |<null>',
      '',
    ].join('\n'));
  });

  test('表示幅', () => {
    assert.equal(C.displayWidth('山田 太郎'), 9);
    assert.equal(C.displayWidth('ｱｲｳ'), 3, '半角カナは幅1');
    assert.equal(C.displayWidth('Ａ'), 2);
  });
});

test.describe('チェック', () => {
  test('列数と値の数の不一致', () => {
    assert.deepEqual(lintOf('[t]\n{a|b}\n1\n'), [['error', 0, null, '列数(2)と値の数(1)が一致しません']]);
  });

  test('パターン番号が数値ではない', () => {
    assert.deepEqual(lintOf('[t]\n{#|a}\n1,x|1\n'), [['error', 0, 0, 'パターン番号が数値ではありません: 1,x']]);
    assert.deepEqual(lintOf('[t]\n{#|a}\n1,,2|1\n'), [['error', 0, 0, 'パターン番号が数値ではありません: 1,,2']]);
    assert.deepEqual(lintOf('[t]\n{#|a}\n+1, -2|1\n'), [], 'Integer.parseInt と同じく符号を許す');
  });

  test('列名の誤り', () => {
    assert.deepEqual(lintOf('[t]\n{a@<|b}\n1|2\n'), [['error', 'h', 0, '列名の末尾の記号は1つだけ付けられます: a@<']]);
    assert.deepEqual(lintOf('[t]\n{a||b}\n1|2|3\n'), [['error', 'h', 1, '空の列名があります']]);
    assert.deepEqual(lintOf('[t]\n{#}\n1\n')[0], ['error', 'h', null, '列名がありません']);
  });

  test('同じ列が2回あるのは情報（検証の範囲指定では正しい）', () => {
    assert.deepEqual(lintOf('[t]\n{a>=|a<}\n1|2\n'),
      [['info', 'h', 1, '列 a が2回あります。検証の範囲指定には使えますが、投入に使うとエラーになります']]);
  });

  test('表で入力された値の誤り', () => {
    const doc = parseOk('[t]\n{a|b}\n1|2\n');
    const s = doc.sections[0];
    C.setField(s, s.rows[0], 0, '[x');
    C.setField(s, s.rows[0], 1, 'p|q');
    assert.deepEqual(C.lint(doc).map(i => [i.level, i.f, i.msg]), [
      ['error', 1, '値に | は書けません（区切り文字のため）'],
      ['error', 0, '行の先頭が [ { // になる値は書けません（テーブル名・列名・コメントの行として読まれます）'],
    ]);
    C.setField(s, s.rows[0], 0, ' x');
    C.setField(s, s.rows[0], 1, 'y');
    assert.deepEqual(C.lint(doc).map(i => [i.level, i.f, i.msg]), [['warn', 0, '値の前後の空白は取り除かれます']]);
  });

  test('空の値だけの1列の行は空行になる', () => {
    const doc = parseOk('[t]\n{a}\n1\n');
    doc.sections[0].rows[0].cells = [''];
    assert.deepEqual(C.lint(doc).map(i => i.msg), ['空の値だけの行は、空行として無視されます']);
  });

  test('データの行が1行も無い', () => {
    assert.deepEqual(lintOf('[t]\n{a}\n'), [
      ['info', 'h', null, 'データの行がありません'],
      ['warn', null, null, 'データの行が1行もありません（検証に使うとエラーになります）'],
    ]);
  });
});

test.describe('TSV', () => {
  test('Excel の引用符付きの値を読む', () => {
    assert.deepEqual(C.parseTsv('a\tb\n"x\ty"\t"q""z"\r\n\t3\n'), [['a', 'b'], ['x\ty', 'q"z'], ['', '3']]);
  });

  test('TSV に書き出して読み直すと同じ', () => {
    const doc = parseOk('[t]\n{#|a|b@}\n1|x|<null>\n|y|\n');
    assert.deepEqual(C.parseTsv(C.toTsv(doc.sections[0])), [['#', 'a', 'b@'], ['1', 'x', '<null>'], ['', 'y', '']]);
  });
});
