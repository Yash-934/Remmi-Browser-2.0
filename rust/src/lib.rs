use std::collections::HashSet;
use std::sync::RwLock;
use std::sync::atomic::{AtomicU64, Ordering};
use jni::JNIEnv;
use jni::objects::{JClass, JString};
use jni::sys::{jboolean, jint, jlong, jstring, JNI_TRUE, JNI_FALSE};
use lazy_static::lazy_static;
use serde::Serialize;
use adblock::Engine;
use adblock::lists::{FilterSet, ParseOptions};
use adblock::request::Request;

struct AdblockEngineState {
    default_engine: RwLock<Option<Engine>>,
    additional_engine: RwLock<Option<Engine>>,
    filter_count: AtomicU64,
    blocked_count: AtomicU64,
    allowed_count: AtomicU64,
    generation: AtomicU64,
}

lazy_static! {
    static ref GLOBAL_STATE: AdblockEngineState = AdblockEngineState {
        default_engine: RwLock::new(None),
        additional_engine: RwLock::new(None),
        filter_count: AtomicU64::new(0),
        blocked_count: AtomicU64::new(0),
        allowed_count: AtomicU64::new(0),
        generation: AtomicU64::new(0),
    };
}

const DEFAULT_RULES: &[&str] = &[
    "||google-analytics.com^$third-party",
    "||googletagmanager.com^$third-party",
    "||doubleclick.net^$third-party",
    "||facebook.net^$third-party",
    "||scorecardresearch.com^$third-party",
    "||criteo.com^$third-party",
    "||taboola.com^$third-party",
    "||outbrain.com^$third-party",
    "||hotjar.com^$third-party",
    "||adnxs.com^$third-party",
];

#[derive(Serialize)]
struct CosmeticResponse {
    ok: bool,
    generation: u64,
    #[serde(rename = "hideSelectors")]
    hide_selectors: Vec<String>,
    #[serde(rename = "forceHideSelectors")]
    force_hide_selectors: Vec<String>,
    procedural: Vec<String>,
    #[serde(rename = "proceduralCount")]
    procedural_count: usize,
    generics: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    error: Option<String>,
}

#[no_mangle]
pub extern "system" fn Java_com_remmi_adblock_AdblockBridge_nativeInit(
    _env: JNIEnv,
    _class: JClass,
) -> jboolean {
    let mut filter_set = FilterSet::new(true);
    filter_set.add_filters(DEFAULT_RULES, ParseOptions::default());
    let initial_engine = Engine::from_filter_set(filter_set, true);

    match GLOBAL_STATE.default_engine.write() {
        Ok(mut guard) => {
            *guard = Some(initial_engine);
            GLOBAL_STATE.filter_count.store(DEFAULT_RULES.len() as u64, Ordering::SeqCst);
            GLOBAL_STATE.blocked_count.store(0, Ordering::SeqCst);
            GLOBAL_STATE.allowed_count.store(0, Ordering::SeqCst);
            GLOBAL_STATE.generation.store(1, Ordering::SeqCst);
            JNI_TRUE
        }
        Err(_) => JNI_FALSE,
    }
}

