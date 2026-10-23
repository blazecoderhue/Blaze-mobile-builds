# Blaze Engine dependencies (allowed: Apache-2.0, MIT, BSD only)

Checked 2026-10-23 against each project's LICENSE file (GitHub licence API + the file text).

## In use / planned
| Library | Coordinates | Licence | Licence URL | Used for | Milestone |
|---|---|---|---|---|---|
| TestNG | org.testng:testng:7.10.2 (test only, never shipped) | Apache-2.0 | https://github.com/testng-team/testng/blob/master/LICENSE.txt | JVM unit tests | M1 |
| (TestNG transitive) JCommander 1.82 | com.beust:jcommander | Apache-2.0 | https://github.com/cbeust/jcommander/blob/master/license.txt | test only | M1 |
| (TestNG transitive) SLF4J API 1.7.36 | org.slf4j:slf4j-api | MIT | https://www.slf4j.org/license.html | test only | M1 |
| (TestNG transitive, runtime) jQuery webjar 3.7.1 | org.webjars:jquery | MIT | https://github.com/jquery/jquery/blob/main/LICENSE.txt | TestNG HTML report, test only | M1 |
| AndroidHiddenApiBypass | org.lsposed.hiddenapibypass:hiddenapibypass:6.1 | Apache-2.0 | https://github.com/LSPosed/AndroidHiddenApiBypass/blob/main/LICENSE | non-SDK access on API 28+ (only through `runtime/HiddenApi`) | M1 runtime (added when the runtime code lands) |
| ByteHook (bhook) | com.bytedance:bytehook | MIT | https://github.com/bytedance/bhook/blob/main/LICENSE | PLT hooks for native path redirection | M3 (planned) |

Test runner: the app's own tests use JUnit 4 (EPL-1.0). The engine does NOT use JUnit, so that every engine dependency,
test scope included, is Apache-2.0/MIT/BSD. TestNG's optional deps (Guice, SnakeYAML) are not pulled.

## Checked and REJECTED (do not add)
| Library | Licence found | Why |
|---|---|---|
| Pine (canyie/pine) | Anti-996 | not Apache/MIT/BSD |
| SandHook (asLody/SandHook) | Anti-996 | not allowed |
| YAHFA | GPL-3.0 | copyleft |
| JUnit 4 (for engine tests) | EPL-1.0 | not on the allowed list; TestNG used instead |
| Any VirtualApp/BlackBox-family/DroidPlugin/VirtualAPK/Shelter/VirtualXposed code | GPL/LGPL/unlicensed | forbidden by CLEANROOM_RULES.md |

## Checked, allowed but not planned
| Library | Licence | URL | Note |
|---|---|---|---|
| ShadowHook (bytedance/android-inline-hook) | MIT | https://github.com/bytedance/android-inline-hook/blob/main/LICENSE | inline hooks; only if PLT hooks are not enough |
| Dobby | Apache-2.0 | https://github.com/jmpews/Dobby/blob/master/LICENSE | alternative inline hook |
| FreeReflection | MIT | https://github.com/tiann/FreeReflection/blob/master/LICENSE | alternative to the bypass library |
| epic (tiann/epic) | Apache-2.0 (LICENSE file) | https://github.com/tiann/epic/blob/master/LICENSE | ART method hooking; GitHub flags NOASSERTION, re-check before any use |
| xHook (iqiyi) | MIT for most files, "some other source code" under other terms | https://github.com/iqiyi/xHook/blob/master/LICENSE | mixed; prefer bhook |
