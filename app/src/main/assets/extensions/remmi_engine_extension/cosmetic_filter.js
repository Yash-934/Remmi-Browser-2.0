// Remmi Engine Extension - Brave-style Cosmetic Filtering Content Script
// Injects hide selectors at document_start and dynamically applies class/id cosmetic rules.

(function () {
  'use strict';

  // Only run in HTML/XHTML web documents
  if (
    !window.location ||
    (!window.location.protocol.startsWith('http') &&
      !window.location.protocol.startsWith('https'))
  ) {
    return;
  }

  const STYLE_ID_PREFIX = 'remmi-cosmetic-style-';
  const MAX_SELECTORS_PER_TAG = 800;
  const SEEN_CLASSES = new Set();
  const SEEN_IDS = new Set();
  const INJECTED_SELECTORS = new Set();

  let styleContainer = null;
  let styleTagIndex = 0;
  let isScanning = false;
  let scanTimer = null;
  let pendingClasses = [];
  let pendingIds = [];

  function getOrCreateStyleContainer() {
    if (styleContainer && styleContainer.isConnected) {
      return styleContainer;
    }
    const target =
      document.head || document.documentElement || document.body;
    if (!target) return null;

    let container = document.getElementById('remmi-cosmetic-container');
    if (!container) {
      container = document.createElement('div');
      container.id = 'remmi-cosmetic-container';
      container.style.display = 'none';
      container.setAttribute('aria-hidden', 'true');
      container.setAttribute('data-remmi-shield', 'cosmetic');
      try {
        if (target.firstChild) {
          target.insertBefore(container, target.firstChild);
        } else {
          target.appendChild(container);
        }
      } catch (_e) {
        try {
          target.appendChild(container);
        } catch (_e2) {}
      }
    }
    styleContainer = container;
    return styleContainer;
  }

  function injectSelectors(selectors) {
    if (!selectors || selectors.length === 0) return;

    const newSelectors = [];
    for (let i = 0; i < selectors.length; i++) {
      const sel = selectors[i].trim();
      if (sel && !INJECTED_SELECTORS.has(sel)) {
        INJECTED_SELECTORS.add(sel);
        newSelectors.push(sel);
      }
    }

    if (newSelectors.length === 0) return;

    const container = getOrCreateStyleContainer();
    if (!container) {
      // DOM might not have head/documentElement yet, schedule retry
      setTimeout(() => injectSelectors(newSelectors), 20);
      return;
    }

    for (let i = 0; i < newSelectors.length; i += MAX_SELECTORS_PER_TAG) {
      const chunk = newSelectors.slice(i, i + MAX_SELECTORS_PER_TAG);
      const styleEl = document.createElement('style');
      styleEl.id = `${STYLE_ID_PREFIX}${styleTagIndex++}`;
      styleEl.type = 'text/css';
      styleEl.setAttribute('data-remmi-rules', String(chunk.length));

      // Rule: display: none !important
      const cssText = chunk.join(',\n') + ' { display: none !important; }\n';
      styleEl.textContent = cssText;

      try {
        container.appendChild(styleEl);
      } catch (_e) {
        const root = document.head || document.documentElement;
        if (root) root.appendChild(styleEl);
      }
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
            if (
              Array.isArray(response.hideSelectors) &&
              response.hideSelectors.length > 0
            ) {
              allHide.push(...response.hideSelectors);
            }
            if (
              Array.isArray(response.forceHideSelectors) &&
              response.forceHideSelectors.length > 0
            ) {
              allHide.push(...response.forceHideSelectors);
            }
            if (allHide.length > 0) {
              injectSelectors(allHide);
            }
          }
        })
        .catch((_e) => {
          // Native or background communication error; fail-safe
        });
    } catch (_err) {}
  }

  // Flush dynamic classes and IDs to background for hidden class/id selector matching
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
            if (
              Array.isArray(response.hideSelectors) &&
              response.hideSelectors.length > 0
            ) {
              allHide.push(...response.hideSelectors);
            }
            if (
              Array.isArray(response.forceHideSelectors) &&
              response.forceHideSelectors.length > 0
            ) {
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
      scanDocumentForClassesAndIds();
    }, 150);
  }

  function scanDocumentForClassesAndIds() {
    if (isScanning) return;
    isScanning = true;

    try {
      const elements = document.querySelectorAll('[class], [id]');
      let foundNew = false;

      for (let i = 0; i < elements.length; i++) {
        const el = elements[i];

        // Process IDs
        const id = el.id;
        if (id && !SEEN_IDS.has(id) && id.length < 120) {
          SEEN_IDS.add(id);
          pendingIds.push(id);
          foundNew = true;
        }

        // Process Classes
        const className = el.className;
        if (className && typeof className === 'string') {
          const parts = className.split(/\s+/);
          for (let j = 0; j < parts.length; j++) {
            const cls = parts[j].trim();
            if (cls && !SEEN_CLASSES.has(cls) && cls.length < 120) {
              SEEN_CLASSES.add(cls);
              pendingClasses.push(cls);
              foundNew = true;
            }
          }
        }
      }

      if (foundNew) {
        flushDynamicSelectors();
      }
    } catch (_e) {
    } finally {
      isScanning = false;
    }
  }

  // Monitor DOM modifications for dynamically loaded ads / tracker elements
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
        if (m.type === 'childList' && m.addedNodes.length > 0) {
          shouldScan = true;
          break;
        } else if (
          m.type === 'attributes' &&
          (m.attributeName === 'class' || m.attributeName === 'id')
        ) {
          shouldScan = true;
          break;
        }
      }
      if (shouldScan) {
        scheduleScan();
      }

      // Safeguard: Ensure styleContainer remains attached
      if (styleContainer && !styleContainer.isConnected) {
        const root =
          document.head || document.documentElement || document.body;
        if (root) {
          try {
            root.appendChild(styleContainer);
          } catch (_e) {}
        }
      }
    });

    observer.observe(target, {
      childList: true,
      subtree: true,
      attributes: true,
      attributeFilter: ['class', 'id']
    });
  }

  // Initial trigger at document_start
  fetchInitialCosmetics();

  if (
    document.readyState === 'interactive' ||
    document.readyState === 'complete'
  ) {
    setupMutationObserver();
    scheduleScan();
  } else {
    document.addEventListener(
      'DOMContentLoaded',
      () => {
        setupMutationObserver();
        scheduleScan();
      },
      { once: true }
    );
  }
})();
