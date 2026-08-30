import re

with open("rust/src/lib.rs", "r") as f:
    content = f.read()

old_logic = """        let mut block = false;
        let mut final_important = false;

        if let Some(ref default_eng) = *default_guard {
            let result = default_eng.check_network_request(&req);
            if result.matched {
                if result.important {
                    final_important = true;
                }
                block = result.exception.is_none();
            }
        }

        if let Some(ref additional) = *additional_guard {
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

new_logic = """        let mut block = false;
        let mut final_important = false;

        if let Some(ref default_eng) = *default_guard {
            let result = default_eng.check_network_request(&req);
            println!("Default eng: matched={}, exception={}, important={}", result.matched, result.exception.is_some(), result.important);
            if result.matched {
                if result.important {
                    final_important = true;
                }
                block = result.exception.is_none();
            }
        }

        if let Some(ref additional) = *additional_guard {
            let result = additional.check_network_request(&req);
            println!("Additional eng: matched={}, exception={}, important={}", result.matched, result.exception.is_some(), result.important);
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
# Wait, let's just make it simple.
# Wait, if an exception rule matches in the additional engine, does `result.matched` become true?
# If the additional engine ONLY has an exception rule `@@||.../safe^`, and no block rule matches, adblock-rust will NOT return matched=true!
# Wait, because `check_network_request` returns `matched=true` ONLY if a BLOCK rule matched. 
# "If there's an exception, but no block rule matches, does it return matched=false?"
# YES. In adblock-rust, check_network_request only checks exceptions if a block rule was found! Wait.
# Let's check adblock-rust documentation or source.

