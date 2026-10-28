# Verified YiRanExpressionLib Maven artifact

> Note: paths such as `evidence/...` refer to the evidence directory of the private development
> tree and are **not** part of this public source snapshot. They are kept only to record where the
> observations were made; no such files exist here and cannot be checked from this snapshot.

This directory contains the JAR and POM the NeoForge 1.21.1 build resolves for `com.github.yiran1457:YiRanExpressionLib`. The repository is exclusive to that module, so a remote repository cannot silently replace the normalized `1.0.1` module.

**The JAR bytes are now the official upstream build**, not a third-party redistribution: the file is the JitPack `universal` artifact of upstream commit `60584101e698d1e3f79d327b18982dec7ddb5465`, copied without modification.

Source repository: https://github.com/yiran1457/YiRanExpressionLib

Official build (byte origin of the shipped file): https://jitpack.io/com/github/yiran1457/YiRanExpressionLib/60584101e6/YiRanExpressionLib-60584101e6-universal.jar

Immutable source commit: `60584101e698d1e3f79d327b18982dec7ddb5465` (branch `universal`, tag `1.0.1-universal`, 2026-09-02)

Original paths:

- `libs/maven/com/github/yiran1457/YiRanExpressionLib/1.0.1/YiRanExpressionLib-1.0.1.jar`
- `libs/maven/com/github/yiran1457/YiRanExpressionLib/1.0.1/YiRanExpressionLib-1.0.1.pom`
- `licenses/YiRanExpressionLib-LICENSE.txt`

| File | Bytes | SHA-256 | Git blob SHA-1 |
| --- | ---: | --- | --- |
| YiRanExpressionLib-1.0.1.jar | 62897 | `d797d9eaf22923b5f0d0d152b583aef3d60600bf349aa481a89ecd159255106a` | `30e9f0d433ae811f8703f7cbbfc0414a20f5bec8` |
| YiRanExpressionLib-1.0.1.pom | 455 | `854c1015b867664d30ef39928074fe1ddfa8f2ef0b6cfc2226c6c18cca04e87e` | `c46b45721ae1ea4c1e03458f66810b664c72cc08` |
| YiRanExpressionLib-LICENSE.txt | 8846 | `80fef18d1f418b257b061783249ccb85a4883cb1542e4e372b12c2a69d66219c` | `13ffb4b1536a62b2a6dacde9ad4cf60bb3ecb419` |

Re-verify with:

```
sha256sum libs/maven/com/github/yiran1457/YiRanExpressionLib/1.0.1/*.jar \
          libs/maven/com/github/yiran1457/YiRanExpressionLib/1.0.1/*.pom \
          licenses/YiRanExpressionLib-LICENSE.txt
git hash-object libs/maven/com/github/yiran1457/YiRanExpressionLib/1.0.1/*.jar \
                libs/maven/com/github/yiran1457/YiRanExpressionLib/1.0.1/*.pom \
                licenses/YiRanExpressionLib-LICENSE.txt
curl -sS -o /tmp/up-universal.jar \
  https://jitpack.io/com/github/yiran1457/YiRanExpressionLib/60584101e6/YiRanExpressionLib-60584101e6-universal.jar
sha256sum /tmp/up-universal.jar && cmp /tmp/up-universal.jar \
  libs/maven/com/github/yiran1457/YiRanExpressionLib/1.0.1/YiRanExpressionLib-1.0.1.jar
```

The JAR digest above is the JitPack artifact digest recorded in the earlier audit sample (`evidence/license-audit-2026-10-26/manifest.md`, `up-universal.jar`, 62897 bytes). `tools/expression_lib.py` pins the same size and digest, and both `tools/release_pipeline.py` and `tools/check_release.py` reject a build whose nested library or resolved artifact deviates from it.

## Normalized identity versus published identity

The shipped file keeps the local Maven coordinates `com.github.yiran1457:YiRanExpressionLib:1.0.1` (`path`/`version` in `META-INF/jarjar/metadata.json` stay at `[1.0.1]` / `1.0.1`, the identity this project has used since it first bundled the library), while the official artifact is published by JitPack under the commit-derived version `60584101e6`.

That identity is **not** the range the current Advanced Leveling fork requests. `AdvancedLeveling-1.21.1-1.0.1-fix` embeds its own `META-INF/jarjar/YiRanExpressionLib-1.21.1-NeoForge-1.0.0.jar` (47022 bytes, `dbf7f922…`, descriptor `version="1.0.0"`, `license="AGPL v3.0"`) with JarJar range `[1.21.1-NeoForge-1.0.0,)`, and requires NeoForge `[21.1.248,)`. Co-loading it next to this JAR in an isolated dedicated server failed before startup with `JarJar VERSION_RESOLUTION_FAILED` for `com.github.yiran1457:YiRanExpressionLib` (requested `[1.0.1,1.0.1]` by `tactical` versus `[1.21.1-NeoForge-1.0.0,)` by `autoleveling`), plus that mod's NeoForge requirement against the 21.1.216 dev runtime. So "aligned with Advanced Leveling" described the older distribution that embedded the same `1.0.1` artifact; it is **not** true for the fork named above.

- The `1.0.1` POM here (455 bytes, no license or dependency block) is **not** the JitPack POM. The JitPack POM for that build is 1258 bytes (`94a4b80502a137e9926f427e43e6f5a89cd8531e0ddfac981e308ef9a6218ec1`) and its license field says AGPL.
- So: bytes come from the official build, coordinates and POM are normalized locally. Byte identity is proven by the digest; identity normalization is a deliberate local decision and is **not** an upstream statement that version `1.0.1` equals `60584101e6`.
- Version `1.0.1` inside the JAR's own descriptors is upstream's own `version="1.0.1"`, which is why the normalized path was chosen.

