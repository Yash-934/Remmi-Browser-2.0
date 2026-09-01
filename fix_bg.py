import re

with open('app/src/main/assets/extensions/remmi_engine_extension/background.js', 'r') as f:
    content = f.read()

# Add to BLOCKABLE_TYPES
content = content.replace('"media"', '"media",\n  "beacon",\n  "ping",\n  "csp_report",\n  "websocket",\n  "other"')

# Update getNativeDecision to pass full context
old_get_native = """    const response = await withTimeout(
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
    );"""

new_get_native = """    // Calculate thirdParty using origin matching if available
    let isThirdParty = null;
    try {
      if (details.url && (details.originUrl || details.documentUrl)) {
        let u1 = new URL(details.url);
        let u2 = new URL(details.originUrl || details.documentUrl);
        // Simple host comparison - native engine should do full eTLD+1 matching
        isThirdParty = (u1.hostname !== u2.hostname);
      }
    } catch(e) {}

    const response = await withTimeout(
      browser.runtime.sendNativeMessage(
        "remmi_engine_extension",
        {
          type: "SHOULD_BLOCK",
          url: details.url,
          sourceUrl: details.documentUrl || "",
          initiator: details.originUrl || "",
          method: details.method || "GET",
          resourceType: details.type || "other",
          aggressive: currentProfile === "GHOST" || currentProfile === "TOR",
          thirdParty: isThirdParty !== null ? isThirdParty : true
        }
      ),
      NATIVE_DECISION_TIMEOUT_MS
    );"""

content = content.replace(old_get_native, new_get_native)

# Update return values from getNativeDecision
old_get_native_return = """    if (response.generation && response.generation > rulesGeneration) {
      rulesGeneration = response.generation;
    }
    return response.cancel === true;"""

new_get_native_return = """    if (response.generation && response.generation > rulesGeneration) {
      rulesGeneration = response.generation;
    }
    return response;"""
content = content.replace(old_get_native_return, new_get_native_return)

# Update onBeforeRequest fast path and handling
old_fast_path = """    if (isIdempotent) {
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
    }"""

new_fast_path = """    if (isIdempotent) {
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
    }"""
content = content.replace(old_fast_path, new_fast_path)

# Update onBeforeRequest decision handling
old_handling = """      const shouldCancel = await getNativeDecision(details, cacheKey);
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
      return { cancel: false };"""

new_handling = """      const response = await getNativeDecision(details, cacheKey);
      const shouldCancel = response.cancel === true;
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
        // CSP injection requires onHeadersReceived modifying response headers.
        // Returning it in onBeforeRequest does nothing in standard WebExtensions.
        // Marking it PARTIAL.
        logToNative(`[WEBEXT_PARTIAL] unsupported action: csp`);
      }

      if ((BLOCKER_METRICS.requests) % 50 === 0) {
        logToNative(
          `[WEBEXT_METRICS] requests=${BLOCKER_METRICS.requests} cacheHits=${BLOCKER_METRICS.cacheHits} inflightHits=${BLOCKER_METRICS.inflightHits} nativeCalls=${BLOCKER_METRICS.nativeCalls} errors=${BLOCKER_METRICS.nativeErrors} blocked=${BLOCKER_METRICS.blocked}`
        );
      }
      return finalResult;"""
content = content.replace(old_handling, new_handling)

with open('app/src/main/assets/extensions/remmi_engine_extension/background.js', 'w') as f:
    f.write(content)

