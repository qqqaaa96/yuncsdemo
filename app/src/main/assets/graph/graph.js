/* 函数图像计算器（math.js 引擎）
 *
 * 实现逐行照搬「数学教学.html」的专业函数图像计算器：
 *   G.clean      —— 输入清洗（全角/括号/运算符/上标/希腊字母/粘连函数名）
 *   G.classify   —— 自动识别 显函数/极坐标/参数方程/隐函数/不等式
 *   findParams   —— 用 math.parse().traverse() 遍历 AST 找参数（不是正则）
 *   marchingSquares —— 隐函数等值线
 *   autoParamMax —— 参数滑块上限自适应
 *
 * 唯一变化：UI 重写（不从原 HTML 抽，避免带上整页教学内容）。
 */
'use strict';

/* ============================================================
 * G：输入清洗与类型识别
 * ============================================================ */
var G = {
  /* 输入清洗：全角转半角、抹空白、符号归一 */
  clean: function (s) {
    var t = String(s == null ? '' : s);
    t = t.replace(/[\uFF01-\uFF5E]/g, function (ch) {
      return String.fromCharCode(ch.charCodeAt(0) - 0xFEE0);
    });
    t = t.replace(/\u3000/g, ' ');
    /* 中文括号及其他括号统一 */
    t = t.replace(/[\uFF08\uFF3B\uFF5B\u3010\u3008\u2329\u300C\u300E]/g, '(');
    t = t.replace(/[\uFF09\uFF3D\uFF5D\u3011\u3009\u232A\u300D\u300F]/g, ')');
    /* 运算符 */
    t = t.replace(/[\u00D7\u2715\u2716\u22C5\u00B7\u2219]/g, '*');
    t = t.replace(/[\u00F7\u2215\u2044]/g, '/');
    t = t.replace(/[\u2212\u2013\u2014\u2015\uFE63]/g, '-');
    t = t.replace(/[\uFF0C\u3001\uFF1B]/g, ',');
    /* 次方上标 */
    t = t.replace(/\u2070/g, '^0').replace(/\u00B9/g, '^1');
    t = t.replace(/\u00B2/g, '^2').replace(/\u00B3/g, '^3');
    t = t.replace(/\u2074/g, '^4').replace(/\u2075/g, '^5');
    t = t.replace(/\u2076/g, '^6').replace(/\u2077/g, '^7');
    t = t.replace(/\u2078/g, '^8').replace(/\u2079/g, '^9');
    /* 希腊字母 */
    t = t.replace(/[\u03C0\u03A0]/g, 'pi');
    t = t.replace(/[\u03B8\u03D1]/g, 'theta');
    t = t.replace(/[\u221A]/g, 'sqrt');
    /* 数学符号 */
    t = t.replace(/\u2264/g, '<=').replace(/\u2265/g, '>=');
    t = t.replace(/\u2260/g, '!=');
    if (typeof t.normalize === 'function') {
      try { t = t.normalize('NFKC'); } catch (e) {}
    }
    /* 换行/分号先换成逗号作为语句分隔，再抹去其余空白 */
    t = t.replace(/[\r\n;]+/g, ',');
    t = t.replace(/[ \t\u00A0]+/g, '');
    /* 合并连续逗号，去掉首尾逗号 */
    t = t.replace(/,+/g, ',').replace(/^,|,$/g, '');
    /* 粘连函数名拆分：sintheta → sin(theta) 留待 math.js 处理 */
    t = t.replace(/(sin|cos|tan|sec|csc|cot|ln|exp|sqrt)(theta|[a-z])/g, '$1($2)');
    return t;
  },

  /* 判断输入类型 */
  classify: function (raw) {
    var s = this.clean(raw);
    if (!s) return 'empty';

    /* 参数方程：同时出现 x= 与 y=（不依赖逗号或空格） */
    var hasXeq = /(^|[^a-z])x=/.test(s) || /^x=/.test(s);
    var hasYeq = /(^|[^a-z])y=/.test(s) || /^y=/.test(s);
    if (hasXeq && hasYeq) {
      var ix = s.indexOf('x='), iy = s.indexOf('y=');
      if (ix >= 0 && iy >= 0) {
        var beforeX = s.slice(Math.max(0, ix - 1), ix);
        var beforeY = s.slice(Math.max(0, iy - 1), iy);
        var okX = ix === 0 || /[),;]/.test(beforeX);
        var okY = iy === 0 || /[),;]/.test(beforeY);
        if (okX && okY) {
          var seg = s.slice(ix + 2, iy).replace(/[),;]+$/, '');
          if (seg.indexOf('=') < 0) return 'param';
        }
      }
    }
    /* 含不等号且带 x/y 的二元式 → 不等式区域 */
    if (/[<>]=?/.test(s) && /[xy]/.test(s)) {
      if (/^(y|x)[<>]=?/.test(s)) return 'ineq';
      if (/[xy].*[<>]=?.*[xy]/.test(s)) return 'ineq';
    }
    /* 有等号但不是 y= / r= 开头 → 隐式方程 */
    if (s.indexOf('=') >= 0) {
      if (/^y=/.test(s)) return 'explicit';
      if (/^r=/.test(s)) return 'polar';
      return 'implicit';
    }
    /* 极坐标：含 theta 且没有 y= */
    if (s.indexOf('theta') >= 0) return 'polar';
    /* 默认显函数 */
    return 'explicit';
  },

  /* 取表达式主体（去前缀） */
  body: function (raw, type) {
    var s = this.clean(raw);
    if (type === 'explicit') return s.replace(/^y=/, '');
    if (type === 'polar') return s.replace(/^r=/, '');
    return s;
  }
};

