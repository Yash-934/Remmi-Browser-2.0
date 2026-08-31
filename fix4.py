import re

with open('app/src/test/java/com/remmi/browser/CrashReportingSystemTest.kt', 'r') as f:
    content = f.read()

content = content.replace('assertTrue(report.contains("Last startup phase: ADBLOCK_CONSTRUCTION_START"))', 'println("REPORT_IS: " + report)\n    assertTrue(report.contains("Last startup phase: ADBLOCK_CONSTRUCTION_START"))')

with open('app/src/test/java/com/remmi/browser/CrashReportingSystemTest.kt', 'w') as f:
    f.write(content)
