// Update existing nodes so background synchronization preserves focus and scroll.
function nodeKey(node) {
  if (node.nodeType !== Node.ELEMENT_NODE) return null;
  return node.getAttribute('data-sync-key') || (node.hasAttribute('data-photo') ? 'photo:'+node.getAttribute('data-photo') : null);
}

function sameKind(left, right) {
  return left.nodeType === right.nodeType && left.nodeName === right.nodeName;
}

function patchNode(current, next) {
  if (current.nodeType !== Node.ELEMENT_NODE) {
    if (current.nodeValue !== next.nodeValue) current.nodeValue = next.nodeValue;
    return;
  }
  // An expanded history section stays expanded while its records update.
  const preserveOpen = current.tagName === 'DETAILS';
  const preservePhoto = current.hasAttribute('data-photo') && current.getAttribute('src');
  const preserveAttribute = name => (preserveOpen && name === 'open') || (preservePhoto && ['src','hidden'].includes(name));
  for (const {name} of [...current.attributes]) {
    if (!preserveAttribute(name) && !next.hasAttribute(name)) current.removeAttribute(name);
  }
  for (const {name, value} of [...next.attributes]) {
    if (!preserveAttribute(name) && current.getAttribute(name) !== value) current.setAttribute(name, value);
  }
  patchChildren(current, next);
  if (current instanceof HTMLInputElement && current !== document.activeElement) {
    current.value = next.value;
    current.checked = next.checked;
  } else if (current instanceof HTMLTextAreaElement && current !== document.activeElement) {
    current.value = next.value;
  } else if (current instanceof HTMLSelectElement && current !== document.activeElement) {
    current.value = next.value;
  }
}

function patchChildren(current, next) {
  let cursor = current.firstChild;
  for (const wanted of [...next.childNodes]) {
    const key = nodeKey(wanted);
    let match = cursor;
    if (key) {
      match = [...current.childNodes].find(node => nodeKey(node) === key && sameKind(node, wanted));
    } else if (match && (nodeKey(match) || !sameKind(match, wanted))) {
      match = null;
    }
    if (!match) {
      const replacement = wanted.cloneNode(true);
      if (!key && cursor && !nodeKey(cursor)) {
        current.replaceChild(replacement, cursor);
        cursor = replacement.nextSibling;
      } else {
        current.insertBefore(replacement, cursor);
      }
      continue;
    }
    if (match !== cursor) current.insertBefore(match, cursor);
    patchNode(match, wanted);
    cursor = match.nextSibling;
  }
  while (cursor) {
    const following = cursor.nextSibling;
    current.removeChild(cursor);
    cursor = following;
  }
}

export function patchContent(element, html) {
  if (!element) return;
  const template = document.createElement('template');
  template.innerHTML = html;
  patchChildren(element, template.content);
}

export function startLiveSync(sync, interval = 5000) {
  let inFlight = false;
  async function tick() {
    if (inFlight || document.visibilityState !== 'visible') return;
    inFlight = true;
    try { await sync(); } finally { inFlight = false; }
  }
  const timer = window.setInterval(tick, interval);
  const onVisible = () => { if (document.visibilityState === 'visible') void tick(); };
  document.addEventListener('visibilitychange', onVisible);
  window.addEventListener('online', onVisible);
  return () => {
    clearInterval(timer);
    document.removeEventListener('visibilitychange', onVisible);
    window.removeEventListener('online', onVisible);
  };
}
