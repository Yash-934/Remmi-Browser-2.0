import re

with open("app/src/main/java/com/remmi/adblock/AdblockBridge.kt", "r") as f:
    content = f.read()

# Add nativeGetBuildId
if "private external fun nativeGetBuildId" not in content:
    content = content.replace("private external fun nativeGetVersion(): String", "private external fun nativeGetVersion(): String\n  private external fun nativeGetBuildId(): String\n  private external fun nativeGetAbi(): String")

# Add startup log
if "[ADBLOCK_NATIVE_BINARY]" not in content:
    init_block_start = content.find("val testOk = selfTest()")
    if init_block_start != -1:
        log_code = """        try {
          val engineVer = nativeGetVersion()
          val buildId = nativeGetBuildId()
          val abi = nativeGetAbi()
          Log.i(TAG, "[ADBLOCK_NATIVE_BINARY]")
          Log.i(TAG, "version=$engineVer")
          Log.i(TAG, "build=$buildId")
          Log.i(TAG, "abi=$abi")
        } catch (e: Throwable) {
          Log.e(TAG, "[ADBLOCK_NATIVE_BINARY] Could not read build info: ${e.message}")
        }
        val testOk = selfTest()"""
        content = content[:init_block_start] + log_code + content[init_block_start+len("val testOk = selfTest()"):]

with open("app/src/main/java/com/remmi/adblock/AdblockBridge.kt", "w") as f:
    f.write(content)

print("Patched AdblockBridge.kt")
