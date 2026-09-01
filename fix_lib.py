import re

with open('rust/src/lib.rs', 'r') as f:
    text = f.read()

# Fix is_third_party
text = text.replace("let actual_third_party = req.is_third_party();", "let actual_third_party = req.is_third_party.unwrap_or(false);")

# Fix GLOBAL_STATE engines access
# Replace: let default_guard = match GLOBAL_STATE.default_engine.read() {
text = text.replace("let default_guard = match GLOBAL_STATE.engines.read() {", "let default_guard = match GLOBAL_STATE.engines.read() {")

# Actually, I used sed -i 's/GLOBAL_STATE\.default_engine/GLOBAL_STATE\.engines/g' rust/src/lib.rs
# which changed GLOBAL_STATE.default_engine to GLOBAL_STATE.engines everywhere.
# And same for additional_engine.
# And same for generation.
# So now it's all GLOBAL_STATE.engines everywhere! That's bad.
# Let's restore the file if we can.