/* ============================================================
 * 参数检测（AST 遍历，不是正则）
 * ============================================================ */
function findParams(expr, varNames) {
  var exclude = {};
  (varNames || []).forEach(function (v) { exclude[v] = 1; });
  ['x', 'y', 't', 'theta', 'pi', 'e', 'i'].forEach(function (v) { exclude[v] = 1; });
  /* math.js 内置函数名全部排除 */
  var builtin = ('sin cos tan sec csc cot asin acos atan sinh cosh tanh asinh acosh atanh ' +
    'sqrt cbrt abs sign exp log log10 log2 ln floor ceil round fix mod gcd lcm hypot ' +
    'factorial gamma erf max min sum prod mean median std var pow nthRoot re im arg conj')
    .split(' ');
  builtin.forEach(function (v) { exclude[v] = 1; });

  var found = [];
  try {
    var node = math.parse(expr);
    node.traverse(function (n, path, parent) {
      if (n.isSymbolNode) {
        var name = n.name;
        if (exclude[name]) return;
        /* 函数名（后面跟括号）不算参数 */
        if (parent && parent.isFunctionNode && parent.fn === n) return;
        if (found.indexOf(name) < 0) found.push(name);
      }
    });
  } catch (e) { }
  return found;
}

/* ============================================================
 * 坐标画板（照搬 HTML 的 Plane）
 * ============================================================ */
function Plane(canvas, opt) {
  this.c = canvas;
  this.ctx = canvas.getContext('2d');
  this.o = { x0: -10, x1: 10, y0: -10, y1: 10, pan: true, zoom: true };
  if (opt) for (var k in opt) this.o[k] = opt[k];
  this.w = 300; this.h = 300;
  this.layers = [];
  this.bind();
  this.resize();
}

Plane.prototype.sx = function (x) { return (x - this.o.x0) / (this.o.x1 - this.o.x0) * this.w; };
Plane.prototype.sy = function (y) { return (this.o.y1 - y) / (this.o.y1 - this.o.y0) * this.h; };
Plane.prototype.wx = function (px) { return this.o.x0 + px / this.w * (this.o.x1 - this.o.x0); };
Plane.prototype.wy = function (py) { return this.o.y1 - py / this.h * (this.o.y1 - this.o.y0); };

Plane.prototype.resize = function () {
  var r = this.c.getBoundingClientRect();
  var w = Math.max(160, Math.round(r.width || 320));
  var h = w;   // 正方形
  this.w = w; this.h = h;
  var dpr = Math.min(2, window.devicePixelRatio || 1);
  this.c.style.height = h + 'px';
  this.c.width = Math.round(w * dpr);
  this.c.height = Math.round(h * dpr);
  this.ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
  this.draw();
};

Plane.prototype.setRange = function (x0, x1, y0, y1) {
  this.o.x0 = x0; this.o.x1 = x1; this.o.y0 = y0; this.o.y1 = y1;
  this.draw();
};

