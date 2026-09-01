import re

with open('rust/src/lib.rs', 'r') as f:
    text = f.read()

text = text.replace("let source_url = ctx.source_url.unwrap_or_default();", "let source_url = ctx.source_url.clone().unwrap_or_default();")

with open('rust/src/lib.rs', 'w') as f:
    f.write(text)