#[no_mangle]
pub extern "system" fn Java_com_remmi_adblock_AdblockBridge_nativeMatches(
    mut env: JNIEnv,
    _class: JClass,
    url: JString,
    source_url: JString,
    request_type: JString,
) -> jboolean {
    let url_str: String = match env.get_string(&url) {
        Ok(s) => s.into(),
        Err(_) => return JNI_FALSE,
    };
    let source_str: String = match env.get_string(&source_url) {
        Ok(s) => s.into(),
        Err(_) => String::new(),
    };
    let type_str: String = match env.get_string(&request_type) {
        Ok(s) => s.into(),
        Err(_) => "other".to_string(),
    };

    let default_guard = match GLOBAL_STATE.default_engine.read() {
        Ok(guard) => guard,
        Err(_) => return JNI_FALSE,
    };
    let additional_guard = match GLOBAL_STATE.additional_engine.read() {
        Ok(guard) => guard,
        Err(_) => return JNI_FALSE,
    };

    if let Ok(req) = Request::new(&url_str, &source_str, &type_str) {
        let mut def_matched = false;
        let mut def_exception = false;
        let mut def_important = false;
        
        let mut add_matched = false;
        let mut add_exception = false;
        let mut add_important = false;

        let mut block = false;
        let mut final_important = false;

        if let Some(ref default_eng) = *default_guard {
            let result = default_eng.check_network_request(&req);
            if result.matched {
                def_matched = true;
                def_exception = result.exception.is_some();
                def_important = result.important;
                
                if result.important {
                    final_important = true;
                }
                block = result.exception.is_none();
            }
        }

        if let Some(ref additional) = *additional_guard {
            let result = additional.check_network_request(&req);
            if result.matched {
                add_matched = true;
                add_exception = result.exception.is_some();
                add_important = result.important;
                
                if !final_important {
                    if result.exception.is_some() {
                        block = false;
                    } else {
                        block = true;
                    }
                }
            }
        }
        
        #[cfg(debug_assertions)]
        {
            println!(
                "[AB_DECISION] type={} host={} thirdParty={} defaultMatched={} defaultException={} defaultImportant={} additionalMatched={} additionalException={} additionalImportant={} finalBlocked={}",
                type_str,
                url_str, // We use url_str here as host proxy for debug
                false, // Request doesn't expose third_party easily without host matching, so stubbing
                def_matched, def_exception, def_important,
                add_matched, add_exception, add_important, block
            );
        }

        if block {
            GLOBAL_STATE.blocked_count.fetch_add(1, Ordering::Relaxed);
            return JNI_TRUE;
        }
    }
    
    GLOBAL_STATE.allowed_count.fetch_add(1, Ordering::Relaxed);
    JNI_FALSE
}

#[no_mangle]
pub extern "system" fn Java_com_remmi_adblock_AdblockBridge_nativeCompileRules(
    mut env: JNIEnv,
    _class: JClass,
    default_rules_text: JString,
    additional_rules_text: JString,
) -> jint {
    let default_str: String = match env.get_string(&default_rules_text) {
        Ok(s) => s.into(),
        Err(_) => String::new(),
    };
    let additional_str: String = match env.get_string(&additional_rules_text) {
        Ok(s) => s.into(),
        Err(_) => String::new(),
    };

    let default_lines: Vec<&str> = default_str.lines().collect();
    let additional_lines: Vec<&str> = additional_str.lines().collect();
    
    let default_valid_count = default_lines
        .iter()
        .filter(|line| !line.trim().is_empty() && !line.starts_with('!'))
        .count();
    let additional_valid_count = additional_lines
        .iter()
        .filter(|line| !line.trim().is_empty() && !line.starts_with('!'))
        .count();

    if default_valid_count == 0 && additional_valid_count == 0 {
        return 0;
    }

    let mut default_filter_set = FilterSet::new(true);
    default_filter_set.add_filters(&default_lines, ParseOptions::default());
    let new_default_engine = Engine::from_filter_set(default_filter_set, true);

    let mut additional_filter_set = FilterSet::new(true);
    if additional_valid_count > 0 {
        additional_filter_set.add_filters(&additional_lines, ParseOptions::default());
    }
    let new_additional_engine = if additional_valid_count > 0 {
        Some(Engine::from_filter_set(additional_filter_set, true))
    } else {
        None
    };

    // Swap default
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
    total_count as jint
}