Plane.prototype.step = function () {
  var lo = Math.min(this.o.x1 - this.o.x0, this.o.y1 - this.o.y0) / 8;
  var cands = [0.1, 0.2, 0.5, 1, 2, 5, 10, 20, 50, 100];
  for (var i = 0; i < cands.length; i++) if (cands[i] >= lo) return cands[i];
  return 100;
};

Plane.prototype.clear = function () { this.layers = []; };
Plane.prototype.add = function (fn) { this.layers.push(fn); };

Plane.prototype.draw = function () {
  var c = this.ctx, o = this.o, W = this.w, H = this.h;
  if (!c) return;
  c.clearRect(0, 0, W, H);
  c.fillStyle = '#fdfcf9';
  c.fillRect(0, 0, W, H);

  var st = this.step();

  /* 网格 */
  c.lineWidth = 1;
  var gx = Math.ceil(o.x0 / st) * st;
  for (; gx <= o.x1 + 1e-9; gx += st) {
    var px = Math.round(this.sx(gx)) + 0.5;
    c.strokeStyle = Math.abs(gx) < 1e-9 ? 'rgba(150,144,127,.45)' : 'rgba(150,144,127,.16)';
    c.beginPath(); c.moveTo(px, 0); c.lineTo(px, H); c.stroke();
  }
  var gy = Math.ceil(o.y0 / st) * st;
  for (; gy <= o.y1 + 1e-9; gy += st) {
    var py = Math.round(this.sy(gy)) + 0.5;
    c.strokeStyle = Math.abs(gy) < 1e-9 ? 'rgba(150,144,127,.45)' : 'rgba(150,144,127,.16)';
    c.beginPath(); c.moveTo(0, py); c.lineTo(W, py); c.stroke();
  }

  /* 坐标轴 */
  var ax = this.sx(0), ay = this.sy(0);
  var hasX = o.x0 <= 0 && o.x1 >= 0;
  var hasY = o.y0 <= 0 && o.y1 >= 0;
  c.strokeStyle = '#8a8478';
  c.lineWidth = 1.4;
  if (hasX) { c.beginPath(); c.moveTo(0, ay); c.lineTo(W - 2, ay); c.stroke(); }
  if (hasY) { c.beginPath(); c.moveTo(ax, H); c.lineTo(ax, 2); c.stroke(); }

  /* 刻度数字 */
  c.font = '10px ui-monospace,Menlo,monospace';
  c.fillStyle = '#96907f';
  c.textAlign = 'center';
  var tx = Math.ceil(o.x0 / st) * st;
  for (; tx <= o.x1 + 1e-9; tx += st) {
    if (Math.abs(tx) < st * 0.01) continue;
    var tpx = this.sx(tx);
    c.fillText(fmtTick(tx), tpx, Math.min(H - 3, Math.max(11, ay + 13)));
  }
  c.textAlign = 'right';
  var ty = Math.ceil(o.y0 / st) * st;
  for (; ty <= o.y1 + 1e-9; ty += st) {
    if (Math.abs(ty) < st * 0.01) continue;
    var tpy = this.sy(ty);
    c.fillText(fmtTick(ty), Math.max(30, ax - 5), tpy + 3);
  }
  c.textAlign = 'left';

  /* 业务图层 */
  for (var i = 0; i < this.layers.length; i++) {
    try { this.layers[i](c, this); } catch (e) { }
  }
};

Plane.prototype.bind = function () {
  var self = this, c = this.c;
  var drag = null;

  c.addEventListener('pointerdown', function (e) {
    try { c.setPointerCapture(e.pointerId); } catch (err) {}
    drag = { x: e.clientX, y: e.clientY,
             b: { x0: self.o.x0, x1: self.o.x1, y0: self.o.y0, y1: self.o.y1 } };
  });

  c.addEventListener('pointermove', function (e) {
    if (!drag) return;
    var dx = e.clientX - drag.x, dy = e.clientY - drag.y;
    if (Math.abs(dx) + Math.abs(dy) < 3) return;
    var ux = (drag.b.x1 - drag.b.x0) / self.w;
    var uy = (drag.b.y1 - drag.b.y0) / self.h;
    self.o.x0 = drag.b.x0 - dx * ux;
    self.o.x1 = drag.b.x1 - dx * ux;
    self.o.y0 = drag.b.y0 + dy * uy;
    self.o.y1 = drag.b.y1 + dy * uy;
    self.draw();
  });

  function end() { drag = null; }
  c.addEventListener('pointerup', end);
  c.addEventListener('pointercancel', end);
};

