/* 计算器主逻辑
 * 9 个面板：普通 / 科学 / 函数图像 / 方程 / 矩阵 / 统计 / 进制 / 单位 / 几何
 * 表达式解析用 engine.js（来自数学教学.html）
 */
(function () {
  'use strict';

  var E = window.ExprEngine;
  var P = window.Plot;

  // ============ 工具 ============
  function $(s) { return document.querySelector(s); }

  function el(tag, cls, text) {
    var d = document.createElement(tag);
    if (cls) d.className = cls;
    if (text != null) d.textContent = text;
    return d;
  }

  // 数值格式化：最多 10 位有效，去掉末尾 0
  function fmt(v, digits) {
    if (v == null || !isFinite(v)) return '无法计算';
    var d = digits == null ? 10 : digits;
    var s = Number(v).toPrecision(d);
    if (s.indexOf('e') >= 0) return s;
    if (s.indexOf('.') >= 0) s = s.replace(/0+$/, '').replace(/\.$/, '');
    return s;
  }

  // ============ 注册面板 ============
  var TABS = [];

  function register(id, name, build) {
    TABS.push({ id: id, name: name, build: build });
  }

  function mount() {
    var tabsBar = $('#tabs');
    var panels = $('#panels');

    TABS.forEach(function (t, i) {
      var b = el('button', 'tab', t.name);
      b.onclick = function () { activate(i); };
      tabsBar.appendChild(b);

      var p = el('div', 'panel');
      p.id = 'panel-' + t.id;
      panels.appendChild(p);
      try { t.build(p); } catch (e) { p.textContent = '加载失败：' + e.message; }
    });

    activate(0);
  }

  function activate(i) {
    var tabs = document.querySelectorAll('.tab');
    var panels = document.querySelectorAll('.panel');
    for (var k = 0; k < tabs.length; k++) {
      tabs[k].classList.toggle('on', k === i);
      panels[k].classList.toggle('on', k === i);
    }
    // 切换到图表页时重算尺寸
    var cv = panels[i] && panels[i].querySelector('canvas.graph');
    if (cv && cv.__plane) setTimeout(function () { cv.__plane.resize(); }, 30);
  }

  /* ============================================================
   * 1. 普通计算器
   * ============================================================ */
  register('basic', '普通', function (root) {
    var expr = '';

    var screen = el('div', 'screen');
    var eLine = el('div', 'expr', '');
    var rLine = el('div', 'result', '0');
    screen.appendChild(eLine);
    screen.appendChild(rLine);
    root.appendChild(screen);

    var keys = el('div', 'keys c4');
    root.appendChild(keys);

    // 按键定义：[标签, 动作, 类型]
    // 类型：'' 数字 / 'op' 运算符 / 'fn' 功能 / 'eq' 等号 / 'del' 删除
    var LAYOUT = [
      ['AC', function () { expr = ''; rLine.textContent = '0'; rLine.className = 'result'; }, 'del'],
      ['⌫', function () { expr = expr.slice(0, -1); }, 'fn'],
      ['%', function () { expr += '/100'; }, 'op'],
      ['÷', function () { expr += '/'; }, 'op'],

      ['7', function () { expr += '7'; }, ''],
      ['8', function () { expr += '8'; }, ''],
      ['9', function () { expr += '9'; }, ''],
      ['×', function () { expr += '*'; }, 'op'],

      ['4', function () { expr += '4'; }, ''],
      ['5', function () { expr += '5'; }, ''],
      ['6', function () { expr += '6'; }, ''],
      ['−', function () { expr += '-'; }, 'op'],

      ['1', function () { expr += '1'; }, ''],
      ['2', function () { expr += '2'; }, ''],
      ['3', function () { expr += '3'; }, ''],
      ['+', function () { expr += '+'; }, 'op'],

      ['±', function () {
        if (expr === '') return;
        var m = expr.match(/(-?\d*\.?\d+)$/);
        if (!m) { expr += '-'; return; }
        var numStr = m[1];
        var head = expr.slice(0, expr.length - numStr.length);
        expr = head + (numStr.charAt(0) === '-' ? numStr.slice(1) : '-' + numStr);
      }, 'fn'],
      ['0', function () { expr += '0'; }, ''],
      ['.', function () {
        var m = expr.match(/\d*\.?\d*$/);
        if (m && m[0].indexOf('.') >= 0) return;
        expr += expr === '' ? '0.' : '.';
      }, ''],
      ['=', calcNow, 'eq'],
    ];

    function refresh() {
      eLine.textContent = expr || '';
    }

    function calcNow() {
      if (!expr) return;
      try {
        var v = E.calc(expr);
        rLine.textContent = fmt(v);
        rLine.className = 'result';
      } catch (e) {
        rLine.textContent = '表达式错误';
        rLine.className = 'result err';
      }
    }

    LAYOUT.forEach(function (item) {
      var b = el('button', 'key' + (item[2] ? ' ' + item[2] : ''), item[0]);
      b.onclick = function () { item[1](); refresh(); };
      keys.appendChild(b);
    });
  });

  /* ============================================================
   * 2. 科学计算器
   * ============================================================ */
  register('sci', '科学', function (root) {
    var expr = '';

    var screen = el('div', 'screen');
    var eLine = el('div', 'expr', '');
    var rLine = el('div', 'result', '0');
    screen.appendChild(eLine);
    screen.appendChild(rLine);
    root.appendChild(screen);

    var keys = el('div', 'keys c5');
    root.appendChild(keys);

    function refresh() { eLine.textContent = expr || ''; }

    function calcNow() {
      if (!expr) return;
      try {
        var v = E.calc(expr);
        rLine.textContent = fmt(v);
        rLine.className = 'result';
      } catch (e) {
        rLine.textContent = '表达式错误';
        rLine.className = 'result err';
      }
    }

    // 插入函数名（自动补左括号）
    function fnWrap(name) {
      return function () { expr += name + '('; };
    }

    var LAYOUT = [
      ['AC', function () { expr = ''; rLine.textContent = '0'; rLine.className = 'result'; }, 'del'],
      ['⌫', function () { expr = expr.slice(0, -1); }, 'fn'],
      ['(', function () { expr += '('; }, 'fn'],
      [')', function () { expr += ')'; }, 'fn'],
      ['÷', function () { expr += '/'; }, 'op'],

      ['sin', fnWrap('sin'), 'fn'],
      ['cos', fnWrap('cos'), 'fn'],
      ['tan', fnWrap('tan'), 'fn'],
      ['^', function () { expr += '^'; }, 'op'],
      ['×', function () { expr += '*'; }, 'op'],

      ['ln', fnWrap('ln'), 'fn'],
      ['log', fnWrap('log'), 'fn'],
      ['√', fnWrap('sqrt'), 'fn'],
      ['!', function () { expr += '!'; }, 'fn'],
      ['−', function () { expr += '-'; }, 'op'],

      ['π', function () { expr += 'pi'; }, 'fn'],
      ['e', function () { expr += 'e'; }, 'fn'],
      ['|x|', fnWrap('abs'), 'fn'],
      ['%', function () { expr += '/100'; }, 'op'],
      ['+', function () { expr += '+'; }, 'op'],

      ['7', function () { expr += '7'; }, ''],
      ['8', function () { expr += '8'; }, ''],
      ['9', function () { expr += '9'; }, ''],
      ['asin', fnWrap('asin'), 'fn'],
      ['1/x', function () { expr += '1/('; }, 'fn'],

      ['4', function () { expr += '4'; }, ''],
      ['5', function () { expr += '5'; }, ''],
      ['6', function () { expr += '6'; }, ''],
      ['acos', fnWrap('acos'), 'fn'],
      ['exp', fnWrap('exp'), 'fn'],

      ['1', function () { expr += '1'; }, ''],
      ['2', function () { expr += '2'; }, ''],
      ['3', function () { expr += '3'; }, ''],
      ['atan', fnWrap('atan'), 'fn'],
      ['x²', function () { expr += '^2'; }, 'fn'],

      ['±', function () {
        if (expr === '') return;
        var m = expr.match(/(-?\d*\.?\d+)$/);
        if (!m) { expr += '-'; return; }
        var numStr = m[1];
        var head = expr.slice(0, expr.length - numStr.length);
        expr = head + (numStr.charAt(0) === '-' ? numStr.slice(1) : '-' + numStr);
      }, 'fn'],
      ['0', function () { expr += '0'; }, ''],
      ['.', function () {
        var m = expr.match(/\d*\.?\d*$/);
        if (m && m[0].indexOf('.') >= 0) return;
        expr += expr === '' ? '0.' : '.';
      }, ''],
      ['E', function () { expr += '*10^'; }, 'fn'],
      ['=', calcNow, 'eq'],
    ];

    LAYOUT.forEach(function (item) {
      var b = el('button', 'key' + (item[2] ? ' ' + item[2] : ''), item[0]);
      b.onclick = function () { item[2] === 'eq' ? item[1]() : (item[1](), refresh()); };
      keys.appendChild(b);
    });

    // 阶乘预处理：engine 不支持后缀 !，所以在求值前展开
    // 支持：5!、x!、(1+2)! 、3!!（多重阶乘会依次处理）
    function expandFactorial(src) {
      // 从右往左扫，遇到 ! 就把前面完整的数字/右括号当作操作数
      var out = src;
      var guard = 0;
      while (out.indexOf('!') >= 0 && guard++ < 20) {
        var idx = out.lastIndexOf('!');
        // 向前找操作数起点
        var j = idx - 1;
        if (j < 0) { out = out.slice(0, idx); continue; }
        var end = j + 1;
        if (out[j] === ')') {
          // 括号：配对退回
          var depth = 0;
          while (j >= 0) {
            if (out[j] === ')') depth++;
            else if (out[j] === '(') { depth--; if (depth === 0) break; }
            j--;
          }
          if (j < 0) { out = out.slice(0, idx) + out.slice(idx + 1); continue; }
        } else {
          // 数字：向前贪到非数字
          while (j >= 0 && /[0-9.]/.test(out[j])) j--;
          j++;
        }
        var operand = out.slice(j, end);
        // 展开为 fact(operand)，engine 不认 fact，所以直接算结果插回去
        var v = E.calc(operand);
        var n = Math.round(v);
        if (!isFinite(v) || n < 0 || n > 170) throw new Error('阶乘参数超范围');
        var r = 1;
        for (var k = 2; k <= n; k++) r *= k;
        out = out.slice(0, j) + '(' + r + ')' + out.slice(idx + 1);
      }
      return out;
    }

    // 重新绑定等号键的求值逻辑
    keys.children[keys.children.length - 1].onclick = function () {
      if (!expr) return;
      try {
        var v = E.calc(expandFactorial(expr));
        rLine.textContent = fmt(v);
        rLine.className = 'result';
      } catch (e) {
        rLine.textContent = e.message || '表达式错误';
        rLine.className = 'result err';
      }
    };
  });

  /* ============================================================
   * 3. 函数图像
   * ============================================================ */
  register('graph', '函数图像', function (root) {
    var inp = el('input');
    inp.type = 'text';
    inp.value = 'y = sin(x)';
    inp.placeholder = '例如 sin(x)、x^2、r=1-sin(theta)';

    var f = el('div', 'field');
    f.appendChild(el('label', '', '表达式（支持 y=、f(x)=、r= 极坐标、参数方程）'));
    f.appendChild(inp);
    root.appendChild(f);

    var modeRow = el('div', 'row2');
    var selMode = el('select');
    [['cart', '直角坐标 y=f(x)'], ['polar', '极坐标 r=f(θ)'], ['param', '参数方程']].forEach(function (o) {
      var op = el('option', '', o[1]);
      op.value = o[0];
      selMode.appendChild(op);
    });
    var mf = el('div', 'field');
    mf.appendChild(el('label', '', '坐标类型'));
    mf.appendChild(selMode);
    modeRow.appendChild(mf);

    var rngField = el('div', 'field');
    rngField.appendChild(el('label', '', '范围 X (min,max)'));
    var rngInp = el('input');
    rngInp.value = '-10,10';
    rngField.appendChild(rngInp);
    modeRow.appendChild(rngField);
    root.appendChild(modeRow);

    var pRow = el('div', 'row2');
    var fd = el('div', 'field');
    fd.appendChild(el('label', '', '参数 x(t)'));
    var inFx = el('input');
    inFx.value = 'cos(t)*5';
    fd.appendChild(inFx);
    pRow.appendChild(fd);

    var fd2 = el('div', 'field');
    fd2.appendChild(el('label', '', '参数 y(t)'));
    var inFy = el('input');
    inFy.value = 'sin(t)*5';
    fd2.appendChild(inFy);
    pRow.appendChild(fd2);
    pRow.style.display = 'none';
    root.appendChild(pRow);

    var tRow = el('div', 'row2');
    var tf = el('div', 'field');
    tf.appendChild(el('label', '', '参数 t 范围'));
    var inT = el('input');
    inT.value = '0,6.2832';
    tf.appendChild(inT);
    tRow.appendChild(tf);
    tRow.style.display = 'none';
    root.appendChild(tRow);

    var btn = el('button', 'btn', '绘制');
    root.appendChild(btn);

    var canvas = document.createElement('canvas');
    canvas.className = 'graph';
    root.appendChild(canvas);

    var hint = el('div', 'hint', '提示：拖动可平移，双指缩放（若浏览器支持）。');
    root.appendChild(hint);

    selMode.onchange = function () {
      var m = selMode.value;
      pRow.style.display = m === 'param' ? 'grid' : 'none';
      tRow.style.display = m === 'param' ? 'grid' : 'none';
      rngField.style.display = m === 'param' ? 'none' : 'block';
      inp.value = m === 'polar' ? 'r = 1 - sin(theta)' : (m === 'param' ? '' : 'y = sin(x)');
    };

    var plane = null;

    function draw() {
      if (!plane) {
        plane = new P.Plane(canvas, { x0: -10, x1: 10, y0: -10, y1: 10 });
        canvas.__plane = plane;
      }

      var m = selMode.value;
      plane.clear();

      try {
        if (m === 'param') {
          var fx = E.compile(inFx.value);
          var fy = E.compile(inFy.value);
          var tr = inT.value.split(',').map(Number);
          var t0 = isFinite(tr[0]) ? tr[0] : 0;
          var t1 = isFinite(tr[1]) ? tr[1] : Math.PI * 2;
          plane.setRange(-12, 12, -12, 12);
          plane.add(function (c, p) { P.plotParam(p, fx.f, fy.f, t0, t1, { color: '#1b6ef3', w: 2.2 }); });
        } else if (m === 'polar') {
          var rf = E.compile(inp.value);
          plane.setRange(-6, 6, -6, 6);
          plane.add(function (c, p) { P.plotPolarRad(p, rf.f, { color: '#17803d', w: 2.2 }); });
        } else {
          var r = rngInp.value.split(',').map(Number);
          var x0 = isFinite(r[0]) ? r[0] : -10;
          var x1 = isFinite(r[1]) ? r[1] : 10;
          var fn = E.compile(inp.value);
          plane.setRange(x0, x1, -10, 10);
          plane.add(function (c, p) { P.plot(p, fn.f, { color: '#1b6ef3', w: 2.2 }); });
        }
        plane.draw();
        hint.textContent = '已绘制：' + (m === 'param' ? 'x=' + inFx.value + ', y=' + inFy.value : inp.value);
      } catch (e) {
        hint.textContent = '解析失败：' + e.message;
      }
    }

    btn.onclick = draw;
    setTimeout(function () { plane = new P.Plane(canvas, { x0: -10, x1: 10, y0: -10, y1: 10 }); canvas.__plane = plane; draw(); }, 50);
  });

  /* ============================================================
   * 4. 方程求解
   * ============================================================ */
  register('eq', '方程', function (root) {
    var f = el('div', 'field');
    f.appendChild(el('label', '', '方程输入（支持 ax²+bx+c=0、ax+b=0、或写方程式如 2x+3=7）'));
    var inp = el('input');
    inp.value = 'x^2 - 5x + 6 = 0';
    f.appendChild(inp);
    root.appendChild(f);

    var f2 = el('div', 'field');
    f2.appendChild(el('label', '', '未知数'));
    var unk = el('input');
    unk.value = 'x';
    f2.appendChild(unk);
    root.appendChild(f2);

    var btn = el('button', 'btn', '求解');
    root.appendChild(btn);
    var out = el('div', 'out', '输入方程后点求解');
    root.appendChild(out);

    btn.onclick = function () {
      out.className = 'out';
      try {
        var v = unk.value.trim() || 'x';
        var raw = inp.value.trim();
        if (!raw) throw new Error('请输入方程');

        // 处理等式：左边 - 右边 = 0
        var sides = raw.split('=');
        var exprStr;
        if (sides.length === 2) {
          exprStr = '(' + sides[0] + ')-(' + sides[1] + ')';
        } else if (sides.length === 1) {
          exprStr = raw;
        } else {
          throw new Error('方程只能有一个等号');
        }

        // 把未知数统一成 x 供 engine 解析
        var norm = E.normalizeInput(exprStr).split(v).join('x');
        var c = E.compile(norm);
        var coeffs = E.asPoly(c.ast);

        if (!coeffs) {
          // 非多项式：用数值求解（二分/牛顿简化版）
          var fv = c.f;
          var roots = [];
          var step = 0.5;
          var prevX = -100, prevY = fv(prevX);
          for (var gx = -100 + step; gx <= 100; gx += step) {
            var gy = fv(gx);
            if (isFinite(prevY) && isFinite(gy) && prevY * gy <= 0 && prevY !== gy) {
              // 二分细化
              var lo = prevX, hi = gx;
              for (var it = 0; it < 60; it++) {
                var mid = (lo + hi) / 2;
                var my = fv(mid);
                if (prevY * my <= 0) { hi = mid; } else { lo = mid; prevY = my; }
              }
              var r = (lo + hi) / 2;
              if (!roots.some(function (q) { return Math.abs(q - r) < 1e-6; })) roots.push(r);
            }
            prevX = gx;
            prevY = gy;
          }
          // 清理极小值
          coeffs = null;
          if (roots.length === 0) {
            out.className = 'out err';
            out.textContent = '在 [-100, 100] 内未找到实根（可能无解或解在范围外）';
            return;
          }
          out.textContent = '数值解（近似）：\n' + roots.map(function (r, i) {
            return v + (i + 1) + ' = ' + fmt(r, 10);
          }).join('\n');
          return;
        }

        while (coeffs.length > 0 && Math.abs(coeffs[coeffs.length - 1]) < 1e-12) coeffs.pop();
        if (coeffs.length === 0) {
          out.className = 'out err';
          out.textContent = '等式恒成立（所有值都是解）';
          return;
        }

        var deg = coeffs.length - 1;

        if (deg === 1) {
          var b = coeffs[1], a0 = coeffs[0];
          var x1 = -a0 / b;
          out.innerHTML = '<span class="k">一次方程</span>\n' +
            '<span class="k">' + v + ' = </span><span class="v">' + fmt(x1, 12) + '</span>';
          return;
        }

        if (deg === 2) {
          var A = coeffs[2], B = coeffs[1], C = coeffs[0];
          var D = B * B - 4 * A * C;
          var lines = [];
          lines.push('<span class="k">二次方程</span>');
          lines.push('<span class="k">a = </span>' + fmt(A) + '   <span class="k">b = </span>' + fmt(B) + '   <span class="k">c = </span>' + fmt(C));
          lines.push('<span class="k">Δ = b² − 4ac = </span>' + fmt(D));
          if (Math.abs(D) < 1e-12) {
            var xr = -B / (2 * A);
            lines.push('<span class="k">Δ = 0，两个相等实根</span>');
            lines.push('<span class="k">' + v + ' = </span><span class="v">' + fmt(xr, 12) + '</span>');
          } else if (D > 0) {
            var s = Math.sqrt(D);
            var r1 = (-B + s) / (2 * A);
            var r2 = (-B - s) / (2 * A);
            lines.push('<span class="k">Δ > 0，两个不等实根</span>');
            lines.push('<span class="k">' + v + '₁ = </span><span class="v">' + fmt(r1, 12) + '</span>');
            lines.push('<span class="k">' + v + '₂ = </span><span class="v">' + fmt(r2, 12) + '</span>');
          } else {
            var re = -B / (2 * A);
            var im = Math.sqrt(-D) / (2 * A);
            lines.push('<span class="k">Δ < 0，无实数根（共轭复根）</span>');
            lines.push('<span class="k">' + v + ' = </span><span class="v">' + fmt(re) + ' ± ' + fmt(Math.abs(im)) + ' i</span>');
          }
          out.innerHTML = lines.join('\n');
          return;
        }

        // 三次及以上：数值
        out.textContent = '次数为 ' + deg + '，暂只支持一次/二次方程的解析解。\n系数（低次→高次）：' + coeffs.map(function (x) { return fmt(x); }).join(', ');
      } catch (e) {
        out.className = 'out err';
        out.textContent = '错误：' + (e.message || e);
      }
    };
  });

  /* ============================================================
   * 5. 矩阵计算
   * ============================================================ */
  register('matrix', '矩阵', function (root) {
    function parseMat(text) {
      var s = String(text || '').trim();
      if (!s) throw new Error('矩阵为空');
      var rows = s.split(/\n/).map(function (l) { return l.trim(); }).filter(function (l) { return l.length > 0; });
      var m = rows.map(function (line) {
        return line.split(/[\s,]+/).filter(function (x) { return x.length > 0; }).map(function (x) {
          var v = Number(x);
          if (!isFinite(v)) throw new Error('无法识别的数字：' + x);
          return v;
        });
      });
      var cols = m[0].length;
      if (m.some(function (r) { return r.length !== cols; })) throw new Error('每行列数必须一致');
      return m;
    }

    function matText(m) {
      return m.map(function (r) { return r.map(function (v) { return fmt(v); }).join('\t'); }).join('\n');
    }

    var fa = el('div', 'field');
    fa.appendChild(el('label', '', '矩阵 A（每行一行，空格或逗号分隔）'));
    var ta = el('textarea');
    ta.value = '1 2\n3 4';
    fa.appendChild(ta);
    root.appendChild(fa);

    var fb = el('div', 'field');
    fb.appendChild(el('label', '', '矩阵 B（与 A 同尺寸做加减，或满足乘法维度）'));
    var tb = el('textarea');
    tb.value = '5 6\n7 8';
    fb.appendChild(tb);
    root.appendChild(fb);

    var btnRow = el('div');
    root.appendChild(btnRow);

    var out = el('div', 'out', '输入矩阵后选择运算');
    root.appendChild(out);

    function show(text, isErr) {
      out.className = 'out' + (isErr ? ' err' : '');
      out.textContent = text;
    }

    function makeBtn(label, fn) {
      var b = el('button', 'btn ghost', label);
      b.onclick = fn;
      btnRow.appendChild(b);
    }

    makeBtn('A + B', function () {
      try {
        var A = parseMat(ta.value), B = parseMat(tb.value);
        if (A.length !== B.length || A[0].length !== B[0].length) throw new Error('尺寸不同，无法相加');
        var R = A.map(function (r, i) { return r.map(function (v, j) { return v + B[i][j]; }); });
        show('A + B =\n' + matText(R));
      } catch (e) { show('错误：' + e.message, true); }
    });

    makeBtn('A − B', function () {
      try {
        var A = parseMat(ta.value), B = parseMat(tb.value);
        if (A.length !== B.length || A[0].length !== B[0].length) throw new Error('尺寸不同，无法相减');
        var R = A.map(function (r, i) { return r.map(function (v, j) { return v - B[i][j]; }); });
        show('A − B =\n' + matText(R));
      } catch (e) { show('错误：' + e.message, true); }
    });

    makeBtn('A × B', function () {
      try {
        var A = parseMat(ta.value), B = parseMat(tb.value);
        if (A[0].length !== B.length) throw new Error('A 的列数必须等于 B 的行数');
        var R = [];
        for (var i = 0; i < A.length; i++) {
          R[i] = [];
          for (var j = 0; j < B[0].length; j++) {
            var s = 0;
            for (var k = 0; k < B.length; k++) s += A[i][k] * B[k][j];
            R[i][j] = s;
          }
        }
        show('A × B =\n' + matText(R));
      } catch (e) { show('错误：' + e.message, true); }
    });

    makeBtn('行列式', function () {
      try {
        var A = parseMat(ta.value);
        if (A.length !== A[0].length) throw new Error('行列式只对方阵有效');
        function det(m) {
          var n = m.length;
          if (n === 1) return m[0][0];
          if (n === 2) return m[0][0] * m[1][1] - m[0][1] * m[1][0];
          var s = 0;
          for (var c = 0; c < n; c++) {
            var sub = [];
            for (var r = 1; r < n; r++) {
              var row = [];
              for (var cc = 0; cc < n; cc++) if (cc !== c) row.push(m[r][cc]);
              sub.push(row);
            }
            s += ((c % 2 === 0) ? 1 : -1) * m[0][c] * det(sub);
          }
          return s;
        }
        show('det(A) = ' + fmt(det(A), 12));
      } catch (e) { show('错误：' + e.message, true); }
    });

    makeBtn('转置 A', function () {
      try {
        var A = parseMat(ta.value);
        var R = [];
        for (var j = 0; j < A[0].length; j++) {
          R[j] = [];
          for (var i = 0; i < A.length; i++) R[j][i] = A[i][j];
        }
        show('Aᵀ =\n' + matText(R));
      } catch (e) { show('错误：' + e.message, true); }
    });

    makeBtn('逆矩阵 A', function () {
      try {
        var A = parseMat(ta.value);
        var n = A.length;
        if (n !== A[0].length) throw new Error('求逆只对方阵有效');
        // 高斯-约当消元
        var M = A.map(function (r, i) {
          var row = r.slice();
          for (var j = 0; j < n; j++) row.push(i === j ? 1 : 0);
          return row;
        });
        for (var col = 0; col < n; col++) {
          var piv = -1, best = 0;
          for (var r2 = col; r2 < n; r2++) {
            if (Math.abs(M[r2][col]) > best) { best = Math.abs(M[r2][col]); piv = r2; }
          }
          if (piv < 0 || best < 1e-12) throw new Error('矩阵不可逆（行列式为 0）');
          var tmp = M[col]; M[col] = M[piv]; M[piv] = tmp;
          var pv = M[col][col];
          for (var j2 = 0; j2 < 2 * n; j2++) M[col][j2] /= pv;
          for (var r3 = 0; r3 < n; r3++) {
            if (r3 === col) continue;
            var f = M[r3][col];
            if (Math.abs(f) < 1e-15) continue;
            for (var j3 = 0; j3 < 2 * n; j3++) M[r3][j3] -= f * M[col][j3];
          }
        }
        var inv = M.map(function (r) { return r.slice(n); });
        show('A⁻¹ =\n' + matText(inv));
      } catch (e) { show('错误：' + e.message, true); }
    });
  });

  /* ============================================================
   * 6. 统计计算
   * ============================================================ */
  register('stat', '统计', function (root) {
    var f = el('div', 'field');
    f.appendChild(el('label', '', '数据（空格、逗号或换行分隔）'));
    var ta = el('textarea');
    ta.value = '12, 15, 18, 20, 22, 25, 30';
    f.appendChild(ta);
    root.appendChild(f);

    var btn = el('button', 'btn', '计算');
    root.appendChild(btn);
    var out = el('div', 'out', '');
    root.appendChild(out);

    btn.onclick = function () {
      out.className = 'out';
      try {
        var nums = ta.value.split(/[\s,]+/).filter(function (x) { return x.length > 0; }).map(Number);
        if (nums.some(function (v) { return !isFinite(v); })) throw new Error('含有无法识别的数字');
        if (nums.length === 0) throw new Error('数据为空');

        var n = nums.length;
        var sum = nums.reduce(function (a, b) { return a + b; }, 0);
        var mean = sum / n;

        var sorted = nums.slice().sort(function (a, b) { return a - b; });
        var median = n % 2 === 1 ? sorted[(n - 1) / 2] : (sorted[n / 2 - 1] + sorted[n / 2]) / 2;

        // 众数（可能多个）
        var freq = {};
        nums.forEach(function (v) { freq[v] = (freq[v] || 0) + 1; });
        var maxF = 0;
        Object.keys(freq).forEach(function (k) { if (freq[k] > maxF) maxF = freq[k]; });
        var modes = Object.keys(freq).filter(function (k) { return freq[k] === maxF; }).map(Number);

        var varSum = nums.reduce(function (a, v) { return a + (v - mean) * (v - mean); }, 0);
        var varP = varSum / n;          // 总体方差
        var varS = n > 1 ? varSum / (n - 1) : 0;  // 样本方差
        var sdP = Math.sqrt(varP);
        var sdS = Math.sqrt(varS);

        var range = sorted[n - 1] - sorted[0];

        var lines = [];
        lines.push('<span class="k">个数 n = </span><span class="v">' + n + '</span>');
        lines.push('<span class="k">总和 Σ = </span><span class="v">' + fmt(sum, 12) + '</span>');
        lines.push('<span class="k">平均值 x̄ = </span><span class="v">' + fmt(mean, 12) + '</span>');
        lines.push('<span class="k">中位数 = </span><span class="v">' + fmt(median, 12) + '</span>');
        lines.push('<span class="k">众数 = </span><span class="v">' + (maxF > 1 ? modes.map(function (v) { return fmt(v); }).join(', ') : '无重复值') + '</span>');
        lines.push('<span class="k">最小值 = </span>' + fmt(sorted[0], 12) + '   <span class="k">最大值 = </span>' + fmt(sorted[n - 1], 12));
        lines.push('<span class="k">极差 = </span>' + fmt(range, 12));
        lines.push('<span class="k">总体方差 σ² = </span>' + fmt(varP, 10));
        lines.push('<span class="k">总体标准差 σ = </span><span class="v">' + fmt(sdP, 10) + '</span>');
        if (n > 1) {
          lines.push('<span class="k">样本方差 s² = </span>' + fmt(varS, 10));
          lines.push('<span class="k">样本标准差 s = </span>' + fmt(sdS, 10));
        }
        out.innerHTML = lines.join('\n');
      } catch (e) {
        out.className = 'out err';
        out.textContent = '错误：' + e.message;
      }
    };
  });

  /* ============================================================
   * 7. 进制转换
   * ============================================================ */
  register('base', '进制', function (root) {
    var f = el('div', 'field');
    f.appendChild(el('label', '', '输入值'));
    var inp = el('input');
    inp.value = '255';
    f.appendChild(inp);
    root.appendChild(f);

    var f2 = el('div', 'field');
    f2.appendChild(el('label', '', '输入进制'));
    var sel = el('select');
    [['2', '二进制'], ['8', '八进制'], ['10', '十进制'], ['16', '十六进制']].forEach(function (o) {
      var op = el('option', '', o[1]);
      op.value = o[0];
      sel.appendChild(op);
    });
    sel.value = '10';
    f2.appendChild(sel);
    root.appendChild(f2);

    var btn = el('button', 'btn', '转换');
    root.appendChild(btn);
    var out = el('div', 'out', '');
    root.appendChild(out);

    btn.onclick = function () {
      out.className = 'out';
      try {
        var base = parseInt(sel.value, 10);
        var s = inp.value.trim().replace(/^0[bxo]/i, '');
        if (!s) throw new Error('请输入数值');

        // 校验每一位是否合法
        var digits = '0123456789abcdef'.slice(0, base);
        for (var i = 0; i < s.length; i++) {
          if (digits.indexOf(s[i].toLowerCase()) < 0) {
            throw new Error(base + ' 进制不允许字符 "' + s[i] + '"');
          }
        }

        var v = parseInt(s, base);
        if (!isFinite(v)) throw new Error('无法解析');

        var lines = [];
        lines.push('<span class="k">十进制</span>   <span class="v">' + v + '</span>');
        lines.push('<span class="k">二进制</span>   ' + v.toString(2));
        lines.push('<span class="k">八进制</span>   ' + v.toString(8));
        lines.push('<span class="k">十六进制</span> ' + v.toString(16).toUpperCase());
        lines.push('');
        lines.push('<span class="k">32 位二进制</span>');
        lines.push((v >>> 0).toString(2).padStart(32, '0').replace(/(.{8})(?=.)/g, '$1 '));
        out.innerHTML = lines.join('\n');
      } catch (e) {
        out.className = 'out err';
        out.textContent = '错误：' + e.message;
      }
    };
  });

  /* ============================================================
   * 8. 单位换算
   * ============================================================ */
  register('unit', '单位', function (root) {
    // 每类：base 为换算到基准单位的系数
    var CATS = {
      '长度': {
        '毫米 mm': 0.001, '厘米 cm': 0.01, '米 m': 1, '千米 km': 1000,
        '英寸 in': 0.0254, '英尺 ft': 0.3048, '英里 mi': 1609.344, '海里 nmi': 1852
      },
      '面积': {
        '平方厘米 cm²': 0.0001, '平方米 m²': 1, '平方千米 km²': 1e6,
        '公顷 ha': 10000, '亩': 666.6667, '平方英尺 ft²': 0.092903
      },
      '体积': {
        '毫升 mL': 0.001, '升 L': 1, '立方米 m³': 1000,
        '加仑 gal': 3.78541, '立方英尺 ft³': 28.3168
      },
      '质量': {
        '毫克 mg': 0.001, '克 g': 1, '千克 kg': 1000, '吨 t': 1e6,
        '斤': 500, '磅 lb': 453.592, '盎司 oz': 28.3495
      },
      '温度': { '摄氏度 °C': 'C', '华氏度 °F': 'F', '开尔文 K': 'K' },
      '速度': {
        '米/秒 m/s': 1, '千米/时 km/h': 0.277778,
        '英里/时 mph': 0.44704, '节 kn': 0.514444
      },
      '数据': {
        '字节 B': 1, '千字节 KB': 1024, '兆字节 MB': 1048576,
        '吉字节 GB': 1073741824, '太字节 TB': 1099511627776
      }
    };

    var catNames = Object.keys(CATS);

    var f1 = el('div', 'field');
    f1.appendChild(el('label', '', '类别'));
    var selCat = el('select');
    catNames.forEach(function (n) {
      var op = el('option', '', n);
      op.value = n;
      selCat.appendChild(op);
    });
    f1.appendChild(selCat);
    root.appendChild(f1);

    var row = el('div', 'row2');

    var f2 = el('div', 'field');
    f2.appendChild(el('label', '', '从'));
    var selFrom = el('select');
    f2.appendChild(selFrom);
    row.appendChild(f2);

    var f3 = el('div', 'field');
    f3.appendChild(el('label', '', '到'));
    var selTo = el('select');
    f3.appendChild(selTo);
    row.appendChild(f3);

    root.appendChild(row);

    var f4 = el('div', 'field');
    f4.appendChild(el('label', '', '数值'));
    var inp = el('input');
    inp.value = '1';
    f4.appendChild(inp);
    root.appendChild(f4);

    var btn = el('button', 'btn', '换算');
    root.appendChild(btn);
    var out = el('div', 'out', '');
    root.appendChild(out);

    function rebuild() {
      var units = Object.keys(CATS[selCat.value]);
      selFrom.innerHTML = '';
      selTo.innerHTML = '';
      units.forEach(function (u, i) {
        var o1 = el('option', '', u); o1.value = u;
        var o2 = el('option', '', u); o2.value = u;
        selFrom.appendChild(o1);
        selTo.appendChild(o2);
        if (i === 0) o1.selected = true;
        if (i === 1) o2.selected = true;
      });
      if (units.length === 1) selTo.value = units[0];
    }

    selCat.onchange = rebuild;
    rebuild();

    function tempConv(v, from, to) {
      // 先转摄氏
      var c;
      if (from === 'C') c = v;
      else if (from === 'F') c = (v - 32) * 5 / 9;
      else c = v - 273.15;
      // 从摄氏转出去
      if (to === 'C') return c;
      if (to === 'F') return c * 9 / 5 + 32;
      return c + 273.15;
    }

    btn.onclick = function () {
      out.className = 'out';
      try {
        var v = Number(inp.value);
        if (!isFinite(v)) throw new Error('数值无效');
        var cat = selCat.value;
        var from = selFrom.value;
        var to = selTo.value;
        var res;

        if (cat === '温度') {
          res = tempConv(v, CATS[cat][from], CATS[cat][to]);
        } else {
          var factorFrom = CATS[cat][from];
          var factorTo = CATS[cat][to];
          res = v * factorFrom / factorTo;
        }

        out.innerHTML = '<span class="k">' + fmt(v) + ' ' + from + ' = </span>\n' +
          '<span class="v">' + fmt(res, 12) + ' ' + to + '</span>';
      } catch (e) {
        out.className = 'out err';
        out.textContent = '错误：' + e.message;
      }
    };
  });

  /* ============================================================
   * 9. 几何计算
   * ============================================================ */
  register('geo', '几何', function (root) {
    var shapes = {
      '圆形': ['半径 r'],
      '三角形': ['底 b', '高 h', '边长 a', '边长 c'],
      '矩形': ['长 a', '宽 b'],
      '梯形': ['上底 a', '下底 b', '高 h'],
      '球体': ['半径 r'],
      '圆柱': ['半径 r', '高 h'],
      '圆锥': ['半径 r', '高 h'],
      '正方体': ['棱长 a']
    };

    var f1 = el('div', 'field');
    f1.appendChild(el('label', '', '形状'));
    var sel = el('select');
    Object.keys(shapes).forEach(function (n) {
      var op = el('option', '', n);
      op.value = n;
      sel.appendChild(op);
    });
    f1.appendChild(sel);
    root.appendChild(f1);

    var paramBox = el('div');
    root.appendChild(paramBox);

    var btn = el('button', 'btn', '计算');
    root.appendChild(btn);
    var out = el('div', 'out', '');
    root.appendChild(out);

    var inputs = {};

    function rebuild() {
      paramBox.innerHTML = '';
      inputs = {};
      shapes[sel.value].forEach(function (p) {
        var f = el('div', 'field');
        f.appendChild(el('label', '', p));
        var i = el('input');
        i.type = 'number';
        i.step = 'any';
        i.value = '1';
        f.appendChild(i);
        paramBox.appendChild(f);
        inputs[p] = i;
      });
    }
    sel.onchange = rebuild;
    rebuild();

    btn.onclick = function () {
      out.className = 'out';
      try {
        var s = sel.value;
        function g(key) {
          var match = Object.keys(inputs).find(function (k) { return k.indexOf(key) === 0; });
          var v = match ? Number(inputs[match].value) : NaN;
          if (!isFinite(v)) throw new Error('请填写有效的 ' + key);
          return v;
        }

        var lines = [];
        var PI = Math.PI;

        if (s === '圆形') {
          var r = g('半径');
          if (r <= 0) throw new Error('半径必须大于 0');
          lines.push('<span class="k">周长 C = 2πr = </span><span class="v">' + fmt(2 * PI * r, 12) + '</span>');
          lines.push('<span class="k">面积 S = πr² = </span><span class="v">' + fmt(PI * r * r, 12) + '</span>');
          lines.push('<span class="k">直径 d = </span>' + fmt(2 * r, 12));
        } else if (s === '三角形') {
          var b = g('底'), h = g('高'), a = g('边长 a'), c = g('边长 c');
          lines.push('<span class="k">面积 S = 底×高÷2 = </span><span class="v">' + fmt(b * h / 2, 12) + '</span>');
          lines.push('<span class="k">周长 P = a+b+c = </span>' + fmt(a + b + c, 12));
          if (a + b > c && a + c > b && b + c > a) {
            // 海伦公式
            var p2 = (a + b + c) / 2;
            var area2 = Math.sqrt(p2 * (p2 - a) * (p2 - b) * (p2 - c));
            lines.push('<span class="k">海伦公式面积 = </span>' + fmt(area2, 12));
          } else {
            lines.push('<span class="k" style="color:var(--bad)">注意：三边无法构成三角形</span>');
          }
        } else if (s === '矩形') {
          var aa = g('长'), bb = g('宽');
          lines.push('<span class="k">面积 S = a×b = </span><span class="v">' + fmt(aa * bb, 12) + '</span>');
          lines.push('<span class="k">周长 P = 2(a+b) = </span><span class="v">' + fmt(2 * (aa + bb), 12) + '</span>');
          lines.push('<span class="k">对角线 = </span>' + fmt(Math.sqrt(aa * aa + bb * bb), 12));
        } else if (s === '梯形') {
          var ta = g('上底'), tb = g('下底'), th = g('高');
          lines.push('<span class="k">面积 S = (a+b)×h÷2 = </span><span class="v">' + fmt((ta + tb) * th / 2, 12) + '</span>');
        } else if (s === '球体') {
          var sr = g('半径');
          lines.push('<span class="k">体积 V = 4/3·πr³ = </span><span class="v">' + fmt(4 / 3 * PI * sr * sr * sr, 12) + '</span>');
          lines.push('<span class="k">表面积 S = 4πr² = </span><span class="v">' + fmt(4 * PI * sr * sr, 12) + '</span>');
        } else if (s === '圆柱') {
          var cr = g('半径'), ch = g('高');
          lines.push('<span class="k">体积 V = πr²h = </span><span class="v">' + fmt(PI * cr * cr * ch, 12) + '</span>');
          lines.push('<span class="k">侧面积 = 2πrh = </span>' + fmt(2 * PI * cr * ch, 12));
          lines.push('<span class="k">表面积 = 2πr²+2πrh = </span>' + fmt(2 * PI * cr * cr + 2 * PI * cr * ch, 12));
        } else if (s === '圆锥') {
          var kr = g('半径'), kh = g('高');
          var l = Math.sqrt(kr * kr + kh * kh);
          lines.push('<span class="k">母线 l = √(r²+h²) = </span>' + fmt(l, 12));
          lines.push('<span class="k">体积 V = 1/3·πr²h = </span><span class="v">' + fmt(PI * kr * kr * kh / 3, 12) + '</span>');
          lines.push('<span class="k">侧面积 = πrl = </span>' + fmt(PI * kr * l, 12));
          lines.push('<span class="k">表面积 = πr²+πrl = </span>' + fmt(PI * kr * kr + PI * kr * l, 12));
        } else if (s === '正方体') {
          var ea = g('棱长');
          lines.push('<span class="k">体积 V = a³ = </span><span class="v">' + fmt(ea * ea * ea, 12) + '</span>');
          lines.push('<span class="k">表面积 S = 6a² = </span><span class="v">' + fmt(6 * ea * ea, 12) + '</span>');
          lines.push('<span class="k">对角线 = √3·a = </span>' + fmt(Math.sqrt(3) * ea, 12));
        }

        out.innerHTML = lines.join('\n');
      } catch (e) {
        out.className = 'out err';
        out.textContent = '错误：' + e.message;
      }
    };
  });

  // ============ 启动 ============
  mount();

})();
