// fixture-editor.html の <script id="core">（FixtureCore）と <script id="schema">（SchemaCore）を取り出して読み込む
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const ROOT = path.resolve(__dirname, '..', '..');
const EDITOR = path.join(ROOT, 'tools', 'fixture-editor.html');

// fixture-editor.html の <script id="..."> を取り出して読み込む
function loadScript(id) {
  const html = fs.readFileSync(EDITOR, 'utf8');
  const match = html.match(new RegExp(`<script id="${id}">([\\s\\S]*?)<\\/script>`));
  if (!match) throw new Error(`<script id="${id}"> が見つかりません: ` + EDITOR);
  // 別のコンテキストで実行すると配列のプロトタイプが変わり、assert.deepStrictEqual で一致しなくなる
  const module = { exports: {} };
  vm.runInThisContext(`(function (module) {${match[1]}\n})`, { filename: `fixture-editor.html#${id}` })(module);
  return module.exports;
}

// FixtureCore（フィクスチャの解析・書き出し・チェック）
const loadCore = () => loadScript('core');

// SchemaCore（DDL の解析、#29）
const loadSchema = () => loadScript('schema');

module.exports = { ROOT, EDITOR, loadCore, loadSchema };
