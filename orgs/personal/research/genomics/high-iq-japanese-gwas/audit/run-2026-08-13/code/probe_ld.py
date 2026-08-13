"""Calibration probe: does the independent clumping engine reproduce the
declared lead counts of DS_FIG3_PANEL_B / DS_CROSSPOP_PRIMARY?

Checks the two defensible inclusion rules for variants that are absent from
the EAS reference panel, so the choice is made on evidence rather than guessed.
"""
from __future__ import annotations

import time

import numpy as np
import pandas as pd

from common import ds, normalized_variant_key
from ld_clump import GenotypeSource, clump, load_pvar

TARGET = {"PRIMARY_R010_KB500": 86681}


def build() -> pd.DataFrame:
    m = pd.read_csv(ds("DS_CROSSPOP_MERGED_GENOMEWIDE"), sep="\t")
    pv = load_pvar()
    m["key"] = m["CHR"].astype(str) + ":" + m["BP"].astype(str)
    m = m.merge(pv[["key", "ref_index"]], on="key", how="left")
    m["variant_key"] = [
        normalized_variant_key(c, b, a1, a2)
        for c, b, a1, a2 in zip(m["CHR"], m["BP"], m["A1"], m["A2"])
    ]
    m = m.rename(columns={"CHR": "chrom_int", "BP": "pos"})
    return m


def main() -> None:
    m = build()
    gs = GenotypeSource()
    print(f"pgen: {gs.variant_ct} variants x {gs.sample_ct} samples")
    print(f"merged rows            : {len(m)}")
    has_eur = m["European_P"].notna() & np.isfinite(m["European_P"])
    print(f"with European_P        : {int(has_eur.sum())}")
    in_ref = m["ref_index"].notna()
    print(f"in EAS reference       : {int(in_ref.sum())}")
    both = has_eur & in_ref
    print(f"both                   : {int(both.sum())}")
    print(f"European_P but no ref  : {int((has_eur & ~in_ref).sum())}")
    print(f"TARGET n_leads primary : {TARGET['PRIMARY_R010_KB500']}")
    print()

    sub = m.loc[both].copy()
    sub["ref_index"] = sub["ref_index"].astype(np.int64)
    t0 = time.time()
    res = clump(sub, r2_threshold=0.10, window_kb=500,
                p_col="European_P", genotypes=gs)
    dt = time.time() - t0
    n_ref_only = res["n_leads"]
    print(f"RULE A (reference-present only): n_leads={n_ref_only}  "
          f"members={res['n_members']}  [{dt:.1f}s]")
    print(f"   diff vs target: {n_ref_only - TARGET['PRIMARY_R010_KB500']}")

    n_orphan = int((has_eur & ~in_ref).sum())
    print(f"RULE B (+ non-reference variants as singleton leads): "
          f"n_leads={n_ref_only + n_orphan}")
    print(f"   diff vs target: {n_ref_only + n_orphan - TARGET['PRIMARY_R010_KB500']}")


if __name__ == "__main__":
    main()
