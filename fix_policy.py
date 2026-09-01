import re

with open('app/src/main/java/com/remmi/browser/security/SiteSecurityPolicyManager.kt', 'r') as f:
    content = f.read()

content = content.replace(
    'val cookiePolicy: String = "ISOLATE", // ISOLATE, BLOCK, ALLOW',
    'val cookiePolicy: String = "ISOLATE", // ISOLATE, BLOCK, ALLOW\n  val shieldsDown: Boolean = false,'
)

content = content.replace(
    'val cookie = json.optString("cookie", "ISOLATE")',
    'val cookie = json.optString("cookie", "ISOLATE")\n          val shieldsDown = json.optBoolean("shieldsDown", false)'
)

content = content.replace(
    'cookiePolicy = cookie,',
    'cookiePolicy = cookie,\n            shieldsDown = shieldsDown,'
)

content = content.replace(
    'put("cookie", settings.cookiePolicy)',
    'put("cookie", settings.cookiePolicy)\n      put("shieldsDown", settings.shieldsDown)'
)

with open('app/src/main/java/com/remmi/browser/security/SiteSecurityPolicyManager.kt', 'w') as f:
    f.write(content)

with open('app/src/main/java/com/remmi/browser/engine/GeckoEngineManager.kt', 'r') as f:
    g_content = f.read()

g_content = g_content.replace(
    'policy.cookiePolicy == "ALLOW"',
    'policy.shieldsDown'
)

with open('app/src/main/java/com/remmi/browser/engine/GeckoEngineManager.kt', 'w') as f:
    f.write(g_content)
