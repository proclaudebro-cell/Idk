/*
 * Coach: rule-based analysis with no network or AI service.
 *  - A 2-ply material search (your move, then the bot's best reply) ranks moves.
 *  - Each move you play is graded against the best move and tagged with the
 *    mistake it represents (hanging piece, missed capture, missed mate, ...).
 *  - hint() explains what to do next, in the learning style you choose.
 *  - focusFrom() turns your recurring mistakes into a practice focus.
 */
(function () {
  'use strict';

  const E = (typeof module !== 'undefined' && module.exports) ? require('./engine.js') : globalThis.ChessEngine;

  const MATE = 100000;
  const NAME = { p: 'pawn', n: 'knight', b: 'bishop', r: 'rook', q: 'queen', k: 'king' };
  const VALUE = E.PIECE_VALUE;
  const CENTRE = [27, 28, 35, 36]; // d4 e4 d5 e5
  const pawns = (cp) => (cp / 100).toFixed(1);
  // The king can never be "won" by capture, so treat it as very valuable when counting attackers.
  const attackerValue = (board, sq) => (E.typeOf(board[sq]) === 'k' ? 10000 : VALUE[E.typeOf(board[sq])]);

  const FOCUS = {
    'moved-into-danger': 'before each move, check whether the square you land on can be taken without enough protection.',
    'left-piece-hanging': 'before each move, list your loose pieces and make sure none can be taken for free.',
    'missed-capture': 'every turn, scan for enemy pieces that nothing protects.',
    'missed-mate': 'before each move, check whether any move gives checkmate.',
    'early-queen': 'develop knights and bishops before bringing out the queen.',
  };

  function material(board) {
    let v = 0;
    for (const p of board) if (p) v += (E.colorOf(p) === 'w' ? 1 : -1) * VALUE[E.typeOf(p)];
    return v;
  }
  // From colour c's point of view: positive means c is ahead.
  const balanceFor = (board, c) => (c === 'w' ? 1 : -1) * material(board);

  // Score of playing m, from the mover's point of view: our move, then the bot's best reply.
  function scoreMove(s, m) {
    const c = s.turn;
    const after = E.applyMove(s, m);
    const replies = E.legalMoves(after);
    if (!replies.length) {
      return E.inCheck(after.board, after.turn) ? MATE : balanceFor(after.board, c);
    }
    let worst = Infinity;
    for (const r of replies) {
      const next = E.applyMove(after, r);
      let v;
      if (E.inCheck(next.board, next.turn) && E.legalMoves(next).length === 0) v = -MATE; // we get mated
      else v = balanceFor(next.board, c);
      if (v < worst) worst = v;
    }
    return worst;
  }

  // Small positional nudges, used only to break ties between equal-material moves.
  function positional(s, m) {
    let bonus = 0;
    const t = E.typeOf(s.board[m.from]);
    const after = E.applyMove(s, m);
    const homeRank = s.turn === 'w' ? 0 : 7;
    if (m.castle) bonus += 30;
    if (E.inCheck(after.board, after.turn)) bonus += 10;
    if (CENTRE.includes(m.to) && t !== 'k' && t !== 'r') bonus += 8;
    if ((t === 'n' || t === 'b') && (m.from >> 3) === homeRank && s.fullmove <= 12) bonus += 12;
    if (t === 'q' && s.fullmove <= 6) bonus -= 10;
    if (t === 'k' && !m.castle) bonus -= 5;
    return bonus;
  }

  // All legal moves, best first, each with score, tie-break bonus and SAN.
  function rankMoves(s) {
    return E.legalMoves(s)
      .map((m) => ({ move: m, score: scoreMove(s, m), bonus: positional(s, m), san: E.toSan(s, m) }))
      .sort((a, b) => (b.score - a.score) || (b.bonus - a.bonus));
  }

  // Pieces of colour c (except the king) that the opponent can take for free or profitably.
  function hanging(board, c) {
    const out = [];
    for (let sq = 0; sq < 64; sq++) {
      const p = board[sq];
      if (!p || E.colorOf(p) !== c || E.typeOf(p) === 'k') continue;
      const hitters = E.attackers(board, sq, E.other(c));
      if (!hitters.length) continue;
      const cheapest = Math.min(...hitters.map((a) => attackerValue(board, a)));
      const defended = E.attackers(board, sq, c).length > 0;
      if (!defended || cheapest < VALUE[E.typeOf(p)]) out.push(sq);
    }
    return out;
  }

  // Grades the move `m` played in `before`, given rankMoves(before).
  function review(before, m, ranked) {
    const us = before.turn;
    const best = ranked[0];
    const mine = ranked.find((x) => x.move.from === m.from && x.move.to === m.to && x.move.promo === m.promo);
    const loss = best.score - mine.score;
    const grade = loss <= 0 ? 'best' : loss <= 20 ? 'good' : loss < 150 ? 'inaccuracy' : loss < 250 ? 'mistake' : 'blunder';
    const movedType = E.typeOf(before.board[m.from]);
    const after = E.applyMove(before, m);
    const tags = [];

    if (mine.score >= MATE / 2) tags.push('found-mate');
    if (grade !== 'best' && grade !== 'good') {
      const hangAfter = hanging(after.board, us);
      if (best.score >= MATE / 2 && mine.score < MATE / 2) tags.push('missed-mate');
      else if (hangAfter.includes(m.to)) tags.push('moved-into-danger');
      else if (hangAfter.length > hanging(before.board, us).length) tags.push('left-piece-hanging');
      else if (best.move.captured && !m.captured && loss >= 100) tags.push('missed-capture');
      if (movedType === 'q' && before.fullmove <= 5) tags.push('early-queen');
    }

    const parts = [];
    if (tags.includes('found-mate')) parts.push(`Checkmate! ${mine.san} ends the game.`);
    if (tags.includes('missed-mate')) parts.push(`You missed a checkmate in one: ${best.san}.`);
    if (tags.includes('moved-into-danger')) parts.push(`${mine.san} put your ${NAME[movedType]} on a square the bot can take without enough protection.`);
    if (tags.includes('left-piece-hanging')) parts.push(`${mine.san} left another piece hanging. Check what the bot can capture before each move.`);
    if (tags.includes('missed-capture')) parts.push(`A free ${NAME[E.typeOf(best.move.captured)]} was there to win: ${best.san}.`);
    if (tags.includes('early-queen')) parts.push('Moving the queen this early lets the bot gain tempo. Develop knights and bishops first.');
    if (grade === 'best' || grade === 'good') {
      if (mine.move.captured) parts.push(`Good: you won a ${NAME[E.typeOf(mine.move.captured)]}.`);
      else if (parts.length === 0) parts.push(grade === 'best' ? 'Strong, well judged.' : 'Solid move.');
    } else if (parts.length === 0) {
      parts.push(`You gave up about ${pawns(loss)} pawns of material compared with ${best.san}.`);
    }
    if (grade !== 'best' && grade !== 'good' && best.san !== mine.san) parts.push(`Better: ${best.san}.`);

    return { san: mine.san, grade, loss, tags, bestSan: best.san, comment: parts.join(' ') };
  }

  // Plain-English reason for a move from a ranked entry.
  function explain(s, entry) {
    const m = entry.move, us = s.turn, them = E.other(us);
    const after = E.applyMove(s, m);
    const t = E.typeOf(s.board[m.from]);
    const piece = NAME[t];
    if (entry.score >= MATE / 2) return 'It is checkmate: the king has no safe square left.';
    if (m.castle) return 'Castling tucks the king into safety and brings a rook toward the centre.';
    const out = [];
    if (m.captured || m.ep) {
      const victim = NAME[E.typeOf(m.captured)];
      const recapture = E.isAttacked(after.board, m.to, them);
      out.push(`It captures a ${victim}${recapture ? ', but your piece can be recaptured, so count the trade.' : ' and nothing can take back, so it is a free gain.'}`);
    }
    if (E.inCheck(after.board, after.turn)) out.push('It gives check, so the opponent has to respond.');
    if (!m.captured && !m.ep && E.attackers(after.board, m.to, them).length && !E.attackers(after.board, m.to, us).length) {
      out.push('It lands on a square the opponent attacks with no defender behind it, so it can be lost.');
    }
    if (out.length) return out.join(' ');
    if ((t === 'n' || t === 'b') && (m.from >> 3) === (us === 'w' ? 0 : 7)) return `It develops your ${piece} from the back rank into play.`;
    if (CENTRE.includes(m.to)) return `It fights for the centre with your ${piece}.`;
    if (t === 'p') return 'It advances a pawn and gains space.';
    return `It improves your ${piece} and keeps your material safe.`;
  }

  // Coach's advice for the position: title, text, lesson, checklist, board arrows.
  function hint(s, focusKey, ranked) {
    const me = s.turn;
    const st = E.status(s);
    if (st.over) {
      return {
        title: 'Game over',
        text: st.reason === 'checkmate' ? 'The game ended in checkmate.' : `The game is drawn (${st.reason}).`,
        lesson: '', steps: [], arrows: [], focus: null,
      };
    }
    const list = ranked || rankMoves(s);
    const best = list[0];
    const gain = best.score - balanceFor(s.board, me);
    const threats = hanging(s.board, me);
    const mate = list.find((x) => x.score >= MATE / 2) || null;
    const safetyFirst = focusKey === 'moved-into-danger' || focusKey === 'left-piece-hanging';

    const arrows = [{ from: best.move.from, to: best.move.to, kind: 'good' }];
    for (const sq of threats) {
      for (const a of E.attackers(s.board, sq, E.other(me))) arrows.push({ from: a, to: sq, kind: 'threat' });
    }

    const steps = [
      { q: 'Am I in check?', a: st.check ? 'Yes. Deal with the check first.' : 'No.', ok: !st.check },
      { q: 'Can I checkmate?', a: mate ? `Yes: ${mate.san}.` : 'No mate in one.', ok: !!mate },
      { q: 'Can I win material?', a: gain >= 100 ? `Yes: ${best.san} (about ${pawns(gain)} pawns).` : 'No clear gain.', ok: gain >= 100 },
      {
        q: 'Are my pieces safe?',
        a: threats.length
          ? `No: ${threats.map((sq) => NAME[E.typeOf(s.board[sq])] + ' on ' + E.sqName(sq)).join(', ')} under attack.`
          : 'Yes, nothing is loose.',
        ok: threats.length === 0,
      },
      { q: 'What is my plan?', a: `${best.san}: ${explain(s, best)}`, ok: true },
    ];
    const ordered = safetyFirst ? [steps[3], steps[0], steps[1], steps[2], steps[4]] : steps;

    let title, text, lesson;
    if (st.check) {
      title = 'You are in check';
      text = `Your king is under attack. You must move the king, block, or capture the attacker. Strongest option: ${best.san}. ${explain(s, best)}`;
      lesson = 'Lesson: a check always comes first. Look for the response that keeps your king safe and your material.';
    } else if (mate) {
      title = 'Checkmate in one';
      text = `Play ${mate.san}. ${explain(s, mate)}`;
      lesson = 'Lesson: before every move, ask whether any check leaves the enemy king with nowhere to go.';
    } else if (gain >= 100 && best.move.captured) {
      title = 'Free material';
      text = `${best.san} wins a ${NAME[E.typeOf(best.move.captured)]} and the bot cannot win it back. ${explain(s, best)}`;
      lesson = 'Lesson: scan for enemy pieces that nothing protects. Those are free gains.';
    } else if (gain >= 100) {
      title = 'Winning tactic';
      text = `${best.san} wins material by force. ${explain(s, best)}`;
      lesson = 'Lesson: look for forks, pins and moves that attack two things at once.';
    } else if (threats.length) {
      const names = threats.map((sq) => NAME[E.typeOf(s.board[sq])]).join(' and ');
      title = 'A piece is under attack';
      text = `Your ${names} ${threats.length > 1 ? 'are' : 'is'} attacked. ${best.san} deals with it best. ${explain(s, best)}`;
      lesson = 'Lesson: move, defend or trade a threatened piece before you do anything else.';
    } else if (s.fullmove <= 10) {
      title = 'Opening: develop and castle';
      text = `Bring out your knights and bishops, fight for the centre, then castle to keep your king safe. A good move here is ${best.san}. ${explain(s, best)}`;
      lesson = 'Lesson: in the opening, aim to develop pieces, control the centre and castle early.';
    } else {
      title = 'Keep improving';
      text = `There are no immediate tactics. A solid choice is ${best.san}. ${explain(s, best)}`;
      lesson = 'Lesson: each turn, check checks, captures and threats, then improve your least active piece.';
    }

    return {
      title, text, lesson, steps: ordered, arrows,
      focus: focusKey ? FOCUS[focusKey] : null,
      best: best.move,
    };
  }

  // The most frequent mistake in a set of tag counts, with practice advice.
  function focusFrom(tagCounts) {
    let key = null, n = 0;
    for (const k of Object.keys(FOCUS)) {
      const c = (tagCounts && tagCounts[k]) || 0;
      if (c > n) { n = c; key = k; }
    }
    return key ? { key, count: n, text: FOCUS[key] } : null;
  }

  const api = { MATE, NAME, FOCUS, material, scoreMove, rankMoves, hanging, review, explain, hint, focusFrom };
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  else globalThis.ChessCoach = api;
})();
