import re

with open('rust/src/lib.rs', 'r') as f:
    content = f.read()

old_compile = """        // Swap default
        if let Ok(mut guard) = GLOBAL_STATE.default_engine.write() {
            *guard = Some(new_default_engine);
        }
        
        // Swap additional
        if let Ok(mut guard) = GLOBAL_STATE.additional_engine.write() {
            *guard = new_additional_engine;
        }

        let total_count = (default_valid_count + additional_valid_count) as u64;
        GLOBAL_STATE.filter_count.store(total_count, Ordering::SeqCst);
        let new_gen = GLOBAL_STATE.generation.fetch_add(1, Ordering::SeqCst) + 1;
        println!("[ADBLOCK_ENGINE_SWAP] newGeneration={} rules={}", new_gen, total_count);
        total_count as jint"""

new_compile = """        let total_count = (default_valid_count + additional_valid_count) as u64;
        let mut new_gen = 0;
        
        if let Ok(mut guard) = GLOBAL_STATE.engines.write() {
            guard.default_engine = Some(new_default_engine);
            guard.additional_engine = new_additional_engine;
            guard.generation += 1;
            new_gen = guard.generation;
        }

        GLOBAL_STATE.filter_count.store(total_count, Ordering::SeqCst);
        println!("[ADBLOCK_ENGINE_SWAP] newGeneration={} rules={}", new_gen, total_count);
        
        let metrics = serde_json::json!({
            "inputLines": default_lines.len() + additional_lines.len(),
            "parsedCandidates": total_count,
            "engineGeneration": new_gen,
            "activeEnginePresence": true
        });
        
        // Wait, we can't return JSON from compileRules if it returns jint.
        // We will just return total_count as jint for now, or change the return type.
        // Since Kotlin expects Int, we will leave it as returning jint,
        // and Kotlin will just return that as compiledCount.
        // We'll log the metrics in Kotlin via JNI or just keep it simple.
        total_count as jint"""

content = content.replace(old_compile, new_compile)

# Replace generation access in nativeGetCosmeticResources
old_gen = """let gen = GLOBAL_STATE.generation.load(Ordering::Relaxed);"""
new_gen = """let gen = match GLOBAL_STATE.engines.read() {
            Ok(g) => g.generation,
            Err(_) => 0,
        };"""
content = content.replace(old_gen, new_gen)

# Replace engine access in nativeGetCosmeticResources
old_cosmetic_access = """        let default_guard = match GLOBAL_STATE.default_engine.read() {
            Ok(guard) => guard,
            Err(_) => {
                let resp = CosmeticResponse {
                    ok: false,
                    generation: gen,
                    hide_selectors: vec![],
                    force_hide_selectors: vec![],
                    procedural: vec![],
                    procedural_count: 0,
                    generics: false,
                    error: Some("engine_lock_failed".to_string()),
                };
                let json_str = serde_json::to_string(&resp).unwrap_or_default();
                return match env.new_string(&json_str) {
                    Ok(s) => s.into_raw(),
                    Err(_) => std::ptr::null_mut(),
                };
            }
        };

        let additional_guard = match GLOBAL_STATE.additional_engine.read() {
            Ok(guard) => guard,
            Err(_) => {
                let resp = CosmeticResponse {
                    ok: false,
                    generation: gen,
                    hide_selectors: vec![],
                    force_hide_selectors: vec![],
                    procedural: vec![],
                    procedural_count: 0,
                    generics: false,
                    error: Some("engine_lock_failed".to_string()),
                };
                let json_str = serde_json::to_string(&resp).unwrap_or_default();
                return match env.new_string(&json_str) {
                    Ok(s) => s.into_raw(),
                    Err(_) => std::ptr::null_mut(),
                };
            }
        };

        let default_engine = default_guard.as_ref();
        let additional_engine = additional_guard.as_ref();"""

new_cosmetic_access = """        let engines_guard = match GLOBAL_STATE.engines.read() {
            Ok(guard) => guard,
            Err(_) => {
                let resp = CosmeticResponse {
                    ok: false,
                    generation: gen,
                    hide_selectors: vec![],
                    force_hide_selectors: vec![],
                    procedural: vec![],
                    procedural_count: 0,
                    generics: false,
                    error: Some("engine_lock_failed".to_string()),
                };
                let json_str = serde_json::to_string(&resp).unwrap_or_default();
                return match env.new_string(&json_str) {
                    Ok(s) => s.into_raw(),
                    Err(_) => std::ptr::null_mut(),
                };
            }
        };

        let default_engine = engines_guard.default_engine.as_ref();
        let additional_engine = engines_guard.additional_engine.as_ref();"""
content = content.replace(old_cosmetic_access, new_cosmetic_access)

with open('rust/src/lib.rs', 'w') as f:
    f.write(content)

