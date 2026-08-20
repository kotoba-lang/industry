"""Read-only KPI collectors for the kawasakijun Living System.

Each collector exposes a `collect() -> dict` function. The dispatcher
merges all results into a single state dict.

Adding a new collector:
  1. Drop a file in this directory
  2. Implement `def collect() -> dict: ...`
  3. Add the module name to `ENABLED` below
"""

ENABLED = [
    "git",
    # "gmail",        # TODO: implement via stored OAuth or IMAP
    # "calendar",     # TODO: implement via CalDAV
    # "animeka",      # TODO: RunPod endpoint health + queue depth
    # "moneyforward", # TODO: MoneyForward Cloud API
    # "photos",       # TODO: ~/Pictures/Photos Library.photoslibrary (SQLite, gated)
]


def collect_all() -> dict:
    import importlib
    merged: dict = {}
    for name in ENABLED:
        try:
            mod = importlib.import_module(f"kawasakijun.living.collectors.{name}")
            merged.update(mod.collect())
        except Exception as e:  # noqa: BLE001
            merged[f"_error.{name}"] = str(e)
    return merged
