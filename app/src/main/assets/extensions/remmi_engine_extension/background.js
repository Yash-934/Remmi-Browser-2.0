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

function withTimeout(promise, timeoutMs, timeoutName = "native_timeout") {
  let timer = null;
  const timeoutPromise = new Promise((_, reject) => {
    timer = setTimeout(() => {
      const err = new Error(timeoutName);
      err.name = "TimeoutError";
      reject(err);
    }, timeoutMs);
  });

  return Promise.race([promise, timeoutPromise]).finally(() => {
    if (timer !== null) {
      clearTimeout(timer);
      timer = null;
    }
  });
}

function isTraceCandidateUrl(url) {
  if (!url || typeof url !== "string") return false;
  return (
    url.includes("google-analytics") ||
    url.includes("adblock-tester") ||
    url.includes("googletagmanager") ||
    url.includes("banner")
  );
}

async function runPingBenchmark(count = 100) {
  const latencies = [];
  let successes = 0;
  let failures = 0;

  for (let i = 0; i < count; i++) {
    const reqId = `ping_${i}_${Date.now()}`;
    const start = Date.now();
    try {
      logToNative(`[NM_SEND_START] requestId=${reqId} messageType=PING`);
      const res = await withTimeout(
        browser.runtime.sendNativeMessage("remmi_engine_extension", {
          type: "PING",
          requestId: reqId
        }),
        1500
      );
      const elapsed = Date.now() - start;
      if (res && res.ok === true && res.pong === true) {
        successes++;
        latencies.push(elapsed);
        logToNative(`[NM_SEND_SUCCESS] requestId=${reqId} responseOk=true elapsedMs=${elapsed}`);
      } else {
        failures++;
        logToNative(`[NM_SEND_ERROR] requestId=${reqId} errorName=INVALID_NATIVE_RESPONSE errorMessage=bad_pong`);
      }
    } catch (e) {
      failures++;
      let errName = "NATIVE_MESSAGE_FAILURE";
      if (e?.message === "native_timeout") errName = "NATIVE_TIMEOUT";
      logToNative(`[NM_SEND_ERROR] requestId=${reqId} errorName=${errName} errorMessage=${e?.message || String(e)}`);
    }
  }

  latencies.sort((a, b) => a - b);
  const p50 = latencies.length > 0 ? latencies[Math.floor(latencies.length * 0.5)] : 0;
  const p95 = latencies.length > 0 ? latencies[Math.floor(latencies.length * 0.95)] : 0;
  const max = latencies.length > 0 ? latencies[latencies.length - 1] : 0;

  logToNative(`[WEBEXT_PING_BENCHMARK] count=${count} success=${successes} failure=${failures} p50=${p50}ms p95=${p95}ms max=${max}ms`);
  return { successes, failures, p50, p95, max };
}

