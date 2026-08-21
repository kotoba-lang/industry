"""Independent East Asian LD clumping engine.

Implemented from the M03/M04 contract text; no PLINK binary is used and no
project clumping implementation was consulted. Only the PGEN *file format*
reader (pgenlib) is third-party - the clumping algorithm itself is here.

Definition used (M03):
  * unphased East Asian LD, so r is the Pearson correlation of ALT-allele
    dosages in the EAS reference panel and r2 = r**2
  * greedy: walk candidate variants in ascending association P; the first
    unassigned variant becomes a lead; every still-unassigned variant within
    +/- window_kb of the lead with r2 >= r2_threshold is assigned to it as a
    member; a clump is the lead plus its assigned members
  * exact P ties are broken by chromosome, then position, then the normalized
    variant key
"""
from __future__ import annotations

import numpy as np
import pandas as pd
import pgenlib

from common import ds


def load_pvar() -> pd.DataFrame:
    pv = pd.read_csv(
        ds("DS_EAS_LD_PVAR"), sep="\t", comment="#", header=None,
        names=["CHROM", "POS", "ID", "REF", "ALT"], usecols=[0, 1, 2, 3, 4],
        dtype={"CHROM": str, "ID": str, "REF": str, "ALT": str},
    )
    pv["POS"] = pv["POS"].astype(np.int64)
    pv["ref_index"] = np.arange(len(pv), dtype=np.int64)
    pv["key"] = pv["CHROM"].astype(str) + ":" + pv["POS"].astype(str)
    return pv


class GenotypeSource:
    """Reads ALT dosages out of the PGEN, one chromosome slice at a time."""

    def __init__(self) -> None:
        self._reader = pgenlib.PgenReader(str(ds("DS_EAS_LD_PGEN")).encode())
        self.sample_ct = self._reader.get_raw_sample_ct()
        self.variant_ct = self._reader.get_variant_ct()

    def standardized(self, ref_indices: np.ndarray) -> np.ndarray:
        """Return a (len(ref_indices) x sample_ct) float32 matrix whose rows are
        mean-centred and scaled to unit L2 norm, so a row-row dot product is r.

        Missing calls (-9) are mean-imputed on the observed calls of that
        variant. A monomorphic / all-missing row gets a zero row, which yields
        r2 = 0 against everything and therefore never clumps.
        """
        idx = np.ascontiguousarray(np.asarray(ref_indices, dtype=np.uint32))
        buf = np.empty((idx.size, self.sample_ct), dtype=np.int8)
        self._reader.read_list(idx, buf)
        g = buf.astype(np.float32)
        miss = g < 0
        if miss.any():
            g[miss] = np.nan
            mean = np.nanmean(g, axis=1)
            mean = np.where(np.isfinite(mean), mean, 0.0).astype(np.float32)
            g = np.where(miss, mean[:, None], g)
        else:
            mean = g.mean(axis=1)
        g -= g.mean(axis=1, keepdims=True)
        norm = np.sqrt((g * g).sum(axis=1))
        norm[norm == 0] = np.inf
        g /= norm[:, None]
        return g


def clump(
    variants: pd.DataFrame,
    r2_threshold: float,
    window_kb: float,
    p_col: str,
    genotypes: GenotypeSource,
    collect_members: bool = False,
) -> dict:
    """Greedy LD clumping.

    `variants` needs columns: chrom_int, pos, ref_index, variant_key, <p_col>.
    Rows must already be restricted to variants that have a usable P and are
    present in the LD reference.
    """
    window_bp = int(round(window_kb * 1000))
    leads: list[dict] = []
    n_assigned_members = 0

    for chrom, sub in variants.groupby("chrom_int", sort=True):
        sub = sub.sort_values("pos", kind="mergesort").reset_index(drop=True)
        pos = sub["pos"].to_numpy(dtype=np.int64)
        ref_idx = sub["ref_index"].to_numpy(dtype=np.int64)
        pvals = sub[p_col].to_numpy(dtype=float)
        keys = sub["variant_key"].to_numpy(dtype=object)
        n = len(sub)

        g = genotypes.standardized(ref_idx)

        # Deterministic association ordering: P, then chromosome, then
        # position, then normalized variant key. Within one chromosome the
        # chromosome term is constant, so (P, pos, key) is the effective order.
        order = sorted(range(n), key=lambda i: (pvals[i], int(pos[i]), keys[i]))

        assigned = np.zeros(n, dtype=bool)
        for i in order:
            if assigned[i]:
                continue
            lo = int(np.searchsorted(pos, pos[i] - window_bp, side="left"))
            hi = int(np.searchsorted(pos, pos[i] + window_bp, side="right"))
            cand = np.arange(lo, hi)
            cand = cand[~assigned[lo:hi]]
            cand = cand[cand != i]
            members: list[int] = []
            if cand.size:
                r = g[cand] @ g[i]
                hit = cand[(r * r) >= r2_threshold]
                if hit.size:
                    assigned[hit] = True
                    members = hit.tolist()
            assigned[i] = True
            n_assigned_members += len(members)
            rec = {
                "chrom": int(chrom),
                "pos": int(pos[i]),
                "ref_index": int(ref_idx[i]),
                "variant_key": keys[i],
                "p": float(pvals[i]),
                "n_variants_in_clump": 1 + len(members),
            }
            if collect_members:
                mem_sorted = sorted(members, key=lambda j: int(pos[j]))
                rec["member_positions"] = [int(pos[j]) for j in mem_sorted]
                rec["member_keys"] = [keys[j] for j in mem_sorted]
                rec["span_start"] = int(min([pos[i]] + [pos[j] for j in members]))
                rec["span_end"] = int(max([pos[i]] + [pos[j] for j in members]))
            leads.append(rec)

    return {
        "n_leads": len(leads),
        "n_members": n_assigned_members,
        "n_input": len(variants),
        "leads": leads,
    }
