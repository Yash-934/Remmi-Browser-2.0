import re

with open('app/src/main/assets/extensions/remmi_engine_extension/background.js', 'r') as f:
    content = f.read()

# Update onBeforeRequest decision handling
content = re.sub(
    r'      const shouldCancel = await getNativeDecision\(details, cacheKey\);.*?return \{ cancel: false \};',
    """      const response = await getNativeDecision(details, cacheKey);
      const shouldCancel = response.cancel === true;
      if (isIdempotent && !response.redirect && !response.rewrittenUrl && !response.csp) {
        setCachedDecision(cacheKey, shouldCancel, resType);
      }
      
      let finalResult = { cancel: shouldCancel };
      
      if (shouldCancel) {
        BLOCKER_METRICS.blocked++;
        // NOTE: We no longer post "BLOCKED" URL messages from the hot path
        // to avoid I/O bottlenecks.
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
      return finalResult;""",
    content,
    flags=re.DOTALL
)

with open('app/src/main/assets/extensions/remmi_engine_extension/background.js', 'w') as f:
    f.write(content)
