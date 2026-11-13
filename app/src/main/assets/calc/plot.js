/* 绘图模块 - 提取自数学教学.html 的 Plane / plot / plotPolar / plotParam
 * 逻辑未改，只精简掉本计算器用不到的回调。
 */
(function (global) {
  'use strict';

  function Plane(canvas, opt) {
    this.c = canvas;
    this.ctx = canvas.getContext('2d');
    this.o = { x0: -8, x1: 8, y0: -8, y1: 8, grid: true, axis: true, label: true, pan: true, zoom: true };
    if (opt) for (var k in opt) this.o[k] = opt[k];
    this.w = 300; this.h = 200;
    this.layers = [];
    this.drag = null;
    this.onHover = null;
    this.bind();
    this.resize();
  }

  Plane.prototype.sx = function (x) { return (x - this.o.x0) / (this.o.x1 - this.o.x0) * this.w; };
  Plane.prototype.sy = function (y) { return (this.o.y1 - y) / (this.o.y1 - this.o.y0) * this.h; };
  Plane.prototype.wx = function (px) { return this.o.x0 + px / this.w * (this.o.x1 - this.o.x0); };
  Plane.prototype.wy = function (py) { return this.o.y1 - py / this.h * (this.o.y1 - this.o.y0); };

  Plane.prototype.resize = function () {
    var r = this.c.getBoundingClientRect();
    var w = Math.max(140, Math.round(r.width || 300));
    var h = this.o.h || Math.round(w * (w < 480 ? 0.82 : 0.6));
    h = Math.max(150, Math.min(420, h));
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

  Plane.prototype.clear = function () { this.layers = []; };
  Plane.prototype.add = function (fn) { this.layers.push(fn); };

  Plane.prototype.step = function () {
    var lo = Math.min(this.o.x1 - this.o.x0, this.o.y1 - this.o.y0) / 8;
    var cands = [0.1, 0.2, 0.5, 1, 2, 5, 10, 20, 50, 100];
    for (var i = 0; i < cands.length; i++) if (cands[i] >= lo) return cands[i];
    return 100;
  };

  function numFmt(v, d) {
    if (v == null || !isFinite(v)) return '—';
    var r = Number(Number(v).toFixed(d == null ? 2 : d));
    return String(r);
  }

  Plane.prototype.draw = function () {
    var c = this.ctx, o = this.o, W = this.w, H = this.h;
    if (!c) return;
    c.clearRect(0, 0, W, H);
    c.fillStyle = '#fdfcf9';
    c.fillRect(0, 0, W, H);

    var st = this.step();

    if (o.grid) {
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
    }

    var ax = this.sx(0), ay = this.sy(0);
    var hasX = o.x0 <= 0 && o.x1 >= 0;
    var hasY = o.y0 <= 0 && o.y1 >= 0;

    if (o.axis) {
      c.strokeStyle = '#8a8478';
      c.lineWidth = 1.4;
      if (hasX) { c.beginPath(); c.moveTo(0, ay); c.lineTo(W - 2, ay); c.stroke(); }
      if (hasY) { c.beginPath(); c.moveTo(ax, H); c.lineTo(ax, 2); c.stroke(); }
      var arrow = function (x1, y1, x2, y2) {
        var a = Math.atan2(y2 - y1, x2 - x1), L = 7;
        c.beginPath();
        c.moveTo(x2, y2);
        c.lineTo(x2 - L * Math.cos(a - 0.42), y2 - L * Math.sin(a - 0.42));
        c.lineTo(x2 - L * Math.cos(a + 0.42), y2 - L * Math.sin(a + 0.42));
        c.closePath();
        c.fillStyle = '#8a8478';
        c.fill();
      };
      if (hasX) arrow(0, ay, W - 2, ay);
      if (hasY) arrow(ax, H, ax, 2);
    }

    if (o.label) {
      c.font = '10px ui-monospace,Menlo,monospace';
      c.fillStyle = '#96907f';
      c.strokeStyle = '#b9b09c';
      c.lineWidth = 1;
      var tx = Math.ceil(o.x0 / st) * st;
      for (; tx <= o.x1 + 1e-9; tx += st) {
        if (Math.abs(tx) < st * 0.01) continue;
        var tpx = this.sx(tx);
        c.beginPath(); c.moveTo(tpx, ay - 3); c.lineTo(tpx, ay + 3); c.stroke();
        var lb = numFmt(tx, 2);
        c.fillText(lb, tpx - c.measureText(lb).width / 2, Math.min(H - 3, Math.max(11, ay + 14)));
      }
      var ty = Math.ceil(o.y0 / st) * st;
      for (; ty <= o.y1 + 1e-9; ty += st) {
        if (Math.abs(ty) < st * 0.01) continue;
        var tpy = this.sy(ty);
        c.beginPath(); c.moveTo(ax - 3, tpy); c.lineTo(ax + 3, tpy); c.stroke();
        var lb2 = numFmt(ty, 2);
        c.fillText(lb2, Math.max(3, ax - c.measureText(lb2).width - 6), tpy + 3);
      }
    }

    for (var i = 0; i < this.layers.length; i++) {
      try { this.layers[i](c, this); } catch (e) { }
    }
  };

  Plane.prototype.bind = function () {
    var self = this, c = this.c;
    c.addEventListener('pointerdown', function (e) {
      if (!self.o.pan) return;
      try { c.setPointerCapture(e.pointerId); } catch (err) { }
      self.drag = { x: e.clientX, y: e.clientY, b: { x0: self.o.x0, x1: self.o.x1, y0: self.o.y0, y1: self.o.y1 }, moved: false };
    });
    c.addEventListener('pointermove', function (e) {
      if (!self.drag) return;
      var dx = e.clientX - self.drag.x, dy = e.clientY - self.drag.y;
      if (Math.abs(dx) + Math.abs(dy) > 3) self.drag.moved = true;
      if (!self.drag.moved) return;
      var ux = (self.drag.b.x1 - self.drag.b.x0) / self.w;
      var uy = (self.drag.b.y1 - self.drag.b.y0) / self.h;
      self.o.x0 = self.drag.b.x0 - dx * ux;
      self.o.x1 = self.drag.b.x1 - dx * ux;
      self.o.y0 = self.drag.b.y0 + dy * uy;
      self.o.y1 = self.drag.b.y1 + dy * uy;
      self.draw();
    });
    c.addEventListener('pointerup', function () { self.drag = null; });
    c.addEventListener('pointercancel', function () { self.drag = null; });
  };

  // 画 y = fn(x)，带自动断点检测
  function plot(P, fn, o) {
    o = o || {};
    var c = P.ctx;
    var color = o.color || '#2f6f4e';
    var lw = o.w || 2;
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

  // 极坐标 r = f(theta)，theta 为弧度
  function plotPolarRad(P, rf, o) {
    o = o || {};
    var c = P.ctx;
    var N = o.samples || 3000;
    c.save();
    c.beginPath();
    c.rect(0, 0, P.w, P.h);
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
    c.strokeStyle = o.color || '#2f6f4e';
    c.lineWidth = o.w || 2;
    c.lineJoin = 'round';
    c.lineCap = 'round';
    c.stroke();
    c.restore();
  }

  // 参数方程 x=fx(t), y=fy(t)
  function plotParam(P, fx, fy, t0, t1, o) {
    o = o || {};
    var c = P.ctx;
    var N = o.samples || 3000;
    c.save();
    c.beginPath();
    c.rect(0, 0, P.w, P.h);
    c.clip();
    c.beginPath();
    var pen = false;
    for (var i = 0; i <= N; i++) {
      var tt = t0 + (t1 - t0) * i / N;
      var x, y;
      try { x = fx(tt); y = fy(tt); } catch (e) { x = NaN; y = NaN; }
      if (x == null || y == null || !isFinite(x) || !isFinite(y)) { pen = false; continue; }
      if (Math.abs(x) > 1e6 || Math.abs(y) > 1e6) { pen = false; continue; }
      var px = P.sx(x), py = P.sy(y);
      if (!pen) { c.moveTo(px, py); pen = true; }
      else c.lineTo(px, py);
    }
    c.strokeStyle = o.color || '#2f6f4e';
    c.lineWidth = o.w || 2;
    c.lineJoin = 'round';
    c.lineCap = 'round';
    c.stroke();
    c.restore();
  }

  global.Plot = {
    Plane: Plane,
    plot: plot,
    plotPolarRad: plotPolarRad,
    plotParam: plotParam
  };
})(window);
