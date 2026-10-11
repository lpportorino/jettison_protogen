// Orthogonal edge routing with libavoid (Adaptagrams, WebAssembly build libavoid-js) plus four
// house rules libavoid does not have. Input on stdin: {"figures": [geom, ...]} — the resolved
// geometry `place.resolve` writes (boxes, buses with their spine line and taps, edges with sides).
// Output on stdout: {"figures": [{"edges": [...]}]} — one polyline per edge and per bus tap, each
// with its net, look and arrow. Nothing in the output depends on the clock.
//
// Keep-outs: every leaf box (node or note), every group's title band, every bus spine, every bus
// label. Groups themselves are NOT obstacles: an edge may cross into a room to reach a box inside.
// The house rules:
//   aligned ports  two boxes joined n<->s (or e<->w) that overlap across the edge get a pin pair in
//                  the middle of the overlap, so the edge drops straight instead of jogging
//   facing sides   an edge that names no sides leaves and enters by the two sides that face each
//                  other across the widest gap (the placer reserves arrow room on one of them)
//   arrow room     the last segment before an arrowhead is at least as long as the head: if it is
//                  shorter, the bend before it is pushed outward — only when that moves neither end
//                  (a one-bend route is left as it is; the placer reserves its last leg)
//   bundling       skip edges that leave one box from one side run ONE trunk (the outermost)
//   bus spine      taps decide the spine: one pin per tap where the tapped box projects onto it
// libavoid behaviours guarded here: pins are exclusive unless told otherwise; a second pin at the
// SAME point of one shape makes the connector fall back, silently, to a straight centre-to-centre
// line (so the aligned pin is not added when it coincides with the side centre); a shape buffer
// above half the gap between boxes does the same (BUFFER below).
import fs from 'fs';
import { AvoidLib } from '/opt/js/node_modules/libavoid-js/dist/index-node.mjs';

await AvoidLib.load('/opt/js/node_modules/libavoid-js/dist/libavoid.wasm');
const A = AvoidLib.getInstance();
const input = JSON.parse(fs.readFileSync(0, 'utf8'));
const HEAD = 6 * 1.2 * 0.9 + 2;
// shape buffer: must stay under half the narrowest gap the placer leaves (tap-room 6 less half a
// 3.2-unit spine = 4.4 units), or libavoid falls back silently to a straight centre-to-centre line
const BUFFER = 2;
const DIR = { n: A.ConnDirUp, s: A.ConnDirDown, e: A.ConnDirRight, w: A.ConnDirLeft };
const SIDES = { n: [0.5, 0], s: [0.5, 1], e: [1, 0.5], w: [0, 0.5] };
const CLS = { n: 1, s: 2, e: 3, w: 4 };

