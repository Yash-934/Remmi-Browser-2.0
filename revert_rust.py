import re
with open("rust/src/lib.rs", "r") as f:
    content = f.read()

bad_debug = """        let mut debug_info = String::new();
        debug_info.push_str(&format!("URL: {}\\n", url_str));"""

if bad_debug in content:
    content = re.sub(r"        let mut debug_info = String::new\(\);\n        debug_info\.push_str.*?\n", "", content)
    content = re.sub(r"            debug_info\.push_str.*?\n", "", content)
    content = re.sub(r"        let _ = std::fs::write\(format\!\(\"/tmp/remmi_debug_\{\}\.txt\", url_str\.replace\(\"/\", \"_\"\)\), debug_info\);\n", "", content)
    with open("rust/src/lib.rs", "w") as f:
        f.write(content)
