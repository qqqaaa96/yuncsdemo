/* 表达式引擎 - 提取自数学教学.html
 * 包含：normalizeInput / splitWord / lex / buildAst / evalAst / compile / asPoly
 * 算法与原作者一致，未改逻辑。
 */
(function (global) {
  'use strict';

  // 基础函数库（sin/cos/tan 为角度制，与原实现一致）
  var FN = {
    sqrt: function (x) { return Math.sqrt(x); },
    abs: function (x) { return Math.abs(x); },
    sin: function (x) { return Math.sin(x * Math.PI / 180); },
    cos: function (x) { return Math.cos(x * Math.PI / 180); },
    tan: function (x) { return Math.tan(x * Math.PI / 180); },
    ln: function (x) { return Math.log(x); },
    log: function (x) { return Math.log(x) / Math.LN10; }
  };

  // 科学计算器扩展函数
  var FN_EXTRA = {
    asin: function (x) { return Math.asin(x) * 180 / Math.PI; },
    acos: function (x) { return Math.acos(x) * 180 / Math.PI; },
    atan: function (x) { return Math.atan(x) * 180 / Math.PI; },
    exp: function (x) { return Math.exp(x); },
    floor: function (x) { return Math.floor(x); },
    ceil: function (x) { return Math.ceil(x); },
    round: function (x) { return Math.round(x); },
    sinh: function (x) { return Math.sinh(x); },
    cosh: function (x) { return Math.cosh(x); },
    tanh: function (x) { return Math.tanh(x); },
    cbrt: function (x) { return Math.cbrt(x); }
  };

  var ALL_FN = Object.assign({}, FN, FN_EXTRA);

  // 输入归一化：中文/全角符号 -> 英文半角，抹去所有空白
  function normalizeInput(src) {
    var s = String(src == null ? '' : src);
    s = s.replace(/[\\uFF01-\\uFF5E]/g, function (ch) {
      return String.fromCharCode(ch.charCodeAt(0) - 0xFEE0);
    });
    s = s.replace(/\\u3000/g, ' ');
    s = s.replace(/[\\uFF08\\uFF3B\\uFF5B\\u3010\\u3008\\u2329\\u300C\\u300E]/g, '(');
    s = s.replace(/[\\uFF09\\uFF3D\\uFF5D\\u3011\\u3009\\u232A\\u300D\\u300F]/g, ')');
    s = s.replace(/[\\u00D7\\u2715\\u2716\\u22C5\\u00B7\\u2219]/g, '*');
    s = s.replace(/[\\u00F7\\u2215\\u2044]/g, '/');
    s = s.replace(/[\\u2212\\u2013\\u2014\\u2015\\uFE63]/g, '-');
    s = s.replace(/[\\uFF0C\\u3001\\uFF1B]/g, ',');
    s = s.replace(/\\u3002/g, '.');
    s = s.replace(/[\\u2070]/g, '^0').replace(/[\\u00B9]/g, '^1');
    s = s.replace(/[\\u00B2]/g, '^2').replace(/[\\u00B3]/g, '^3');
    s = s.replace(/[\\u2074]/g, '^4').replace(/[\\u2075]/g, '^5');
    s = s.replace(/[\\u2076]/g, '^6').replace(/[\\u2077]/g, '^7');
    s = s.replace(/[\\u2078]/g, '^8').replace(/[\\u2079]/g, '^9');
    s = s.replace(/[\\u03C0\\u03A0]/g, 'pi');
    s = s.replace(/[\\u03B8\\u03D1]/g, 'theta');
    s = s.replace(/[\\u221A]/g, 'sqrt');
    s = s.replace(/\\s+/g, '');
    return s;
  }

  function isPolarForm(src) {
    var s = normalizeInput(src);
    return /^r=/.test(s) || /^p=/.test(s);
  }

  function looksPolar(src) {
    var s = normalizeInput(src);
    if (/^r=/.test(s)) return true;
    if (/^\\w+=/.test(s) && s.indexOf('theta') >= 0) return true;
    if (s.indexOf('theta') >= 0) return true;
    return false;
  }

  function prep(src) {
    var s = normalizeInput(src);
    s = s.replace(/^[a-zA-Z]\\s*\\(\\s*[a-zA-Z]\\s*\\)=/, '');
    s = s.replace(/^[a-zA-Z]=/, '');
    return s;
  }

  // 把粘连的标识符拆成已知符号序列
  function splitWord(word) {
    var KNOWN = ['sqrt', 'abs', 'sin', 'cos', 'tan', 'asin', 'acos', 'atan',
      'sinh', 'cosh', 'tanh', 'cbrt',
      'ln', 'log', 'exp', 'floor', 'ceil', 'round',
      'theta', 'pi', 'e', 'x', 'y', 't', 'r'];
    KNOWN.sort(function (a, b) { return b.length - a.length; });
    var out = [];
    var i = 0;
    var guard = 0;
    while (i < word.length && guard++ < 200) {
      var matched = null;
      for (var k = 0; k < KNOWN.length; k++) {
        var cand = KNOWN[k];
        if (word.substr(i, cand.length) === cand) { matched = cand; break; }
      }
      if (matched) {
        out.push(matched);
        i += matched.length;
      } else {
        out.push(word.slice(i));
        break;
      }
    }
    return out.length ? out : [word];
  }

  function lex(s) {
    var t = [], i = 0;
    while (i < s.length) {
      var c = s[i];
      if (/[0-9.]/.test(c)) {
        var j = i;
        while (j < s.length && /[0-9.]/.test(s[j])) j++;
        var v = parseFloat(s.slice(i, j));
        if (!isFinite(v)) throw new Error('数字无效');
        t.push({ k: 'n', v: v });
        i = j;
        continue;
      }
      if (/[a-zA-Z_]/.test(c)) {
        var j2 = i;
        while (j2 < s.length && /[a-zA-Z_0-9]/.test(s[j2])) j2++;
        var word = s.slice(i, j2).toLowerCase();
        var pieces = splitWord(word);
        for (var pi = 0; pi < pieces.length; pi++) t.push({ k: 'i', v: pieces[pi] });
        i = j2;
        continue;
      }
      if ('+-*/^(),|%'.indexOf(c) >= 0) { t.push({ k: 'o', v: c }); i++; continue; }
      throw new Error('无法识别：' + c);
    }
    return t;
  }

  function buildAst(src) {
    var t = lex(prep(src));
    if (!t.length) throw new Error('表达式为空');

    // 补隐式乘法
    var out = [];
    for (var i = 0; i < t.length; i++) {
      var cur = t[i], prev = out[out.length - 1];
      if (prev) {
        var prevEnd = (prev.k === 'n') ||
          (prev.k === 'i' && prev.v !== 'pi' && prev.v !== 'e' && !ALL_FN[prev.v]) ||
          prev.v === ')' || prev.v === '|';
        var curStart = (cur.k === 'n') || (cur.k === 'i') || cur.v === '(' || cur.v === '|';
        if (prevEnd && curStart) out.push({ k: 'o', v: '*' });
      }
      out.push(cur);
    }

    var p = 0;
    function peek() { return out[p]; }
    function eat(v) {
      var x = out[p];
      if (!x || (v && x.v !== v)) throw new Error('缺少 ' + (v || '符号'));
      p++;
      return x;
    }

    function expr() {
      var a = term();
      while (peek() && (peek().v === '+' || peek().v === '-')) {
        var op = out[p++].v;
        a = { k: 'op', v: op, a: a, b: term() };
      }
      return a;
    }

    function term() {
      var a = unary();
      while (peek() && (peek().v === '*' || peek().v === '/')) {
        var op = out[p++].v;
        a = { k: 'op', v: op, a: a, b: unary() };
      }
      return a;
    }

    function unary() {
      if (peek() && peek().v === '-') { p++; return { k: 'neg', a: unary() }; }
      if (peek() && peek().v === '+') { p++; return unary(); }
      return power();
    }

    function power() {
      var base = atom();
      if (peek() && peek().v === '^') {
        p++;
        return { k: 'op', v: '^', a: base, b: unary() };
      }
      return base;
    }

    function atom() {
      var x = peek();
      if (!x) throw new Error('表达式不完整');
      if (x.k === 'n') { p++; return { k: 'n', v: x.v }; }
      if (x.v === '(') { p++; var e = expr(); eat(')'); return e; }
      if (x.v === '|') { p++; var e2 = expr(); eat('|'); return { k: 'fn', f: 'abs', a: e2 }; }
      if (x.k === 'i') {
        p++;
        if (x.v === 'x') return { k: 'x' };
        if (x.v === 'theta' || x.v === 't' || x.v === 'r') return { k: 'x' };
        if (x.v === 'y') return { k: 'x' };
        if (x.v === 'pi') return { k: 'n', v: Math.PI };
        if (x.v === 'e') return { k: 'n', v: Math.E };
        if (ALL_FN[x.v]) {
          if (peek() && peek().v === '^') {
            p++;
            var expNode = unary();
            var argNode;
            if (peek() && peek().v === '(') {
              p++;
              argNode = expr();
              eat(')');
            } else if (peek()) {
              argNode = power();
            } else {
              throw new Error('函数缺少参数');
            }
            return { k: 'op', v: '^', a: { k: 'fn', f: x.v, a: argNode }, b: expNode };
          }
          if (peek() && peek().v === '(') {
            p++;
            var arg = expr();
            eat(')');
            return { k: 'fn', f: x.v, a: arg };
          }
          return { k: 'fn', f: x.v, a: power() };
        }
        throw new Error('未知符号 ' + x.v);
      }
      throw new Error('无法解析 ' + (x.v || x.k));
    }

    var ast = expr();
    if (p < out.length) throw new Error('多余内容');
    return ast;
  }

  function evalAst(ast, x) {
    switch (ast.k) {
      case 'n': return ast.v;
      case 'x': return x;
      case 'neg': return -evalAst(ast.a, x);
      case 'op':
        var A = evalAst(ast.a, x), B = evalAst(ast.b, x);
        if (ast.v === '+') return A + B;
        if (ast.v === '-') return A - B;
        if (ast.v === '*') return A * B;
        if (ast.v === '/') return A / B;
        if (ast.v === '^') return Math.pow(A, B);
        return NaN;
      case 'fn': return ALL_FN[ast.f](evalAst(ast.a, x));
    }
    return NaN;
  }

  function compile(src) {
    var ast = buildAst(src);
    var f = function (x) { return evalAst(ast, x); };
    var test = f(1.234);
    if (typeof test !== 'number') throw new Error('无法计算');
    return { f: f, ast: ast, src: prep(src) };
  }

  // 多项式系数（低次到高次），失败返回 null
  function asPoly(ast) {
    function walk(n, depth) {
      if (depth > 8) return null;
      if (n.k === 'n') return [n.v];
      if (n.k === 'x') return [0, 1];
      if (n.k === 'neg') {
        var a = walk(n.a, depth + 1);
        return a ? a.map(function (v) { return -v; }) : null;
      }
      if (n.k === 'op' && (n.v === '+' || n.v === '-')) {
        var x1 = walk(n.a, depth + 1), y1 = walk(n.b, depth + 1);
        if (!x1 || !y1) return null;
        var len = Math.max(x1.length, y1.length), r = [];
        for (var i = 0; i < len; i++) {
          r.push((x1[i] || 0) + (y1[i] || 0) * (n.v === '-' ? -1 : 1));
        }
        return r;
      }
      if (n.k === 'op' && n.v === '*') {
        var x2 = walk(n.a, depth + 1), y2 = walk(n.b, depth + 1);
        if (!x2 || !y2) return null;
        var res = [];
        for (var j = 0; j < x2.length + y2.length - 1; j++) res.push(0);
        for (var u = 0; u < x2.length; u++) {
          for (var w = 0; w < y2.length; w++) res[u + w] += x2[u] * y2[w];
        }
        return res;
      }
      if (n.k === 'op' && n.v === '/') {
        var x3 = walk(n.a, depth + 1), y3 = walk(n.b, depth + 1);
        if (!x3 || !y3 || y3.length !== 1 || y3[0] === 0) return null;
        return x3.map(function (v) { return v / y3[0]; });
      }
      if (n.k === 'op' && n.v === '^') {
        var base = walk(n.a, depth + 1), exp = walk(n.b, depth + 1);
        if (!base || !exp || exp.length !== 1) return null;
        var e = exp[0];
        if (e < 0 || e > 12 || Math.abs(e - Math.round(e)) > 1e-9) return null;
        var out = [1];
        for (var k = 0; k < Math.round(e); k++) {
          var nx = [];
          for (var z = 0; z < out.length + base.length - 1; z++) nx.push(0);
          for (var uu = 0; uu < out.length; uu++) {
            for (var ww = 0; ww < base.length; ww++) nx[uu + ww] += out[uu] * base[ww];
          }
          out = nx;
        }
        return out;
      }
      return null;
    }
    return walk(ast, 0);
  }

  global.ExprEngine = {
    FN: FN,
    FN_EXTRA: FN_EXTRA,
    ALL_FN: ALL_FN,
    normalizeInput: normalizeInput,
    isPolarForm: isPolarForm,
    looksPolar: looksPolar,
    prep: prep,
    splitWord: splitWord,
    lex: lex,
    buildAst: buildAst,
    evalAst: evalAst,
    compile: compile,
    asPoly: asPoly,
    calc: function (src) { return compile(src).f(0); }
  };
})(window);