#[no_mangle]
pub extern "system" fn Java_com_remmi_adblock_AdblockBridge_nativeGetCosmeticResources(
    mut env: JNIEnv,
    _class: JClass,
    url: JString,
    classes: JString,
    ids: JString,
    exceptions: JString,
    aggressive: jboolean,
) -> jstring {
    let gen = GLOBAL_STATE.generation.load(Ordering::Relaxed);
    let url_str: String = match env.get_string(&url) {
        Ok(s) => s.into(),
        Err(_) => {
            let resp = CosmeticResponse {
                ok: false,
                generation: gen,
                hide_selectors: vec![],
                force_hide_selectors: vec![],
                procedural: vec![],
                procedural_count: 0,
                generics: false,
                error: Some("invalid_url".to_string()),
            };
            let json_str = serde_json::to_string(&resp).unwrap_or_default();
            return env.new_string(json_str).expect("java string").into_raw();
        }
    };

    let classes_str: String = match env.get_string(&classes) {
        Ok(s) => s.into(),
        Err(_) => String::new(),
    };
    let ids_str: String = match env.get_string(&ids) {
        Ok(s) => s.into(),
        Err(_) => String::new(),
    };
    let exceptions_str: String = match env.get_string(&exceptions) {
        Ok(s) => s.into(),
        Err(_) => String::new(),
    };

    let classes_vec: Vec<String> = if classes_str.is_empty() {
        vec![]
    } else {
        serde_json::from_str(&classes_str).unwrap_or_else(|_| {
            classes_str.split(',').map(|s| s.trim().to_string()).filter(|s| !s.is_empty()).collect()
        })
    };

    let ids_vec: Vec<String> = if ids_str.is_empty() {
        vec![]
    } else {
        serde_json::from_str(&ids_str).unwrap_or_else(|_| {
            ids_str.split(',').map(|s| s.trim().to_string()).filter(|s| !s.is_empty()).collect()
        })
    };

    let exceptions_set: HashSet<String> = if exceptions_str.is_empty() {
        HashSet::new()
    } else {
        serde_json::from_str(&exceptions_str).unwrap_or_else(|_| {
            exceptions_str.split(',').map(|s| s.trim().to_string()).filter(|s| !s.is_empty()).collect()
        })
    };

    let default_guard = match GLOBAL_STATE.default_engine.read() {
        Ok(g) => g,
        Err(_) => {
            let resp = CosmeticResponse {
                ok: false,
                generation: gen,
                hide_selectors: vec![],
                force_hide_selectors: vec![],
                procedural: vec![],
                procedural_count: 0,
                generics: false,
                error: Some("lock_error".to_string()),
            };
            let json_str = serde_json::to_string(&resp).unwrap_or_default();
            return env.new_string(json_str).expect("java string").into_raw();
        }
    };
    let additional_guard = GLOBAL_STATE.additional_engine.read().ok();

    let mut hide_selectors: HashSet<String> = HashSet::new();
    let mut force_hide_selectors: HashSet<String> = HashSet::new();
    let mut procedural: HashSet<String> = HashSet::new();
    let mut generics = false;

    let is_aggressive = aggressive != 0;

    if let Some(ref engine) = *default_guard {
        let cosmetic_resources = engine.url_cosmetic_resources(&url_str);
        hide_selectors.extend(cosmetic_resources.hide_selectors);
        
        if is_aggressive {
            if !cosmetic_resources.injected_script.is_empty() {
                procedural.insert(cosmetic_resources.injected_script);
            }
        }
        generics = generics || cosmetic_resources.generichide;

        if !classes_vec.is_empty() || !ids_vec.is_empty() {
            let hidden = engine.hidden_class_id_selectors(&classes_vec, &ids_vec, &exceptions_set);
            hide_selectors.extend(hidden);
        }
    }

    if let Some(guard) = additional_guard {
        if let Some(ref engine) = *guard {
            let cosmetic_resources = engine.url_cosmetic_resources(&url_str);
            force_hide_selectors.extend(cosmetic_resources.hide_selectors);
            
            if !cosmetic_resources.injected_script.is_empty() {
                procedural.insert(cosmetic_resources.injected_script);
            }
            generics = generics || cosmetic_resources.generichide;

            if !classes_vec.is_empty() || !ids_vec.is_empty() {
                let hidden = engine.hidden_class_id_selectors(&classes_vec, &ids_vec, &exceptions_set);
                force_hide_selectors.extend(hidden);
            }
        }
    }

    let procedural_vec: Vec<String> = procedural.into_iter().collect();
    let procedural_count = procedural_vec.len();
    
    let resp = CosmeticResponse {
        ok: true,
        generation: gen,
        hide_selectors: hide_selectors.into_iter().collect(),
        force_hide_selectors: force_hide_selectors.into_iter().collect(),
        procedural: procedural_vec,
        procedural_count,
        generics,
        error: None,
    };
    let json_str = serde_json::to_string(&resp).unwrap_or_default();
    env.new_string(json_str).expect("java string").into_raw()
}

