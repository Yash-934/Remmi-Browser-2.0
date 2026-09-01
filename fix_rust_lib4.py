import re

with open('rust/src/lib.rs', 'r') as f:
    content = f.read()

old_print = """            #[cfg(debug_assertions)]
            {
                println!(
                    "[AB_DECISION] type={} host={} thirdParty={} defaultMatched={} defaultException={} defaultImportant={} additionalMatched={} additionalException={} additionalImportant={} finalBlocked={}",
                    ctx.resource_type,
                    ctx.url,
                    ctx.third_party,
                    out.default_matched, out.default_exception, out.default_important,
                    out.additional_matched, out.additional_exception, out.additional_important, out.blocked
                );
            }"""

new_print = """            let is_test_request = ctx.url.contains("test") || ctx.url.contains("example") || ctx.url.contains("mock") || ctx.url.contains("custom-popup-ad") || ctx.url.contains("-ad") || ctx.url.contains("tracker");
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

content = content.replace(old_print, new_print)

with open('rust/src/lib.rs', 'w') as f:
    f.write(content)
