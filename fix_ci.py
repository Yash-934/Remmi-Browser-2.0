import re

with open('.github/workflows/build-native-android.yml', 'r') as f:
    content = f.read()

old_script = """    - name: Extract and Verify APK Native Libraries (SHA256 Match & Stale Detection)
      run: |
        APK_FILE=$(find app/build/outputs/apk/debug -name "*.apk" | head -n 1)
        if [ -z "$APK_FILE" ]; then
          echo "ERROR: No APK found after assembleDebug!"
          exit 1
        fi
        echo "Inspecting APK: $APK_FILE"
        EXTRACT_DIR="/tmp/apk_libs_extract"
        rm -rf "$EXTRACT_DIR"
        mkdir -p "$EXTRACT_DIR"
        unzip -q "$APK_FILE" -d "$EXTRACT_DIR"

        for abi in arm64-v8a armeabi-v7a x86_64; do
          FRESH_LIB="app/src/main/jniLibs/$abi/libadblock_rust.so"
          APK_LIB="$EXTRACT_DIR/lib/$abi/libadblock_rust.so"
          if [ ! -f "$APK_LIB" ]; then
            echo "ERROR: ABI $abi missing in packaged APK!"
            exit 1
          fi
          FRESH_HASH=$(sha256sum "$FRESH_LIB" | awk '{print $1}')
          APK_HASH=$(sha256sum "$APK_LIB" | awk '{print $1}')
          echo "ABI $abi -> Fresh: $FRESH_HASH | APK: $APK_HASH"
          if [ "$FRESH_HASH" != "$APK_HASH" ]; then
            echo "FATAL ERROR: Packaged library in APK does not match freshly compiled library for $abi!"
            exit 1
          fi
        done
        echo "SUCCESS: All native libraries in APK match freshly compiled binaries.""""

new_script = """    - name: Extract and Verify APK Native Libraries (SHA256 Match & Stale Detection)
      run: |
        APK_FILES=$(find app/build/outputs/apk/debug -name "*.apk")
        if [ -z "$APK_FILES" ]; then
          echo "ERROR: No APK found after assembleDebug!"
          exit 1
        fi
        for APK_FILE in $APK_FILES; do
          echo "Inspecting APK: $APK_FILE"
          EXTRACT_DIR="/tmp/apk_libs_extract_$(basename $APK_FILE .apk)"
          rm -rf "$EXTRACT_DIR"
          mkdir -p "$EXTRACT_DIR"
          unzip -q "$APK_FILE" -d "$EXTRACT_DIR"
          
          # Check which ABIs are actually in the APK
          for APK_LIB in $(find "$EXTRACT_DIR/lib" -name "libadblock_rust.so" 2>/dev/null || true); do
            abi=$(basename $(dirname "$APK_LIB"))
            FRESH_LIB="app/src/main/jniLibs/$abi/libadblock_rust.so"
            if [ ! -f "$FRESH_LIB" ]; then
              echo "ERROR: Found ABI $abi in APK but no fresh library exists!"
              exit 1
            fi
            FRESH_HASH=$(sha256sum "$FRESH_LIB" | awk '{print $1}')
            APK_HASH=$(sha256sum "$APK_LIB" | awk '{print $1}')
            echo "ABI $abi -> Fresh: $FRESH_HASH | APK: $APK_HASH"
            if [ "$FRESH_HASH" != "$APK_HASH" ]; then
              echo "FATAL ERROR: Packaged library in APK does not match freshly compiled library for $abi!"
              exit 1
            fi
          done
        done
        echo "SUCCESS: All native libraries in APK match freshly compiled binaries.""""

content = content.replace(old_script, new_script)

with open('.github/workflows/build-native-android.yml', 'w') as f:
    f.write(content)