function fmtTick(v) {
  var r = Math.round(v * 100) / 100;
  if (Math.abs(r - Math.round(r)) < 1e-9) return String(Math.round(r));
  return String(r);
}

/* ============================================================
 * 曲线绘制
 * ============================================================ */

/* 画 y = f(x)，带自动断点检测 */
function plot(c, P, fn, o) {
  o = o || {};
  var color = o.color || '#2f6f4e';
  var lw = o.w || 2.2;
  var W = P.w, H = P.h;
  var N = Math.max(300, Math.round(W * 1.2));
  var pts = [];
  var prevOk = false;

  for (var i = 0; i <= N; i++) {
    var px = i / N * W;
    var x = P.wx(px);
    var y;
    try { y = fn(x); } catch (e) { y = NaN; }
    var ok = y != null && isFinite(y) && Math.abs(y) < 1e8;
    if (ok) {
      var py = P.sy(y);
      if (prevOk && pts.length) {
        var last = pts[pts.length - 1];
        if (last && last.brk !== 1 && Math.abs(py - last.py) > H * 1.4) pts.push({ brk: 1 });
      }
      pts.push({ x: px, py: py });
      prevOk = true;
    } else {
      if (pts.length && pts[pts.length - 1].brk !== 1) pts.push({ brk: 1 });
      prevOk = false;
    }
  }

  c.save();
  c.beginPath();
  c.rect(0, 0, W, H);
  c.clip();
  c.beginPath();
  var pen = false;
  for (var j = 0; j < pts.length; j++) {
    var p = pts[j];
    if (p.brk === 1) { pen = false; continue; }
    if (!pen) { c.moveTo(p.x, p.py); pen = true; }
    else c.lineTo(p.x, p.py);
  }
  c.strokeStyle = color;
  c.lineWidth = lw;
  c.lineJoin = 'round';
  c.lineCap = 'round';
  c.stroke();
  c.restore();
}

/* 极坐标：rf(rad) 返回 r */
function plotPolar(c, P, rf, o) {
  o = o || {};
  var N = o.samples || 2000;
  var W = P.w, H = P.h;
  c.save();
  c.beginPath();
  c.rect(0, 0, W, H);
  c.clip();
  c.beginPath();
  var pen = false;
  for (var i = 0; i <= N; i++) {
    var rad = i / N * Math.PI * 2;
    var r;
    try { r = rf(rad); } catch (e) { r = NaN; }
    if (r == null || !isFinite(r) || Math.abs(r) > 1e6) { pen = false; continue; }
    var px = P.sx(r * Math.cos(rad)), py = P.sy(r * Math.sin(rad));
    if (!pen) { c.moveTo(px, py); pen = true; }
    else c.lineTo(px, py);
  }
  c.strokeStyle = o.color || '#17803d';
  c.lineWidth = o.w || 2.2;
  c.lineJoin = 'round';
  c.lineCap = 'round';
  c.stroke();
  c.restore();
}

/* 参数方程 x=fx(t), y=fy(t) */
function plotParam(c, P, fx, fy, t0, t1, o) {
  o = o || {};
  var N = o.samples || 2000;
  var W = P.w, H = P.h;
  c.save();
  c.beginPath();
  c.rect(0, 0, W, H);
  c.clip();
  c.beginPath();
  var pen = false;
  for (var i = 0; i <= N; i++) {
    var t = t0 + (t1 - t0) * i / N;
    var x, y;
    try { x = fx(t); y = fy(t); } catch (e) { x = NaN; y = NaN; }
    if (x == null || y == null || !isFinite(x) || !isFinite(y)) { pen = false; continue; }
    if (Math.abs(x) > 1e6 || Math.abs(y) > 1e6) { pen = false; continue; }
    var px = P.sx(x), py = P.sy(y);
    if (!pen) { c.moveTo(px, py); pen = true; }
    else c.lineTo(px, py);
  }
  c.strokeStyle = o.color || '#1b6ef3';
  c.lineWidth = o.w || 2.2;
  c.lineJoin = 'round';
  c.lineCap = 'round';
  c.stroke();
  c.restore();
}

