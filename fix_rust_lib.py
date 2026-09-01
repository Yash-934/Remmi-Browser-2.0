import re

with open('rust/src/lib.rs', 'r') as f:
    content = f.read()

# Add missing imports for JSON parsing
if 'use serde::Deserialize;' not in content:
    content = content.replace('use serde::Serialize;', 'use serde::{Serialize, Deserialize};')

# Replace AdblockEngineState and GLOBAL_STATE
old_state = """struct AdblockEngineState {
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
}"""

new_state = """struct EngineSet {
    default_engine: Option<Engine>,
    additional_engine: Option<Engine>,
    generation: u64,
}

struct AdblockEngineState {
    engines: RwLock<EngineSet>,
    filter_count: AtomicU64,
    blocked_count: AtomicU64,
    allowed_count: AtomicU64,
}

lazy_static! {
    static ref GLOBAL_STATE: AdblockEngineState = AdblockEngineState {
        engines: RwLock::new(EngineSet {
            default_engine: None,
            additional_engine: None,
            generation: 0,
        }),
        filter_count: AtomicU64::new(0),
        blocked_count: AtomicU64::new(0),
        allowed_count: AtomicU64::new(0),
    };
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct RequestContext {
    url: String,
    request_initiator: Option<String>,
    source_url: Option<String>,
    resource_type: String,
    method: String,
    aggressive: bool,
    third_party: bool,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct MatchResult {
    blocked: bool,
    redirect: Option<String>,
    rewritten_url: Option<String>,
    csp: Option<String>,
    default_matched: bool,
    default_exception: bool,
    default_important: bool,
    additional_matched: bool,
    additional_exception: bool,
    additional_important: bool,
}
"""
content = content.replace(old_state, new_state)

# Replace nativeInit
old_init = """    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
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
    }));"""

new_init = """    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let mut filter_set = FilterSet::new(true);
        filter_set.add_filters(DEFAULT_RULES, ParseOptions::default());
        let initial_engine = Engine::from_filter_set(filter_set, true);

        match GLOBAL_STATE.engines.write() {
            Ok(mut guard) => {
                guard.default_engine = Some(initial_engine);
                guard.generation = 1;
                GLOBAL_STATE.filter_count.store(DEFAULT_RULES.len() as u64, Ordering::SeqCst);
                GLOBAL_STATE.blocked_count.store(0, Ordering::SeqCst);
                GLOBAL_STATE.allowed_count.store(0, Ordering::SeqCst);
                JNI_TRUE
            }
            Err(_) => JNI_FALSE,
        }
    }));"""
content = content.replace(old_init, new_init)

with open('rust/src/lib.rs', 'w') as f:
    f.write(content)
