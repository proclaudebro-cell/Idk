const test = require('node:test');
const assert = require('node:assert/strict');
const E = require('../app/src/main/assets/engine.js');
const C = require('../app/src/main/assets/coach.js');

const BACK_RANK = '6k1/5ppp/8/8/8/8/8/R5K1 w - - 0 1'; // Ra8# is mate in one
const FREE_ROOK = '4k3/8/8/8/3r4/8/8/3RK3 w - - 0 1';  // Rxd4 wins an undefended rook

test('finds mate in one and explains it', () => {
  const s = E.fromFen(BACK_RANK);
  const ranked = C.rankMoves(s);
  assert.equal(ranked[0].san, 'Ra8#');
  const h = C.hint(s, null, ranked);
  assert.equal(h.title, 'Checkmate in one');
  assert.match(h.text, /Ra8#/);
});

test('spots free material', () => {
  const s = E.fromFen(FREE_ROOK);
  const h = C.hint(s, null);
  assert.equal(h.title, 'Free material');
  assert.match(h.text, /Rxd4/);
  assert.ok(h.arrows.some((a) => a.kind === 'good'));
});

test('grades a missed mate as a blunder with the missed-mate tag', () => {
  const s = E.fromFen(BACK_RANK);
  const ranked = C.rankMoves(s);
  const r = C.review(s, E.parseUci(s, 'a1a2'), ranked);
  assert.equal(r.grade, 'blunder');
  assert.ok(r.tags.includes('missed-mate'));
  assert.match(r.comment, /Ra8#/);
});

test('grades the mating move as best', () => {
  const s = E.fromFen(BACK_RANK);
  const r = C.review(s, E.parseUci(s, 'a1a8'), C.rankMoves(s));
  assert.equal(r.grade, 'best');
  assert.ok(r.tags.includes('found-mate'));
});

test('flags moving the queen onto a pawn-attacked square', () => {
  // Black pawn on c6 attacks d5, so Qd5 hands the queen to a pawn.
  const s = E.fromFen('4k3/8/2p5/8/8/8/8/3QK3 w - - 0 1');
  const r = C.review(s, E.parseUci(s, 'd1d5'), C.rankMoves(s));
  assert.equal(r.grade, 'blunder');
  assert.ok(r.tags.includes('moved-into-danger'));
});

test('hanging() lists undefended pieces under attack', () => {
  // Rook on d2 is attacked by the black rook on d4 and nothing defends it.
  const s = E.fromFen('4k3/8/8/8/3r4/8/3R4/K7 w - - 0 1');
  assert.ok(C.hanging(s.board, 'w').includes(E.sqIndex('d2')));
  // Rook on d1 is attacked too, but the king defends it and rook-for-rook is an even trade.
  const s2 = E.fromFen('4k3/8/8/8/3r4/8/8/3RK3 w - - 0 1');
  assert.deepEqual(C.hanging(s2.board, 'w'), []);
});

test('focusFrom picks the most frequent mistake', () => {
  const f = C.focusFrom({ 'missed-capture': 2, 'left-piece-hanging': 5 });
  assert.equal(f.key, 'left-piece-hanging');
  assert.equal(C.focusFrom({}), null);
});
