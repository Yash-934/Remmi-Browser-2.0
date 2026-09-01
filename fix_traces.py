import re

with open('rust/src/lib.rs', 'r') as f:
    content = f.read()

old_trace = """            let is_test_request = ctx.url.contains("test") || ctx.url.contains("example") || ctx.url.contains("mock") || ctx.url.contains("custom-popup-ad") || ctx.url.contains("-ad") || ctx.url.contains("tracker");
            if is_test_request {
                println!(
                    "[AB_DIAGNOSTIC] host={} type={} method={} initiator={} thirdParty={} defaultMatched={} defaultException={} defaultImportant={} additionalMatched={} additionalException={} additionalImportant={} finalBlocked={} enforcementResult={}",
                    ctx.url,
                    ctx.resource_type,
                    ctx.method,
                    ctx.request_initiator.as_deref().unwrap_or(""),
                    ctx.third_party,
                    out.default_matched, out.default_exception, out.default_important,
                    out.additional_matched, out.additional_exception, out.additional_important, out.blocked,
                    if out.blocked { "blocked" } else { "allowed" }
                );
            }"""

new_trace = """            let is_test_request = ctx.url.contains("tester_target_trigger");
            if is_test_request {
                let actual_third_party = req.is_third_party();
                println!("[AB_REQUEST_IN]");
                println!("requestType={}", ctx.resource_type);
                println!("method={}", ctx.method);
                println!("requestHost={}", ctx.url);
                println!("topOriginHost={}", ctx.source_url.as_deref().unwrap_or(""));
                println!("initiatorHost={}", ctx.request_initiator.as_deref().unwrap_or(""));
                println!("thirdParty={}", actual_third_party);
                println!("aggressive={}", ctx.aggressive);
                println!("generation={}", engines_guard.generation);
                
                println!("[AB_DEFAULT_RESULT]");
                println!("matched={}", out.default_matched);
                println!("exception={}", out.default_exception);
                println!("important={}", out.default_important);

                println!("[AB_ADDITIONAL_RESULT]");
                println!("matched={}", out.additional_matched);
                println!("exception={}", out.additional_exception);
                println!("important={}", out.additional_important);

                println!("[AB_FINAL_RESULT]");
                println!("matched={}", out.default_matched || out.additional_matched);
                println!("exception={}", !out.blocked && (out.default_exception || out.additional_exception));
                println!("important={}", out.default_important || out.additional_important);
                println!("redirect={}", out.redirect.as_deref().unwrap_or(""));
                println!("rewrittenUrl={}", out.rewritten_url.as_deref().unwrap_or(""));

                println!("[AB_ENFORCEMENT]");
                println!("blocked={}", out.blocked);
            }"""

content = content.replace(old_trace, new_trace)

with open('rust/src/lib.rs', 'w') as f:
    f.write(content)
