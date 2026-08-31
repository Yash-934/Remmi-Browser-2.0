gradle testDebugUnitTest --tests "com.remmi.browser.StartupIsolationMatrixTest.testConfigD_ExecutionTrace" > test_output.log 2>&1 || true
cat test_output.log | grep -A 20 "StartupIsolationMatrixTest > testConfigD_ExecutionTrace FAILED"
