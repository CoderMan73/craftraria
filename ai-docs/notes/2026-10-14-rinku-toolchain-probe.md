Rinku 3.0.5-26.2 is not published to the public maven; the newest published artifact is 3.0.4-26.2, compiled at class-file 69 (Java 25), so the toolchain stays at Java 25.

**Problem**: PLAN.md pins `de.keksuccino:rinku-fabric:3.0.5-26.2` and AGENTS.md repeats it. Before
scaffolding anything, the artifact has to resolve, and the toolchain question ("pin the Java toolchain
after checking the artifact's class-file version at M1", PLAN.md M1) needs a real artifact to check.

**Solution** (probe evidence, 2026-10-14):

- `https://keksuccino.github.io/maven/de/keksuccino/rinku-fabric/` serves only up to
  `3.0.4-26.2`. Direct HEAD/GET probes of `.../3.0.5-26.2/rinku-fabric-3.0.5-26.2.jar|pom|module`
  all return **404**, while `.../3.0.4-26.2/rinku-fabric-3.0.4-26.2.jar` returns 200 (809,100 bytes).
  The GitHub API listing of `keksuccino/keksuccino.github.io` at `maven/de/keksuccino/rinku-fabric/`
  confirms it: newest entry is `3.0.4-26.2`, and no `3.0.3`/`3.0.5` exists at all. The local source
  clone `E:\Coding_Projects\MCSourceCode\Rinku` (branch `26.2.0`) declares `mod_version=3.0.5`, but
  that build has not been published.
- The cached `rinku-fabric-3.0.4-26.2.jar` (from craftlatro's own resolved build, in
  `~/.gradle/caches`) has classes at **major version 69 = Java 25** (checked `de/keksuccino/rinku/
  Rinku.class` bytes 6-7). Rinku's source `gradle.properties` also declares `java_version=25`.
- Local JDK situation: PATH `java` is 1.8 and `JAVA_HOME` (Prism's java-runtime-epsilon) points at a
  JRE 25, but IntelliJ has provisioned `C:\Users\Lati\.jdks\loom-ea-25-loom+1-11`
  (`openjdk version "25-loom" 2025-09-16`), which is a full JDK and works. Builds must run with
  `JAVA_HOME` set to that (or an equivalent JDK 25) — do not hardcode it in `gradle.properties`.

**Decision**: pin `rinku_version=3.0.4-26.2` (newest published, and the exact version craftlatro
shipped 0.9.0 on, so the API surface is proven), and compile with `options.release = 25`. This
deviates from PLAN.md's coordinate safely: it is one line in `gradle.properties`, and when 3.0.5-26.2
is eventually published the bump is that same one line plus a re-run of the M1 probes. AGENTS.md's
stack table gets the same correction.

**Why it matters**: 3.0.5 does not exist upstream in consumable form; anyone following the PLAN
coordinate verbatim gets an unresolvable dependency and (worse) may conclude Rinku is broken. The
craftlatro line is the proven fallback, and Java 25 — not 21 — is the required toolchain, so CI and
every build command on this machine must select it explicitly.
