import re

with open('app/src/main/assets/extensions/remmi_engine_extension/background.js', 'r') as f:
    content = f.read()

# Let's see what sourceUrl really evaluates to!
# And let's see if we can log it!
