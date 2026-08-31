# macOS disk map

Use this sequence to explain pressure without traversing every repository.

## Capacity first

- `/System/Volumes/Data` is the writable data-volume view. Use `df -k` there
  for current usable bytes.
- `diskutil apfs list` explains container and volume allocation. VM/Preboot
  volume use is real, but directory tools can double-count clones or firmlinks.
- Do not add APFS volume totals to `/System/Volumes/Data` directory totals as
  if they were independent.

## Targeted map

Start with fixed major areas: `/Users`, `/private`, `/opt`, `/Applications`,
`/Library`, `/System/Volumes/VM`, `/System/Volumes/Preboot`, and any installer
staging directory visible at the data-volume root. Measure the largest one,
then descend one level. Stop descending when the next decision is already
clear or the path is a preserved class.

For `/private`, inspect `/private/tmp`, `/private/var/folders`,
`/private/var/db`, `/private/var/vm`, and logs separately. Treat swapfiles and
sleep images as OS-managed. Never remove them directly.

For `/opt`, distinguish installed Homebrew Cellar/Caskroom/toolchains from
download caches. Installed packages are not cache candidates merely because
they are large.

For `/Library/Developer`, distinguish installed runtimes/toolchains from exact
regenerable caches. Simulator runtimes must be managed through `simctl`, not
filesystem deletion.

## Temp areas

Age-bucket top-level temp entries before examining names. Large recent temp
content can be active work. Audit an exact candidate for open files and Git
markers, then establish how it can be regenerated or recovered. A directory
that contains nested repositories, a dirty tree, a release artifact, or an
unknown producer is `preserve` or `unverified`, not an automatic cleanup.

## Classification

- `reclaimable`: exact reviewed class, no open files, no repository/user-data
  evidence, and regeneration or recovery is established.
- `review-required`: likely reclaimable, but deleting it discards a download,
  build, model, release, or other expensive artifact requiring selection.
- `preserve`: protected class or evidence of active/unique work.
- `unverified`: permission, provenance, recovery, or activity could not be
  established. Lack of evidence never upgrades this to reclaimable.
