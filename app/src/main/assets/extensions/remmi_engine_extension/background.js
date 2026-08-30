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

function withTimeout(promise, timeoutMs) {
  return Promise.race([
    promise,
    new Promise((_, reject) =>
      setTimeout(() => reject(new Error("native_timeout")), timeoutMs)
    )
  ]);
}

async function nativePing() {
  try {
    const res = await withTimeout(
      browser.runtime.sendNativeMessage("remmi_engine_extension", {
        type: "PING"
      }),
      1500
    );
    logToNative(`[WEBEXT_PING_RESULT] ok=${res?.ok} pong=${res?.pong}`);
    return res;
  } catch (e) {
    logToNative(`[WEBEXT_PING_ERROR] error=${e?.message || String(e)}`);
    return null;
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

    // Run native ping diagnostic test
    nativePing();

    port.onMessage.addListener((msg) => {
      if (!msg) return;
      if (msg.type === "CLEAR_CACHE" || msg.type === "RULES_UPDATED") {
        rulesGeneration++;
        DECISION_CACHE.clear();
        INFLIGHT_DECISIONS.clear();
        console.log(`[Remmi] Decision cache cleared on rules/profile update (gen=${rulesGeneration})`);
      } else if (msg.type === "PROFILE_CHANGED") {
        currentProfile = msg.profile || "SHIELD";
        rulesGeneration++;
        DECISION_CACHE.clear();
        INFLIGHT_DECISIONS.clear();
        console.log(`[Remmi] Profile changed to ${currentProfile}, cleared decision cache (gen=${rulesGeneration})`);
      } else if (msg.type === "EXTRACT_HTML") {
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
let currentProfile = "SHIELD";
let rulesGeneration = 0;
const DECISION_CACHE = new Map();
const INFLIGHT_DECISIONS = new Map();

const MAX_CACHE_SIZE = 800;
const CACHE_TTL_MS = 300000; // 5 minutes default
const NATIVE_DECISION_TIMEOUT_MS = 1500;

// STEP 2: Blockable resource types policy (websocket intentionally excluded for separate lifecycle handling)
const BLOCKABLE_TYPES = new Set([
  "main_frame",
  "sub_frame",
  "script",
  "stylesheet",
  "image",
  "imageset",
  "font",
  "xmlhttprequest",
  "web_manifest",
  "object",
  "media"
]);

const BLOCKER_METRICS = {
  requests: 0,
  cacheHits: 0,
  inflightHits: 0,
  nativeCalls: 0,
  nativeErrors: 0,
  blocked: 0
};

function getCacheTtl(resourceType) {
  switch (resourceType) {
    case "script":
    case "stylesheet":
    case "font":
      return 5 * 60 * 1000;
    case "image":
    case "imageset":
    case "media":
      return 2 * 60 * 1000;
    case "main_frame":
    case "sub_frame":
      return 60 * 1000;
    default:
      return 60 * 1000;
  }
}

function getCachedDecision(key) {
  const item = DECISION_CACHE.get(key);
  if (!item) {
    return null;
  }
  if (Date.now() - item.ts < item.ttl) {
    return item.cancel;
  }
  DECISION_CACHE.delete(key);
  return null;
}

function setCachedDecision(key, cancel, resourceType = "other") {
  if (DECISION_CACHE.size >= MAX_CACHE_SIZE) {
    const firstKey = DECISION_CACHE.keys().next().value;
    if (firstKey) {
      DECISION_CACHE.delete(firstKey);
    }
  }
  DECISION_CACHE.set(key, {
    cancel: !!cancel,
    ts: Date.now(),
    ttl: getCacheTtl(resourceType)
  });
}

function buildDecisionKey(details) {
  const method = (details.method || "GET").toUpperCase();
  const resType = details.type || "other";
  const origin = details.originUrl || details.documentUrl || "";

  return [
    currentProfile,
    rulesGeneration,
    method,
    resType,
    origin,
    details.url
  ].join("|");
}

async function getNativeDecision(details, cacheKey) {
  if (INFLIGHT_DECISIONS.has(cacheKey)) {
    BLOCKER_METRICS.inflightHits++;
    logToNative(
      `[WEBEXT_INFLIGHT_HIT] type=${details.type || "other"}`
    );
    return INFLIGHT_DECISIONS.get(cacheKey);
  }

  const promise = (async () => {
    BLOCKER_METRICS.nativeCalls++;
    const response = await withTimeout(
      browser.runtime.sendNativeMessage(
        "remmi_engine_extension",
        {
          type: "SHOULD_BLOCK",
          url: details.url,
          sourceUrl: details.originUrl || details.documentUrl || "",
          resourceType: details.type || "other"
        }
      ),
      NATIVE_DECISION_TIMEOUT_MS
    );

    if (!response || response.ok !== true) {
      throw new Error(
        `native_decision_invalid:${response?.error || "unknown"}`
      );
    }

    return response.cancel === true;
  })();

  INFLIGHT_DECISIONS.set(cacheKey, promise);

  try {
    return await promise;
  } finally {
    INFLIGHT_DECISIONS.delete(cacheKey);
  }
}

browser.webRequest.onBeforeRequest.addListener(
  async function(details) {
    const url = details.url;
    if (!url) {
      return { cancel: false };
    }

    // Internal/non-network protocols never need the network blocker.
    if (
      !url.startsWith("http://") &&
      !url.startsWith("https://")
    ) {
      return { cancel: false };
    }

    const resType = details.type || "other";
    const method = (details.method || "GET").toUpperCase();

    // Resource types outside the blocker policy stay on Gecko's normal network path.
    if (!BLOCKABLE_TYPES.has(resType)) {
      return { cancel: false };
    }

    BLOCKER_METRICS.requests++;

    const isIdempotent =
      method === "GET" ||
      method === "HEAD" ||
      method === "OPTIONS";

    const cacheKey = buildDecisionKey(details);

    // Fast path: cached decision.
    if (isIdempotent) {
      const cached = getCachedDecision(cacheKey);
      if (cached !== null) {
        BLOCKER_METRICS.cacheHits++;
        logToNative(
          `[WEBEXT_CACHE_HIT] type=${resType} cancel=${cached}`
        );
        if ((BLOCKER_METRICS.requests) % 50 === 0) {
          logToNative(
            `[WEBEXT_METRICS] requests=${BLOCKER_METRICS.requests} cacheHits=${BLOCKER_METRICS.cacheHits} inflightHits=${BLOCKER_METRICS.inflightHits} nativeCalls=${BLOCKER_METRICS.nativeCalls} errors=${BLOCKER_METRICS.nativeErrors} blocked=${BLOCKER_METRICS.blocked}`
          );
        }
        return { cancel: cached };
      }
    }

    logToNative(
      `[WEBEXT_CACHE_MISS] type=${resType} method=${method}`
    );

    try {
      const shouldCancel = await getNativeDecision(details, cacheKey);

      if (isIdempotent) {
        setCachedDecision(cacheKey, shouldCancel, resType);
      }

      if (shouldCancel) {
        BLOCKER_METRICS.blocked++;
        logToNative(`[WEBEXT_BLOCK] type=${resType}`);

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

        if ((BLOCKER_METRICS.requests) % 50 === 0) {
          logToNative(
            `[WEBEXT_METRICS] requests=${BLOCKER_METRICS.requests} cacheHits=${BLOCKER_METRICS.cacheHits} inflightHits=${BLOCKER_METRICS.inflightHits} nativeCalls=${BLOCKER_METRICS.nativeCalls} errors=${BLOCKER_METRICS.nativeErrors} blocked=${BLOCKER_METRICS.blocked}`
          );
        }

        return { cancel: true };
      }

      if ((BLOCKER_METRICS.requests) % 50 === 0) {
        logToNative(
          `[WEBEXT_METRICS] requests=${BLOCKER_METRICS.requests} cacheHits=${BLOCKER_METRICS.cacheHits} inflightHits=${BLOCKER_METRICS.inflightHits} nativeCalls=${BLOCKER_METRICS.nativeCalls} errors=${BLOCKER_METRICS.nativeErrors} blocked=${BLOCKER_METRICS.blocked}`
        );
      }

      return { cancel: false };
    } catch (e) {
      BLOCKER_METRICS.nativeErrors++;
      if ((BLOCKER_METRICS.requests) % 50 === 0) {
        logToNative(
          `[WEBEXT_METRICS] requests=${BLOCKER_METRICS.requests} cacheHits=${BLOCKER_METRICS.cacheHits} inflightHits=${BLOCKER_METRICS.inflightHits} nativeCalls=${BLOCKER_METRICS.nativeCalls} errors=${BLOCKER_METRICS.nativeErrors} blocked=${BLOCKER_METRICS.blocked}`
        );
      }
      logToNative(
        `[WEBEXT_NATIVE_ERROR] type=${resType} name=${e?.name || "unknown"} message=${e?.message || String(e)}`
      );

      /*
       * IMPORTANT:
       * Blocking failure must not destroy normal webpage rendering.
       * Ghost/Tor routing security is enforced by the native route authority,
       * not by the adblocker's failure path.
       */
      return { cancel: false };
    }
  },
  { urls: ["<all_urls>"] },
  ["blocking"]
);
