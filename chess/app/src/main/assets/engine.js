/*
 * Chess rules engine: board representation, legal move generation,
 * check / mate / draw detection and standard algebraic notation (SAN).
 * No dependencies. Works in the WebView (global ChessEngine) and in Node.
 *
 * Board: array of 64 entries, index = rank * 8 + file (a1 = 0, h8 = 63).
 * Pieces: 'PNBRQK' for white, 'pnbrqk' for black, null for empty.
 */
(function () {
  'use strict';

  const FILES = 'abcdefgh';
  const START_FEN = 'rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1';
  const KNIGHT_STEPS = [[1, 2], [2, 1], [2, -1], [1, -2], [-1, -2], [-2, -1], [-2, 1], [-1, 2]];
  const KING_STEPS = [[1, 0], [-1, 0], [0, 1], [0, -1], [1, 1], [1, -1], [-1, 1], [-1, -1]];
  const DIAGONALS = [[1, 1], [1, -1], [-1, 1], [-1, -1]];
  const ORTHOGONALS = [[1, 0], [-1, 0], [0, 1], [0, -1]];
  const PIECE_VALUE = { p: 100, n: 300, b: 320, r: 500, q: 900, k: 0 };

  const sqName = (i) => FILES[i & 7] + ((i >> 3) + 1);
  const sqIndex = (name) => (name.charCodeAt(1) - 49) * 8 + (name.charCodeAt(0) - 97);
  const colorOf = (p) => (!p ? null : p === p.toUpperCase() ? 'w' : 'b');
  const typeOf = (p) => p.toLowerCase();
  const other = (c) => (c === 'w' ? 'b' : 'w');
  const pieceOf = (c, t) => (c === 'w' ? t.toUpperCase() : t.toLowerCase());
  const onBoard = (f, r) => f >= 0 && f < 8 && r >= 0 && r < 8;

  function fromFen(fen) {
    const parts = fen.trim().split(/\s+/);
    const placement = parts[0], turn = parts[1], castling = parts[2] || '-';
    const ep = parts[3] || '-', half = parts[4] || '0', full = parts[5] || '1';
    const board = new Array(64).fill(null);
    placement.split('/').forEach((row, i) => {
      const r = 7 - i;
      let f = 0;
      for (const ch of row) {
        if (ch >= '1' && ch <= '8') f += Number(ch);
        else { board[r * 8 + f] = ch; f++; }
      }
    });
    return {
      board,
      turn,
      castling: { K: castling.includes('K'), Q: castling.includes('Q'), k: castling.includes('k'), q: castling.includes('q') },
      ep: ep === '-' ? -1 : sqIndex(ep),
      halfmove: Number(half),
      fullmove: Number(full),
    };
  }

  function toFen(s) {
    const rows = [];
    for (let r = 7; r >= 0; r--) {
      let row = '', empty = 0;
      for (let f = 0; f < 8; f++) {
        const p = s.board[r * 8 + f];
        if (!p) { empty++; continue; }
        if (empty) row += empty;
        empty = 0;
        row += p;
      }
      if (empty) row += empty;
      rows.push(row);
    }
    const c = (s.castling.K ? 'K' : '') + (s.castling.Q ? 'Q' : '') + (s.castling.k ? 'k' : '') + (s.castling.q ? 'q' : '');
    return [rows.join('/'), s.turn, c || '-', s.ep >= 0 ? sqName(s.ep) : '-', s.halfmove, s.fullmove].join(' ');
  }

  // Squares of every piece of colour `by` that attacks `sq`.
  function attackers(board, sq, by) {
    const f = sq & 7, r = sq >> 3, found = [];
    const is = (nf, nr, t) => onBoard(nf, nr) && board[nr * 8 + nf] === pieceOf(by, t);
    const pr = by === 'w' ? r - 1 : r + 1;
    for (const df of [-1, 1]) if (is(f + df, pr, 'p')) found.push(pr * 8 + f + df);
    for (const [df, dr] of KNIGHT_STEPS) if (is(f + df, r + dr, 'n')) found.push((r + dr) * 8 + f + df);
    for (const [df, dr] of KING_STEPS) if (is(f + df, r + dr, 'k')) found.push((r + dr) * 8 + f + df);
    const slide = (steps, types) => {
      for (const [df, dr] of steps) {
        let nf = f + df, nr = r + dr;
        while (onBoard(nf, nr)) {
          const p = board[nr * 8 + nf];
          if (p) {
            if (types.includes(p)) found.push(nr * 8 + nf);
            break;
          }
          nf += df;
          nr += dr;
        }
      }
    };
    slide(DIAGONALS, [pieceOf(by, 'b'), pieceOf(by, 'q')]);
    slide(ORTHOGONALS, [pieceOf(by, 'r'), pieceOf(by, 'q')]);
    return found;
  }

  const isAttacked = (board, sq, by) => attackers(board, sq, by).length > 0;
  const kingSquare = (board, c) => board.indexOf(pieceOf(c, 'k'));
  function inCheck(board, c) {
    const k = kingSquare(board, c);
    return k >= 0 && isAttacked(board, k, other(c));
  }

  function castleMoves(s, from, add) {
    const b = s.board, them = other(s.turn), home = s.turn === 'w' ? 4 : 60;
    if (from !== home || isAttacked(b, home, them)) return;
    const w = s.turn === 'w';
    const rook = w ? 'R' : 'r';
    if (s.castling[w ? 'K' : 'k'] && b[home + 3] === rook && !b[home + 1] && !b[home + 2] &&
        !isAttacked(b, home + 1, them) && !isAttacked(b, home + 2, them)) {
      add(from, home + 2, { castle: 'K' });
    }
    if (s.castling[w ? 'Q' : 'q'] && b[home - 4] === rook && !b[home - 1] && !b[home - 2] && !b[home - 3] &&
        !isAttacked(b, home - 1, them) && !isAttacked(b, home - 2, them)) {
      add(from, home - 2, { castle: 'Q' });
    }
  }

  // Moves that follow the rules of piece movement, ignoring whether the own king is left in check.
  function pseudoMoves(s) {
    const b = s.board, us = s.turn, them = other(us), moves = [];
    const add = (from, to, extra) => moves.push(Object.assign({
      from, to, piece: b[from], captured: b[to] || null, ep: false, castle: null, promo: null, double: false,
    }, extra));

    for (let from = 0; from < 64; from++) {
      const p = b[from];
      if (!p || colorOf(p) !== us) continue;
      const t = typeOf(p), f = from & 7, r = from >> 3;

      if (t === 'p') {
        const dir = us === 'w' ? 1 : -1;
        const startRank = us === 'w' ? 1 : 6, lastRank = us === 'w' ? 7 : 0;
        const addPawn = (to, extra) => {
          if ((to >> 3) === lastRank) {
            for (const promo of ['q', 'r', 'b', 'n']) add(from, to, Object.assign({ promo }, extra));
          } else {
            add(from, to, extra);
          }
        };
        const r1 = r + dir;
        if (onBoard(f, r1) && !b[r1 * 8 + f]) {
          addPawn(r1 * 8 + f, {});
          const r2 = r + 2 * dir;
          if (r === startRank && !b[r2 * 8 + f]) add(from, r2 * 8 + f, { double: true });
        }
        for (const df of [-1, 1]) {
          const nf = f + df;
          if (!onBoard(nf, r1)) continue;
          const to = r1 * 8 + nf;
          if (b[to] && colorOf(b[to]) === them) addPawn(to, {});
          else if (to === s.ep) add(from, to, { ep: true, captured: pieceOf(them, 'p') });
        }
      } else if (t === 'n' || t === 'k') {
        for (const [df, dr] of (t === 'n' ? KNIGHT_STEPS : KING_STEPS)) {
          const nf = f + df, nr = r + dr;
          if (!onBoard(nf, nr)) continue;
          const to = nr * 8 + nf;
          if (!b[to] || colorOf(b[to]) === them) add(from, to, {});
        }
        if (t === 'k') castleMoves(s, from, add);
      } else {
        const dirs = t === 'b' ? DIAGONALS : t === 'r' ? ORTHOGONALS : DIAGONALS.concat(ORTHOGONALS);
        for (const [df, dr] of dirs) {
          let nf = f + df, nr = r + dr;
          while (onBoard(nf, nr)) {
            const to = nr * 8 + nf;
            if (!b[to]) {
              add(from, to, {});
            } else {
              if (colorOf(b[to]) === them) add(from, to, {});
              break;
            }
            nf += df;
            nr += dr;
          }
        }
      }
    }
    return moves;
  }

  function applyMove(s, m) {
    const b = s.board.slice();
    const p = b[m.from];
    const mover = colorOf(p);
    b[m.from] = null;
    b[m.to] = m.promo ? pieceOf(mover, m.promo) : p;
    if (m.ep) b[m.to + (mover === 'w' ? -8 : 8)] = null;
    if (m.castle === 'K') { b[m.from + 1] = b[m.from + 3]; b[m.from + 3] = null; }
    if (m.castle === 'Q') { b[m.from - 1] = b[m.from - 4]; b[m.from - 4] = null; }

    const c = Object.assign({}, s.castling);
    if (typeOf(p) === 'k') {
      if (mover === 'w') { c.K = false; c.Q = false; } else { c.k = false; c.q = false; }
    }
    for (const sq of [m.from, m.to]) {
      if (sq === 0) c.Q = false;
      if (sq === 7) c.K = false;
      if (sq === 56) c.q = false;
      if (sq === 63) c.k = false;
    }
    const isDouble = typeOf(p) === 'p' && Math.abs(m.to - m.from) === 16;
    return {
      board: b,
      turn: other(mover),
      castling: c,
      ep: isDouble ? (m.from + m.to) >> 1 : -1,
      halfmove: (typeOf(p) === 'p' || m.captured || m.ep) ? 0 : s.halfmove + 1,
      fullmove: s.fullmove + (mover === 'b' ? 1 : 0),
    };
  }

  function legalMoves(s) {
    return pseudoMoves(s).filter((m) => !inCheck(applyMove(s, m).board, s.turn));
  }

  const movesFrom = (s, sq) => legalMoves(s).filter((m) => m.from === sq);

  function insufficient(board) {
    let minors = 0;
    for (const p of board) {
      if (!p) continue;
      const t = typeOf(p);
      if (t === 'p' || t === 'r' || t === 'q') return false;
      if (t === 'n' || t === 'b') minors++;
    }
    return minors <= 1;
  }

  // { over, winner ('w' | 'b' | null), reason, check }
  function status(s) {
    const check = inCheck(s.board, s.turn);
    if (!legalMoves(s).length) {
      return check
        ? { over: true, winner: other(s.turn), reason: 'checkmate', check }
        : { over: true, winner: null, reason: 'stalemate', check };
    }
    if (insufficient(s.board)) return { over: true, winner: null, reason: 'insufficient material', check };
    if (s.halfmove >= 100) return { over: true, winner: null, reason: '50-move rule', check };
    return { over: false, winner: null, reason: null, check };
  }

  function toSan(s, m) {
    const t = typeOf(s.board[m.from]);
    const cap = m.captured || m.ep;
    let san;
    if (m.castle) {
      san = m.castle === 'K' ? 'O-O' : 'O-O-O';
    } else if (t === 'p') {
      san = (cap ? FILES[m.from & 7] + 'x' : '') + sqName(m.to);
      if (m.promo) san += '=' + m.promo.toUpperCase();
    } else {
      const rivals = legalMoves(s).filter((o) => o.to === m.to && o.from !== m.from && typeOf(s.board[o.from]) === t);
      let dis = '';
      if (rivals.length) {
        const sameFile = rivals.some((o) => (o.from & 7) === (m.from & 7));
        const sameRank = rivals.some((o) => (o.from >> 3) === (m.from >> 3));
        dis = !sameFile ? FILES[m.from & 7] : !sameRank ? String((m.from >> 3) + 1) : sqName(m.from);
      }
      san = t.toUpperCase() + dis + (cap ? 'x' : '') + sqName(m.to);
    }
    const after = applyMove(s, m);
    if (inCheck(after.board, after.turn)) san += legalMoves(after).length ? '+' : '#';
    return san;
  }

  // Looks up a legal move by square indices. promo is 'q' | 'r' | 'b' | 'n' or null.
  function findMove(s, from, to, promo) {
    return legalMoves(s).find((m) => m.from === from && m.to === to && m.promo === (promo || null)) || null;
  }

  const uci = (m) => sqName(m.from) + sqName(m.to) + (m.promo || '');

  function parseUci(s, text) {
    if (!text || text.length < 4) return null;
    return findMove(s, sqIndex(text.slice(0, 2)), sqIndex(text.slice(2, 4)), text[4] || null);
  }

  const api = {
    START_FEN, FILES, PIECE_VALUE,
    sqName, sqIndex, colorOf, typeOf, other, pieceOf,
    attackers, isAttacked, inCheck, kingSquare,
    fromFen, toFen, newGame: () => fromFen(START_FEN),
    legalMoves, movesFrom, applyMove, status, toSan,
    findMove, uci, parseUci,
  };

  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  else globalThis.ChessEngine = api;
})();
