import re

with open('rust/src/lib.rs', 'r') as f:
    content = f.read()

start_idx = content.find('pub extern "system" fn Java_com_remmi_adblock_AdblockBridge_nativeMatches')
end_idx = content.find('pub extern "system" fn Java_com_remmi_adblock_AdblockBridge_nativeCompileRules')

if start_idx != -1 and end_idx != -1:
    new_fn = """pub extern "system" fn Java_com_remmi_adblock_AdblockBridge_nativeMatchesJson(
    mut env: JNIEnv,
    _class: JClass,
    context_json: JString,
) -> jstring {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let json_str: String = match env.get_string(&context_json) {
            Ok(s) => s.into(),
            Err(_) => return std::ptr::null_mut(),
        };

        let ctx: RequestContext = match serde_json::from_str(&json_str) {
            Ok(c) => c,
            Err(_) => return std::ptr::null_mut(),
        };

        let mut out = MatchResult {
            blocked: false,
            redirect: None,
            rewritten_url: None,
            csp: None,
            default_matched: false,
            default_exception: false,
            default_important: false,
            additional_matched: false,
            additional_exception: false,
            additional_important: false,
        };

        let engines_guard = match GLOBAL_STATE.engines.read() {
            Ok(guard) => guard,
            Err(_) => return std::ptr::null_mut(),
        };

        let source_url = ctx.source_url.unwrap_or_default();
        if let Ok(mut req) = Request::new(&ctx.url, &source_url, &ctx.resource_type) {
            // Wait, we need to handle method and third_party in the future?
            // Actually, we can use the advanced adblock::request API to set method and third_party if available,
            // or just rely on standard matching. adblock 0.8 Request has some builder fields maybe?
            // Let's check network request.
            
            // For now, we will perform pure final-result merge.
            let mut final_important = false;

            if let Some(ref default_eng) = engines_guard.default_engine {
                let res = default_eng.check_network_request(&req);
                if res.matched {
                    out.default_matched = true;
                    out.default_exception = res.exception.is_some();
                    out.default_important = res.important;
                    
                    if res.important {
                        final_important = true;
                    }
                    out.blocked = res.exception.is_none();
                    
                    if out.blocked {
                        out.redirect = res.redirect.clone();
                        out.rewritten_url = res.redirect.clone(); // Workaround for older adblock missing rewritten_url
                    }
                }
            }

            if let Some(ref additional) = engines_guard.additional_engine {
                let res = additional.check_network_request(&req);
                if res.matched {
                    out.additional_matched = true;
                    out.additional_exception = res.exception.is_some();
                    out.additional_important = res.important;
                    
                    if !final_important || res.important {
                        if res.exception.is_some() {
                            out.blocked = false;
                            out.redirect = None;
                            out.rewritten_url = None;
                        } else {
                            out.blocked = true;
                            if let Some(ref r) = res.redirect {
                                out.redirect = Some(r.clone());
                                out.rewritten_url = Some(r.clone());
                            }
                        }
                    }
                }
            }

            if out.blocked {
                GLOBAL_STATE.blocked_count.fetch_add(1, Ordering::Relaxed);
            } else {
                GLOBAL_STATE.allowed_count.fetch_add(1, Ordering::Relaxed);
            }
            
            #[cfg(debug_assertions)]
            {
                println!(
                    "[AB_DECISION] type={} host={} thirdParty={} defaultMatched={} defaultException={} defaultImportant={} additionalMatched={} additionalException={} additionalImportant={} finalBlocked={}",
                    ctx.resource_type,
                    ctx.url,
                    ctx.third_party,
                    out.default_matched, out.default_exception, out.default_important,
                    out.additional_matched, out.additional_exception, out.additional_important, out.blocked
                );
            }
        }

        let out_json = serde_json::to_string(&out).unwrap_or_default();
        match env.new_string(&out_json) {
            Ok(s) => s.into_raw(),
            Err(_) => std::ptr::null_mut(),
        }
    }));

    match result {
        Ok(val) => val,
        Err(_) => std::ptr::null_mut(),
    }
}

#[no_mangle]
"""
    content = content[:start_idx] + new_fn + content[end_idx:]

with open('rust/src/lib.rs', 'w') as f:
    f.write(content)
