// Remmi Engine WebExtension - Dedicated Ad/Tracker Blocker & Click Transparency Bridge
// CRITICAL SECURITY INVARIANT: Native Gecko layer is the SOLE authoritative proxy manager.
// The WebExtension does NOT modify browser.proxy or route settings.

let port = null;
let isConnecting = false;
let reconnectTimer = null;
const pendingMessages = [];

function logToNative(msg) {
  if (port) {
    try {
      port.postMessage({ type: "LOG", message: msg, action: "log", msg: msg });
    } catch (_e) {}
  }
}

function flushPendingMessages() {
  if (!port) return;
  while (pendingMessages.length > 0) {
    const msg = pendingMessages.shift();
    try {
      port.postMessage(msg);
    } catch (_e) {
      pendingMessages.unshift(msg);
      break;
    }
  }
}

function connectNative() {
  if (isConnecting) return;
  isConnecting = true;
  if (reconnectTimer) {
    clearTimeout(reconnectTimer);
    reconnectTimer = null;
  }

  try {
    port = browser.runtime.connectNative("remmi_engine_extension");
    if (!port) {
      console.warn("[Remmi] connectNative returned null");
      isConnecting = false;
      return;
    }
    console.log("[Remmi] connectNative SUCCESS");

    try {
      port.postMessage({
        type: "PORT_STATUS",
        status: "CONNECTED",
        role: "AD_TRACKER_BLOCKER_ONLY"
      });
    } catch (_err) {}

    flushPendingMessages();
    isConnecting = false;

    port.onMessage.addListener((msg) => {
      if (!msg) return;
      if (msg.type === "EXTRACT_HTML") {
        const requestId = msg.requestId;
        const tabId = msg.tabId;
        if (tabId !== undefined && tabId !== null) {
          browser.tabs.executeScript(tabId, {
            code: "document.documentElement.outerHTML;"
          }).then((res) => {
            let html = (res && res[0]) ? res[0] : "";
            const MAX_HTML_BYTES = 2 * 1024 * 1024; // 2MB limit for bridge
            if (new Blob([html]).size > MAX_HTML_BYTES) {
               html = html.substring(0, MAX_HTML_BYTES) + "<!-- Truncated by Remmi Native Bridge -->";
            }
            if (port) port.postMessage({ type: "EXTRACTED_HTML", html: html, url: "", requestId: requestId, tabId: tabId });
          }).catch(e => {
            if (port) port.postMessage({ type: "EXTRACTED_HTML", html: "", url: "", requestId: requestId, tabId: tabId });
          });
        }
      } else if (msg.type === "EXECUTE_SCRIPT") {
        const tabId = msg.tabId;
        const scriptCode = msg.script;
        if (tabId !== undefined && tabId !== null && scriptCode) {
          browser.tabs.executeScript(tabId, { code: scriptCode }).catch(e => {
            console.error("[Remmi] EXECUTE_SCRIPT failed", e);
          });
        }
      }
    });

    port.onDisconnect.addListener(() => {
      console.warn("[Remmi] connectNative onDisconnect");
      port = null;
      isConnecting = false;
      if (!reconnectTimer) {
        reconnectTimer = setTimeout(connectNative, 3000);
      }
    });
  } catch (_e) {
    console.error("[Remmi] connectNative FAILED", _e);
    port = null;
    isConnecting = false;
    if (!reconnectTimer) {
      reconnectTimer = setTimeout(connectNative, 5000);
    }
  }
}

connectNative();

// 1. Content Script Message Listener: Forward CLICK_INSPECTED -> native port -> BlockExtension
browser.runtime.onMessage.addListener((message, _sender, sendResponse) => {
  if (!message) return;
  
  // Validate sender context
  if (!_sender || !_sender.tab || !_sender.tab.url || !_sender.tab.url.startsWith("http")) {
      return;
  }

  if (message.type === "CLICK_INSPECTED") {
    const payload = {
      type: "CLICK_INSPECTION_RESULT",
      candidates: message.candidates || [],
      hasOverlay: !!message.hasOverlay,
      intercepted: !!message.intercepted,
      pageUrl: message.pageUrl || "",
      timestamp: message.timestamp || Date.now()
    };

    if (port) {
      try {
        port.postMessage(payload);
      } catch (_e) {
        pendingMessages.push(payload);
      }
    } else {
      pendingMessages.push(payload);
    }
  }

  if (sendResponse) sendResponse({ received: true });
  return true;
});

// 2. Delegate network requests to Native Engine with fast-path filtering and LRU decision caching
const BLOCKABLE_SCHEMES = ["http:", "https:"];
const DECISION_CACHE = new Map();
const MAX_CACHE_SIZE = 800;
const CACHE_TTL_MS = 300000; // 5 minutes

function getCachedDecision(key) {
  if (DECISION_CACHE.has(key)) {
    const item = DECISION_CACHE.get(key);
    if (Date.now() - item.ts < CACHE_TTL_MS) {
      return item.cancel;
    }
    DECISION_CACHE.delete(key);
  }
  return null;
}

function setCachedDecision(key, cancel) {
  if (DECISION_CACHE.size >= MAX_CACHE_SIZE) {
    const firstKey = DECISION_CACHE.keys().next().value;
    if (firstKey) DECISION_CACHE.delete(firstKey);
  }
  DECISION_CACHE.set(key, { cancel: !!cancel, ts: Date.now() });
}

browser.webRequest.onBeforeRequest.addListener(
  async function(details) {
    const url = details.url;
    if (!url) return { cancel: false };

    // Fast-Path 1: Skip non-HTTP(S) internal protocols (e.g. data:, blob:, moz-extension:, about:)
    if (!url.startsWith("http://") && !url.startsWith("https://")) {
      return { cancel: false };
    }

    // Fast-Path 2: Check LRU Decision Cache to eliminate IPC overhead for repeat requests
    const origin = details.originUrl || details.documentUrl || "";
    const resType = details.type || "other";
    const cacheKey = `${resType}|${origin}|${url}`;

    const cached = getCachedDecision(cacheKey);
    if (cached !== null) {
      return { cancel: cached };
    }

    try {
      const response = await browser.runtime.sendNativeMessage("remmi_engine_extension", {
        type: "SHOULD_BLOCK",
        url: url,
        sourceUrl: origin,
        resourceType: resType
      });

      const shouldCancel = !!(response && response.cancel === true);
      setCachedDecision(cacheKey, shouldCancel);

      if (shouldCancel) {
        if (port) {
          try {
            port.postMessage({
              type: "BLOCKED",
              action: "blocked",
              url: url,
              category: resType
            });
          } catch (_e) {}
        }
        return { cancel: true };
      }
    } catch (e) {
      logToNative(
        `[WEBEXT] SHOULD_BLOCK_ERROR type=${details.type} name=${e?.name || "unknown"} message=${e?.message || String(e)}`
      );
      // Controlled fallback: Allow request to proceed if bridge fails so stylesheets/scripts are not canceled
      return { cancel: false };
    }
    
    return { cancel: false };
  },
  { urls: ["<all_urls>"] },
  ["blocking"]
);
