import re

with open('app/src/main/assets/extensions/remmi_engine_extension/background.js', 'r') as f:
    content = f.read()

old_third = """    // Calculate thirdParty using origin matching if available
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
      ),"""

new_third = """    // We pass the raw URLs to the native Rust engine, which uses a 
    // complete Public Suffix List (PSL) to correctly identify eTLD+1 
    // matching and third-party status.
    const sourceUrl = details.documentUrl || details.originUrl || "";
    
    const response = await withTimeout(
      browser.runtime.sendNativeMessage(
        "remmi_engine_extension",
        {
          type: "SHOULD_BLOCK",
          url: details.url,
          sourceUrl: sourceUrl,
          initiator: details.originUrl || "",
          method: details.method || "GET",
          resourceType: details.type || "other",
          aggressive: currentProfile === "GHOST" || currentProfile === "TOR",
          thirdParty: true // Placeholder, Rust engine does the real eTLD+1 calculation
        }
      ),"""

content = content.replace(old_third, new_third)

with open('app/src/main/assets/extensions/remmi_engine_extension/background.js', 'w') as f:
    f.write(content)