function route(G) {
  const R = new A.Router(A.OrthogonalRouting);
  R.setRoutingParameter(A.shapeBufferDistance, BUFFER);
  R.setRoutingParameter(A.idealNudgingDistance, 4);
  R.setRoutingParameter(A.segmentPenalty, 50);
  R.setRoutingParameter(A.crossingPenalty, 200);
  R.setRoutingOption(A.nudgeOrthogonalSegmentsConnectedToShapes, true);
  R.setRoutingOption(A.nudgeSharedPathsWithCommonEndPoint, true);
  R.setRoutingOption(A.penaliseOrthogonalSharedPathsAtConnEnds, true);
  const P = (x, y) => new A.Point(x, y);
  const box = {}, shapes = {};
  for (const b of G.boxes) box[b.id] = b;
  const rect = (x, y, w, h) => new A.Rectangle(P(x, y), P(x + w, y + h));
  for (const b of G.boxes) {
    if (b.kind === 'group') {               // the title band is a keep-out; the room is not
      if (b.texts.length) new A.ShapeRef(R, rect(b.x + 2, b.y + 2, b.w - 4, b.title_h - 4));
      continue;
    }
    const s = new A.ShapeRef(R, rect(b.x, b.y, b.w, b.h));
    for (const k of 'nsew') new A.ShapeConnectionPin(s, CLS[k], SIDES[k][0], SIDES[k][1], true, 0, DIR[k]).setExclusive(false);
    new A.ShapeConnectionPin(s, 5, 0.5, 0.5, true, 0, A.ConnDirAll).setExclusive(false);
    shapes[b.id] = s;
  }
  const sidePt = (b, side) => ({ n: [b.x + b.w / 2, b.y], s: [b.x + b.w / 2, b.y + b.h], e: [b.x + b.w, b.y + b.h / 2], w: [b.x, b.y + b.h / 2] })[side];
  // plane connection: an edge ending on a group border meets it at the point nearest the other end
  function planePt(b, side, other) {
    const o = box[other] || b, ox = o.x + o.w / 2, oy = o.y + o.h / 2;
    const cx = Math.min(Math.max(ox, b.x + 6), b.x + b.w - 6), cy = Math.min(Math.max(oy, b.y + 6), b.y + b.h - 6);
    return ({ n: [cx, b.y], s: [cx, b.y + b.h], e: [b.x + b.w, cy], w: [b.x, cy] })[side || 'n'];
  }
  function endFor(id, side, other) {
    const b = box[id];
    if (b.kind === 'group') { const [x, y] = planePt(b, side, other); return new A.ConnEnd(P(x, y)); }
    return new A.ConnEnd(shapes[id], side ? CLS[side] : 5);
  }
  let pinCls = 1000;
  function alignedEnds(e) {
    const a = box[e.from], b = box[e.to];
    if (!shapes[e.from] || !shapes[e.to] || !e['from-side'] || !e['to-side']) return null;
    const fs_ = e['from-side'], ts = e['to-side'];
    const vert = 'ns'.includes(fs_) && 'ns'.includes(ts), hor = 'ew'.includes(fs_) && 'ew'.includes(ts);
    if (!(vert || hor) || fs_ === ts) return null;
    const [p0, p1, q0, q1] = vert ? [a.x, a.x + a.w, b.x, b.x + b.w] : [a.y, a.y + a.h, b.y, b.y + b.h];
    const lo = Math.max(p0, q0) + 6, hi = Math.min(p1, q1) - 6;
    if (hi - lo < 0) return null;
    const nc = (p1 - p0 < q1 - q0) ? (p0 + p1) / 2 : (q0 + q1) / 2, m = Math.min(Math.max(nc, lo), hi);
    const mk = (id, bx, side) => {
      const off = vert ? (m - bx.x) / bx.w : (m - bx.y) / bx.h;
      if (Math.abs(off - 0.5) < 1e-6) return new A.ConnEnd(shapes[id], CLS[side]);
      const c = pinCls++;
      new A.ShapeConnectionPin(shapes[id], c, vert ? off : SIDES[side][0], vert ? SIDES[side][1] : off, true, 0, DIR[side]).setExclusive(false);
      return new A.ConnEnd(shapes[id], c);
    };
    return [mk(e.from, a, fs_), mk(e.to, b, ts)];
  }
  // the two sides that face each other: among the directions with a gap between the boxes, the
  // widest one whose exits are both clear (a point 2 x BUFFER out from each side's centre lies in
  // no other box or title band), else the widest whose start is clear, else the widest gap
  const blockers = G.boxes.map(b => b.kind === 'group'
    ? (b.texts.length ? { id: b.id, x: b.x + 2, y: b.y + 2, w: b.w - 4, h: b.title_h - 4 } : null)
    : b).filter(Boolean);
  const OUT = { n: [0, -1], s: [0, 1], e: [1, 0], w: [-1, 0] };
  function clear(id, side, other) {
    const [x, y] = sidePt(box[id], side), [dx, dy] = OUT[side];
    const px = x + dx * 2 * BUFFER, py = y + dy * 2 * BUFFER;
    return !blockers.some(b => b.id !== id && b.id !== other && px > b.x - 2 && px < b.x + b.w + 2 && py > b.y - 2 && py < b.y + b.h + 2);
  }
  function facing(e) {
    if (e['from-side'] && e['to-side']) return [e['from-side'], e['to-side']];
    const a = box[e.from], b = box[e.to];
    const gaps = [[b.y - (a.y + a.h), 's', 'n'], [a.y - (b.y + b.h), 'n', 's'],
      [b.x - (a.x + a.w), 'e', 'w'], [a.x - (b.x + b.w), 'w', 'e']];
    gaps.sort((p, q) => q[0] - p[0]);
    const open = gaps.filter(([g]) => g >= 0);
    const ok = open.find(([, f, t]) => clear(e.from, e['from-side'] || f, e.to) && clear(e.to, e['to-side'] || t, e.from))
      || open.find(([, f]) => clear(e.from, e['from-side'] || f, e.to));
    const [, f, t] = ok || gaps[0];
    return [e['from-side'] || f, e['to-side'] || t];
  }
  const conns = [];
  for (const e0 of G.edges) {
    const [fs0, ts0] = facing(e0);
    const e = Object.assign({}, e0, { 'from-side': fs0, 'to-side': ts0 });
    const al = alignedEnds(e);
    const net = e.bundle || e.id;
    conns.push({ id: e.id, net, look: e.look, arrow: e.arrow,
      c: new A.ConnRef(R, ...(al || [endFor(e.from, e['from-side'], e.to), endFor(e.to, e['to-side'], e.from)])) });
  }
  for (const bus of G.buses) {
    const v = bus.orient === 'v', [x1, y1, x2, y2] = bus.line;
    const th = 3.2;
    const sp = new A.ShapeRef(R, v ? rect(x1 - th / 2, Math.min(y1, y2), th, Math.abs(y2 - y1)) : rect(Math.min(x1, x2), y1 - th / 2, Math.abs(x2 - x1), th));
    const len = v ? Math.abs(y2 - y1) : Math.abs(x2 - x1), start = v ? Math.min(y1, y2) : Math.min(x1, x2);
    if (bus.label) new A.ShapeRef(R, rect(bus.label.x, bus.label.y, bus.label.w, bus.label.h));
    bus.taps.forEach((t, i) => {
      const off = (t.at - start) / len;
      const onBus = v ? (t.side === 'e' ? 'w' : 'e') : (t.side === 's' ? 'n' : 's');
      const cls = 100 + i, s0 = SIDES[onBus];
      new A.ShapeConnectionPin(sp, cls, v ? s0[0] : off, v ? off : s0[1], true, 0, DIR[onBus]).setExclusive(false);
      const b = box[t.node];
      const tEnd = b.kind === 'group' ? (() => { const [x, y] = sidePt(b, t.side); return new A.ConnEnd(P(x, y)); })() : endFor(t.node, t.side);
      conns.push({ id: `${bus.id}~${t.node}`, net: bus.id, look: t.look, arrow: 'none', c: new A.ConnRef(R, new A.ConnEnd(sp, cls), tEnd) });
    });
  }
  R.processTransaction();
  const edges = [];
  for (const k of conns) {
    const pl = k.c.displayRoute(); const pts = [];
    for (let i = 0; i < pl.size(); i++) { const p = pl.get_ps(i); pts.push([p.x, p.y]); }
    const room = (Q) => {
      const n = Q.length; if (n < 4) return;   // Q[n - 3] must be an inner bend, never the start
      const [x2, y2] = Q[n - 1], [x1, y1] = Q[n - 2]; const L = Math.hypot(x2 - x1, y2 - y1); if (L >= HEAD) return;
      const ux = (x1 - x2) / (L || 1), uy = (y1 - y2) / (L || 1), d = HEAD - L;
      Q[n - 2] = [x1 + ux * d, y1 + uy * d]; Q[n - 3] = [Q[n - 3][0] + ux * d, Q[n - 3][1] + uy * d];
    };
    if (k.arrow === 'end' || k.arrow === 'both') room(pts);
    if (k.arrow === 'both') { pts.reverse(); room(pts); pts.reverse(); }
    edges.push({ id: k.id, net: k.net, look: k.look, arrow: k.arrow, points: pts, unrouted: pts.length < 2 });
  }
  const bundles = {};
  for (const e of G.edges) if (e.bundle) (bundles[e.bundle] ??= []).push(e.id);
  for (const ids of Object.values(bundles)) {
    const es = edges.filter(e => ids.includes(e.id) && e.points.length === 4);
    if (es.length < 2) continue;
    const vtrunk = es.every(e => Math.abs(e.points[1][0] - e.points[2][0]) < 0.5);
    const ax = vtrunk ? 0 : 1, bx = vtrunk ? 1 : 0;
    const out = es[0].points[1][ax] - es[0].points[0][ax] >= 0 ? Math.max : Math.min;
    const t = out(...es.map(e => e.points[1][ax]));
    const s0 = es[0].points[0][bx];
    for (const e of es) { e.points[1][ax] = t; e.points[2][ax] = t; e.points[0][bx] = s0; e.points[1][bx] = s0; }
  }
  for (const e of edges) e.points = e.points.map(([x, y]) => [Math.round(x * 100) / 100, Math.round(y * 100) / 100]);
  return { edges };
}

const out = { figures: [] };
for (const G of input.figures) out.figures.push(route(G));
process.stdout.write(JSON.stringify(out));
