use std::sync::RwLock;
use jni::JNIEnv;
use jni::objects::{JClass, JString};
use jni::sys::{jboolean, jint, jstring, JNI_TRUE, JNI_FALSE};
use lazy_static::lazy_static;
use adblock::Engine;
use adblock::lists::{FilterSet, ParseOptions};
use adblock::request::Request;

struct AdblockEngineState {
    engine: Option<Engine>,
    filter_count: u64,
    blocked_count: u64,
    allowed_count: u64,
}

lazy_static! {
    static ref GLOBAL_STATE: RwLock<AdblockEngineState> = RwLock::new(AdblockEngineState {
        engine: None,
        filter_count: 0,
        blocked_count: 0,
        allowed_count: 0,
    });
}

#[no_mangle]
pub extern "system" fn Java_com_remmi_adblock_AdblockBridge_nativeInit(
    _env: JNIEnv,
    _class: JClass,
) -> jboolean {
    let mut state = match GLOBAL_STATE.write() {
        Ok(guard) => guard,
        Err(_) => return JNI_FALSE,
    };
    let mut filter_set = FilterSet::new(true);
    let default_rules = vec![
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
    filter_set.add_filters(&default_rules, ParseOptions::default());
    state.filter_count = default_rules.len() as u64;
    state.engine = Some(Engine::from_filter_set(filter_set, true));
    state.blocked_count = 0;
    state.allowed_count = 0;
    JNI_TRUE
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

    let mut state = match GLOBAL_STATE.write() {
        Ok(guard) => guard,
        Err(_) => return JNI_FALSE,
    };

    if let Some(ref engine) = state.engine {
        if let Ok(req) = Request::new(&url_str, &source_str, &type_str) {
            let blocker_result = engine.check_network_request(&req);
            if blocker_result.matched {
                state.blocked_count += 1;
                return JNI_TRUE;
            }
        }
    }
    state.allowed_count += 1;
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

    let mut state = match GLOBAL_STATE.write() {
        Ok(guard) => guard,
        Err(_) => return 0,
    };

    let lines: Vec<&str> = rules_str.lines().collect();
    let count = lines.len() as jint;
    let mut filter_set = FilterSet::new(true);
    filter_set.add_filters(&lines, ParseOptions::default());

    if let Some(ref mut engine) = state.engine {
        *engine = Engine::from_filter_set(filter_set, true);
    } else {
        state.engine = Some(Engine::from_filter_set(filter_set, true));
    }
    state.filter_count = count as u64;
    count
}

#[no_mangle]
pub extern "system" fn Java_com_remmi_adblock_AdblockBridge_nativeGetFilterCount(
    _env: JNIEnv,
    _class: JClass,
) -> jint {
    let state = match GLOBAL_STATE.read() {
        Ok(guard) => guard,
        Err(_) => return 0,
    };
    state.filter_count as jint
}

#[no_mangle]
pub extern "system" fn Java_com_remmi_adblock_AdblockBridge_nativeGetBlockedCount(
    _env: JNIEnv,
    _class: JClass,
) -> jint {
    let state = match GLOBAL_STATE.read() {
        Ok(guard) => guard,
        Err(_) => return 0,
    };
    state.blocked_count as jint
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
