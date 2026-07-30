# itonami fleet deployment

The three scripts that took the fleet from 9 callable actors to 67. Design
record: `90-docs/adr/2607300200-itonami-fleet-callable-67-actors.edn`.

```
emit-iso3166-edge.py   generate an actor's Worker edge (store adapter, worker,
                       5 config files) — iso3166 market-entry family only
ship.sh                build -> deploy -> verify, ONE actor at a time
add-dispatch-endpoint.py  record the deployed address in the blueprint
```

## Order, and why each step is separate

```bash
# 1. select only bare three-letter suffixes. 35 of the 223 iso3166 repos are a
#    country code plus an agency or subject (jpn-meti, ind-clean-air) with
#    their own namespace, and the generator does not apply to them. A naive
#    `sed 's/.*-//'` yields "air" as a country code — it failed loudly once,
#    and could as easily have selected the wrong repositories in silence.
ls -d orgs/cloud-itonami/cloud-itonami-iso3166-* \
  | awk -F'iso3166-' '{print $2}' | awk 'length($0)==3'

# 2. worktrees in a west-sibling layout — the deps are :local/root, so a
#    worktree elsewhere cannot resolve them.
git -C <repo> worktree add -b <branch> /tmp/ws/orgs/cloud-itonami/<repo> <remote>/main

# 3. generate
python3 scripts/itonami-fleet/emit-iso3166-edge.py \
  orgs/cloud-itonami/cloud-itonami-iso3166-ago/src/marketentry/edge/worker.cljs \
  /tmp/ws/orgs/cloud-itonami/cloud-itonami-iso3166-*

# 4. share node_modules and add :cache-root to each shadow-cljs.edn
#    (69s per build instead of ~180s)

# 5. ship
scripts/itonami-fleet/ship.sh /tmp/ws/orgs/cloud-itonami <cc> <cc> ...

# 6. record the address — the step most easily forgotten. Thirteen actors were
#    once live and invisible to `fleet_search callable=true` because of it.
python3 scripts/itonami-fleet/add-dispatch-endpoint.py --apply cloud-itonami-iso3166-<cc>
```

## Two things not to change without measuring again

**No bundle reuse.** `governor.cljc` differs per country — 184 distinct code
shapes across the family even with strings stripped — and it compiles into the
bundle. Building once and patching the service name would ship one country's
compliance rules to another.

**The shared `:cache-root` is safe only while builds are serialized.** The repo
resource-guard does that; two concurrent writers would corrupt it. It was also
verified not to leak one country into another's bundle, because a cache keyed
on namespace names could in principle do exactly that: four builds gave four
sizes and four digests, two differing in 159,127 bytes. Checking with keywords
or docstrings proves nothing — neither survives `:advanced`.
