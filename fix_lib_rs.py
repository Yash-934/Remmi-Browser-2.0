import re

with open('rust/src/lib.rs', 'r') as f:
    content = f.read()

# Fix is_third_party
content = content.replace("req.is_third_party()", "req.is_third_party.unwrap_or(false)")

# Fix default_guard = match GLOBAL_STATE.default_engine.read() {
content = re.sub(
    r'let default_guard = match GLOBAL_STATE\.engines\.read\(\) \{[\s]*Ok\(g\) => g,[\s]*Err\(_\) => return out,[\s]*\};',
    r'let engines_guard = match GLOBAL_STATE.engines.read() { Ok(g) => g, Err(_) => return out, };',
    content
)

# Wait, let's just do it manually with sed since my replacement didn't run anyway.
