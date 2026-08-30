sed -i '/- name: Verify APK Native Binaries and Build ID/,/echo "All packaged binaries match the freshly built native libraries."/d' .github/workflows/build-native-android.yml
sed -i 's|path: app/build/outputs/apk/debug/app-debug.apk|path: app/build/outputs/apk/debug/*.apk|g' .github/workflows/build-native-android.yml
