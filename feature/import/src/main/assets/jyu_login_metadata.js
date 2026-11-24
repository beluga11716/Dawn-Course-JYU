/* 只读课表页当前选择；不改学期，不猜当前学年。找不到时沿教务现有菜单进入课表。 */
(function () {
  const origin = 'https://https-jwcjwxt-jyu-edu-cn-443.webvpn.jyu.cn';
  if (location.origin !== origin) return JSON.stringify({});
  function inspect(win, depth) {
    try {
      if (win.location.origin !== origin) return null;
      const doc = win.document;
      // 登录页可能带有隐藏学期字段，不能把它当成已登录课表。
      if (doc.querySelector('input[type="password"]')) return null;
      const year = doc.querySelector('#xnm, select[name="xnm"], input[name="xnm"]');
      const term = doc.querySelector('#xqm, select[name="xqm"], input[name="xqm"]');
      if (year && term && /^20\d{2}$/.test(year.value) && ['3', '12', '16'].includes(term.value)) {
        const date = doc.querySelector('#xqksrq, input[name="xqksrq"], #kssj, input[name="kssj"], [data-semester-start-date]');
        const student = doc.querySelector('#xh, input[name="xh"], [data-student-id]');
        return { year: year.value, term: term.value,
          username: student ? (student.getAttribute('data-student-id') || student.value || student.textContent || '').trim() : '',
          startDate: date ? (date.getAttribute('data-semester-start-date') || date.value || '').trim() : '' };
      }
      if (depth < 2) {
        for (const frame of Array.from(doc.querySelectorAll('iframe'))) {
          const found = inspect(frame.contentWindow, depth + 1);
          if (found) return found;
        }
      }
      if (!doc.querySelector('input[type="password"]') && !win.__dawnJyuOpenedTimetable) {
        const link = Array.from(doc.querySelectorAll('a[href]')).find(a => {
          try {
            const url = new URL(a.getAttribute('data-href') || a.getAttribute('href'), win.location.href);
            if (url.origin === origin && /\/kbcx\//.test(url.pathname) && /xskbcx/i.test(url.pathname)) {
              return url.pathname !== win.location.pathname;
            }
            const action = a.getAttribute('onclick') || '';
            return /个人课表|学生课表/.test(a.textContent || '') && /\/kbcx\/xskbcx/i.test(action) &&
              !/https?:\/\//i.test(action);
          } catch (_) { return false; }
        });
        if (link) { win.__dawnJyuOpenedTimetable = true; link.target = '_self'; link.click(); }
      }
    } catch (_) { /* 跨域 iframe 不读取。 */ }
    return null;
  }
  return JSON.stringify(inspect(window, 0) || {});
})();
