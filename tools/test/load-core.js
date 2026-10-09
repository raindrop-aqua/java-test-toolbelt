// fixture-editor.html の <script id="core">（FixtureCore）を取り出して読み込む
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const ROOT = path.resolve(__dirname, '..', '..');
const EDITOR = path.join(ROOT, 'tools', 'fixture-editor.html');

function loadCore() {
  const html = fs.readFileSync(EDITOR, 'utf8');
  const match = html.match(/<script id="core">([\s\S]*?)<\/script>/);
  if (!match) throw new Error('<script id="core"> が見つかりません: ' + EDITOR);
  // 別のコンテキストで実行すると配列のプロトタイプが変わり、assert.deepStrictEqual で一致しなくなる
  const module = { exports: {} };
  vm.runInThisContext(`(function (module) {${match[1]}\n})`, { filename: 'fixture-editor.html#core' })(module);
  return module.exports;
}

module.exports = { ROOT, EDITOR, loadCore };
