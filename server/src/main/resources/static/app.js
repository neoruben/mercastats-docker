(() => {
  'use strict';

  const MONTH_SHORT_ES = {
    '01': 'ENE', '02': 'FEB', '03': 'MAR', '04': 'ABR',
    '05': 'MAY', '06': 'JUN', '07': 'JUL', '08': 'AGO',
    '09': 'SEP', '10': 'OCT', '11': 'NOV', '12': 'DIC'
  };

  const MONTH_FULL_ES = {
    '01': 'Enero', '02': 'Febrero', '03': 'Marzo', '04': 'Abril',
    '05': 'Mayo', '06': 'Junio', '07': 'Julio', '08': 'Agosto',
    '09': 'Septiembre', '10': 'Octubre', '11': 'Noviembre', '12': 'Diciembre'
  };

  const state = {
    me: null,
    activeTab: 'tickets',
    tickets: [],
    stats: null,
    statsMode: 'month',
    products: [],
    productSort: 'frequency',
    productQuery: '',
    ticketQuery: '',
    expandedMonths: new Set(),
    initializedMonths: false,
    currentModalTicketId: null
  };

  const $ = (id) => document.getElementById(id);

  function escapeHtml(value) {
    return String(value ?? '')
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;')
      .replace(/'/g, '&#39;');
  }

  function formatEuro(amount) {
    const num = Number(amount || 0);
    return num.toLocaleString('es-ES', { minimumFractionDigits: 2, maximumFractionDigits: 2 }) + ' €';
  }

  function formatMonthYearTitle(monthKey) {
    const [year, month] = monthKey.split('-');
    return `${MONTH_FULL_ES[month] || month} ${year}`;
  }

  function formatRelativeTime(ms) {
    if (!ms) return '';
    const diffMin = Math.round((Date.now() - ms) / 60000);
    if (diffMin < 1) return 'hace un momento';
    if (diffMin < 60) return `hace ${diffMin} min`;
    const date = new Date(ms);
    const time = date.toLocaleTimeString('es-ES', { hour: '2-digit', minute: '2-digit' });
    const startOfToday = new Date(); startOfToday.setHours(0, 0, 0, 0);
    if (ms >= startOfToday.getTime()) return `hoy a las ${time}`;
    if (ms >= startOfToday.getTime() - 86400000) return `ayer a las ${time}`;
    return date.toLocaleString('es-ES', { day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' });
  }

  async function apiFetch(url, options = {}) {
    const res = await fetch(url, {
      credentials: 'same-origin',
      ...options
    });
    const text = await res.text();
    let data = null;
    if (text) {
      try { data = JSON.parse(text); } catch (_) { data = { error: text }; }
    }
    if (!res.ok) {
      const msg = (data && data.error) ? data.error : `Error HTTP ${res.status}`;
      const err = new Error(msg);
      err.status = res.status;
      throw err;
    }
    return data;
  }

  const bannerTimers = {};
  function showBanner(elId, message, type) {
    const el = $(elId);
    if (!el) return;
    clearTimeout(bannerTimers[elId]);
    if (!message) {
      el.classList.add('hidden');
      el.textContent = '';
      return;
    }
    el.textContent = message;
    el.classList.remove('hidden', 'alert-success', 'alert-error');
    if (!el.classList.contains('toast')) {
      if (type === 'success') el.classList.add('alert-success');
      if (type === 'error') el.classList.add('alert-error');
    }
    // Los avisos flotantes de éxito desaparecen solos; los de error esperan a que los cierres
    if (el.classList.contains('toast') && type !== 'error') {
      bannerTimers[elId] = setTimeout(() => showBanner(elId, ''), 6000);
    }
  }

  // percent: null = barra animada sin porcentaje; número 0-100 = barra con avance real
  function setBusy(busy, text = 'Procesando...', percent = null) {
    const wrap = $('sync-progress');
    $('sync-progress-text').textContent = text;
    wrap.classList.toggle('hidden', !busy);
    const determinate = busy && typeof percent === 'number';
    $('sync-progress-bar').classList.toggle('indeterminate', !determinate);
    $('sync-progress-fill').style.width = determinate ? `${Math.max(0, Math.min(100, percent))}%` : '0';
    ['btn-upload-pdfs', 'btn-sync-imap', 'btn-sync-gmail', 'btn-scan-inbox', 'btn-quick-sync'].forEach((id) => {
      const btn = $(id);
      if (btn) btn.disabled = busy;
    });
    $('btn-quick-sync').classList.toggle('spinning', busy);
    state.busy = busy;
    if (busy) {
      // Al empezar cualquier importación se cierra el panel para ver el progreso y la lista
      $('import-modal').classList.add('hidden');
      $('sync-strip-dot').className = 'status-dot busy';
      $('sync-strip-text').textContent = 'Importando tickets…';
    } else {
      renderSyncStrip(state.lastSyncStatus);
    }
  }

  // ---------------------------------------------------------------------------
  // Bootstrap & Auth
  // ---------------------------------------------------------------------------

  async function bootstrap() {
    try {
      const me = await apiFetch('/api/auth/me');
      state.me = me;
      if (me.authenticated && me.user) {
        showApp();
        handleUrlNotifications();
        await loadAllData();
      } else {
        showAuthScreen(me);
      }
    } catch (err) {
      showAuthScreen(null, err.message);
    }
  }

  function showAuthScreen(me, proxyError) {
    $('app-shell').classList.add('hidden');
    $('auth-screen').classList.remove('hidden');

    const proxyErrEl = $('auth-proxy-error');
    const formsEl = $('auth-forms');
    const tabsEl = $('auth-tabs');

    if (proxyError || (me && me.authMode === 'proxy')) {
      formsEl.classList.add('hidden');
      proxyErrEl.textContent = proxyError || 'Autenticación delegada al proxy (AUTH_MODE=proxy). Accede a través de tu reverse proxy (authentik / Authelia).';
      proxyErrEl.classList.remove('hidden');
      return;
    }

    proxyErrEl.classList.add('hidden');
    formsEl.classList.remove('hidden');
    tabsEl.classList.toggle('hidden', !(me && me.allowRegistration));
  }

  // Qué fuentes puede sincronizar a mano este usuario
  function syncSources(me) {
    if (!me || !me.user) return { imap: false, gmail: false, inbox: false };
    return {
      imap: !!(me.imapConfigured && me.imapAllowed),
      gmail: !!(me.gmailOAuthConfigured && me.gmailConnected),
      inbox: !!(me.inboxEnabled && me.user.isAdmin)
    };
  }

  function showApp() {
    $('auth-screen').classList.add('hidden');
    $('app-shell').classList.remove('hidden');

    const me = state.me;
    $('user-chip-label').textContent = me.user.displayName || me.user.username;

    // Sync controls visibility
    const canSync = syncSources(me);
    const anySync = canSync.imap || canSync.gmail || canSync.inbox;
    $('btn-sync-imap').classList.toggle('hidden', !anySync);
    $('btn-quick-sync').classList.toggle('hidden', !anySync);
    $('btn-connect-gmail').classList.toggle('hidden', !(me.gmailOAuthConfigured && !me.gmailConnected));
    $('btn-sync-gmail').classList.toggle('hidden', !(me.gmailOAuthConfigured && me.gmailConnected));
    $('btn-disconnect-gmail').classList.toggle('hidden', !(me.gmailOAuthConfigured && me.gmailConnected));
    $('btn-scan-inbox').classList.toggle('hidden', !(me.inboxEnabled && me.user.isAdmin));

    const badges = [];
    if (me.imapConfigured) badges.push('<span class="badge">Correo conectado</span>');
    if (me.inboxEnabled) badges.push('<span class="badge">Carpeta vigilada</span>');
    if (me.gmailOAuthConfigured) {
      badges.push(me.gmailConnected
        ? '<span class="badge">Gmail conectado</span>'
        : '<span class="badge badge-muted">Gmail sin conectar</span>');
    }
    $('sync-badges').innerHTML = badges.join('');

    // Account modal visibility
    $('btn-logout').classList.toggle('hidden', me.authMode !== 'local');
    $('account-password-section').classList.toggle('hidden', me.authMode !== 'local');
    $('admin-users-section').classList.toggle('hidden', !me.user.isAdmin);
    $('admin-create-user-form').classList.toggle('hidden', !(me.user.isAdmin && me.authMode === 'local'));
  }

  function handleUrlNotifications() {
    const params = new URLSearchParams(window.location.search);
    if (params.get('gmail') === 'connected') {
      showBanner('tickets-status-msg', 'Cuenta de Gmail conectada correctamente. Ya puedes sincronizar tus tickets.', 'success');
      window.history.replaceState({}, '', '/');
    } else if (params.get('gmail_error')) {
      showBanner('tickets-error-msg', `Error al conectar Gmail: ${params.get('gmail_error')}`, 'error');
      window.history.replaceState({}, '', '/');
    }
  }

  async function loadAllData() {
    const [tickets, stats, productsResp, syncStatus] = await Promise.all([
      apiFetch('/api/tickets'),
      apiFetch('/api/stats'),
      apiFetch(`/api/products?sort=${encodeURIComponent(state.productSort)}`),
      apiFetch('/api/sync/status')
    ]);
    state.tickets = tickets || [];
    state.stats = stats;
    state.products = (productsResp && productsResp.products) || [];
    renderHeader();
    renderTickets();
    renderStats();
    renderProducts();
    renderSyncStates(syncStatus);
    state.syncSignature = syncSignature(syncStatus);
    // Si el servidor está sincronizando por su cuenta (al arrancar o cada hora), mostrar la barra
    if (syncStatus && syncStatus.imapProgress) watchImapProgress(true);
    startIdleSyncCheck();
  }

  // Huella de la última ejecución de cada fuente (IMAP, Gmail, inbox): si cambia, hay tickets nuevos.
  function syncSignature(status) {
    return ((status && status.states) || []).map((s) => `${s.source}:${s.lastRunMs}`).sort().join('|');
  }

  // Con la página abierta y sin tocar nada, mira cada 30 s si el servidor ha importado algo
  // (sincronización automática o PDFs en la carpeta inbox) y recarga los datos solo.
  let idleCheckTimer = null;
  function startIdleSyncCheck() {
    if (idleCheckTimer) return;
    idleCheckTimer = setInterval(async () => {
      if (imapWatchTimer || document.hidden || !state.me || !state.me.authenticated) return;
      try {
        const status = await apiFetch('/api/sync/status');
        if (status.imapProgress) {
          watchImapProgress(true);
        } else if (syncSignature(status) !== state.syncSignature) {
          await loadAllData();
        } else {
          state.lastSyncStatus = status;
          renderSyncStrip(status);
        }
      } catch (_) { /* sin conexión: se reintenta en la siguiente vuelta */ }
    }, 30000);
  }

  // ---------------------------------------------------------------------------
  // Progreso de la sincronización IMAP (se consulta cada segundo mientras dura)
  // ---------------------------------------------------------------------------

  let imapWatchTimer = null;

  function imapProgressText(p) {
    if (!p.total) return `${p.phase}...`;
    const parts = [`${p.phase}: ${p.done} de ${p.total}`];
    if (p.inserted) parts.push(`${p.inserted} nuevos`);
    if (p.duplicates) parts.push(`${p.duplicates} duplicados`);
    if (p.failed) parts.push(`${p.failed} con error`);
    return parts.join(' · ');
  }

  function watchImapProgress(showResult, extraNotes = []) {
    if (imapWatchTimer) return;
    let lastLoad = Date.now();
    const tick = async () => {
      let status;
      try {
        status = await apiFetch('/api/sync/status');
      } catch (_) {
        imapWatchTimer = setTimeout(tick, 2000);
        return;
      }
      const p = status.imapProgress;
      if (p) {
        setBusy(true, imapProgressText(p), p.total ? (p.done / p.total) * 100 : null);
        // Refresca la lista de vez en cuando para ir viendo los tickets nuevos
        if (Date.now() - lastLoad > 10000) {
          lastLoad = Date.now();
          loadAllData().catch(() => {});
        }
        imapWatchTimer = setTimeout(tick, 1000);
        return;
      }
      imapWatchTimer = null;
      setBusy(false);
      if (showResult) {
        const imapState = (status.states || []).find((s) => s.source === 'imap');
        if (imapState && imapState.status === 'error') {
          showBanner('tickets-error-msg', imapState.message, 'error');
          if (extraNotes.length) showBanner('tickets-status-msg', `Importados ${extraNotes.join(' y ')}.`, 'success');
        } else if (imapState) {
          const extra = extraNotes.length ? ` Además, ${extraNotes.join(' y ')}.` : '';
          showBanner('tickets-status-msg', `${imapState.message}${extra}`, 'success');
        }
      }
      await loadAllData();
    };
    tick();
  }

  // ---------------------------------------------------------------------------
  // Evolución de precio de un producto (al pulsar en el ranking)
  // ---------------------------------------------------------------------------

  const PRICE_FMT = new Intl.NumberFormat('es-ES', { style: 'currency', currency: 'EUR', minimumFractionDigits: 2, maximumFractionDigits: 2 });
  const SHORT_DATE = new Intl.DateTimeFormat('es-ES', { day: 'numeric', month: 'short', year: '2-digit' });
  const MONTH_YEAR = new Intl.DateTimeFormat('es-ES', { month: 'short', year: '2-digit' });

  function parseIsoDate(iso) {
    // "YYYY-MM-DD HH:mm" -> Date local
    const [d, t] = iso.split(' ');
    const [y, m, day] = d.split('-').map(Number);
    const [hh, mm] = (t || '00:00').split(':').map(Number);
    return new Date(y, m - 1, day, hh || 0, mm || 0);
  }

  async function openProductModal(productKey) {
    $('product-modal-title').textContent = 'Cargando...';
    $('product-modal-sub').textContent = '';
    $('product-modal-body').innerHTML = '<p class="muted small">Cargando evolución de precio...</p>';
    $('product-modal').classList.remove('hidden');
    try {
      const data = await apiFetch(`/api/products/history?key=${encodeURIComponent(productKey)}`);
      renderPriceHistory(data);
    } catch (err) {
      $('product-modal-title').textContent = 'Producto';
      $('product-modal-body').innerHTML = `<div class="alert alert-error">${escapeHtml(err.message)}</div>`;
    }
  }

  function renderPriceHistory(data) {
    const pts = data.points.map((p) => ({ ...p, date: parseIsoDate(p.dateTimeIso) }));
    const perUnit = data.unit === 'kg' ? '/kg' : '/ud';
    const fmt = (v) => `${PRICE_FMT.format(v)}${perUnit}`;

    const first = pts[0];
    const last = pts[pts.length - 1];
    const prices = pts.map((p) => p.unitPrice);
    const min = Math.min(...prices);
    const max = Math.max(...prices);
    const change = first.unitPrice > 0 ? ((last.unitPrice - first.unitPrice) / first.unitPrice) * 100 : 0;
    const changeClass = change > 0.05 ? 'price-up' : change < -0.05 ? 'price-down' : '';
    const changeText = `${change > 0 ? '+' : ''}${change.toLocaleString('es-ES', { maximumFractionDigits: 1 })} %`;

    $('product-modal-title').textContent = data.displayName;
    $('product-modal-sub').textContent =
      `${pts.length} ${pts.length === 1 ? 'compra' : 'compras'} · desde ${SHORT_DATE.format(first.date)}`;

    const rows = [...pts].reverse().map((p) => `
      <div class="price-history-row">
        <span>${escapeHtml(SHORT_DATE.format(p.date))}</span>
        <span class="muted">${data.unit === 'kg'
          ? `${p.quantity.toLocaleString('es-ES', { maximumFractionDigits: 3 })} kg`
          : `${Math.round(p.quantity)} ud${Math.round(p.quantity) === 1 ? '' : 's'}`}</span>
        <strong>${escapeHtml(fmt(p.unitPrice))}</strong>
      </div>`).join('');

    $('product-modal-body').innerHTML = `
      <div class="price-summary">
        <div class="price-chip"><div class="price-chip-label">Último precio</div><div class="price-chip-value">${escapeHtml(fmt(last.unitPrice))}</div></div>
        <div class="price-chip"><div class="price-chip-label">Desde la 1.ª compra</div><div class="price-chip-value ${changeClass}">${escapeHtml(changeText)}</div></div>
        <div class="price-chip"><div class="price-chip-label">Mínimo</div><div class="price-chip-value">${escapeHtml(fmt(min))}</div></div>
        <div class="price-chip"><div class="price-chip-label">Máximo</div><div class="price-chip-value">${escapeHtml(fmt(max))}</div></div>
      </div>
      <div class="price-chart-wrap" id="price-chart-wrap"></div>
      <div class="price-history-list">${rows}</div>`;

    drawPriceChart($('price-chart-wrap'), pts, fmt);
  }

  // Gráfica de escalones: el precio se mantiene hasta la siguiente compra en que cambia.
  function drawPriceChart(wrap, pts, fmt) {
    const W = Math.max(300, Math.round(wrap.clientWidth) || 640);
    const H = W < 500 ? 210 : 240, padL = 54, padR = 12, padT = 30, padB = 26;
    const svgNS = 'http://www.w3.org/2000/svg';
    const t0 = pts[0].date.getTime();
    const t1 = pts[pts.length - 1].date.getTime();
    const span = Math.max(t1 - t0, 1);

    const prices = pts.map((p) => p.unitPrice);
    let lo = Math.min(...prices), hi = Math.max(...prices);
    if (hi - lo < 0.01) { lo -= 0.1; hi += 0.1; }
    const margin = (hi - lo) * 0.15;
    lo = Math.max(0, lo - margin); hi += margin;

    const x = (d) => pts.length === 1 ? (padL + W - padR) / 2 : padL + ((d.getTime() - t0) / span) * (W - padL - padR);
    const y = (v) => padT + (1 - (v - lo) / (hi - lo)) * (H - padT - padB);

    const el = (name, attrs) => {
      const n = document.createElementNS(svgNS, name);
      Object.entries(attrs).forEach(([k, v]) => n.setAttribute(k, v));
      return n;
    };

    const svg = el('svg', { viewBox: `0 0 ${W} ${H}`, class: 'price-chart', role: 'img',
      'aria-label': `Evolución de precio: de ${fmt(pts[0].unitPrice)} a ${fmt(pts[pts.length - 1].unitPrice)}` });

    // Líneas de referencia del eje Y (4 tramos)
    for (let i = 0; i <= 4; i++) {
      const v = lo + ((hi - lo) * i) / 4;
      svg.appendChild(el('line', { x1: padL, x2: W - padR, y1: y(v), y2: y(v), class: 'grid-line' }));
      const label = el('text', { x: padL - 6, y: y(v) + 4, 'text-anchor': 'end', class: 'axis-label' });
      label.textContent = PRICE_FMT.format(v);
      svg.appendChild(label);
    }

    // Eje X: primera y última fecha, y una intermedia si hay sitio
    const xTicks = pts.length > 2 ? [pts[0], pts[Math.floor(pts.length / 2)], pts[pts.length - 1]] : [pts[0], pts[pts.length - 1]];
    [...new Set(xTicks)].forEach((p, i, arr) => {
      const anchor = arr.length === 1 ? 'middle' : i === 0 ? 'start' : i === arr.length - 1 ? 'end' : 'middle';
      const label = el('text', { x: x(p.date), y: H - 6, 'text-anchor': anchor, class: 'axis-label' });
      label.textContent = MONTH_YEAR.format(p.date);
      svg.appendChild(label);
    });

    // Línea en escalones
    let d = `M ${x(pts[0].date)} ${y(pts[0].unitPrice)}`;
    for (let i = 1; i < pts.length; i++) {
      d += ` H ${x(pts[i].date)} V ${y(pts[i].unitPrice)}`;
    }
    svg.appendChild(el('path', { d, class: 'price-line' }));

    // Puntos solo donde cambia el precio (y el primero y el último), para no saturar
    const dots = [];
    pts.forEach((p, i) => {
      const changed = i === 0 || i === pts.length - 1 || Math.abs(p.unitPrice - pts[i - 1].unitPrice) > 0.004;
      const dot = el('circle', { cx: x(p.date), cy: y(p.unitPrice), r: changed ? 4 : 0, class: 'price-dot' });
      svg.appendChild(dot);
      dots.push(dot);
    });

    const cursor = el('line', { y1: padT - 6, y2: H - padB, class: 'cursor-line', visibility: 'hidden' });
    svg.appendChild(cursor);

    const tip = document.createElement('div');
    tip.className = 'price-tooltip hidden';
    wrap.appendChild(svg);
    wrap.appendChild(tip);

    let active = -1;
    const show = (evt) => {
      const rect = svg.getBoundingClientRect();
      const px = ((evt.clientX - rect.left) / rect.width) * W;
      let best = 0;
      pts.forEach((p, i) => { if (Math.abs(x(p.date) - px) < Math.abs(x(pts[best].date) - px)) best = i; });
      if (active >= 0) { dots[active].classList.remove('active'); dots[active].setAttribute('r', dots[active].dataset.r || dots[active].getAttribute('r')); }
      active = best;
      const dot = dots[best];
      dot.dataset.r = dot.dataset.r || dot.getAttribute('r');
      dot.setAttribute('r', 5);
      dot.classList.add('active');
      const cx = x(pts[best].date);
      cursor.setAttribute('x1', cx); cursor.setAttribute('x2', cx); cursor.setAttribute('visibility', 'visible');
      tip.textContent = `${SHORT_DATE.format(pts[best].date)} · ${fmt(pts[best].unitPrice)}`;
      tip.style.left = `${Math.min(Math.max((cx / W) * 100, 12), 88)}%`;
      tip.classList.remove('hidden');
    };
    const hide = () => {
      if (active >= 0) { dots[active].classList.remove('active'); dots[active].setAttribute('r', dots[active].dataset.r); }
      active = -1;
      cursor.setAttribute('visibility', 'hidden');
      tip.classList.add('hidden');
    };
    svg.addEventListener('pointermove', show);
    svg.addEventListener('pointerdown', show);
    svg.addEventListener('pointerleave', hide);
  }

  function renderHeader() {
    const stats = state.stats;
    if (!stats || stats.ticketCount === 0) {
      $('header-date-range').textContent = 'Sin compras registradas';
      $('header-total-spent').textContent = formatEuro(0);
      $('header-ticket-count').textContent = '0 tickets';
      return;
    }
    const range = stats.oldestDate === stats.newestDate
      ? `Fecha: ${stats.newestDate}`
      : `Del ${stats.oldestDate} al ${stats.newestDate}`;
    $('header-date-range').textContent = range;
    $('header-total-spent').textContent = formatEuro(stats.totalSpent);
    $('header-ticket-count').textContent = `${stats.ticketCount} ${stats.ticketCount === 1 ? 'ticket' : 'tickets'}`;
  }

  const SOURCE_NAMES = { imap: 'Correo', gmail: 'Gmail', inbox: 'Carpeta inbox', upload: 'Subida' };

  // Una frase con el estado de la última sincronización (la del correo si existe)
  function renderSyncStrip(syncStatus) {
    if (state.busy) return;
    const dot = $('sync-strip-dot');
    const text = $('sync-strip-text');
    const states = (syncStatus && syncStatus.states) || [];
    const main = states.find((s) => s.source === 'imap') ||
      states.find((s) => s.source === 'gmail') ||
      [...states].sort((a, b) => b.lastRunMs - a.lastRunMs)[0];

    if (main) {
      const name = SOURCE_NAMES[main.source] || main.source;
      const when = formatRelativeTime(main.lastRunMs);
      if (main.status === 'error') {
        dot.className = 'status-dot error';
        text.textContent = `${name}: error al sincronizar · ${when}`;
      } else {
        dot.className = 'status-dot ok';
        text.textContent = main.source === 'imap' || main.source === 'gmail'
          ? `${name} al día · ${when}`
          : `${name} · ${when}`;
      }
      return;
    }
    dot.className = 'status-dot';
    text.textContent = syncStatus && syncStatus.imapConfigured
      ? 'Correo pendiente de la primera sincronización'
      : 'Pulsa Importar para añadir tus tickets en PDF';
  }

  function renderSyncStates(syncStatus) {
    state.lastSyncStatus = syncStatus;
    renderSyncStrip(syncStatus);
    const el = $('sync-states-list');
    if (!syncStatus || !syncStatus.states || syncStatus.states.length === 0) {
      el.classList.add('hidden');
      return;
    }
    el.classList.remove('hidden');
    el.innerHTML = syncStatus.states.map((s) => {
      const icon = s.status === 'ok' ? '●' : '▲';
      const color = s.status === 'ok' ? 'var(--green)' : 'var(--danger)';
      const name = SOURCE_NAMES[s.source] || s.source;
      return `<div><span style="color:${color}">${icon}</span> <strong>${escapeHtml(name)}</strong> (${escapeHtml(formatRelativeTime(s.lastRunMs))}): ${escapeHtml(s.message)}</div>`;
    }).join('');
  }

  // ---------------------------------------------------------------------------
  // Tab 1: Tickets
  // ---------------------------------------------------------------------------

  function renderTickets() {
    const container = $('tickets-list');
    const q = state.ticketQuery.trim().toLowerCase();
    const filtered = q
      ? state.tickets.filter((t) =>
          (t.storeAddress || '').toLowerCase().includes(q) ||
          (t.ticketNumber || '').toLowerCase().includes(q) ||
          (t.dateDisplay || '').toLowerCase().includes(q)
        )
      : state.tickets;

    if (filtered.length === 0) {
      container.innerHTML = `
        <div class="card empty-card">
          <h3>${state.tickets.length === 0 ? 'Sin tickets todavía' : 'Sin resultados'}</h3>
          <p class="small">${state.tickets.length === 0
            ? 'Pulsa Importar para subir tus PDFs de Mercadona o sincronizar tu correo.'
            : `No se encontraron tickets para "${escapeHtml(state.ticketQuery)}".`}</p>
        </div>`;
      return;
    }

    const groups = new Map();
    for (const t of filtered) {
      const key = (t.dateTimeIso || '').slice(0, 7) || '0000-00';
      if (!groups.has(key)) groups.set(key, []);
      groups.get(key).push(t);
    }

    if (!state.initializedMonths) {
      const firstKey = groups.keys().next().value;
      if (firstKey) state.expandedMonths.add(firstKey);
      state.initializedMonths = true;
    }

    const html = [];
    for (const [monthKey, items] of groups.entries()) {
      const monthTotal = items.reduce((acc, t) => acc + Number(t.totalAmount || 0), 0);
      const isExpanded = q.length > 0 || state.expandedMonths.has(monthKey);
      const cardsHtml = items.map((t) => {
        const datePart = (t.dateTimeIso || '').split(' ')[0];
        const parts = datePart.split('-');
        const day = parts[2] || '--';
        const mon = MONTH_SHORT_ES[parts[1]] || '---';
        const timeOnly = (t.dateDisplay || '').split(' ')[1] || '';
        return `
          <button type="button" class="ticket-card" data-ticket-id="${t.id}">
            <div class="date-badge">
              <span class="date-badge-day">${escapeHtml(day)}</span>
              <span class="date-badge-month">${escapeHtml(mon)}</span>
            </div>
            <div class="ticket-meta">
              <div class="ticket-address">${escapeHtml(t.storeAddress)}</div>
              <div class="ticket-sub">${escapeHtml(timeOnly)} · ${t.itemCount} prod. · IVA ${escapeHtml(formatEuro(t.totalVatAmount))}</div>
            </div>
            <div class="ticket-amount">${escapeHtml(formatEuro(t.totalAmount))}</div>
          </button>`;
      }).join('');

      html.push(`
        <div class="month-group ${isExpanded ? '' : 'collapsed'}" data-month="${escapeHtml(monthKey)}">
          <button type="button" class="month-header" data-toggle-month="${escapeHtml(monthKey)}">
            <div>
              <div class="month-title">${escapeHtml(formatMonthYearTitle(monthKey))}</div>
              <div class="month-count">${items.length} ${items.length === 1 ? 'compra' : 'compras'}</div>
            </div>
            <div class="month-right">
              <span class="month-total-pill">${escapeHtml(formatEuro(monthTotal))}</span>
              <span class="chevron">▾</span>
            </div>
          </button>
          <div class="month-body">${cardsHtml}</div>
        </div>`);
    }

    container.innerHTML = html.join('');
  }

  async function openTicketDetail(ticketId) {
    try {
      const ticket = await apiFetch(`/api/tickets/${ticketId}`);
      state.currentModalTicketId = ticket.id;
      $('ticket-modal-title').textContent = `Ticket ${ticket.dateDisplay}`;
      $('ticket-modal-sub').textContent = `${ticket.storeAddress} · #${ticket.ticketNumber}`;

      const itemsHtml = (ticket.items || []).map((item) => {
        const qtyText = item.unit === 'kg'
          ? `${Number(item.quantity).toFixed(3)} kg`
          : `${Math.round(item.quantity)}x`;
        const unitInfo = item.unitPrice != null && item.quantity > 1 && item.unit !== 'kg'
          ? ` <span class="muted small">(${formatEuro(item.unitPrice)}/u)</span>`
          : (item.unit === 'kg' && item.unitPrice != null
              ? ` <span class="muted small">(${formatEuro(item.unitPrice)}/kg)</span>`
              : '');
        return `
          <div class="detail-row">
            <span><strong>${escapeHtml(qtyText)}</strong> ${escapeHtml(item.description)}${unitInfo}</span>
            <strong>${escapeHtml(formatEuro(item.totalPrice))}</strong>
          </div>`;
      }).join('');

      const vatHtml = (ticket.vatBreakdown || []).map((v) => `
        <div class="detail-row">
          <span class="muted">IVA ${v.vatRatePercent}% (Base ${escapeHtml(formatEuro(v.baseAmount))})</span>
          <span class="muted">${escapeHtml(formatEuro(v.vatAmount))}</span>
        </div>`).join('');

      $('ticket-modal-body').innerHTML = `
        <div>
          <div class="detail-section-title">Productos (${ticket.items.length})</div>
          ${itemsHtml || '<div class="muted small">Sin líneas de producto.</div>'}
        </div>
        <div>
          <div class="detail-section-title">Desglose IVA</div>
          ${vatHtml || '<div class="muted small">Sin desglose de IVA.</div>'}
        </div>
        <div class="detail-total">
          <span>TOTAL</span>
          <span>${escapeHtml(formatEuro(ticket.totalAmount))}</span>
        </div>`;

      $('ticket-modal').classList.remove('hidden');
    } catch (err) {
      showBanner('tickets-error-msg', err.message, 'error');
    }
  }

  async function uploadPdfFiles(fileList) {
    const files = Array.from(fileList || []).filter((f) => f.size > 0);
    if (files.length === 0) return;

    showBanner('tickets-status-msg', '');
    showBanner('tickets-error-msg', '');
    $('import-modal').classList.add('hidden');
    setBusy(true, `Subiendo y procesando ${files.length} ${files.length === 1 ? 'PDF' : 'PDFs'}...`);

    try {
      const formData = new FormData();
      for (const f of files) {
        formData.append('files', f, f.name);
      }
      const res = await apiFetch('/api/tickets/import', {
        method: 'POST',
        body: formData
      });
      const msg = `Importación: ${res.inserted} nuevos, ${res.duplicates} duplicados, ${res.failed} errores.`;
      if (res.failed > 0 && res.inserted === 0) {
        const firstErr = (res.results || []).find((r) => r.status === 'error');
        showBanner('tickets-error-msg', `${msg}${firstErr && firstErr.error ? ' (' + firstErr.error + ')' : ''}`, 'error');
      } else {
        showBanner('tickets-status-msg', msg, 'success');
      }
      await loadAllData();
    } catch (err) {
      showBanner('tickets-error-msg', err.message, 'error');
    } finally {
      setBusy(false);
      $('pdf-file-input').value = '';
    }
  }

  // ---------------------------------------------------------------------------
  // Tab 2: Estadísticas
  // ---------------------------------------------------------------------------

  function renderStats() {
    const s = state.stats;
    if (!s) return;

    $('kpi-total-spent').textContent = formatEuro(s.totalSpent);
    $('kpi-avg-ticket').textContent = formatEuro(s.averageTicket);
    $('kpi-total-vat').textContent = formatEuro(s.totalVat);
    $('kpi-ticket-count').textContent = String(s.ticketCount);

    const list = state.statsMode === 'month' ? (s.monthlyStats || []) : (s.weeklyStats || []);
    const chartEl = $('stats-chart');
    const breakdownEl = $('stats-breakdown-list');

    if (list.length === 0) {
      chartEl.innerHTML = '<div class="muted small">Sin datos suficientes todavía.</div>';
      breakdownEl.innerHTML = '';
      return;
    }

    const recent = list.slice(-14);
    const maxVal = Math.max(...recent.map((x) => Number(x.totalAmount || 0)), 1);

    chartEl.innerHTML = recent.map((item, idx) => {
      const ratio = Math.max(0.06, Math.min(1, Number(item.totalAmount || 0) / maxVal));
      const heightPx = Math.round(ratio * 135);
      const isLatest = idx === recent.length - 1;
      const tooltip = `${item.periodLabel}: ${formatEuro(item.totalAmount)} (${item.ticketCount} compras · IVA ${formatEuro(item.totalVatAmount)})`;
      return `
        <div class="bar-col ${isLatest ? 'latest' : ''}" title="${escapeHtml(tooltip)}">
          <div class="bar-val">${Math.round(item.totalAmount)}€</div>
          <div class="bar-track">
            <div class="bar-fill" style="height:${heightPx}px"></div>
          </div>
          <div class="bar-label">${escapeHtml(item.periodLabel)}</div>
        </div>`;
    }).join('');
    chartEl.scrollLeft = chartEl.scrollWidth;

    breakdownEl.innerHTML = [...list].reverse().map((item) => `
      <div class="period-row">
        <div>
          <div class="period-row-title">${escapeHtml(item.periodLabel)}</div>
          <div class="period-row-sub">${item.ticketCount} ${item.ticketCount === 1 ? 'compra' : 'compras'} · IVA ${escapeHtml(formatEuro(item.totalVatAmount))}</div>
        </div>
        <div class="period-row-amount">${escapeHtml(formatEuro(item.totalAmount))}</div>
      </div>`).join('');
  }

  // ---------------------------------------------------------------------------
  // Tab 3: Productos
  // ---------------------------------------------------------------------------

  function renderProducts() {
    const q = state.productQuery.trim().toLowerCase();
    let list = q
      ? state.products.filter((p) => (p.displayName || '').toLowerCase().includes(q))
      : [...state.products];

    if (state.productSort === 'amount') {
      list.sort((a, b) => b.totalAmount - a.totalAmount);
    } else {
      list.sort((a, b) =>
        (b.purchaseCount - a.purchaseCount) ||
        (b.totalQuantity - a.totalQuantity) ||
        (b.totalAmount - a.totalAmount)
      );
    }

    $('products-heading').textContent = `Ranking (${list.length})`;
    const container = $('products-list');

    if (list.length === 0) {
      container.innerHTML = `
        <div class="card empty-card">
          <p class="small">${state.products.length === 0
            ? 'Todavía no hay productos registrados. Importa tus tickets primero.'
            : `No se encontraron productos para "${escapeHtml(state.productQuery)}".`}</p>
        </div>`;
      return;
    }

    const maxBar = Math.max(
      ...list.map((p) => state.productSort === 'amount' ? p.totalAmount : p.purchaseCount),
      1
    );

    container.innerHTML = list.map((p, index) => {
      const ratio = Math.max(5, Math.min(100, Math.round(
        ((state.productSort === 'amount' ? p.totalAmount : p.purchaseCount) / maxBar) * 100
      )));
      const qtyText = p.unit === 'kg'
        ? `${Number(p.totalQuantity).toLocaleString('es-ES', { minimumFractionDigits: 2, maximumFractionDigits: 2 })} kg`
        : (Math.abs(p.totalQuantity - Math.round(p.totalQuantity)) < 0.01
            ? `${Math.round(p.totalQuantity)} uds`
            : `${Number(p.totalQuantity).toLocaleString('es-ES', { minimumFractionDigits: 2, maximumFractionDigits: 2 })} uds`);
      const rankClass = index === 0 ? 'rank-1' : index === 1 ? 'rank-2' : index === 2 ? 'rank-3' : '';

      return `
        <div class="product-card" data-key="${escapeHtml(p.productKey)}" role="button" tabindex="0"
             aria-label="Ver evolución de precio de ${escapeHtml(p.displayName)}">
          <div class="product-top">
            <div class="product-left">
              <div class="rank-badge ${rankClass}">${index + 1}</div>
              <div>
                <div class="product-name">${escapeHtml(p.displayName)}</div>
                <div class="product-sub">${escapeHtml(qtyText)} · ${p.purchaseCount} ${p.purchaseCount === 1 ? 'compra' : 'compras'}</div>
              </div>
            </div>
            <div class="product-amount">${escapeHtml(formatEuro(p.totalAmount))}</div>
          </div>
          <div class="product-bar">
            <div class="product-bar-fill" style="width:${ratio}%"></div>
          </div>
        </div>`;
    }).join('');
  }

  // ---------------------------------------------------------------------------
  // Account & Admin Modal
  // ---------------------------------------------------------------------------

  async function openAccountModal() {
    const me = state.me;
    $('account-modal-sub').textContent = `${me.user.username} · modo ${me.authMode}${me.user.isAdmin ? ' · admin' : ''}`;
    showBanner('pw-msg', '');
    showBanner('admin-user-msg', '');
    $('account-modal').classList.remove('hidden');

    if (me.user.isAdmin) {
      await loadAdminUsers();
    }
  }

  async function loadAdminUsers() {
    try {
      const users = await apiFetch('/api/admin/users');
      const container = $('admin-users-list');
      container.innerHTML = users.map((u) => {
        const canDelete = u.id !== state.me.user.id;
        return `
          <div class="user-row">
            <div>
              <strong>${escapeHtml(u.username)}</strong>
              <span class="muted small"> · ${escapeHtml(u.authSource)}${u.isAdmin ? ' · admin' : ''}</span>
            </div>
            ${canDelete ? `<button type="button" class="btn btn-danger-ghost small" data-delete-user="${u.id}">Eliminar</button>` : '<span class="muted small">Tú</span>'}
          </div>`;
      }).join('');
    } catch (err) {
      showBanner('admin-user-msg', err.message, 'error');
    }
  }

  // ---------------------------------------------------------------------------
  // Event Listeners
  // ---------------------------------------------------------------------------

  function switchTab(tabName) {
    state.activeTab = tabName;
    document.querySelectorAll('.tab-panel').forEach((p) => {
      p.classList.toggle('active', p.id === `tab-${tabName}`);
    });
    document.querySelectorAll('[data-tab]').forEach((btn) => {
      btn.classList.toggle('active', btn.dataset.tab === tabName);
    });
  }

  function bindEvents() {
    // Tabs (desktop + mobile)
    document.querySelectorAll('[data-tab]').forEach((btn) => {
      btn.addEventListener('click', () => switchTab(btn.dataset.tab));
    });

    // Auth tabs
    document.querySelectorAll('[data-auth-tab]').forEach((btn) => {
      btn.addEventListener('click', () => {
        const tab = btn.dataset.authTab;
        document.querySelectorAll('[data-auth-tab]').forEach((b) => b.classList.toggle('active', b === btn));
        $('login-form').classList.toggle('hidden', tab !== 'login');
        $('register-form').classList.toggle('hidden', tab !== 'register');
      });
    });

    $('login-form').addEventListener('submit', async (e) => {
      e.preventDefault();
      showBanner('login-error', '');
      try {
        await apiFetch('/api/auth/login', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({
            username: $('login-username').value,
            password: $('login-password').value
          })
        });
        $('login-password').value = '';
        await bootstrap();
      } catch (err) {
        showBanner('login-error', err.message, 'error');
      }
    });

    $('register-form').addEventListener('submit', async (e) => {
      e.preventDefault();
      showBanner('register-error', '');
      try {
        await apiFetch('/api/auth/register', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({
            username: $('reg-username').value,
            displayName: $('reg-display-name').value || null,
            password: $('reg-password').value
          })
        });
        $('reg-password').value = '';
        await bootstrap();
      } catch (err) {
        showBanner('register-error', err.message, 'error');
      }
    });

    // Upload & Drag/Drop
    const fileInput = $('pdf-file-input');
    const dropzone = $('dropzone');
    $('btn-upload-pdfs').addEventListener('click', () => fileInput.click());
    dropzone.addEventListener('click', () => fileInput.click());
    dropzone.addEventListener('keydown', (e) => {
      if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); fileInput.click(); }
    });
    fileInput.addEventListener('change', (e) => uploadPdfFiles(e.target.files));

    ['dragenter', 'dragover'].forEach((evt) => {
      dropzone.addEventListener(evt, (e) => { e.preventDefault(); dropzone.classList.add('dragover'); });
    });
    ['dragleave', 'drop'].forEach((evt) => {
      dropzone.addEventListener(evt, (e) => { e.preventDefault(); dropzone.classList.remove('dragover'); });
    });
    const overlay = $('drop-overlay');
    let dragDepth = 0;
    const hasFiles = (e) => e.dataTransfer && Array.from(e.dataTransfer.types || []).includes('Files');
    const canDrop = () => state.me && state.me.authenticated && !$('app-shell').classList.contains('hidden');
    document.addEventListener('dragenter', (e) => {
      if (!hasFiles(e) || !canDrop()) return;
      e.preventDefault();
      dragDepth++;
      overlay.classList.remove('hidden');
    });
    document.addEventListener('dragover', (e) => {
      if (hasFiles(e) && canDrop()) e.preventDefault();
    });
    document.addEventListener('dragleave', (e) => {
      if (!hasFiles(e)) return;
      dragDepth = Math.max(0, dragDepth - 1);
      if (dragDepth === 0) overlay.classList.add('hidden');
    });
    document.addEventListener('drop', (e) => {
      if (!hasFiles(e) || !canDrop()) return;
      e.preventDefault();
      dragDepth = 0;
      overlay.classList.add('hidden');
      const pdfs = Array.from(e.dataTransfer.files || []).filter((f) => /\.pdf$/i.test(f.name) || f.type === 'application/pdf');
      if (pdfs.length) uploadPdfFiles(pdfs);
      else showBanner('tickets-error-msg', 'Solo se pueden importar tickets en PDF.', 'error');
    });

    // Sync actions
    $('btn-sync-imap').addEventListener('click', () => syncAll());
    $('btn-quick-sync').addEventListener('click', () => syncAll());
    $('btn-open-import').addEventListener('click', () => $('import-modal').classList.remove('hidden'));
    $('sync-strip-status').addEventListener('click', () => $('import-modal').classList.remove('hidden'));
    ['tickets-status-msg', 'tickets-error-msg'].forEach((id) => {
      $(id).addEventListener('click', () => showBanner(id, ''));
    });

    // Sincroniza todo lo que tenga este usuario: carpeta inbox, Gmail y correo (IMAP).
    // La carpeta y Gmail son rápidas; el correo va al final con su barra de progreso.
    async function syncAll() {
      const can = syncSources(state.me);
      showBanner('tickets-status-msg', '');
      showBanner('tickets-error-msg', '');
      const notes = [];
      const errors = [];
      const plural = (n) => `${n} ${n === 1 ? 'ticket nuevo' : 'tickets nuevos'}`;

      if (can.inbox) {
        setBusy(true, 'Revisando la carpeta…');
        try {
          const res = await apiFetch('/api/sync/inbox', { method: 'POST' });
          if (res.inserted > 0) notes.push(`${plural(res.inserted)} de la carpeta`);
          if (res.failed > 0) errors.push(`${res.failed} PDF de la carpeta no se pudieron leer`);
        } catch (err) { errors.push(`Carpeta: ${err.message}`); }
      }
      if (can.gmail) {
        setBusy(true, 'Buscando en Gmail…');
        try {
          const res = await apiFetch('/api/sync/gmail', { method: 'POST' });
          if (res.inserted > 0) notes.push(`${plural(res.inserted)} de Gmail`);
        } catch (err) { errors.push(`Gmail: ${err.message}`); }
      }
      if (errors.length) showBanner('tickets-error-msg', errors.join(' · '), 'error');

      if (can.imap) {
        setBusy(true, 'Conectando con el correo…');
        try {
          // Si ya había una en curso (p. ej. la automática), simplemente se sigue su progreso.
          await apiFetch('/api/sync/imap', { method: 'POST' });
          watchImapProgress(true, notes);
          return;
        } catch (err) {
          showBanner('tickets-error-msg', [...errors, `Correo: ${err.message}`].join(' · '), 'error');
        }
      }

      setBusy(false);
      if (notes.length) showBanner('tickets-status-msg', `Importados ${notes.join(' y ')}.`, 'success');
      else if (!errors.length) showBanner('tickets-status-msg', 'Todo al día: no hay tickets nuevos.', 'success');
      await loadAllData();
    }

    $('btn-connect-gmail').addEventListener('click', () => {
      window.location.href = '/api/gmail/oauth/start';
    });

    $('btn-sync-gmail').addEventListener('click', async () => {
      showBanner('tickets-status-msg', '');
      showBanner('tickets-error-msg', '');
      setBusy(true, 'Sincronizando correos de Gmail...');
      try {
        const res = await apiFetch('/api/sync/gmail', { method: 'POST' });
        showBanner('tickets-status-msg', res.message, 'success');
        await loadAllData();
      } catch (err) {
        showBanner('tickets-error-msg', err.message, 'error');
      } finally {
        setBusy(false);
      }
    });

    $('btn-disconnect-gmail').addEventListener('click', async () => {
      if (!confirm('¿Desconectar tu cuenta de Gmail? Tus tickets guardados se mantendrán.')) return;
      try {
        await apiFetch('/api/gmail/disconnect', { method: 'POST' });
        showBanner('tickets-status-msg', 'Cuenta de Gmail desconectada.', 'success');
        await bootstrap();
      } catch (err) {
        showBanner('tickets-error-msg', err.message, 'error');
      }
    });

    $('btn-scan-inbox').addEventListener('click', async () => {
      showBanner('tickets-status-msg', '');
      showBanner('tickets-error-msg', '');
      setBusy(true, 'Escaneando carpeta /data/inbox...');
      try {
        const res = await apiFetch('/api/sync/inbox', { method: 'POST' });
        const msg = res.total === 0
          ? 'No había PDFs nuevos en la carpeta inbox.'
          : `Inbox: ${res.inserted} nuevos, ${res.duplicates} duplicados, ${res.failed} errores.`;
        showBanner('tickets-status-msg', msg, 'success');
        await loadAllData();
      } catch (err) {
        showBanner('tickets-error-msg', err.message, 'error');
      } finally {
        setBusy(false);
      }
    });

    // Ticket search & click delegation
    $('tickets-search').addEventListener('input', (e) => {
      state.ticketQuery = e.target.value;
      renderTickets();
    });

    $('tickets-list').addEventListener('click', (e) => {
      const monthBtn = e.target.closest('[data-toggle-month]');
      if (monthBtn) {
        const key = monthBtn.dataset.toggleMonth;
        if (state.expandedMonths.has(key)) state.expandedMonths.delete(key);
        else state.expandedMonths.add(key);
        renderTickets();
        return;
      }
      const card = e.target.closest('[data-ticket-id]');
      if (card) {
        openTicketDetail(Number(card.dataset.ticketId));
      }
    });

    // Delete ticket
    $('btn-delete-ticket').addEventListener('click', async () => {
      if (!state.currentModalTicketId) return;
      if (!confirm('¿Seguro que quieres eliminar este ticket?')) return;
      try {
        await apiFetch(`/api/tickets/${state.currentModalTicketId}`, { method: 'DELETE' });
        $('ticket-modal').classList.add('hidden');
        state.currentModalTicketId = null;
        await loadAllData();
      } catch (err) {
        showBanner('tickets-error-msg', err.message, 'error');
      }
    });

    // Stats mode switch
    document.querySelectorAll('[data-stats-mode]').forEach((btn) => {
      btn.addEventListener('click', () => {
        state.statsMode = btn.dataset.statsMode;
        document.querySelectorAll('[data-stats-mode]').forEach((b) => b.classList.toggle('active', b === btn));
        renderStats();
      });
    });

    // Products sort & search
    document.querySelectorAll('[data-product-sort]').forEach((btn) => {
      btn.addEventListener('click', () => {
        state.productSort = btn.dataset.productSort;
        document.querySelectorAll('[data-product-sort]').forEach((b) => b.classList.toggle('active', b === btn));
        renderProducts();
      });
    });

    $('products-search').addEventListener('input', (e) => {
      state.productQuery = e.target.value;
      renderProducts();
    });

    // Account modal
    $('account-btn').addEventListener('click', openAccountModal);

    $('btn-logout').addEventListener('click', async () => {
      await apiFetch('/api/auth/logout', { method: 'POST' });
      $('account-modal').classList.add('hidden');
      state.initializedMonths = false;
      state.expandedMonths.clear();
      await bootstrap();
    });

    $('change-password-form').addEventListener('submit', async (e) => {
      e.preventDefault();
      showBanner('pw-msg', '');
      try {
        const res = await apiFetch('/api/auth/password', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({
            currentPassword: $('pw-current').value,
            newPassword: $('pw-new').value
          })
        });
        $('pw-current').value = '';
        $('pw-new').value = '';
        showBanner('pw-msg', res.message, 'success');
      } catch (err) {
        showBanner('pw-msg', err.message, 'error');
      }
    });

    $('admin-create-user-form').addEventListener('submit', async (e) => {
      e.preventDefault();
      showBanner('admin-user-msg', '');
      try {
        await apiFetch('/api/admin/users', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({
            username: $('new-user-username').value,
            password: $('new-user-password').value,
            displayName: $('new-user-display').value || null,
            isAdmin: $('new-user-admin').checked
          })
        });
        $('new-user-username').value = '';
        $('new-user-password').value = '';
        $('new-user-display').value = '';
        $('new-user-admin').checked = false;
        showBanner('admin-user-msg', 'Usuario creado.', 'success');
        await loadAdminUsers();
      } catch (err) {
        showBanner('admin-user-msg', err.message, 'error');
      }
    });

    $('admin-users-list').addEventListener('click', async (e) => {
      const btn = e.target.closest('[data-delete-user]');
      if (!btn) return;
      if (!confirm('¿Eliminar este usuario y todos sus tickets?')) return;
      try {
        await apiFetch(`/api/admin/users/${btn.dataset.deleteUser}`, { method: 'DELETE' });
        await loadAdminUsers();
      } catch (err) {
        showBanner('admin-user-msg', err.message, 'error');
      }
    });

    // Close modals
    // Pulsar un producto del ranking abre su evolución de precio
    const productsList = $('products-list');
    productsList.addEventListener('click', (evt) => {
      const card = evt.target.closest('.product-card[data-key]');
      if (card) openProductModal(card.dataset.key);
    });
    productsList.addEventListener('keydown', (evt) => {
      if (evt.key !== 'Enter' && evt.key !== ' ') return;
      const card = evt.target.closest('.product-card[data-key]');
      if (card) { evt.preventDefault(); openProductModal(card.dataset.key); }
    });

    document.querySelectorAll('[data-close-modal]').forEach((btn) => {
      btn.addEventListener('click', () => {
        $(btn.dataset.closeModal).classList.add('hidden');
      });
    });
    document.querySelectorAll('.modal-backdrop').forEach((backdrop) => {
      backdrop.addEventListener('click', (e) => {
        if (e.target === backdrop) backdrop.classList.add('hidden');
      });
    });
    document.addEventListener('keydown', (e) => {
      if (e.key === 'Escape') {
        document.querySelectorAll('.modal-backdrop').forEach((m) => m.classList.add('hidden'));
      }
    });
  }

  bindEvents();
  bootstrap();
})();