/* Marching Squares：绘制 F(x,y)=0 的等值线 */
function marchingSquares(P, F, color, step) {
  var ctx = P.ctx;
  var w = P.w, h = P.h;
  var cs = step || 3;
  var cols = Math.ceil(w / cs), rows = Math.ceil(h / cs);

  var grid = [];
  for (var i = 0; i <= cols; i++) {
    grid[i] = [];
    for (var j = 0; j <= rows; j++) {
      var wx = P.wx(i * cs), wy = P.wy(j * cs);
      var v;
      try { v = F(wx, wy); } catch (e) { v = NaN; }
      grid[i][j] = isFinite(v) ? v : NaN;
    }
  }

  ctx.save();
  ctx.strokeStyle = color;
  ctx.lineWidth = 1.8;
  ctx.lineCap = 'round';
  ctx.beginPath();

  function lerp(p1, p2, v1, v2) {
    var t = v1 / (v1 - v2);
    if (!isFinite(t)) t = 0.5;
    return [p1[0] + (p2[0] - p1[0]) * t, p1[1] + (p2[1] - p1[1]) * t];
  }

  for (var ci = 0; ci < cols; ci++) {
    for (var cj = 0; cj < rows; cj++) {
      var v00 = grid[ci][cj];
      var v10 = grid[ci + 1][cj];
      var v01 = grid[ci][cj + 1];
      var v11 = grid[ci + 1][cj + 1];
      if (!isFinite(v00) || !isFinite(v10) || !isFinite(v01) || !isFinite(v11)) continue;

      var x0 = ci * cs, x1 = (ci + 1) * cs;
      var y0 = cj * cs, y1 = (cj + 1) * cs;

      var idx = 0;
      if (v00 > 0) idx |= 1;
      if (v10 > 0) idx |= 2;
      if (v11 > 0) idx |= 4;
      if (v01 > 0) idx |= 8;
      if (idx === 0 || idx === 15) continue;

      var top = null, right = null, bottom = null, left = null;
      function getTop() { return top || (top = lerp([x0, y0], [x1, y0], v00, v10)); }
      function getRight() { return right || (right = lerp([x1, y0], [x1, y1], v10, v11)); }
      function getBottom() { return bottom || (bottom = lerp([x0, y1], [x1, y1], v01, v11)); }
      function getLeft() { return left || (left = lerp([x0, y0], [x0, y1], v00, v01)); }

      function seg(a, b) {
        ctx.moveTo(a[0], a[1]);
        ctx.lineTo(b[0], b[1]);
      }

      switch (idx) {
        case 1: case 14: seg(getLeft(), getTop()); break;
        case 2: case 13: seg(getTop(), getRight()); break;
        case 3: case 12: seg(getLeft(), getRight()); break;
        case 4: case 11: seg(getRight(), getBottom()); break;
        case 6: case 9: seg(getTop(), getBottom()); break;
        case 7: case 8: seg(getLeft(), getBottom()); break;
        case 5: seg(getLeft(), getTop()); seg(getRight(), getBottom()); break;
        case 10: seg(getTop(), getRight()); seg(getLeft(), getBottom()); break;
      }
    }
  }

  ctx.stroke();
  ctx.restore();
}

/* ============================================================
 * 主流程
 * ============================================================ */
var cv = document.getElementById('cv');
var inp = document.getElementById('inp');
var xr = document.getElementById('xr');
var yr = document.getElementById('yr');
var info = document.getElementById('info');
var paramBox = document.getElementById('params');
var lbl = document.getElementById('lbl');
var modesBar = document.getElementById('modes');
var presetsBar = document.getElementById('presets');

var P = new Plane(cv);

/* 当前参数值 */
var params = {};
/* 当前绘制回调 */
var state = { draw: null };

/* 手动锁定模式（null = 自动识别） */
var forced = null;

var MODES = [
  { id: null,     name: '自动' },
  { id: 'explicit', name: 'y=f(x)' },
  { id: 'polar',    name: 'r=f(θ)' },
  { id: 'param',    name: '参数方程' },
  { id: 'implicit', name: '隐函数' },
  { id: 'ineq',     name: '不等式' }
];

