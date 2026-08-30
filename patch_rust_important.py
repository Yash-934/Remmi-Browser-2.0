import re

with open("rust/src/lib.rs", "r") as f:
    content = f.read()

old_logic = """        if let Some(ref additional) = *additional_guard {
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
        }"""

new_logic = """        if let Some(ref additional) = *additional_guard {
            let result = additional.check_network_request(&req);
            if result.matched {
                if !final_important {
                    if result.exception.is_some() {
                        block = false;
                    } else {
                        block = true;
                    }
                }
            }
        }"""

content = content.replace(old_logic, new_logic)

with open("rust/src/lib.rs", "w") as f:
    f.write(content)
