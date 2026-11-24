'use strict';
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const script = fs.readFileSync(path.resolve(__dirname, '../../main/assets/jyu_login_metadata.js'), 'utf8');
const origin = 'https://https-jwcjwxt-jyu-edu-cn-443.webvpn.jyu.cn';
function page({ year = '', term = '', date = '', password = false, frames = [], links = [], site = origin } = {}) {
  const win = { location: { origin: site, href: site + '/' }, document: {
    querySelector(selector) {
      if (selector.startsWith('#xnm')) return year ? { value: year } : null;
      if (selector.startsWith('#xqm')) return term ? { value: term } : null;
      if (selector.startsWith('#xqksrq')) return date ? { value: date, getAttribute() { return ''; } } : null;
      if (selector === 'input[type="password"]') return password ? {} : null;
      return null;
    },
    querySelectorAll(selector) { return selector === 'iframe' ? frames.map(contentWindow => ({ contentWindow })) : links; }
  }};
  return win;
}
function read(win) { return JSON.parse(vm.runInNewContext(script, { window: win, location: win.location, URL })); }
assert.deepEqual(read(page()), {});
assert.deepEqual(read(page({ year: '2025', term: '12' })), { year: '2025', term: '12', username: '', startDate: '' });
assert.deepEqual(read(page({ year: '2026', term: '3', date: '2026-09-07' })), { year: '2026', term: '3', username: '', startDate: '2026-09-07' });
assert.deepEqual(read(page({ year: '2026', term: '1' })), {});
assert.deepEqual(read(page({ year: '2026', term: '3', site: 'https://webvpn.jyu.cn' })), {});
assert.deepEqual(read(page({ frames: [page({ year: '2025', term: '12' })] })), { year: '2025', term: '12', username: '', startDate: '' });
assert.deepEqual(read(page({ frames: [page({ year: '2025', term: '12', site: 'https://example.com' })] })), {});
let clicks = 0;
const link = href => ({ getAttribute(name) { return name === 'href' ? href : ''; }, click() { clicks++; } });
const home = page({ links: [link('https://example.com/kbcx/xskbcx.html'), link('/kbcx/xskbcx_cxXskbcxIndex.html')] });
read(home); read(home);
assert.equal(clicks, 1);
read(page({ password: true, links: [link('/kbcx/xskbcx_cxXskbcxIndex.html')] }));
assert.equal(clicks, 1);
assert.deepEqual(read(page({ year: '2026', term: '3', password: true })), {});
console.log('嘉应教务学期读取与导航：10 项断言通过');
