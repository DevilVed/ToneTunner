# Dependency security remediation — 21 October 2026

## Scope

GitHub reported **52 open Dependabot alerts** against the default branch (`master`). Every alert was a transitive dependency of the Android Gradle plugin or Gradle build tooling, reported through `settings.gradle`. None of the flagged packages were application runtime dependencies, and none were packaged in the APK. They are still build-supply-chain risk: a compromised or vulnerable build dependency runs in the CI and developer environment that produces the signed APK.

The alerts were:

| Package | Alerts | Severity | Vulnerable versions | First fixed version |
|---|---|---|---|---|
| `io.netty:netty-codec-http` | #17, #27, #30, #32, #36, #38, #39, #41, #42, #43, #50, #52, #53, #54, #55, #56, #57, #60, #63 | 6 high, 12 medium, 1 low | up to `4.1.136.Final` | `4.1.137.Final` |
| `io.netty:netty-codec-http2` | #10, #25, #33, #44, #47, #48, #51, #58, #61 | 5 high, 4 medium | up to `4.1.135.Final` | `4.1.136.Final` |
| `io.netty:netty-handler` | #8, #45, #46, #49, #64, #65, #67 | 1 critical, 4 high, 2 medium | up to `4.1.136.Final` | `4.1.137.Final` |
| `io.netty:netty-codec` | #26, #40, #59 | 2 high, 1 medium | up to `4.1.132.Final` | `4.1.136.Final` |
| `io.netty:netty-common` | #23, #24 | 2 medium | up to `4.1.114.Final` | `4.1.118.Final` |
| `io.netty:netty-handler-proxy` | #37 | 1 low | up to `4.1.132.Final` | `4.1.133.Final` |
| `org.bouncycastle:bcprov-jdk18on` | #69, #70, #71, #72 | 2 critical, 1 high, 1 medium | up to `1.80.1` | `1.85` |
| `org.bouncycastle:bcpkix-jdk18on` | #68 | 1 medium | `< 1.84` | `1.84` |
| `com.google.protobuf:protobuf-java` | #21 | 1 high | `< 3.25.5` | `3.25.5` |
| `com.google.protobuf:protobuf-kotlin` | #66 | 1 high | `< 3.25.5` | `3.25.5` |
| `org.apache.commons:commons-compress` | #14, #15 | 2 medium | `1.21` | `1.26.0` |
| `org.bitbucket.b_c:jose4j` | #31 | 1 high | `< 0.9.6` | `0.9.6` |
| `org.jdom:jdom2` | #28 | 1 high | `< 2.0.6.1` | `2.0.6.1` |

The exact advisory identifiers, CVEs, and affected ranges remain available in the GitHub Security tab; this document groups the 52 alerts by package rather than repeating the advisory text.

## Source of the dependency graph

Resolving the Android Gradle plugin 8.13.2 buildscript classpath showed the vulnerable packages directly:

- Netty `4.1.110.Final` through AGP's gRPC/build tooling
- Bouncy Castle `1.79`
- protobuf `3.24.4`/`3.25.5` depending on the path
- JDOM `2.0.6`
- Commons Compress `1.21`
- jose4j `0.9.5`

Gradle 8.14.5 also contributed an older Netty path. These are build-tool libraries, not APK contents.

## Fix

1. **Upgrade the Android Gradle plugin from 8.13.2 to 9.4.1.** AGP 9.4.1 no longer pulls Netty and uses patched protobuf `3.25.5` and Commons Compress `1.27.1`.
2. **Upgrade Gradle from 8.14.5 to 9.7.1.** The Gradle distribution uses patched Bouncy Castle `1.85` and Commons Compress `1.28.0`.
3. **Pin the remaining transitive build-tool libraries** to their first fixed releases in the root `buildscript` classpath:
   - `org.bouncycastle:bcprov-jdk18on:1.86`
   - `org.bouncycastle:bcpkix-jdk18on:1.86`
   - `org.bitbucket.b_c:jose4j:0.9.7`
   - `org.jdom:jdom2:2.0.6.1`
4. **Use Build Tools 36.0.0**, required by AGP 9.4.1, in the build and release workflows.
5. **Remove `android.enableJetifier=true`**, which is unnecessary for this AndroidX-only project and is no longer part of the AGP 9 build contract.
6. **Add `verifySecurityDependencies`**, a root Gradle task that resolves the buildscript classpath and fails if any of the 13 affected coordinates resolves below its fixed version. The task runs in the lint, verification and release workflows.
7. **Keep dependency submission and CodeQL on `master`**, so the default-branch dependency graph and code-scanning results refresh after this change lands.

## Verification

- The new task fails on the old Bouncy Castle baseline and passes on the pinned classpath.
- Resolution of the AGP 9.4.1 buildscript classpath with the pins contains no Netty artifacts and no vulnerable versions of the other affected packages.
- The master CI runs `verifySecurityDependencies lintDebug` for code checks and `verifySecurityDependencies` before the APK build and release assembly. It does not include the beta-only native extension or emulator matrix jobs.
- The Dependabot graph refreshes after merge. Build-tool-only alerts are not fixed by a runtime dependency bump; the dependency-submission workflow triggered by a push to `master` is the refresh path.
- The 52 CodeQL/lint alerts on `master` are a separate quality backlog, not these Dependabot advisories. Several of them are stale paths from the old Gradle cache and old dependency versions and should disappear after this toolchain update. The remaining unused-parameter and layout warnings are not security vulnerabilities.

## Residual risk

This is a build-tool upgrade, not a runtime security fix. It removes vulnerable code from the build environment and reduces supply-chain exposure. It does not change the APK's endpoint validation, credential storage, microphone permission model, or recovery retention. Those require separate application-level work and are tracked outside this dependency PR.