## Descriptor facts inside the shipped bytes

- `META-INF/neoforge.mods.toml`: `modLoader="lowcodefml"`, `loaderVersion="[1,)"`, `license="LGPL v3.0"`, mod ID `yiranexpressionlib`, mod version `1.0.1`. This is the descriptor a NeoForge 21.1 client reads.
- `META-INF/mods.toml`: same mod ID/version/license, `loaderVersion="[47,)"` (Forge 1.20.1-era loader range; unused on NeoForge but shipped upstream).
- 58 archive entries, no `fabric.mod.json`; classes are Java 17 (`major 61`); `pack.mcmeta` has `pack_format` 15 with `supported_formats` 15–34.
- No library classes were unpacked, relocated, sanitized or regenerated, and none are copied into Tactical's root packages.

## History: the copy that was shipped before this replacement

Earlier versions bundled a 62894-byte copy (SHA-256 `dbaa9f289b0e1a21ff8a0ce0b293ac05cc221e6f1383abc99663b37e1da51ab3`, git blob `7e5cf834…`) taken verbatim from `QLNPLUS/Advanced-Leveling` commit `c4b34fe38ca6008fce844af584b610b249ecadbb` ("Make YiRanExpressionLib fallback reproducible"). Reproducing its bytes from upstream source was never demonstrated while it shipped; a later independent local rebuild of the pinned commit does reproduce 51 of its 52 file entries (only `mods.toml` differs), which is a statement about bytes only — who produced that copy and why the loader range was widened remains unestablished. It was replaced so the shipped bytes are the official build again.

Comparing the two archives (both 58 entries / 52 file entries, same entry names):

| Result | Detail |
| --- | --- |
| Identical | 49 of 52 file entries, class files included |
| `META-INF/mods.toml` | one field differs: upstream `loaderVersion="[47,)"`, Advanced Leveling copy `loaderVersion="[43,)"` |
| `net/yiran/expressionlib/expr/internal/AstNode.class` | bytes differed; `javap -p -c` output identical |
| `net/yiran/expressionlib/expr/internal/Token.class` | same: bytes differed, `-p -c` output identical |
| `javap -v` for both classes | 2 differing lines each after dropping the file-path and `SHA-256 checksum` lines: the position of one entry inside **`PermittedSubclasses`** (`AstNode$BinaryAstNode`, `Token$FunctionToken`); the `InnerClasses` attribute is identical |

Correction of record: earlier revisions of this file and of `NOTICE.md` / `docs/license-audit.md` described the same comparison as "57 of 60 entries identical" and located the moved entry in `InnerClasses`. Recounting the same bytes gives **58 entries / 52 file entries and 49 identical file entries**, and a 2026-10-28 `javap` re-check shows the moved entry belongs to **`PermittedSubclasses`**; the earlier "12 differing lines" count does not reproduce either. The digest, sizes and the set of three differing entries were unchanged.

An independent rebuild of the pinned upstream commit (local JDK 21.0.5, upstream Gradle wrapper; see `evidence/upstream-rebuild-2026-10-28/`) produced a JAR that matches the official artifact in 50 of 52 file entries, and matches **this** pre-swap copy in 51 of 52 (only `mods.toml` differs). Those are byte-sequence observations only: they do not establish who built that copy, in which environment, or why the Forge loader range was widened.

## What is still open

- **Licence metadata conflict is unresolved.** Upstream's default `1.21.1-NeoForge` branch is AGPL; only the `universal` branch at `60584101e6` declares LGPL for `1.0.1`, and the JitPack POM for that build still carries an AGPL licence field. Nothing here is a licence conclusion: LGPL v3.0 section 4 conditions (notices, complete corresponding source, relinking, debugging) are recorded in `NOTICE.md` and `docs/license-audit.md`, not satisfied by this file swap.
- **The distributed binary is the official upstream build, so "corresponding source" now points at upstream commit `60584101e698d1e3f79d327b18982dec7ddb5465` instead of an unpublished variant.** That is what JitPack built from. A local rebuild from that tree was performed and matched 50 of 52 file entries, so the official bytes are **not** proven byte-for-byte reproducible here (the two classes differ only by one `PermittedSubclasses` entry position, and JitPack used JDK 21.0.2 versus 21.0.5 locally). Tactical reports that permission from the library's author to bundle the library was obtained **before** the 2026-10-28 session in which this was stated (the actual acquisition date is unknown); **no permission text, scope or evidence was supplied for this record**, so it is not verified here, is not quoted or interpreted, and does not close any item below.
- **Advanced Leveling coexistence does not hold for the current fork, and compatibility with it is not a goal of this project.** `AdvancedLeveling-1.21.1-1.0.1-fix` requests a different library range and a newer NeoForge; the isolated co-load attempt is recorded in `evidence/libswap-2026-10-28/evidence-summary.json` (`advancedLevelingCoexistence`). This swap changed neither side of the request — this project asked for `[1.0.1]` before and after — so the failure is about the two mods' version requests in that run, not proof that older Tactical or Advanced Leveling builds also fail. The earlier `[43,)` metadata variant, which widened the Forge loader range, is not part of the current 0.23.0 candidate; builds published before this swap still contain it.
