import re

with open('rust/src/lib.rs', 'r') as f:
    content = f.read()

# Change return type in Rust
old_rust = """pub extern "system" fn Java_com_remmi_adblock_AdblockBridge_nativeCompileRules(
    mut env: JNIEnv,
    _class: JClass,
    default_rules_text: JString,
    additional_rules_text: JString,
) -> jint {"""
new_rust = """pub extern "system" fn Java_com_remmi_adblock_AdblockBridge_nativeCompileRules(
    mut env: JNIEnv,
    _class: JClass,
    default_rules_text: JString,
    additional_rules_text: JString,
) -> jstring {"""
content = content.replace(old_rust, new_rust)

old_rust2 = """        if default_valid_count == 0 && additional_valid_count == 0 {
            return 0;
        }"""
new_rust2 = """        if default_valid_count == 0 && additional_valid_count == 0 {
            let metrics = serde_json::json!({
                "inputLines": default_lines.len() + additional_lines.len(),
                "parsedCandidates": 0,
                "engineGeneration": 0,
                "activeEnginePresence": false
            });
            let out_json = serde_json::to_string(&metrics).unwrap_or_default();
            return match env.new_string(&out_json) {
                Ok(s) => s.into_raw(),
                Err(_) => std::ptr::null_mut(),
            };
        }"""
content = content.replace(old_rust2, new_rust2)

old_rust3 = """        // We'll log the metrics in Kotlin via JNI or just keep it simple.
        total_count as jint
    }));

    match result {
        Ok(count) => count,
        Err(_) => 0,
    }
}"""
new_rust3 = """        let out_json = serde_json::to_string(&metrics).unwrap_or_default();
        match env.new_string(&out_json) {
            Ok(s) => s.into_raw(),
            Err(_) => std::ptr::null_mut(),
        }
    }));

    match result {
        Ok(val) => val,
        Err(_) => std::ptr::null_mut(),
    }
}"""
content = content.replace(old_rust3, new_rust3)

with open('rust/src/lib.rs', 'w') as f:
    f.write(content)

with open('app/src/main/java/com/remmi/adblock/AdblockBridge.kt', 'r') as f:
    kt_content = f.read()

kt_content = kt_content.replace('private external fun nativeCompileRules(defaultRules: String, additionalRules: String): Int', 'private external fun nativeCompileRules(defaultRules: String, additionalRules: String): String')

# Kotlin side compileRules calls nativeCompileRules and gets a string
old_kt1 = """        com.remmi.browser.util.CrashHandlerHelper.recordNativeOp(op = "[ADBLOCK_DEFAULT_RULES_START]")
        nativeCompileRules(rulesText, "")
        com.remmi.browser.util.CrashHandlerHelper.recordNativeOp(op = "[ADBLOCK_DEFAULT_RULES_OK]")"""
new_kt1 = """        com.remmi.browser.util.CrashHandlerHelper.recordNativeOp(op = "[ADBLOCK_DEFAULT_RULES_START]")
        val json = nativeCompileRules(rulesText, "")
        Log.d(TAG, "[ADBLOCK_METRICS] init_metrics: $json")
        com.remmi.browser.util.CrashHandlerHelper.recordNativeOp(op = "[ADBLOCK_DEFAULT_RULES_OK]")"""
kt_content = kt_content.replace(old_kt1, new_kt1)

old_kt2 = """        com.remmi.browser.util.CrashHandlerHelper.recordNativeOp(op = "[ADBLOCK_COMPILE_RULES_START]")
        compiledCount = nativeCompileRules(combinedDefaultRulesText, additionalRulesText)
        com.remmi.browser.util.CrashHandlerHelper.recordNativeOp(op = "[ADBLOCK_COMPILE_RULES_OK]")"""
new_kt2 = """        com.remmi.browser.util.CrashHandlerHelper.recordNativeOp(op = "[ADBLOCK_COMPILE_RULES_START]")
        val metricsJson = nativeCompileRules(combinedDefaultRulesText, additionalRulesText)
        val metricsObj = org.json.JSONObject(metricsJson)
        compiledCount = metricsObj.optInt("parsedCandidates", 0)
        Log.i(TAG, "[ADBLOCK_METRICS] compile_metrics: $metricsJson")
        com.remmi.browser.util.CrashHandlerHelper.recordNativeOp(op = "[ADBLOCK_COMPILE_RULES_OK]")"""
kt_content = kt_content.replace(old_kt2, new_kt2)

with open('app/src/main/java/com/remmi/adblock/AdblockBridge.kt', 'w') as f:
    f.write(kt_content)

