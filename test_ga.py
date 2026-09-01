import re

with open('app/src/main/assets/extensions/remmi_engine_extension/background.js', 'r') as f:
    content = f.read()

trace_code = """
    // --- TRACE ---
    if (url.includes("google-analytics.com") || url.includes("adblock-tester.com") || url.includes("googletagmanager")) {
      const traceId = details.requestId || Math.random().toString(36).substring(7);
      logToNative(`[AB_REQUEST_IN] requestId=${traceId} url=${url} type=${details.type} method=${details.method}`);
      
      const traceResponse = await getNativeDecision(details, cacheKey);
      logToNative(`[AB_ENFORCEMENT_RESULT] requestId=${traceId} cancel=${traceResponse.cancel}`);
      
      // We will trace completed/error in another listener
    }
    // --- END TRACE ---
"""

content = content.replace('const response = await getNativeDecision(details, cacheKey);', trace_code + '\n    const response = await getNativeDecision(details, cacheKey);')

with open('app/src/main/assets/extensions/remmi_engine_extension/background.js', 'w') as f:
    f.write(content)
