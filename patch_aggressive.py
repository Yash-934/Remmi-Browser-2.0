import re

with open("app/src/main/java/com/remmi/adblock/AdblockBridge.kt", "r") as f:
    content = f.read()

# Kotlin signature update
old_sig = """  fun getCosmeticResources(
    url: String,
    classes: List<String> = emptyList(),
    ids: List<String> = emptyList(),
    exceptions: List<String> = emptyList()
  ): CosmeticResources {"""

new_sig = """  fun getCosmeticResources(
    url: String,
    classes: List<String> = emptyList(),
    ids: List<String> = emptyList(),
    exceptions: List<String> = emptyList(),
    aggressive: Boolean = false
  ): CosmeticResources {"""

content = content.replace(old_sig, new_sig)

# Native call update
old_native_call = """val resultJson = nativeGetCosmeticResources(url, classesJson, idsJson, exceptionsJson)"""
new_native_call = """val resultJson = nativeGetCosmeticResources(url, classesJson, idsJson, exceptionsJson, aggressive)"""
content = content.replace(old_native_call, new_native_call)

# Native signature update
old_native_sig = """  private external fun nativeGetCosmeticResources(
    url: String,
    classesJson: String,
    idsJson: String,
    exceptionsJson: String
  ): String"""
new_native_sig = """  private external fun nativeGetCosmeticResources(
    url: String,
    classesJson: String,
    idsJson: String,
    exceptionsJson: String,
    aggressive: Boolean
  ): String"""
content = content.replace(old_native_sig, new_native_sig)

# Fallback logic update
old_fallback_proc = """    val proceduralList = fallbackProceduralFilters.toList()"""
new_fallback_proc = """    val proceduralList = if (aggressive) fallbackProceduralFilters.toList() else emptyList()"""
content = content.replace(old_fallback_proc, new_fallback_proc)

with open("app/src/main/java/com/remmi/adblock/AdblockBridge.kt", "w") as f:
    f.write(content)


# Now update rust/src/lib.rs
with open("rust/src/lib.rs", "r") as f:
    rust_content = f.read()

old_rust_sig = """pub extern "system" fn Java_com_remmi_adblock_AdblockBridge_nativeGetCosmeticResources(
    mut env: JNIEnv,
    _class: JClass,
    url: JString,
    classes_json: JString,
    ids_json: JString,
    exceptions_json: JString,
) -> jstring {"""

new_rust_sig = """pub extern "system" fn Java_com_remmi_adblock_AdblockBridge_nativeGetCosmeticResources(
    mut env: JNIEnv,
    _class: JClass,
    url: JString,
    classes_json: JString,
    ids_json: JString,
    exceptions_json: JString,
    aggressive: jboolean,
) -> jstring {"""
rust_content = rust_content.replace(old_rust_sig, new_rust_sig)

old_rust_logic = """    if let Some(ref engine) = *default_guard {
        let cosmetic_resources = engine.url_cosmetic_resources(&url_str);
        hide_selectors.extend(cosmetic_resources.hide_selectors);
        force_hide_selectors.extend(cosmetic_resources.force_hide_selectors);
        procedural.extend(cosmetic_resources.injected_script);
        generics = generics || cosmetic_resources.generics;"""

new_rust_logic = """    let is_aggressive = aggressive != 0;

    if let Some(ref engine) = *default_guard {
        let cosmetic_resources = engine.url_cosmetic_resources(&url_str);
        hide_selectors.extend(cosmetic_resources.hide_selectors);
        force_hide_selectors.extend(cosmetic_resources.force_hide_selectors);
        if is_aggressive {
            procedural.extend(cosmetic_resources.injected_script);
        }
        generics = generics || cosmetic_resources.generics;"""
rust_content = rust_content.replace(old_rust_logic, new_rust_logic)

with open("rust/src/lib.rs", "w") as f:
    f.write(rust_content)

