/*
 * App controller: board UI, bot opponent, persistence and the learning profile.
 * The "bot" plays a uniformly random legal move. All coaching comes from coach.js.
 */
(function () {
  'use strict';

  const E = globalThis.ChessEngine;
  const C = globalThis.ChessCoach;

  const GLYPH = { k: '♚', q: '♛', r: '♜', b: '♝', n: '♞', p: '♟' };
  const GRADE_LABEL = { best: 'Best', good: 'Good', inaccuracy: 'Inaccuracy', mistake: 'Mistake', blunder: 'Blunder' };
  const KEY = { current: 'chess.current', games: 'chess.games', style: 'chess.style', live: 'chess.live' };
  const BOT_DELAY_MS = 450;

  const store = {
    get(key, fallback) {
      try { const v = localStorage.getItem(key); return v === null ? fallback : JSON.parse(v); } catch (e) { return fallback; }
    },
    set(key, value) {
      try { localStorage.setItem(key, JSON.stringify(value)); } catch (e) { /* storage full or disabled */ }
    },
  };

  const S = {
    userColor: 'w',
    view: 'w',                 // colour shown at the bottom of the board
    history: [],               // [{ state, uci, san, note }]; history[0] is the start position
    hints: 0,
    resigned: false,
    saved: false,              // current game already written to the game archive
    selected: null,
    targets: [],
    promo: null,
    hint: null,
    live: '',
    tab: 'play',
    style: store.get(KEY.style, 'visual'),
    liveOn: store.get(KEY.live, true),
    botTimer: null,
    rankCache: { key: null, ranked: null },
  };

  const $ = (id) => document.getElementById(id);
  const cur = () => S.history[S.history.length - 1].state;
  const countBy = (arr) => arr.reduce((o, x) => { o[x] = (o[x] || 0) + 1; return o; }, {});

  function rankedFor(state) {
    const key = E.toFen(state);
    if (S.rankCache.key !== key) S.rankCache = { key, ranked: C.rankMoves(state) };
    return S.rankCache.ranked;
  }

  // ---------- persistence ----------

  function persist() {
    store.set(KEY.current, {
      userColor: S.userColor,
      view: S.view,
      hints: S.hints,
      resigned: S.resigned,
      saved: S.saved,
      moves: S.history.slice(1).map((h) => ({ uci: h.uci, san: h.san, note: h.note })),
    });
    store.set(KEY.style, S.style);
    store.set(KEY.live, S.liveOn);
  }

  function restore() {
    S.history = [{ state: E.newGame(), uci: null, san: null, note: null }];
    const saved = store.get(KEY.current, null);
    if (!saved) return;
    S.userColor = saved.userColor || 'w';
    S.view = saved.view || S.userColor;
    S.hints = saved.hints || 0;
    S.resigned = !!saved.resigned;
    S.saved = !!saved.saved;
    for (const mv of saved.moves || []) {
      const st = cur();
      const m = E.parseUci(st, mv.uci);
      if (!m) break;
      S.history.push({ state: E.applyMove(st, m), uci: mv.uci, san: mv.san, note: mv.note || null });
    }
  }

  // ---------- game archive and learning profile ----------

  // Writes the current game to the archive once (win / loss / draw / abandoned).
  function archive(result) {
    if (S.saved) return;
    S.saved = true;
    const notes = S.history.map((h) => h.note).filter(Boolean);
    if (!notes.length) return;
    const games = store.get(KEY.games, []);
    games.push({
      date: new Date().toISOString().slice(0, 10),
      color: S.userColor,
      result,
      moves: notes.length,
      grades: countBy(notes.map((n) => n.grade)),
      tags: countBy(notes.flatMap((n) => n.tags)),
      hints: S.hints,
    });
    store.set(KEY.games, games.slice(-50));
  }

  function addInto(target, source) {
    for (const k of Object.keys(source)) target[k] = (target[k] || 0) + source[k];
  }

  // Everything the coach has learned about you: archived games plus the game in progress.
  function profile() {
    const games = store.get(KEY.games, []);
    const tags = {}, grades = {};
    let wins = 0, losses = 0, draws = 0, hints = S.saved ? 0 : S.hints;
    for (const g of games) {
      addInto(tags, g.tags || {});
      addInto(grades, g.grades || {});
      hints += g.hints || 0;
      if (g.result === 'win') wins++;
      else if (g.result === 'loss') losses++;
      else if (g.result === 'draw') draws++;
    }
    const notes = S.history.map((h) => h.note).filter(Boolean);
    if (!S.saved) {
      addInto(tags, countBy(notes.flatMap((n) => n.tags)));
      addInto(grades, countBy(notes.map((n) => n.grade)));
    }
    return { games: games.length, wins, losses, draws, hints, tags, grades, focus: C.focusFrom(tags) };
  }

  // ---------- moves ----------

  function afterMove() {
    const st = E.status(cur());
    if (st.over && !S.saved) {
      archive(st.winner === null ? 'draw' : st.winner === S.userColor ? 'win' : 'loss');
    }
    persist();
    render();
    maybeBot();
  }

  function playUser(m) {
    const before = cur();
    const rev = C.review(before, m, rankedFor(before));
    S.history.push({
      state: E.applyMove(before, m),
      uci: E.uci(m),
      san: rev.san,
      note: { san: rev.san, grade: rev.grade, tags: rev.tags, comment: rev.comment, bestSan: rev.bestSan },
    });
    S.selected = null;
    S.targets = [];
    S.hint = null;
    S.live = S.liveOn ? rev.comment : '';
    afterMove();
  }

  function maybeBot() {
    clearTimeout(S.botTimer);
    const s = cur();
    if (S.resigned || E.status(s).over || s.turn === S.userColor) return;
    S.botTimer = setTimeout(() => {
      const st = cur();
      const moves = E.legalMoves(st);
      if (!moves.length) return;
      const m = moves[Math.floor(Math.random() * moves.length)];
      S.history.push({ state: E.applyMove(st, m), uci: E.uci(m), san: E.toSan(st, m), note: null });
      S.hint = null;
      afterMove();
    }, BOT_DELAY_MS);
  }

  function onSquare(sq) {
    if (S.promo || S.resigned) return;
    const s = cur();
    if (E.status(s).over || s.turn !== S.userColor) return;
    if (S.selected !== null) {
      const hits = S.targets.filter((t) => t.to === sq);
      if (hits.length) {
        if (hits[0].promo) openPromo(hits);
        else playUser(hits[0]);
        return;
      }
    }
    const p = s.board[sq];
    if (p && E.colorOf(p) === S.userColor) {
      S.selected = sq;
      S.targets = E.movesFrom(s, sq);
    } else {
      S.selected = null;
      S.targets = [];
    }
    render();
  }

  function openPromo(list) {
    S.promo = list;
    $('promoChoices').innerHTML = list
      .map((m) => `<button class="${S.userColor}" data-promo="${m.promo}">${GLYPH[m.promo]}</button>`)
      .join('');
    $('promo').hidden = false;
  }

  function closePromo() {
    S.promo = null;
    $('promo').hidden = true;
  }

  // ---------- controls ----------

  function newGame(color) {
    clearTimeout(S.botTimer);
    if (!S.saved && S.history.length > 1) archive('abandoned');
    S.userColor = S.view = color;
    S.history = [{ state: E.newGame(), uci: null, san: null, note: null }];
    S.hints = 0;
    S.resigned = false;
    S.saved = false;
    S.selected = null;
    S.targets = [];
    S.hint = null;
    S.live = '';
    closePromo();
    persist();
    render();
    maybeBot();
  }

  function undo() {
    if (S.resigned || S.history.length < 2 || E.status(cur()).over) return;
    clearTimeout(S.botTimer);
    // Remove the bot's reply and your move, so it is your turn again.
    do { S.history.pop(); } while (S.history.length > 1 && cur().turn !== S.userColor);
    S.selected = null;
    S.targets = [];
    S.hint = null;
    S.live = '';
    persist();
    render();
    maybeBot();
  }

  function resign() {
    if (S.resigned || S.history.length < 2 || E.status(cur()).over) return;
    clearTimeout(S.botTimer);
    S.resigned = true;
    S.hint = null;
    archive('loss');
    persist();
    render();
  }

  function showHint() {
    const s = cur();
    if (S.resigned || E.status(s).over || s.turn !== S.userColor) return;
    S.hints++;
    const focus = profile().focus;
    S.hint = C.hint(s, focus ? focus.key : null, rankedFor(s));
    persist();
    render();
  }

  // ---------- rendering ----------

  function render() {
    renderStatus();
    renderBoard();
    renderCoach();
    renderMoves();
    if (S.tab === 'review') renderReview();
    if (S.tab === 'profile') renderProfile();
  }

  function renderStatus() {
    const s = cur();
    const st = E.status(s);
    let text;
    if (S.resigned) text = 'You resigned. Start a new game to play again.';
    else if (st.over && st.reason === 'checkmate') text = st.winner === S.userColor ? 'Checkmate. You win!' : 'Checkmate. The bot wins this one.';
    else if (st.over) text = `Draw (${st.reason}).`;
    else if (s.turn === S.userColor) text = (st.check ? 'You are in check. ' : '') + 'Your move.';
    else text = 'Bot is thinking…';
    $('status').textContent = text;
  }

  function renderBoard() {
    const s = cur();
    const last = S.history[S.history.length - 1];
    const lastSquares = last.uci ? [E.sqIndex(last.uci.slice(0, 2)), E.sqIndex(last.uci.slice(2, 4))] : [];
    const kingSq = E.inCheck(s.board, s.turn) ? E.kingSquare(s.board, s.turn) : -1;
    const white = S.view === 'w';
    const cells = [];
    for (let vr = 0; vr < 8; vr++) {
      for (let vf = 0; vf < 8; vf++) {
        const sq = white ? (7 - vr) * 8 + vf : vr * 8 + (7 - vf);
        const p = s.board[sq];
        const cls = ['sq', ((sq >> 3) + (sq & 7)) % 2 === 0 ? 'dark' : 'light'];
        if (lastSquares.includes(sq)) cls.push('last');
        if (sq === S.selected) cls.push('sel');
        if (sq === kingSq) cls.push('check');
        const target = S.targets.find((t) => t.to === sq);
        if (target) cls.push(p ? 'capture' : 'target');
        const glyph = p ? `<span class="pc ${E.colorOf(p)}">${GLYPH[E.typeOf(p)]}</span>` : '';
        const rankLabel = vf === 0 ? `<b class="lbl r">${(sq >> 3) + 1}</b>` : '';
        const fileLabel = vr === 7 ? `<b class="lbl f">${E.FILES[sq & 7]}</b>` : '';
        cells.push(`<div class="${cls.join(' ')}" data-sq="${sq}">${rankLabel}${glyph}${fileLabel}</div>`);
      }
    }
    $('board').innerHTML = cells.join('');

    const svg = $('arrows');
    const defs = svg.querySelector('defs').outerHTML;
    const arrows = S.hint && S.style !== 'words' ? S.hint.arrows : [];
    const pos = (sq) => {
      const col = white ? (sq & 7) : 7 - (sq & 7);
      const row = white ? 7 - (sq >> 3) : (sq >> 3);
      return [col + 0.5, row + 0.5];
    };
    svg.innerHTML = defs + arrows.map((a) => {
      const [x1, y1] = pos(a.from), [x2, y2] = pos(a.to);
      const marker = a.kind === 'good' ? 'ah-good' : 'ah-threat';
      return `<line class="arr ${a.kind}" x1="${x1}" y1="${y1}" x2="${x2}" y2="${y2}" marker-end="url(#${marker})"/>`;
    }).join('');
  }

  function renderCoach() {
    const h = S.hint;
    $('hintTitle').textContent = h ? h.title : 'Coach';
    const text = $('hintText');
    text.className = h ? '' : 'muted';
    if (!h) {
      text.innerHTML = 'Press <b>Stuck? Hint</b> and I will tell you what to do next and why.';
    } else {
      text.textContent = S.style === 'words' ? `${h.text} ${h.lesson}` : h.text;
    }
    const steps = $('steps');
    steps.innerHTML = h && S.style === 'steps'
      ? h.steps.map((st) => `<li class="${st.ok ? 'ok' : 'todo'}"><b>${st.q}</b> ${st.a}</li>`).join('')
      : '';
    $('live').textContent = S.live ? `Live commentary: ${S.live}` : '';
    const focus = profile().focus;
    $('focusTip').textContent = focus ? `Your practice focus: ${focus.text}` : '';
    $('undo').disabled = S.history.length < 2 || S.resigned;
    $('resign').disabled = S.history.length < 2 || S.resigned || E.status(cur()).over;
  }

  function renderMoves() {
    const parts = [];
    S.history.slice(1).forEach((h, i) => {
      if (i % 2 === 0) parts.push(`<span class="n">${i / 2 + 1}.</span>`);
      const cls = h.note ? ` class="${h.note.grade}"` : '';
      parts.push(`<span${cls}>${h.san}</span>`);
    });
    $('moveList').innerHTML = parts.length ? parts.join(' ') : '<span class="muted">No moves yet.</span>';
  }

  function renderReview() {
    const rows = [];
    S.history.forEach((h, i) => {
      if (!h.note) return;
      const ply = i - 1; // history[1] is White's first move
      const num = Math.floor(ply / 2) + 1;
      const label = `${num}${ply % 2 === 0 ? '.' : '…'} ${h.note.san}`;
      rows.push(`<div class="rv ${h.note.grade}"><b>${label}</b><span class="g ${h.note.grade}">${GRADE_LABEL[h.note.grade]}</span><p>${h.note.comment}</p></div>`);
    });
    const p = profile();
    const counts = Object.keys(GRADE_LABEL).map((g) => `${GRADE_LABEL[g]}: ${(p.grades[g] || 0)}`).join(' · ');
    $('reviewBody').innerHTML = (rows.length
      ? `<div class="card"><p class="muted">${counts}</p></div>${rows.join('')}`
      : '<div class="card muted">Play a few moves and I will annotate each one here.</div>');
  }

  function renderProfile() {
    const p = profile();
    const pct = (n, d) => (d ? Math.round((n / d) * 100) : 0);
    const notesTotal = Object.values(p.grades).reduce((a, b) => a + b, 0);
    const lines = [];
    if (p.focus) {
      lines.push(`Your most common slip is <b>${p.focus.key.replace(/-/g, ' ')}</b> (${p.focus.count} times). Hints lead with a check for it.`);
    }
    if (notesTotal >= 5) {
      const good = (p.grades.best || 0) + (p.grades.good || 0);
      lines.push(`About ${pct(good, notesTotal)}% of your moves were best or good.`);
    }
    if (p.games && p.hints / p.games > 3) {
      lines.push('You lean on hints a lot. Try a game without them, then review it to see what you noticed.');
    }
    if (!lines.length) lines.push('Play a few games and I will learn your patterns and tailor my advice.');

    $('profileBody').innerHTML = `
      <h2>Your learning profile</h2>
      <div class="stats">
        <div><b>${p.games}</b><span>games</span></div>
        <div><b>${p.wins}-${p.losses}-${p.draws}</b><span>W-L-D</span></div>
        <div><b>${p.hints}</b><span>hints used</span></div>
      </div>
      <ul class="muted">${lines.map((l) => `<li>${l}</li>`).join('')}</ul>`;

    document.querySelectorAll('input[name="style"]').forEach((el) => { el.checked = el.value === S.style; });
    $('liveToggle').checked = S.liveOn;
  }

  // ---------- wiring ----------

  function showTab(name) {
    S.tab = name;
    document.querySelectorAll('nav button').forEach((b) => b.classList.toggle('on', b.dataset.tab === name));
    document.querySelectorAll('.tab').forEach((t) => { t.hidden = t.id !== 'tab-' + name; });
    render();
  }

  function init() {
    restore();
    $('board').addEventListener('click', (e) => {
      const el = e.target.closest('.sq');
      if (el) onSquare(Number(el.dataset.sq));
    });
    $('promoChoices').addEventListener('click', (e) => {
      const btn = e.target.closest('button[data-promo]');
      if (!btn || !S.promo) return;
      const m = S.promo.find((x) => x.promo === btn.dataset.promo);
      closePromo();
      if (m) playUser(m);
    });
    $('promoCancel').addEventListener('click', () => { closePromo(); render(); });
    $('newWhite').addEventListener('click', () => newGame('w'));
    $('newBlack').addEventListener('click', () => newGame('b'));
    $('hint').addEventListener('click', showHint);
    $('undo').addEventListener('click', undo);
    $('resign').addEventListener('click', resign);
    $('flip').addEventListener('click', () => { S.view = S.view === 'w' ? 'b' : 'w'; render(); });
    document.querySelectorAll('nav button').forEach((b) => b.addEventListener('click', () => showTab(b.dataset.tab)));
    document.querySelectorAll('input[name="style"]').forEach((el) => el.addEventListener('change', () => {
      S.style = el.value;
      persist();
      render();
    }));
    $('liveToggle').addEventListener('change', (e) => {
      S.liveOn = e.target.checked;
      if (!S.liveOn) S.live = '';
      persist();
      render();
    });
    persist();
    render();
    maybeBot();
  }

  init();
})();
