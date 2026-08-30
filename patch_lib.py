import re

with open("rust/src/lib.rs", "r") as f:
    content = f.read()

# Add nativeGetBuildId and nativeGetAbi
new_funcs = """
#[no_mangle]
pub extern "system" fn Java_com_remmi_adblock_AdblockBridge_nativeGetBuildId(
    env: JNIEnv,
    _class: JClass,
) -> JString {
    let build_id = option_env!("NATIVE_BUILD_ID").unwrap_or("unknown");
    env.new_string(build_id).unwrap_or_else(|_| env.new_string("").unwrap())
}

#[no_mangle]
pub extern "system" fn Java_com_remmi_adblock_AdblockBridge_nativeGetAbi(
    env: JNIEnv,
    _class: JClass,
) -> JString {
    let abi = option_env!("NATIVE_BUILD_ABI").unwrap_or("unknown");
    env.new_string(abi).unwrap_or_else(|_| env.new_string("").unwrap())
}
"""

if "Java_com_remmi_adblock_AdblockBridge_nativeGetBuildId" not in content:
    # Append to the end of the file, but before #[cfg(test)] if it exists
    if "#[cfg(test)]" in content:
        idx = content.find("#[cfg(test)]")
        content = content[:idx] + new_funcs + "\n" + content[idx:]
    else:
        content += "\n" + new_funcs

with open("rust/src/lib.rs", "w") as f:
    f.write(content)

print("Patched rust/src/lib.rs")
