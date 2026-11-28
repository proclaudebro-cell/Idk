const test = require('node:test');
const assert = require('node:assert/strict');
const E = require('../app/src/main/assets/engine.js');

function play(s, ucis) {
  for (const u of ucis) {
    const m = E.parseUci(s, u);
    assert.ok(m, `illegal move in test line: ${u}`);
    s = E.applyMove(s, m);
  }
  return s;
}

function perft(s, d) {
  if (d === 0) return 1;
  let n = 0;
  for (const m of E.legalMoves(s)) n += perft(E.applyMove(s, m), d - 1);
  return n;
}

test('perft matches reference node counts', () => {
  assert.equal(perft(E.newGame(), 3), 8902);
  assert.equal(perft(E.fromFen('r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1'), 2), 2039);
  assert.equal(perft(E.fromFen('8/2p5/3p4/KP5r/1R3p1k/8/4P1P1/8 w - - 0 1'), 3), 2812);
});

test('SAN notation for common moves', () => {
  const s = E.newGame();
  assert.equal(E.toSan(s, E.parseUci(s, 'g1f3')), 'Nf3');
  assert.equal(E.toSan(s, E.parseUci(s, 'e2e4')), 'e4');
  const after = play(s, ['e2e4', 'e7e5', 'g1f3', 'b8c6', 'f1c4', 'f8c5']);
  assert.equal(E.toSan(after, E.parseUci(after, 'e1g1')), 'O-O');
});

test('fool\'s mate is detected and written with #', () => {
  let s = play(E.newGame(), ['f2f3', 'e7e5', 'g2g4']);
  const mate = E.parseUci(s, 'd8h4');
  assert.equal(E.toSan(s, mate), 'Qh4#');
  s = E.applyMove(s, mate);
  const st = E.status(s);
  assert.equal(st.over, true);
  assert.equal(st.reason, 'checkmate');
  assert.equal(st.winner, 'b');
});

test('stalemate is a draw, not a win', () => {
  const st = E.status(E.fromFen('7k/5Q2/6K1/8/8/8/8/8 b - - 0 1'));
  assert.equal(st.over, true);
  assert.equal(st.reason, 'stalemate');
  assert.equal(st.winner, null);
});

test('promotion offers all four pieces', () => {
  const s = E.fromFen('4k3/P7/8/8/8/8/8/4K3 w - - 0 1');
  const promos = E.movesFrom(s, E.sqIndex('a7')).map((m) => m.promo).sort();
  assert.deepEqual(promos, ['b', 'n', 'q', 'r']);
});

test('fen round-trips', () => {
  assert.equal(E.toFen(E.newGame()), E.START_FEN);
});
