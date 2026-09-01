import re

with open('rust/src/lib.rs', 'r') as f:
    content = f.read()

old_code = """pub extern "system" fn Java_com_remmi_adblock_AdblockBridge_nativeSelfTest(
    _env: JNIEnv,
    _class: JClass,
) -> jboolean {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let test_rule = "||remmi-self-test.invalid^";
        let mut filter_set = FilterSet::new(true);
        filter_set.add_filters(&[test_rule], ParseOptions::default());
        let engine = Engine::from_filter_set(filter_set, true);

        let request = match Request::new(
            "https://remmi-self-test.invalid/banner.js",
            "https://example.com/",
            "script",
        ) {
            Ok(r) => r,
            Err(_) => return JNI_FALSE,
        };

        if engine.check_network_request(&request).matched {
            JNI_TRUE
        } else {
            JNI_FALSE
        }
    }));
    match result {
        Ok(val) => val,
        Err(_) => JNI_FALSE,
    }
}"""

new_code = """pub extern "system" fn Java_com_remmi_adblock_AdblockBridge_nativeSelfTest(
    _env: JNIEnv,
    _class: JClass,
) -> jboolean {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let handle = std::thread::Builder::new()
            .stack_size(2 * 1024 * 1024)
            .spawn(|| {
                let test_rule = "||remmi-self-test.invalid^";
                let mut filter_set = FilterSet::new(true);
                filter_set.add_filters(&[test_rule], ParseOptions::default());
                let engine = Engine::from_filter_set(filter_set, true);

                let request = match Request::new(
                    "https://remmi-self-test.invalid/banner.js",
                    "https://example.com/",
                    "script",
                ) {
                    Ok(r) => r,
                    Err(_) => return JNI_FALSE,
                };

                if engine.check_network_request(&request).matched {
                    JNI_TRUE
                } else {
                    JNI_FALSE
                }
            });
        
        match handle {
            Ok(h) => h.join().unwrap_or(JNI_FALSE),
            Err(_) => JNI_FALSE,
        }
    }));
    match result {
        Ok(val) => val,
        Err(_) => JNI_FALSE,
    }
}"""

content = content.replace(old_code, new_code)

with open('rust/src/lib.rs', 'w') as f:
    f.write(content)
