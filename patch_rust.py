import re

with open("rust/src/lib.rs", "r") as f:
    content = f.read()

old_logic = """        // Additional engine overrides (like Brave Unbreak)
        if let Some(ref additional) = *additional_guard {
            let result = additional.check_network_request(&req);
            if result.matched && result.exception.is_some() {
                // If it's an explicit exception, allow it
                GLOBAL_STATE.allowed_count.fetch_add(1, Ordering::Relaxed);
                return JNI_FALSE;
            }
            if result.matched {
                GLOBAL_STATE.blocked_count.fetch_add(1, Ordering::Relaxed);
                return JNI_TRUE;
            }
        }

        // Default engine
        if let Some(ref default_eng) = *default_guard {
            let result = default_eng.check_network_request(&req);
            if result.matched && result.exception.is_some() {
                GLOBAL_STATE.allowed_count.fetch_add(1, Ordering::Relaxed);
                return JNI_FALSE;
            }
            if result.matched {
                GLOBAL_STATE.blocked_count.fetch_add(1, Ordering::Relaxed);
                return JNI_TRUE;
            }
        }"""

new_logic = """        let mut block = false;
        let mut final_important = false;

        if let Some(ref default_eng) = *default_guard {
            let result = default_eng.check_network_request(&req);
            if result.matched {
                block = result.exception.is_none();
                if result.important {
                    final_important = true;
                }
            }
        }

        if let Some(ref additional) = *additional_guard {
            let result = additional.check_network_request(&req);
            if result.matched {
                // Additional engine matches
                if result.exception.is_some() {
                    // It's an exception in additional engine.
                    // But if default had an important block, additional weak exception cannot override it.
                    if !final_important {
                        block = false;
                    }
                } else {
                    // It's a block in additional engine. Overrides default exception/allow.
                    block = true;
                }
            }
        }

        if block {
            GLOBAL_STATE.blocked_count.fetch_add(1, Ordering::Relaxed);
            return JNI_TRUE;
        }"""

content = content.replace(old_logic, new_logic)

with open("rust/src/lib.rs", "w") as f:
    f.write(content)
