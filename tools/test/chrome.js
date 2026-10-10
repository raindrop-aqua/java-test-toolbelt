// 画面のテストで使う、ヘッドレスの Chrome の起動と操作
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { spawn } = require('node:child_process');

// Chrome の場所。環境変数 CHROME_PATH でも指定できる
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

function chromeArgs(profileDir, ...rest) {
  const args = ['--headless=new', '--disable-gpu', '--no-first-run', `--user-data-dir=${profileDir}`, ...rest];
  if (process.platform === 'linux') args.unshift('--no-sandbox');
  return args;
}

// Chrome DevTools Protocol でページを開く。IME の入力など、--dump-dom ではできない操作に使う
async function openPage(chrome, url) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'fixture-editor-cdp-'));
  const proc = spawn(chrome, chromeArgs(dir, '--remote-debugging-port=0', 'about:blank'), { stdio: ['ignore', 'ignore', 'pipe'] });
  const exited = new Promise(resolve => proc.once('exit', resolve));
  // Chrome を終了させてから、一時的なプロファイルを消す。Linux では Chrome の終了後も子プロセスが
  // 書き込んでいることがあり（ENOTEMPTY）、消せなくてもテストの結果には関係しないので、失敗は無視する
  const cleanup = async () => {
    if (proc.exitCode === null && proc.signalCode === null) {
      proc.kill();
      await Promise.race([exited, new Promise(resolve => setTimeout(resolve, 5000))]);
    }
    try {
      fs.rmSync(dir, { recursive: true, force: true, maxRetries: 10, retryDelay: 200 });
    } catch (e) {
      // 一時フォルダなので、残っても OS が片付ける
    }
  };
  try {
    // 起動時に標準エラーへ出力される、接続先のポートを読む
    const port = await new Promise((resolve, reject) => {
      let err = '';
      const timer = setTimeout(() => reject(new Error('Chrome が起動しません: ' + err)), 30000);
      proc.stderr.on('data', chunk => {
        err += chunk;
        const m = err.match(/DevTools listening on ws:\/\/[^:]+:(\d+)\//);
        if (m) {
          clearTimeout(timer);
          resolve(m[1]);
        }
      });
      proc.on('exit', code => reject(new Error(`Chrome が終了しました（${code}）: ${err}`)));
    });
    const targets = await (await fetch(`http://127.0.0.1:${port}/json/list`)).json();
    const target = targets.find(t => t.type === 'page');
    const ws = new WebSocket(target.webSocketDebuggerUrl);
    await new Promise((resolve, reject) => {
      ws.addEventListener('open', resolve);
      ws.addEventListener('error', reject);
    });

    let lastId = 0;
    const pending = new Map();
    const waiters = [];
    ws.addEventListener('message', m => {
      const data = JSON.parse(m.data);
      if (data.id && pending.has(data.id)) {
        const { resolve, reject } = pending.get(data.id);
        pending.delete(data.id);
        if (data.error) reject(new Error(`${data.error.message}`));
        else resolve(data.result);
      } else if (data.method) {
        for (const w of waiters.filter(w => w.method === data.method)) {
          waiters.splice(waiters.indexOf(w), 1);
          w.resolve(data.params);
        }
      }
    });
    const send = (method, params = {}) => new Promise((resolve, reject) => {
      const id = ++lastId;
      pending.set(id, { resolve, reject });
      ws.send(JSON.stringify({ id, method, params }));
    });
    const waitFor = method => new Promise(resolve => waiters.push({ method, resolve }));

    // ページの中で式を評価して、値を返す。例外はテストの失敗にする
    const evaluate = async expression => {
      const r = await send('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true });
      if (r.exceptionDetails) throw new Error('ページでの評価に失敗しました: ' + JSON.stringify(r.exceptionDetails));
      return r.result.value;
    };

    await send('Page.enable');
    const loaded = waitFor('Page.loadEventFired');
    await send('Page.navigate', { url });
    await loaded;

    return {
      send,
      evaluate,
      async close() {
        // まず Chrome に自分で終了してもらう（プロファイルへの書き込みを終えてから終了する）
        await Promise.race([send('Browser.close').catch(() => {}), new Promise(resolve => setTimeout(resolve, 3000))]);
        await Promise.race([exited, new Promise(resolve => setTimeout(resolve, 5000))]);
        ws.close();
        await cleanup();
      },
    };
  } catch (e) {
    await cleanup();
    throw e;
  }
}

module.exports = { findChrome, chromeArgs, openPage };
