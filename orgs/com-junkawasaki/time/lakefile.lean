import Lake
open Lake DSL

package "time" where
  version := v!"0.1.0"

require mathlib from git
  "https://github.com/leanprover-community/mathlib4.git" @ "v4.23.0"

require «incidence-theory» from git
  "https://github.com/com-junkawasaki/inc.git" @
    "c8389955aadf0dd19bd2cea6ced663eaade4ee46" / "incidence-theory"

lean_lib "RelationalTime" where

@[default_target]
lean_exe "time" where
  root := `Main
  supportInterpreter := true