#[no_mangle]
pub extern "system" fn Java_com_remmi_adblock_AdblockBridge_nativeGetHiddenClassIdSelectors(
    mut env: JNIEnv,
    _class: JClass,
    classes: JString,
    ids: JString,
    exceptions: JString,
) -> jstring {
    let gen = GLOBAL_STATE.generation.load(Ordering::Relaxed);
    let classes_str: String = match env.get_string(&classes) {
        Ok(s) => s.into(),
        Err(_) => String::new(),
    };
    let ids_str: String = match env.get_string(&ids) {
        Ok(s) => s.into(),
        Err(_) => String::new(),
    };
    let exceptions_str: String = match env.get_string(&exceptions) {
        Ok(s) => s.into(),
        Err(_) => String::new(),
    };

    let classes_vec: Vec<String> = if classes_str.is_empty() {
        vec![]
    } else {
        serde_json::from_str(&classes_str).unwrap_or_else(|_| {
            classes_str.split(',').map(|s| s.trim().to_string()).filter(|s| !s.is_empty()).collect()
        })
    };

    let ids_vec: Vec<String> = if ids_str.is_empty() {
        vec![]
    } else {
        serde_json::from_str(&ids_str).unwrap_or_else(|_| {
            ids_str.split(',').map(|s| s.trim().to_string()).filter(|s| !s.is_empty()).collect()
        })
    };

    let exceptions_set: HashSet<String> = if exceptions_str.is_empty() {
        HashSet::new()
    } else {
        serde_json::from_str(&exceptions_str).unwrap_or_else(|_| {
            exceptions_str.split(',').map(|s| s.trim().to_string()).filter(|s| !s.is_empty()).collect()
        })
    };

    let default_guard = match GLOBAL_STATE.default_engine.read() {
        Ok(g) => g,
        Err(_) => {
            let resp = CosmeticResponse {
                ok: false,
                generation: gen,
                hide_selectors: vec![],
                force_hide_selectors: vec![],
                procedural: vec![],
                procedural_count: 0,
                generics: false,
                error: Some("lock_error".to_string()),
            };
            let json_str = serde_json::to_string(&resp).unwrap_or_default();
            return env.new_string(json_str).expect("java string").into_raw();
        }
    };
    let additional_guard = GLOBAL_STATE.additional_engine.read().ok();

    let mut hide_selectors: HashSet<String> = HashSet::new();
    let mut force_hide_selectors: HashSet<String> = HashSet::new();

    if let Some(ref engine) = *default_guard {
        let hidden = engine.hidden_class_id_selectors(&classes_vec, &ids_vec, &exceptions_set);
        hide_selectors.extend(hidden);
    }

    if let Some(guard) = additional_guard {
        if let Some(ref engine) = *guard {
            let hidden = engine.hidden_class_id_selectors(&classes_vec, &ids_vec, &exceptions_set);
            force_hide_selectors.extend(hidden);
        }
    }

    let resp = CosmeticResponse {
        ok: true,
        generation: gen,
        hide_selectors: hide_selectors.into_iter().collect(),
        force_hide_selectors: force_hide_selectors.into_iter().collect(),
        procedural: vec![],
        procedural_count: 0,
        generics: false,
        error: None,
    };
    let json_str = serde_json::to_string(&resp).unwrap_or_default();
    env.new_string(json_str).expect("java string").into_raw()
}

#[no_mangle]
pub extern "system" fn Java_com_remmi_adblock_AdblockBridge_nativeGetFilterCount(
    _env: JNIEnv,
    _class: JClass,
) -> jint {
    GLOBAL_STATE.filter_count.load(Ordering::Relaxed) as jint
}

#[no_mangle]
pub extern "system" fn Java_com_remmi_adblock_AdblockBridge_nativeGetBlockedCount(
    _env: JNIEnv,
    _class: JClass,
) -> jint {
    GLOBAL_STATE.blocked_count.load(Ordering::Relaxed) as jint
}

#[no_mangle]
pub extern "system" fn Java_com_remmi_adblock_AdblockBridge_nativeGetGeneration(
    _env: JNIEnv,
    _class: JClass,
) -> jlong {
    GLOBAL_STATE.generation.load(Ordering::Relaxed) as jlong
}

#[no_mangle]
pub extern "system" fn Java_com_remmi_adblock_AdblockBridge_nativeGetEngineGeneration(
    _env: JNIEnv,
    _class: JClass,
) -> jlong {
    GLOBAL_STATE.generation.load(Ordering::Relaxed) as jlong
}

