// SchemaCore（DDL の解析、#29）のテスト。実行: node --test tools/test/
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { ROOT, loadSchema } = require('./load-core');

const S = loadSchema();

const tableOf = (ddl, name) => S.parseDdl(ddl).tables.find(t => t.name === name);
// 列の要約：[名前, 型, 'PK' / 'NN' / '', 既定値があるか]
const columnsOf = table => table.columns.map(c => [c.name, c.type, c.pk ? 'PK' : c.notNull ? 'NN' : '', c.hasDefault]);

test.describe('リポジトリの DDL', () => {
  const dir = path.join(ROOT, 'src', 'test', 'resources', 'schema');
  for (const file of fs.readdirSync(dir).filter(f => f.endsWith('.sql'))) {
    test(`${file}: すべての文を CREATE TABLE として読める`, () => {
      const r = S.parseDdl(fs.readFileSync(path.join(dir, file), 'utf8'));
      assert.ok(r.tables.length > 0);
      assert.deepEqual(r.ignored, []);
    });
  }

  test('order.sql：主キー（列と表）、NOT NULL、外部キー', () => {
    const r = S.parseDdl(fs.readFileSync(path.join(dir, 'order.sql'), 'utf8'));
    assert.deepEqual(r.tables.map(t => t.name), ['customer', 'product', 'orders', 'order_item']);
    const item = r.tables[3];
    assert.deepEqual(columnsOf(item), [
      ['order_no', 'VARCHAR(10)', 'PK', false],
      ['line_no', 'INTEGER', 'PK', false],
      ['product_code', 'VARCHAR(10)', 'NN', false],
      ['quantity', 'INTEGER', 'NN', false],
      ['amount', 'NUMERIC(10)', 'NN', false],
    ]);
    assert.deepEqual(item.fks, [
      { columns: ['order_no'], ref: { table: 'orders', columns: ['order_no'] } },
      { columns: ['product_code'], ref: { table: 'product', columns: ['product_code'] } },
    ]);
  });

  test('postgresql.sql：IDENTITY の列は既定値がある列として扱う', () => {
    const r = S.parseDdl(fs.readFileSync(path.join(dir, 'postgresql.sql'), 'utf8'));
    const member = r.tables.find(t => t.name === 'Member');
    assert.deepEqual(columnsOf(member), [
      ['id', 'INTEGER', 'PK', true],
      ['MemberId', 'NUMERIC(10)', '', false],
      ['MemberName', 'VARCHAR(30)', '', false],
    ]);
    assert.equal(r.tables[0].columns.find(c => c.name === 'double_column').type, 'DOUBLE PRECISION');
  });
});

test.describe('DDL の解析', () => {
  test('Oracle の書き方：VARCHAR2、NUMBER、引用符付きの名前、スキーマ名、CONSTRAINT 名、/ の区切り', () => {
    const ddl = [
      'CREATE TABLE app."Order_Header" (',
      '  "Order No"  VARCHAR2(10 CHAR) CONSTRAINT pk_oh PRIMARY KEY,',
      '  amount      NUMBER(10, 2) DEFAULT 0 NOT NULL,',
      '  created_at  TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP,',
      '  customer_id NUMBER(10),',
      '  CONSTRAINT fk_oh_customer FOREIGN KEY (customer_id) REFERENCES app.customer (id)',
      ')',
      '/',
      'CREATE GLOBAL TEMPORARY TABLE tmp (id NUMBER) ON COMMIT DELETE ROWS;',
    ].join('\n');
    const r = S.parseDdl(ddl);
    assert.deepEqual(r.ignored, []);
    assert.deepEqual(r.tables.map(t => t.name), ['Order_Header', 'tmp']);
    assert.deepEqual(columnsOf(r.tables[0]), [
      ['Order No', 'VARCHAR2(10 CHAR)', 'PK', false],
      ['amount', 'NUMBER(10,2)', 'NN', true],
      ['created_at', 'TIMESTAMP WITH TIME ZONE', '', true],
      ['customer_id', 'NUMBER(10)', '', false],
    ]);
    assert.deepEqual(r.tables[0].fks, [{ columns: ['customer_id'], ref: { table: 'customer', columns: ['id'] } }]);
  });

  test('コメントと文字列の中の ; や括弧では区切らない', () => {
    const ddl = [
      '/* 顧客; 先頭のコメント ( */',
      'CREATE TABLE IF NOT EXISTS t (',
      "  a VARCHAR(10) DEFAULT 'x;y)' NOT NULL, -- 説明; ( ",
      "  b TEXT CHECK (b <> 'it''s;'),",
      '  c INTEGER',
      ');',
    ].join('\n');
    assert.deepEqual(columnsOf(tableOf(ddl, 't')), [
      ['a', 'VARCHAR(10)', 'NN', true],
      ['b', 'TEXT', '', false],
      ['c', 'INTEGER', '', false],
    ]);
  });

  test('DEFAULT NULL は既定値が無いのと同じ。SERIAL と生成列は既定値がある列として扱う', () => {
    const ddl = 'CREATE TABLE t (a INT DEFAULT NULL NOT NULL, b SERIAL PRIMARY KEY, c INT GENERATED ALWAYS AS (a * 2) STORED);';
    assert.deepEqual(columnsOf(tableOf(ddl, 't')), [
      ['a', 'INT', 'NN', false],
      ['b', 'SERIAL', 'PK', true],
      ['c', 'INT', '', true],
    ]);
  });

  test('表に書く UNIQUE・CHECK は列として読まない', () => {
    const ddl = 'CREATE TABLE t (a INT, b INT, UNIQUE (a, b), CONSTRAINT ck CHECK (a > 0), PRIMARY KEY (a));';
    assert.deepEqual(columnsOf(tableOf(ddl, 't')), [['a', 'INT', 'PK', false], ['b', 'INT', '', false]]);
  });

  test('CREATE TABLE 以外の文は、行番号と先頭の部分を返す', () => {
    const ddl = [
      'CREATE TABLE a (id INT);',
      'CREATE INDEX ix_a ON a (id);',
      '',
      "INSERT INTO a VALUES (1); CREATE TABLE b AS SELECT * FROM a;",
      'CREATE FUNCTION f() RETURNS INT AS $$ BEGIN RETURN 1; END; $$ LANGUAGE plpgsql;',
      'ALTER TABLE a ADD COLUMN name VARCHAR(10);',
    ].join('\n');
    const r = S.parseDdl(ddl);
    assert.deepEqual(r.tables.map(t => t.name), ['a']);
    assert.deepEqual(r.ignored, [
      { line: 2, text: 'CREATE INDEX ix_a ON a (id)' },
      { line: 4, text: 'INSERT INTO a VALUES (1)' },
      { line: 4, text: 'CREATE TABLE b AS SELECT * FROM a' },
      { line: 5, text: 'CREATE FUNCTION f() RETURNS INT AS $$ BEGIN RETURN 1; END; $…' },
      { line: 6, text: 'ALTER TABLE a ADD COLUMN name VARCHAR(10)' },
    ]);
  });
});