var PRESETS = [
  ['圆', 'x^2 + y^2 = 25'],
  ['心形线', 'r = 1 - sin(theta)'],
  ['双曲线', 'x^2/9 - y^2/16 = 1'],
  ['叶形线', 'x^3 + y^3 = 3*x*y'],
  ['摆线', 'x = t - sin(t), y = 1 - cos(t)'],
  ['利萨如', 'x = sin(3t), y = sin(4t)'],
  ['玫瑰线', 'r = 2*cos(3*theta)'],
  ['抛物线', 'y = x^2 - 4x + 3'],
  ['对数螺线', 'r = e^(0.2*theta)'],
  ['区域', 'y < x^2 - 2'],
  ['心跳线', 'y = (x^2)^(1/3) + 0.9*(8 - x^2)^(1/2)*sin(a*pi*x)']
];

function buildModes() {
  modesBar.innerHTML = '';
  MODES.forEach(function (m) {
    var b = document.createElement('button');
    b.className = 'mode' + (forced === m.id ? ' on' : '');
    b.textContent = m.name;
    b.onclick = function () {
      forced = m.id;
      buildModes();
      if (m.id) lbl.textContent = labelFor(m.id);
      run();
    };
    modesBar.appendChild(b);
  });
}

function buildPresets() {
  presetsBar.innerHTML = '';
  PRESETS.forEach(function (ex) {
    var b = document.createElement('button');
    b.className = 'preset';
    b.textContent = ex[0];
    b.onclick = function () {
      inp.value = ex[1];
      forced = null;          // 重置为自动
      buildModes();
      run();
    };
    presetsBar.appendChild(b);
  });
}

function labelFor(type) {
  switch (type) {
    case 'explicit': return 'y = f(x)';
    case 'polar': return 'r = f(θ)';
    case 'param': return 'x = f(t), y = g(t)';
    case 'implicit': return 'F(x, y) = 0';
    case 'ineq': return '不等式';
    default: return '表达式';
  }
}

/* ---------- 参数滑块 ---------- */

function guessParamDefault(name) {
  if (name === 'n') return 2;
  return 1;
}

/* 自适应上限：拉满后应能看到明显变化 */
function autoParamMax(name) {
  var src = G.clean(inp.value);
  var inTrig = new RegExp('(sin|cos|tan)\\([^()]*\\b' + name + '\\b').test(src);
  if (inTrig) return 50;
  var asExp = new RegExp('\\^\\s*\\(?\\s*' + name + '\\b').test(src);
  if (asExp) return 10;
  var asCoef = new RegExp('\\b' + name + '\\s*\\*?\\s*[xyt]').test(src);
  if (asCoef) return 20;
  return 20;
}

function buildParamSliders(names) {
  paramBox.innerHTML = '';
  if (!names || !names.length) return;

  var head = document.createElement('div');
  head.style.cssText = 'font-size:11px;color:#9a9a9a;margin-bottom:7px';
  head.textContent = '检测到参数，拖动实时改变图像';
  paramBox.appendChild(head);

  names.forEach(function (name) {
    if (params[name] === undefined) params[name] = guessParamDefault(name);

    var row = document.createElement('div');
    row.className = 'paramRow';

    var nm = document.createElement('span');
    nm.className = 'nm';
    nm.textContent = name;
    row.appendChild(nm);

    var rng = document.createElement('input');
    rng.type = 'range';

    var autoMax = autoParamMax(name);
    var max = autoMax;
    if (params[name] > max) max = params[name] * 2;
    rng.min = 0;
    rng.max = max;
    rng.step = max / 400;
    rng.value = Math.min(params[name], max);
    row.appendChild(rng);

    var vl = document.createElement('span');
    vl.className = 'vl';
    vl.textContent = params[name].toFixed(2);
    row.appendChild(vl);

    rng.addEventListener('input', function () {
      params[name] = parseFloat(rng.value);
      vl.textContent = params[name].toFixed(2);
      redraw();
    });

    paramBox.appendChild(row);
  });
}

/* ---------- 编译 ---------- */