#[no_mangle]
pub extern "system" fn Java_com_remmi_adblock_AdblockBridge_nativeSelfTest(
    _env: JNIEnv,
    _class: JClass,
) -> jboolean {
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
}

#[no_mangle]
pub extern "system" fn Java_com_remmi_adblock_AdblockBridge_nativeGetVersion(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    let version = "adblock-rust-0.8.1-remmi";
    let output = env.new_string(version).expect("Couldn't create java string!");
    output.into_raw()
}

#[no_mangle]
pub extern "system" fn Java_com_remmi_adblock_AdblockBridge_nativeGetApiVersion(
    _env: JNIEnv,
    _class: JClass,
) -> jint {
    2
}


#[no_mangle]
pub extern "system" fn Java_com_remmi_adblock_AdblockBridge_nativeGetBuildId(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    let build_id = option_env!("NATIVE_BUILD_ID").unwrap_or("unknown");
    let output = env.new_string(build_id).unwrap_or_else(|_| env.new_string("").unwrap());
    output.into_raw()
}

#[no_mangle]
pub extern "system" fn Java_com_remmi_adblock_AdblockBridge_nativeGetAbi(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    let abi = option_env!("NATIVE_BUILD_ABI").unwrap_or("unknown");
    let output = env.new_string(abi).unwrap_or_else(|_| env.new_string("").unwrap());
    output.into_raw()
}

#[cfg(test)]
mod tests {
    use super::*;
    use adblock::lists::{FilterSet, ParseOptions};
    use adblock::engine::Engine;
    use adblock::request::Request;

    #[test]
    fn test_diagnostic_urls() {
        let mut default_filters = FilterSet::new(true);
        default_filters.add_filters(&vec![
            "||google-analytics.com^",
            "||sentry-cdn.com^",
            "||adblock-tester.com/banners/*",
            "||adblock-tester.com/banners/pr_advertising_ads_banner.png",
            "||default-block.com^",
            "@@||default-exception.com^",
            "||default-important.com^$important",
            "||override-block.com^",
        ], ParseOptions::default());
        let default_eng = Engine::from_filter_set(default_filters, true);

        let mut add_filters = FilterSet::new(true);
        add_filters.add_filters(&vec![
            "@@||override-block.com^",
            "||additional-block.com^",
            "@@||default-important.com^" // Weak exception against strong block
        ], ParseOptions::default());
        let add_eng = Engine::from_filter_set(add_filters, true);

        let urls = vec![
            ("GA", "https://www.google-analytics.com/analytics.js", "https://example.com/", "script"),
            ("Sentry", "https://browser.sentry-cdn.com/bundle.min.js", "https://example.com/", "script"),
            ("static banner", "https://adblock-tester.com/banners/pr_advertising_ads_banner.png", "https://adblock-tester.com/", "image"),
            ("gif banner", "https://adblock-tester.com/banners/pr_advertising_ads_banner.gif", "https://adblock-tester.com/", "image"),
            ("a) default block", "https://default-block.com/test", "https://example.com/", "script"),
            ("b) default exception", "https://default-exception.com/test", "https://example.com/", "script"),
            ("c) default important", "https://default-important.com/test", "https://example.com/", "script"),
            ("d) additional exception overrides default block", "https://override-block.com/test", "https://example.com/", "script"),
            ("e) additional ordinary block", "https://additional-block.com/test", "https://example.com/", "script"),
            ("f) important default block NOT overridden", "https://default-important.com/test", "https://example.com/", "script"),
            ("g) no-match => allow", "https://no-match-whatsoever.com/test", "https://example.com/", "script"),
        ];

        println!("\n=== DIAGNOSTIC START ===");
        for (desc, url, source, req_type) in urls {
            let req = Request::new(url, source, req_type).unwrap();
            let def_res = default_eng.check_network_request(&req);
            let add_res = add_eng.check_network_request(&req);

            let mut block = false;
            let mut final_important = false;

            if def_res.matched {
                if def_res.important {
                    final_important = true;
                }
                block = def_res.exception.is_none();
            }

            if add_res.matched {
                if !final_important {
                    if add_res.exception.is_some() {
                        block = false;
                    } else {
                        block = true;
                    }
                }
            }

            println!("{} -> blocked={}", desc, block);
        }
        println!("=== DIAGNOSTIC END ===\n");
    }
}
