import re

with open('app/build.gradle.kts', 'r') as f:
    content = f.read()

# I will find the whole signingConfigs block and replace it.
# It starts with "  signingConfigs {" and ends right before "  buildTypes {"
new_block = """  signingConfigs {
    create("release") {
      storeFile = file("${rootDir}/debug.keystore")
      storePassword = "android"
      keyAlias = "androiddebugkey"
      keyPassword = "android"
    }
    create("debugConfig") {
      storeFile = file("${rootDir}/debug.keystore")
      storePassword = "android"
      keyAlias = "androiddebugkey"
      keyPassword = "android"
    }
  }
"""

content = re.sub(r'  signingConfigs \{.*?\n  buildTypes \{', new_block + '  buildTypes {', content, flags=re.DOTALL)

with open('app/build.gradle.kts', 'w') as f:
    f.write(content)
