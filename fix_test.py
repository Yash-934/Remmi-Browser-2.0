import re

with open('app/src/test/java/com/remmi/browser/security/NetworkFilterCoverageTest.kt', 'r') as f:
    content = f.read()

content = content.replace('bridge = AdblockBridge.getInstance(context)', 'bridge = AdblockBridge.getInstance()')
content = content.replace('$image', '\\$image')
content = content.replace('$script', '\\$script')
content = content.replace('$method', '\\$method')
content = content.replace('$third', '\\$third')
content = content.replace('$important', '\\$important')

with open('app/src/test/java/com/remmi/browser/security/NetworkFilterCoverageTest.kt', 'w') as f:
    f.write(content)

