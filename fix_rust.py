import re

with open('rust/src/lib.rs', 'r') as f:
    text = f.read()

# Replace all read() attempts for default_engine/additional_engine to use engines.read() correctly.
# In adblock check:
text = text.replace("""        let default_guard = match GLOBAL_STATE.engines.read() {
            Ok(g) => g,
            Err(_) => {
                return out;
            }
        };""", """        let engines_guard = match GLOBAL_STATE.engines.read() {
            Ok(g) => g,
            Err(_) => return out,
        };
        let default_guard = &engines_guard.default_engine;""")

text = text.replace("""        let default_guard = match GLOBAL_STATE.engines.read() {
            Ok(g) => g,
            Err(_) => {
                let resp = CosmeticResponse {
                    ok: false,
                    generation: gen,
                    hide_selectors: Vec::new(),
                    injected_script: String::new(),
                };
                return match env.new_string(serde_json::to_string(&resp).unwrap_or_default()) {
                    Ok(s) => s.into_raw(),
                    Err(_) => std::ptr::null_mut(),
                };
            }
        };""", """        let engines_guard = match GLOBAL_STATE.engines.read() {
            Ok(g) => g,
            Err(_) => {
                let resp = CosmeticResponse {
                    ok: false,
                    generation: gen,
                    hide_selectors: Vec::new(),
                    injected_script: String::new(),
                };
                return match env.new_string(serde_json::to_string(&resp).unwrap_or_default()) {
                    Ok(s) => s.into_raw(),
                    Err(_) => std::ptr::null_mut(),
                };
            }
        };
        let default_guard = &engines_guard.default_engine;""")

with open('rust/src/lib.rs', 'w') as f:
    f.write(text)
