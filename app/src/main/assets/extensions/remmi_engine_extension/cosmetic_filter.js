// Remmi Engine Extension - Brave-style Cosmetic Filtering Content Script
// Injects hide selectors directly into document.head and dynamically applies class/id cosmetic rules.

(function () {
  'use strict';

  if (!window.location || (!window.location.protocol.startsWith('http') && !window.location.protocol.startsWith('https'))) {
    return;
  }

  const STYLE_ID_PREFIX = 'remmi-cosmetic-style-';
  const MAX_SELECTORS_PER_TAG = 800;
  const SEEN_CLASSES = new Set();
  const SEEN_IDS = new Set();
  const INJECTED_SELECTORS = new Set();

  let styleTagIndex = 0;
  let isScanning = false;
  let scanTimer = null;
  let pendingClasses = [];
  let pendingIds = [];

  // Used to prevent extremely malformed selectors from crashing the style injection
  function isSafeCssSelector(selector) {
    if (!selector) return false;
    // Basic sanity checks:
    if (selector.includes('<') || selector.includes('>')) {
       // > is valid in CSS, but < is not.
       if (selector.includes('<')) return false;
    }
    return true;
  }

  function injectSelectors(selectors) {
    if (!selectors || selectors.length === 0) return;

    const newSelectors = [];
    for (let i = 0; i < selectors.length; i++) {
      const sel = String(selectors[i]).trim();
      if (sel && !INJECTED_SELECTORS.has(sel) && isSafeCssSelector(sel)) {
        INJECTED_SELECTORS.add(sel);
        newSelectors.push(sel);
      }
    }

    if (newSelectors.length === 0) return;

    const root = document.head || document.documentElement;
    if (!root) {
      requestAnimationFrame(() => injectSelectors(newSelectors));
      return;
    }

    for (let i = 0; i < newSelectors.length; i += MAX_SELECTORS_PER_TAG) {
      const chunk = newSelectors.slice(i, i + MAX_SELECTORS_PER_TAG);
      const styleEl = document.createElement('style');
      styleEl.id = `${STYLE_ID_PREFIX}${styleTagIndex++}`;
      styleEl.type = 'text/css';
      styleEl.setAttribute('data-remmi-cosmetic', '1');

      const cssText = chunk.join(',\n') + ' { display: none !important; }\n';
      styleEl.textContent = cssText;

      try {
        root.appendChild(styleEl);
      } catch (_e) {}
    }
  }

  // Request initial cosmetic selectors from background/native engine
  function fetchInitialCosmetics() {
    try {
      browser.runtime
        .sendMessage({
          type: 'GET_COSMETIC_RESOURCES',
          url: window.location.href,
          hostname: window.location.hostname
        })
        .then((response) => {
          if (response && response.ok) {
            const allHide = [];
            if (Array.isArray(response.hideSelectors) && response.hideSelectors.length > 0) {
              allHide.push(...response.hideSelectors);
            }
            if (Array.isArray(response.forceHideSelectors) && response.forceHideSelectors.length > 0) {
              allHide.push(...response.forceHideSelectors);
            }
            if (allHide.length > 0) {
              injectSelectors(allHide);
            }
          }
        })
        .catch((_e) => {});
    } catch (_err) {}
  }

  function flushDynamicSelectors() {
    if (pendingClasses.length === 0 && pendingIds.length === 0) return;

    const classesToSend = pendingClasses.splice(0, 200);
    const idsToSend = pendingIds.splice(0, 200);

    try {
      browser.runtime
        .sendMessage({
          type: 'GET_HIDDEN_CLASS_ID_SELECTORS',
          classes: classesToSend,
          ids: idsToSend
        })
        .then((response) => {
          if (response && response.ok) {
            const allHide = [];
            if (Array.isArray(response.hideSelectors) && response.hideSelectors.length > 0) {
              allHide.push(...response.hideSelectors);
            }
            if (Array.isArray(response.forceHideSelectors) && response.forceHideSelectors.length > 0) {
              allHide.push(...response.forceHideSelectors);
            }
            if (allHide.length > 0) {
              injectSelectors(allHide);
            }
          }
        })
        .catch((_e) => {});
    } catch (_e) {}
  }

  function scheduleScan() {
    if (scanTimer) return;
    scanTimer = setTimeout(() => {
      scanTimer = null;
      flushDynamicSelectors();
    }, 150);
  }

  function collectNode(node) {
    if (!(node instanceof Element)) return;
    let foundNew = false;

    const id = node.id;
    if (id && typeof id === 'string' && id.length < 120 && !SEEN_IDS.has(id)) {
      SEEN_IDS.add(id);
      pendingIds.push(id);
      foundNew = true;
    }

    if (node.classList && node.classList.length > 0) {
      for (let j = 0; j < node.classList.length; j++) {
        const cls = node.classList[j];
        if (cls && typeof cls === 'string' && cls.length < 120 && !SEEN_CLASSES.has(cls)) {
          SEEN_CLASSES.add(cls);
          pendingClasses.push(cls);
          foundNew = true;
        }
      }
    }

    return foundNew;
  }

  function collectNodeTree(rootNode) {
    let foundNew = collectNode(rootNode);
    if (rootNode.querySelectorAll) {
      const elements = rootNode.querySelectorAll('[class], [id]');
      for (let i = 0; i < elements.length; i++) {
        if (collectNode(elements[i])) {
          foundNew = true;
        }
      }
    }
    return foundNew;
  }

  // Monitor DOM modifications incrementally
  function setupMutationObserver() {
    const target = document.documentElement || document;
    if (!target) {
      setTimeout(setupMutationObserver, 50);
      return;
    }

    const observer = new MutationObserver((mutations) => {
      let shouldScan = false;
      for (let i = 0; i < mutations.length; i++) {
        const m = mutations[i];
        if (m.type === 'childList') {
          for (let j = 0; j < m.addedNodes.length; j++) {
            if (collectNodeTree(m.addedNodes[j])) {
              shouldScan = true;
            }
          }
        } else if (m.type === 'attributes') {
          if (collectNode(m.target)) {
            shouldScan = true;
          }
        }
      }
      if (shouldScan) {
        scheduleScan();
      }
    });

    observer.observe(target, {
      childList: true,
      subtree: true,
      attributes: true,
      attributeFilter: ['class', 'id']
    });
  }

  function startCosmeticPipeline() {
    setupMutationObserver();
    
    // Initial scan of whatever is already in the DOM (very early at document_start usually just <html> or <head>)
    if (document.documentElement) {
      if (collectNodeTree(document.documentElement)) {
        scheduleScan();
      }
    }
    
    fetchInitialCosmetics();
  }

  startCosmeticPipeline();
})();