test.describe('スキーマ', () => {
  const schema = S.buildSchema([
    { name: 'a.sql', text: 'CREATE TABLE orders (id INT PRIMARY KEY, note TEXT); CREATE INDEX ix ON orders (id);' },
    { name: 'b.sql', text: 'CREATE TABLE Orders (id INT PRIMARY KEY, status VARCHAR(10) NOT NULL, memo TEXT, ts TIMESTAMP DEFAULT now() NOT NULL);' },
  ]);

  test('同じテーブル名は、後から読んだものを使う（大文字と小文字は区別しない）', () => {
    const t = S.findTable(schema, 'ORDERS');
    assert.equal(t.file, 'b.sql');
    assert.deepEqual(t.columns.map(c => c.name), ['id', 'status', 'memo', 'ts']);
    assert.equal(schema.tables.size, 1);
    assert.deepEqual(schema.ignored, [{ file: 'a.sql', line: 1, text: 'CREATE INDEX ix ON orders (id)' }]);
    assert.equal(S.findColumn(t, 'STATUS').name, 'status');
    assert.equal(S.findColumn(t, 'nothing'), null);
  });

  test('列の展開：全列・必須の列（主キーと、NOT NULL で既定値の無い列）・主キー・展開しない', () => {
    const t = S.findTable(schema, 'orders');
    assert.deepEqual(S.expandColumns(t, 'all'), ['id', 'status', 'memo', 'ts']);
    assert.deepEqual(S.expandColumns(t, 'required'), ['id', 'status']);
    assert.deepEqual(S.expandColumns(t, 'pk'), ['id']);
    assert.deepEqual(S.expandColumns(t, 'none'), []);
  });

  test('列の表示', () => {
    const t = S.findTable(schema, 'orders');
    assert.deepEqual(t.columns.map(S.columnLabel), ['INT PK', 'VARCHAR(10) NN', 'TEXT', 'TIMESTAMP NN']);
  });

  test('候補：前方一致を先に、途中の一致を後に並べる', () => {
    const names = ['customer_order', 'order_item', 'Orders', 'product'];
    assert.deepEqual(S.suggest('ord', names), ['order_item', 'Orders', 'customer_order']);
    assert.deepEqual(S.suggest('', names), ['customer_order', 'order_item', 'Orders', 'product']);
    assert.deepEqual(S.suggest('xyz', names), []);
  });

  test('つづりの近い名前', () => {
    const names = ['customer', 'customer_rank', 'orders', 'order_item', 'product'];
    assert.deepEqual(S.nearNames('custmer', names), ['customer']);
    assert.deepEqual(S.nearNames('order', names), ['orders', 'order_item']);
    assert.deepEqual(S.nearNames('zzz', names), []);
  });
});
