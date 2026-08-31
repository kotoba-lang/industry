# Candidate ledger

Emit one record per non-overlapping exact path. Sort proposed candidates by
measured bytes, largest first.

Required fields:

```edn
{:path "/absolute/canonical/path"
 :bytes 0
 :observed-at "RFC3339"
 :age-bucket :lt-1d
 :classification :unverified
 :open-files :unknown
 :git-evidence []
 :producer nil
 :regeneration-evidence nil
 :recovery-method nil
 :proposed-action :none
 :reason "bounded observed fact"}
```

Allowed classifications are `:reclaimable`, `:review-required`, `:preserve`,
and `:unverified`. Allowed proposal actions are `:none`, `:fixed-cleanup`,
`:trash-after-selection`, and `:native-manager-after-selection`.

Keep the run receipt separate:

```edn
{:schema :disk-space-audit/v1
 :capacity-before-bytes 0
 :candidates []
 :selected-paths []
 :mutation-receipts []
 :capacity-after-bytes nil}
```

`selected-paths` is empty until an authority actually selects targets.
`mutation-receipts` is empty for an audit-only run. Fill
`capacity-after-bytes` only from a post-mutation `df` observation.
