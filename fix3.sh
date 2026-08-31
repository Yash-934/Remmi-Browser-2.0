gradle testDebugUnitTest --tests "com.remmi.browser.CrashReportingSystemTest.testAbnormalTerminationDetection" -i > test_out.log 2>&1
cat test_out.log | grep -A 20 "CrashReportingSystemTest > testAbnormalTerminationDetection FAILED"
