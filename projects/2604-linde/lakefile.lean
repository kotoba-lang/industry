import Lake
open Lake DSL

package «verlinde» where
  leanOptions := #[
    ⟨`pp.unicode.fun, true⟩,
    ⟨`autoImplicit, false⟩
  ]

require mathlib from git
  "https://github.com/leanprover-community/mathlib4.git" @ "v4.15.0"

@[default_target]
lean_lib «Verlinde» where
  globs := #[.andSubmodules `Verlinde]

lean_lib «VerlindeRoot» where
  roots := #[`Verlinde]