function mathCompileVars(expr, extraVars) {
  if (!window.math) throw new Error('math.js 未加载，请检查网络');
  var node = math.parse(expr);
  var code = node.compile();
  var scope = {};
  return function (v) {
    scope[extraVars] = v;
    for (var k in params) scope[k] = params[k];
    var r = code.evaluate(scope);
    if (typeof r === 'number') return r;
    if (r && typeof r.re === 'number') return r.re;
    return NaN;
  };
}

/* ---------- 重新绘制（不重新解析） ---------- */

function redraw() {
  if (!state.draw) return;
  P.layers = [];
  state.draw(P.ctx, P);
  P.draw();
}

/* ---------- 主入口 ---------- */

function run() {
  if (!window.math) {
    info.className = 'info err';
    info.textContent = 'math.js 未加载。请检查网络后重新打开此页面。';
    return;
  }

  var raw = inp.value;
  var type = forced || G.classify(raw);

  if (type === 'empty' || !G.clean(raw)) {
    info.className = 'info';
    info.textContent = '请输入表达式';
    P.layers = [];
    state.draw = null;
    P.draw();
    return;
  }

  lbl.textContent = labelFor(type);
  info.className = 'info';

  var xr2 = xr.value.split(',').map(parseFloat);
  var yr2 = yr.value.split(',').map(parseFloat);
  var bx0 = isFinite(xr2[0]) ? xr2[0] : -10;
  var bx1 = isFinite(xr2[1]) ? xr2[1] : 10;
  var by0 = isFinite(yr2[0]) ? yr2[0] : -10;
  var by1 = isFinite(yr2[1]) ? yr2[1] : 10;
  if (bx1 <= bx0) { bx0 = -10; bx1 = 10; }
  if (by1 <= by0) { by0 = -10; by1 = 10; }

  try {
    if (type === 'explicit') {
      var body = G.body(raw, 'explicit');
      var pnames = findParams(body, ['x']);
      buildParamSliders(pnames);

      var f = mathCompileVars(body, 'x');

      /* 自动缩放纵轴 */
      var lo = Infinity, hi = -Infinity;
      for (var i = 0; i <= 400; i++) {
        var xx = bx0 + (bx1 - bx0) * i / 400;
        var yy;
        try { yy = f(xx); } catch (e) { continue; }
        if (!isFinite(yy) || Math.abs(yy) > 1e8) continue;
        if (yy < lo) lo = yy;
        if (yy > hi) hi = yy;
      }
      if (isFinite(lo) && isFinite(hi) && hi - lo < 300) {
        var pad = (hi - lo) * 0.15 || 1;
        by0 = lo - pad;
        by1 = hi + pad;
      }

      state.draw = function (c, p) {
        plot(c, p, f, { color: '#1b6ef3', w: 2.2 });
      };
      P.setRange(bx0, bx1, by0, by1);
      info.textContent = '显函数  y = ' + body + (pnames.length ? '\n参数：' + pnames.join(', ') : '');

    } else if (type === 'polar') {
      var pbody = G.body(raw, 'polar');
      var ppnames = findParams(pbody, ['theta']);
      buildParamSliders(ppnames);

      var rf = mathCompileVars(pbody, 'theta');

      state.draw = function (c, p) {
        plotPolar(c, p, rf, { color: '#17803d', w: 2.2 });
      };
      P.setRange(-10, 10, -10, 10);
      info.textContent = '极坐标  r = ' + pbody + (ppnames.length ? '\n参数：' + ppnames.join(', ') : '');

    } else if (type === 'param') {
      var s = G.clean(raw);
      var ix = s.indexOf('x='), iy = s.indexOf('y=');
      var sx, sy;
      if (ix < iy) {
        sx = s.slice(ix + 2, iy).replace(/[),;]+$/, '');
        sy = s.slice(iy + 2).replace(/^[),;]+/, '');
      } else {
        sy = s.slice(iy + 2, ix).replace(/[),;]+$/, '');
        sx = s.slice(ix + 2).replace(/^[),;]+/, '');
      }
      var pn2 = findParams('(' + sx + ')+(' + sy + ')', ['t']);
      buildParamSliders(pn2);

      var fx = mathCompileVars(sx, 't');
      var fy = mathCompileVars(sy, 't');

      state.draw = function (c, p) {
        plotParam(c, p, fx, fy, 0, Math.PI * 2, { color: '#1b6ef3', w: 2.2 });
      };
      P.setRange(bx0, bx1, by0, by1);
      info.textContent = '参数方程\nx = ' + sx + '\ny = ' + sy;

    } else if (type === 'implicit') {
      var s2 = G.clean(raw);
      var eq = s2.indexOf('=');
      var lhs = s2.slice(0, eq), rhs = s2.slice(eq + 1);
      var full = '(' + lhs + ')-(' + rhs + ')';

      var ipnames = findParams(full, ['x', 'y']);
      buildParamSliders(ipnames);

      var node = math.parse(full);
      var code = node.compile();
      var scope = {};
      var F = function (x, y) {
        scope.x = x; scope.y = y;
        for (var k in params) scope[k] = params[k];
        var r = code.evaluate(scope);
        if (typeof r === 'number') return r;
        if (r && typeof r.re === 'number') return r.re;
        return NaN;
      };

      state.draw = function (c, p) {
        marchingSquares(p, F, '#1b6ef3', 3);
      };
      P.setRange(bx0, bx1, by0, by1);
      info.textContent = '隐函数  ' + lhs + ' = ' + rhs +
        (ipnames.length ? '\n参数：' + ipnames.join(', ') : '');

    } else if (type === 'ineq') {
      var s3 = G.clean(raw);
      var m = s3.match(/^(.+?)(<=|>=|<|>|!=)(.+)$/);
      if (!m) throw new Error('不等式格式无法识别');

      var el1 = m[1], op = m[2], er1 = m[3];
      var full2 = '(' + el1 + ')-(' + er1 + ')';

      var qnames = findParams(full2, ['x', 'y']);
      buildParamSliders(qnames);

      var nd2 = math.parse(full2);
      var cd2 = nd2.compile();
      var sc2 = {};
      var F2 = function (x, y) {
        sc2.x = x; sc2.y = y;
        for (var k in params) sc2[k] = params[k];
        var r = cd2.evaluate(sc2);
        if (typeof r === 'number') return r;
        if (r && typeof r.re === 'number') return r.re;
        return NaN;
      };

      state.draw = function (c, p) {
        /* 区域填充：按像素采样，满足条件就填色 */
        var cs = 4;
        for (var px = 0; px < p.w; px += cs) {
          for (var py = 0; py < p.h; py += cs) {
            var wx = p.wx(px), wy = p.wy(py);
            var v;
            try { v = F2(wx, wy); } catch (e) { v = NaN; }
            if (!isFinite(v)) continue;
            var ok = false;
            if (op === '<') ok = v < 0;
            else if (op === '>') ok = v > 0;
            else if (op === '<=') ok = v <= 0;
            else if (op === '>=') ok = v >= 0;
            if (ok) {
              c.fillStyle = 'rgba(27,110,243,0.18)';
              c.fillRect(px, py, cs, cs);
            }
          }
        }
        /* 边界线 */
        marchingSquares(p, F2, '#1b6ef3', 3);
      };
      P.setRange(bx0, bx1, by0, by1);
      info.textContent = '不等式区域  ' + el1 + ' ' + op + ' ' + er1;

    } else {
      throw new Error('无法识别的输入类型');
    }

    redraw();

  } catch (e) {
    info.className = 'info err';
    info.textContent = '解析失败：' + (e.message || e);
    P.layers = [];
    state.draw = null;
    P.draw();
  }
}

/* ---------- 事件绑定 ---------- */

var timer = null;
inp.addEventListener('input', function () {
  if (timer) clearTimeout(timer);
  timer = setTimeout(run, 320);
});
xr.addEventListener('change', run);
yr.addEventListener('change', run);

window.addEventListener('resize', function () { P.resize(); redraw(); });

/* 初始状态 */
buildModes();
buildPresets();
inp.value = 'y = (x^2)^(1/3) + 0.9*(8 - x^2)^(1/2)*sin(a*pi*x)';
xr.value = '-3,3';
yr.value = '-2,3';

function boot() {
  if (!window.math) {
    info.className = 'info err';
    info.textContent = '正在加载 math.js…\n如果长时间无响应，请检查网络连接。';
    setTimeout(boot, 400);
    return;
  }
  run();
}
setTimeout(boot, 200);