async function nativePing() {
  const reqId = `ping_init_${Date.now()}`;
  try {
    logToNative(`[NM_SEND_START] requestId=${reqId} messageType=PING`);
    const startTs = Date.now();
    const res = await withTimeout(
      browser.runtime.sendNativeMessage("remmi_engine_extension", {
        type: "PING",
        requestId: reqId
      }),
      1500
    );
    const elapsed = Date.now() - startTs;
    if (res && res.ok === true) {
      logToNative(`[NM_SEND_SUCCESS] requestId=${reqId} responseOk=true elapsedMs=${elapsed}`);
      logToNative(`[WEBEXT_PING_RESULT] ok=true pong=${res.pong}`);
    } else {
      logToNative(`[NM_SEND_ERROR] requestId=${reqId} errorName=INVALID_NATIVE_RESPONSE errorMessage=invalid_response`);
      logToNative(`[WEBEXT_PING_RESULT] ok=false pong=false`);
    }
    return res;
  } catch (e) {
    let errName = "NATIVE_MESSAGE_FAILURE";
    if (e?.message === "native_timeout") errName = "NATIVE_TIMEOUT";
    logToNative(`[NM_SEND_ERROR] requestId=${reqId} errorName=${errName} errorMessage=${e?.message || String(e)}`);
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
        COSMETIC_CACHE.clear();
        INFLIGHT_COSMETIC.clear();
        console.log(`[Remmi] Decision and cosmetic cache cleared on rules/profile update (gen=${rulesGeneration})`);
      } else if (msg.type === "PROFILE_CHANGED") {
        currentProfile = msg.profile || "SHIELD";
        rulesGeneration++;
        DECISION_CACHE.clear();
        INFLIGHT_DECISIONS.clear();
        COSMETIC_CACHE.clear();
        INFLIGHT_COSMETIC.clear();
        console.log(`[Remmi] Profile changed to ${currentProfile}, cleared decision/cosmetic cache (gen=${rulesGeneration})`);
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
      } else if (msg.type === "RUN_BENCHMARK") {
        runPingBenchmark(msg.count || 100);
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

// Priority scheduling for low-priority dynamic cosmetic queries
const MAX_CONCURRENT_CLASS_ID_COSMETICS = 2;
let activeClassIdCosmetics = 0;
const classIdCosmeticQueue = [];

function scheduleClassIdCosmetic(task) {
  return new Promise((resolve, reject) => {
    const execute = () => {
      activeClassIdCosmetics++;
      task()
        .then(resolve)
        .catch(reject)
        .finally(() => {
          activeClassIdCosmetics--;
          if (classIdCosmeticQueue.length > 0) {
            const next = classIdCosmeticQueue.shift();
            next();
          }
        });
    };

    if (activeClassIdCosmetics < MAX_CONCURRENT_CLASS_ID_COSMETICS) {
      execute();
    } else {
      classIdCosmeticQueue.push(execute);
    }
  });
}

// Cosmetic decision cache
const COSMETIC_CACHE = new Map();
const INFLIGHT_COSMETIC = new Map();
const MAX_COSMETIC_CACHE_SIZE = 400;
const COSMETIC_CACHE_TTL_MS = 300000; // 5 minutes

function getCachedCosmetic(key) {
  const item = COSMETIC_CACHE.get(key);
  if (!item) return null;
  if (Date.now() - item.ts < COSMETIC_CACHE_TTL_MS) {
    return item.data;
  }
  COSMETIC_CACHE.delete(key);
  return null;
}

function setCachedCosmetic(key, data) {
  if (COSMETIC_CACHE.size >= MAX_COSMETIC_CACHE_SIZE) {
    const firstKey = COSMETIC_CACHE.keys().next().value;
    if (firstKey) COSMETIC_CACHE.delete(firstKey);
  }
  COSMETIC_CACHE.set(key, {
    data: data,
    ts: Date.now()
  });
}

// 1. Content Script Message Listener: Forward CLICK_INSPECTED, GET_COSMETIC_RESOURCES, GET_HIDDEN_CLASS_ID_SELECTORS
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
    if (sendResponse) sendResponse({ received: true });
    return true;
  }

  if (message.type === "GET_COSMETIC_RESOURCES") {
    const url = message.url || (_sender.tab ? _sender.tab.url : "");
    const hostname = message.hostname || "";
    const cacheKey = [
      currentProfile,
      rulesGeneration,
      "URL_COSMETIC",
      hostname,
      url
    ].join("|");

    const cached = getCachedCosmetic(cacheKey);
    if (cached) {
      if (sendResponse) sendResponse(cached);
      return true;
    }

    if (INFLIGHT_COSMETIC.has(cacheKey)) {
      INFLIGHT_COSMETIC.get(cacheKey).then((data) => {
        if (sendResponse) sendResponse(data);
      });
      return true;
    }

    const reqId = "cosmetic_" + Math.random().toString(36).substring(2, 9);
    const promise = (async () => {
      try {
        const startTs = Date.now();
        const resp = await withTimeout(
          browser.runtime.sendNativeMessage("remmi_engine_extension", {
            type: "GET_COSMETIC_RESOURCES",
            requestId: reqId,
            url: url,
            hostname: hostname,
            classes: message.classes || [],
            ids: message.ids || [],
            exceptions: message.exceptions || []
          }),
          2000,
          "COSMETIC_RESOURCES_TIMEOUT"
        );
        const elapsed = Date.now() - startTs;
        if (resp && resp.ok) {
          setCachedCosmetic(cacheKey, resp);
        } else {
          logToNative(`[NM_SEND_ERROR] requestId=${reqId} errorName=INVALID_NATIVE_RESPONSE errorMessage=${resp?.error || "unknown"}`);
        }
        return resp || { ok: false, hideSelectors: [] };
      } catch (e) {
        let errCategory = e?.message === "COSMETIC_RESOURCES_TIMEOUT" ? "NATIVE_TIMEOUT" : "NATIVE_MESSAGE_FAILURE";
        logToNative(`[NM_SEND_ERROR] requestId=${reqId} errorName=${errCategory} errorMessage=${e?.message || String(e)}`);
        return { ok: false, error: e?.message || "error", hideSelectors: [] };
      } finally {
        INFLIGHT_COSMETIC.delete(cacheKey);
      }
    })();

    INFLIGHT_COSMETIC.set(cacheKey, promise);
    promise.then((res) => {
      if (sendResponse) sendResponse(res);
    });
    return true;
  }

  if (message.type === "GET_HIDDEN_CLASS_ID_SELECTORS") {
    const classes = message.classes || [];
    const ids = message.ids || [];
    const cacheKey = [
      currentProfile,
      rulesGeneration,
      "CLASS_ID_COSMETIC",
      classes.slice().sort().join(","),
      ids.slice().sort().join(",")
    ].join("|");

    const cached = getCachedCosmetic(cacheKey);
    if (cached) {
      if (sendResponse) sendResponse(cached);
      return true;
    }

    if (INFLIGHT_COSMETIC.has(cacheKey)) {
      INFLIGHT_COSMETIC.get(cacheKey).then((data) => {
        if (sendResponse) sendResponse(data);
      });
      return true;
    }

    const reqId = "classid_" + Math.random().toString(36).substring(2, 9);
    const promise = scheduleClassIdCosmetic(async () => {
      try {
        const startTs = Date.now();
        const resp = await withTimeout(
          browser.runtime.sendNativeMessage("remmi_engine_extension", {
            type: "GET_HIDDEN_CLASS_ID_SELECTORS",
            requestId: reqId,
            classes: classes,
            ids: ids,
            exceptions: message.exceptions || []
          }),
          2000,
          "HIDDEN_CLASS_ID_TIMEOUT"
        );
        const elapsed = Date.now() - startTs;
        if (resp && resp.ok) {
          setCachedCosmetic(cacheKey, resp);
        } else {
          logToNative(`[NM_SEND_ERROR] requestId=${reqId} errorName=INVALID_NATIVE_RESPONSE errorMessage=${resp?.error || "unknown"}`);
        }
        return resp || { ok: false, hideSelectors: [] };
      } catch (e) {
        let errCategory = e?.message === "HIDDEN_CLASS_ID_TIMEOUT" ? "NATIVE_TIMEOUT" : "NATIVE_MESSAGE_FAILURE";
        logToNative(`[NM_SEND_ERROR] requestId=${reqId} errorName=${errCategory} errorMessage=${e?.message || String(e)}`);
        return { ok: false, error: e?.message || "error", hideSelectors: [] };
      } finally {
        INFLIGHT_COSMETIC.delete(cacheKey);
      }
    });

    INFLIGHT_COSMETIC.set(cacheKey, promise);
    promise.then((res) => {
      if (sendResponse) sendResponse(res);
    });
    return true;
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

// Blockable resource types policy
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
  "media",
  "beacon",
  "ping",
  "csp_report",
  "websocket",
  "other"
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
    case "beacon":
    case "ping":
    case "csp_report":
    case "websocket":
    case "other":
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

function calculateIsThirdParty(targetUrl, sourceUrl) {
  try {
    if (!sourceUrl || !targetUrl) return false;
    const target = new URL(targetUrl);
    const source = new URL(sourceUrl);
    const targetHost = target.hostname.toLowerCase();
    const sourceHost = source.hostname.toLowerCase();
    if (!targetHost || !sourceHost) return false;
    if (targetHost === sourceHost) return false;
    const tParts = targetHost.split('.');
    const sParts = sourceHost.split('.');
    if (tParts.length >= 2 && sParts.length >= 2) {
      const tBase = tParts.slice(-2).join('.');
      const sBase = sParts.slice(-2).join('.');
      if (tBase === sBase) return false;
    }
    return true;
  } catch (_e) {
    return false;
  }
}

async function getNativeDecision(details, cacheKey, requestId) {
  const reqId = requestId || ("req_" + Math.random().toString(36).substring(2, 9));
  const isTrace = isTraceCandidateUrl(details.url);

  if (INFLIGHT_DECISIONS.has(cacheKey)) {
    BLOCKER_METRICS.inflightHits++;
    if (isTrace) {
      logToNative(`[WEBEXT_INFLIGHT_REUSE] requestId=${reqId} key=${cacheKey}`);
    }
    return INFLIGHT_DECISIONS.get(cacheKey);
  }

  if (isTrace) {
    logToNative(`[WEBEXT_INFLIGHT_OWNER] requestId=${reqId} key=${cacheKey}`);
  }

  const promise = (async () => {
    BLOCKER_METRICS.nativeCalls++;
    const sourceUrl = details.documentUrl || details.originUrl || "";
    const is3p = calculateIsThirdParty(details.url, sourceUrl);
    const startTs = Date.now();

    if (isTrace) {
      logToNative(`[NM_SEND_START] requestId=${reqId} messageType=SHOULD_BLOCK url=${details.url} ts=${startTs}`);
    }

    let response;
    try {
      response = await withTimeout(
        browser.runtime.sendNativeMessage(
          "remmi_engine_extension",
          {
            type: "SHOULD_BLOCK",
            requestId: reqId,
            url: details.url,
            sourceUrl: sourceUrl,
            initiator: details.originUrl || "",
            method: details.method || "GET",
            resourceType: details.type || "other",
            aggressive: currentProfile === "GHOST" || currentProfile === "TOR",
            thirdParty: is3p
          }
        ),
        NATIVE_DECISION_TIMEOUT_MS,
        "NATIVE_DECISION_TIMEOUT"
      );
    } catch (e) {
      let errName = "NATIVE_MESSAGE_FAILURE";
      if (e?.message === "NATIVE_DECISION_TIMEOUT" || e?.name === "TimeoutError") {
        errName = "NATIVE_MESSAGE_QUEUE_TIMEOUT";
      }
      logToNative(`[NM_SEND_ERROR] requestId=${reqId} errorName=${errName} errorMessage=${e?.message || String(e)}`);
      throw e;
    }

    const elapsedMs = Date.now() - startTs;

    if (!response || response.ok !== true) {
      const errName = response ? "NATIVE_DECISION_FAILURE" : "INVALID_NATIVE_RESPONSE";
      const errMsg = response?.error || "null_or_invalid_response";
      logToNative(`[NM_SEND_ERROR] requestId=${reqId} errorName=${errName} errorMessage=${errMsg}`);
      throw new Error(`native_decision_invalid:${errMsg}`);
    }

    if (isTrace) {
      logToNative(`[NM_SEND_SUCCESS] requestId=${reqId} responseOk=true elapsedMs=${elapsedMs}`);
    }

    if (response.generation && response.generation > rulesGeneration) {
      rulesGeneration = response.generation;
    }

    return {
      cancel: response.cancel === true,
      redirect: response.redirectUrl || response.redirect || null,
      rewrittenUrl: response.rewrittenUrl || null,
      csp: response.csp || null,
      ruleId: response.ruleId || null,
      ruleSource: response.ruleSource || null,
      generation: response.generation || rulesGeneration
    };
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
        if (cached === true) BLOCKER_METRICS.blocked++;
        if ((BLOCKER_METRICS.requests) % 50 === 0) {
          logToNative(
            `[WEBEXT_METRICS] requests=${BLOCKER_METRICS.requests} cacheHits=${BLOCKER_METRICS.cacheHits} inflightHits=${BLOCKER_METRICS.inflightHits} nativeCalls=${BLOCKER_METRICS.nativeCalls} errors=${BLOCKER_METRICS.nativeErrors} blocked=${BLOCKER_METRICS.blocked}`
          );
        }
        return { cancel: cached === true };
      }
    }

    const traceId = details.requestId || Math.random().toString(36).substring(7);
    const isTraceCandidate = isTraceCandidateUrl(url);
    if (isTraceCandidate) {
      logToNative(`[AB_REQUEST_IN] requestId=${traceId} url=${url} type=${details.type} method=${details.method}`);
    }

    try {
      // Execute single getNativeDecision call per request (NO duplicate decision calls)
      const response = await getNativeDecision(details, cacheKey, traceId);
      const shouldCancel = response.cancel === true;

      if (isTraceCandidate) {
        logToNative(`[AB_ENFORCEMENT_RESULT] requestId=${traceId} cancel=${shouldCancel}`);
      }

      if (isIdempotent && !response.redirect && !response.rewrittenUrl && !response.csp) {
        setCachedDecision(cacheKey, shouldCancel, resType);
      }
      
      let finalResult = { cancel: shouldCancel };
      
      if (shouldCancel) {
        BLOCKER_METRICS.blocked++;
      } else if (response.redirect) {
        finalResult = { redirectUrl: response.redirect };
      } else if (response.rewrittenUrl) {
        finalResult = { redirectUrl: response.rewrittenUrl };
      }
      
      if (response.csp) {
        logToNative(`[WEBEXT_PARTIAL] unsupported action: csp`);
      }

      if ((BLOCKER_METRICS.requests) % 50 === 0) {
        logToNative(
          `[WEBEXT_METRICS] requests=${BLOCKER_METRICS.requests} cacheHits=${BLOCKER_METRICS.cacheHits} inflightHits=${BLOCKER_METRICS.inflightHits} nativeCalls=${BLOCKER_METRICS.nativeCalls} errors=${BLOCKER_METRICS.nativeErrors} blocked=${BLOCKER_METRICS.blocked}`
        );
      }
      return finalResult;
    } catch (e) {
      BLOCKER_METRICS.nativeErrors++;
      if ((BLOCKER_METRICS.requests) % 50 === 0) {
        logToNative(
          `[WEBEXT_METRICS] requests=${BLOCKER_METRICS.requests} cacheHits=${BLOCKER_METRICS.cacheHits} inflightHits=${BLOCKER_METRICS.inflightHits} nativeCalls=${BLOCKER_METRICS.nativeCalls} errors=${BLOCKER_METRICS.nativeErrors} blocked=${BLOCKER_METRICS.blocked}`
        );
      }
      
      let errorCategory = "NATIVE_MESSAGE_FAILURE";
      if (e?.message === "NATIVE_DECISION_TIMEOUT" || e?.name === "TimeoutError") {
        errorCategory = "NATIVE_MESSAGE_QUEUE_TIMEOUT";
      } else if (e?.message?.startsWith("native_decision_invalid")) {
        errorCategory = "INVALID_NATIVE_RESPONSE";
      } else if (e?.message?.startsWith("native_decision_failure")) {
        errorCategory = "NATIVE_DECISION_FAILURE";
      }

      logToNative(
        `[WEBEXT_NATIVE_ERROR] type=${resType} name=${errorCategory} message=${e?.message || String(e)}`
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

browser.webRequest.onCompleted.addListener(
  (details) => {
    if (details.url.includes("google-analytics") || details.url.includes("adblock-tester") || details.url.includes("googletagmanager")) {
      logToNative(`[AB_REQUEST_COMPLETION] requestId=${details.requestId} url=${details.url} completed=true status=${details.statusCode}`);
    }
  },
  { urls: ["<all_urls>"] }
);

browser.webRequest.onErrorOccurred.addListener(
  (details) => {
    if (details.url.includes("google-analytics") || details.url.includes("adblock-tester") || details.url.includes("googletagmanager")) {
      logToNative(`[AB_REQUEST_COMPLETION] requestId=${details.requestId} url=${details.url} completed=false error=${details.error}`);
    }
  },
  { urls: ["<all_urls>"] }
);
