# phone-engine: upstream

- Project: NewBlackbox by ALEX5402 (BlackBox lineage: niunaijun/BlackBox, Apache-2.0)
- Repository: https://github.com/ALEX5402/NewBlackbox
- Commit: 89b59836c66f173756a4ae258cf379a957649820 (2026-07-19, "Merge pull request #54 from ALEX5402/dev")
- License: Apache License 2.0. `LICENSE` here is upstream's file at that commit, unchanged (verified 23 Oct 2026 against
  the Apache 2.0 text; the only difference is one leading blank line).
- Taken: `Bcore/` (the engine, now this module's `src/`), `black-reflection/` and `compiler/` (its reflection
  annotations and their annotation processor). Not taken: upstream's demo `app/`, `assets/` (README gif), docs.
- Bundled third-party code inside the engine (all permissive):
  - Dobby (jmpews/Dobby, Apache-2.0): prebuilt `src/main/cpp/Dobby/*/libdobby.a` + `dobby.h`, as shipped upstream.
  - xDL (hexhacking/xDL, MIT): `src/main/cpp/xdl/`.
  - FreeReflection (tiann/FreeReflection, MIT): Maven dependency `com.github.tiann:FreeReflection:3.2.2`.
  - `src/main/assets/junit.jar` (JUnit 3/4 dex, Eclipse Public License 1.0 for JUnit, BSD for its bundled Hamcrest):
    upstream ships it for guest apps that reference `junit.framework` at run time. Unmodified.
- No GPL code. VirtualApp / VirtualXposed are not used.
- Blaze's changes: see `CHANGES.md` (diff against the first "vendor ... unmodified" commit on branch feat/blaze-phone).
