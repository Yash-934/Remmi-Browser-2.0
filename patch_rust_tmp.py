import re
with open("rust/src/lib.rs", "r") as f:
    content = f.read()

debug_code = """        let mut block = false;
        let mut final_important = false;
        
        let mut debug_info = String::new();
        debug_info.push_str(&format!("URL: {}\\n", url_str));

        if let Some(ref default_eng) = *default_guard {
            let result = default_eng.check_network_request(&req);
            debug_info.push_str(&format!("Default: matched={}, exception={}, important={}\\n", result.matched, result.exception.is_some(), result.important));
            if result.matched {
                if result.important {
                    final_important = true;
                }
                block = result.exception.is_none();
            }
        }

        if let Some(ref additional) = *additional_guard {
            let result = additional.check_network_request(&req);
            debug_info.push_str(&format!("Additional: matched={}, exception={}, important={}\\n", result.matched, result.exception.is_some(), result.important));
            if result.matched {
                if !final_important {
                    if result.exception.is_some() {
                        block = false;
                    } else {
                        block = true;
                    }
                }
            }
        }
        
        let _ = std::fs::write(format!("/tmp/remmi_debug_{}.txt", url_str.replace("/", "_")), debug_info);
"""

content = re.sub(r"        let mut block = false;.*?        if let Some\(ref additional\) = \*additional_guard \{.*?            \}\n        \}", debug_code, content, flags=re.DOTALL)

with open("rust/src/lib.rs", "w") as f:
    f.write(content)
