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
    engine: RwLock<Option<Engine>>,
    filter_count: AtomicU64,
    blocked_count: AtomicU64,
    allowed_count: AtomicU64,
    generation: AtomicU64,
}

lazy_static! {
    static ref GLOBAL_STATE: AdblockEngineState = AdblockEngineState {
        engine: RwLock::new(None),
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

    match GLOBAL_STATE.engine.write() {
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

    // Read-side lock: concurrent readers do not block each other
    let engine_guard = match GLOBAL_STATE.engine.read() {
        Ok(guard) => guard,
        Err(_) => return JNI_FALSE,
    };

    if let Some(ref engine) = *engine_guard {
        if let Ok(req) = Request::new(&url_str, &source_str, &type_str) {
            let blocker_result = engine.check_network_request(&req);
            if blocker_result.matched {
                GLOBAL_STATE.blocked_count.fetch_add(1, Ordering::Relaxed);
                return JNI_TRUE;
            }
        }
    }
    GLOBAL_STATE.allowed_count.fetch_add(1, Ordering::Relaxed);
    JNI_FALSE
}

#[no_mangle]
pub extern "system" fn Java_com_remmi_adblock_AdblockBridge_nativeCompileRules(
    mut env: JNIEnv,
    _class: JClass,
    rules_text: JString,
) -> jint {
    let rules_str: String = match env.get_string(&rules_text) {
        Ok(s) => s.into(),
        Err(_) => return 0,
    };

    let lines: Vec<&str> = rules_str.lines().collect();
    let valid_count = lines
        .iter()
        .filter(|line| !line.trim().is_empty() && !line.starts_with('!'))
        .count();

    if valid_count == 0 {
        // Do not replace active engine with an empty one
        return 0;
    }

    // 1. Build a NEW filter set and engine completely first
    let mut filter_set = FilterSet::new(true);
    filter_set.add_filters(DEFAULT_RULES, ParseOptions::default());
    filter_set.add_filters(&lines, ParseOptions::default());
    let new_engine = Engine::from_filter_set(filter_set, true);

    // 2. Atomically swap the new engine in place
    match GLOBAL_STATE.engine.write() {
        Ok(mut guard) => {
            *guard = Some(new_engine);
            let total_count = (DEFAULT_RULES.len() + valid_count) as u64;
            GLOBAL_STATE.filter_count.store(total_count, Ordering::SeqCst);
            let new_gen = GLOBAL_STATE.generation.fetch_add(1, Ordering::SeqCst) + 1;
            println!("[ADBLOCK_ENGINE_SWAP] newGeneration={} rules={}", new_gen, total_count);
            valid_count as jint
        }
        Err(_) => 0,
    }
}

#[no_mangle]
pub extern "system" fn Java_com_remmi_adblock_AdblockBridge_nativeGetCosmeticResources(
    mut env: JNIEnv,
    _class: JClass,
    url: JString,
    classes: JString,
    ids: JString,
    exceptions: JString,
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

    let engine_guard = match GLOBAL_STATE.engine.read() {
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

    if let Some(ref engine) = *engine_guard {
        let cosmetic_resources = engine.url_cosmetic_resources(&url_str);
        let mut hide_selectors: Vec<String> = cosmetic_resources.hide_selectors.into_iter().collect();
        let force_hide_selectors: Vec<String> = cosmetic_resources.force_hide_selectors.into_iter().collect();
        let procedural: Vec<String> = cosmetic_resources.injected_script.into_iter().collect();
        let procedural_count = procedural.len();
        let generics = cosmetic_resources.generics;

        // If classes or ids were passed, query hidden class/id selectors
        if !classes_vec.is_empty() || !ids_vec.is_empty() {
            let hidden = engine.hidden_class_id_selectors(&classes_vec, &ids_vec, &exceptions_set);
            for sel in hidden.hide_selectors {
                if !hide_selectors.contains(&sel) {
                    hide_selectors.push(sel);
                }
            }
        }

        let resp = CosmeticResponse {
            ok: true,
            generation: gen,
            hide_selectors,
            force_hide_selectors,
            procedural,
            procedural_count,
            generics,
            error: None,
        };
        let json_str = serde_json::to_string(&resp).unwrap_or_default();
        return env.new_string(json_str).expect("java string").into_raw();
    }

    let resp = CosmeticResponse {
        ok: true,
        generation: gen,
        hide_selectors: vec![],
        force_hide_selectors: vec![],
        procedural: vec![],
        procedural_count: 0,
        generics: false,
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

    let engine_guard = match GLOBAL_STATE.engine.read() {
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

    if let Some(ref engine) = *engine_guard {
        let hidden = engine.hidden_class_id_selectors(&classes_vec, &ids_vec, &exceptions_set);
        let hide_selectors: Vec<String> = hidden.hide_selectors.into_iter().collect();

        let resp = CosmeticResponse {
            ok: true,
            generation: gen,
            hide_selectors,
            force_hide_selectors: vec![],
            procedural: vec![],
            procedural_count: 0,
            generics: false,
            error: None,
        };
        let json_str = serde_json::to_string(&resp).unwrap_or_default();
        return env.new_string(json_str).expect("java string").into_raw();
    }

    let resp = CosmeticResponse {
        ok: true,
        generation: gen,
        hide_selectors: vec![],
        force_hide_selectors: vec![],
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
    let version = "adblock-rust-0.8.0-remmi";
    let output = env.new_string(version).expect("Couldn't create java string!");
    output.into_raw()
}
