#!/usr/bin/env python3
"""
Comprehensive GWAS Analysis Pipeline - Refactored to use a single unified Parquet file.

This script now reads a pre-processed, unified Parquet file as its single source of truth,
ensuring consistency and reliability for all downstream analysis and figure generation.
"""

import pandas as pd
import numpy as np
import sys
from pathlib import Path
import warnings

warnings.filterwarnings('ignore')

# プロジェクトルートを追加
sys.path.append(str(Path(__file__).parent.parent))

from generate_figures import GWASFigureGenerator

def main():
    """Main function to run the simplified analysis pipeline."""
    print("🚀 Starting Simplified and Refactored GWAS Analysis Pipeline...")
    
    output_dir = Path("output")
    unified_file_path = output_dir / "unified_gwas_summary.parquet"
    
    if not unified_file_path.exists():
        print(f"❌ Unified summary file not found at {unified_file_path}")
        print("Please run `create_unified_summary.py` first.")
        return

    print(f"📊 Loading unified data from {unified_file_path}")
    df = pd.read_parquet(unified_file_path)

    # データを日本人用と欧州人用に分割
    # Manhattanプロット用に、日本人データには染色体位置情報などが必要
    jp_df = df[['SNP', 'CHR', 'BP', 'P_jp', 'Z_jp', 'BETA_jp', 'A1_jp', 'A2_jp']].copy()
    jp_df.rename(columns={
        'P_jp': 'P', 'Z_jp': 'Z', 'BETA_jp': 'BETA', 'A1_jp': 'A1', 'A2_jp': 'A2'
    }, inplace=True)

    # 欧州人データは比較用
    eu_df = df[['SNP', 'P_eu', 'Z_eu', 'BETA_eu']].copy()
    eu_df.rename(columns={
        'P_eu': 'P', 'Z_eu': 'Z', 'BETA_eu': 'BETA'
    }, inplace=True)
    
    # Manhattanプロットのための累積位置計算（元々のpreprocess_dataから移植）
    print("🔧 Preprocessing data for plotting...")
    jp_df['neglog10p'] = -np.log10(jp_df['P'].replace(0, 1e-300))
    jp_df = jp_df.sort_values(['CHR', 'BP'])
    chr_lengths = jp_df.groupby('CHR')['BP'].max()
    chr_starts = chr_lengths.cumsum() - chr_lengths
    jp_df['pos_cum'] = 0
    for chr_id in chr_starts.index:
        mask = jp_df['CHR'] == chr_id
        jp_df.loc[mask, 'pos_cum'] = chr_starts[chr_id] + jp_df.loc[mask, 'BP']
    
    print("✅ Data ready for figure generation.")

    # 図表生成
    print("\n7️⃣ Publication Figures")
    fig_generator = GWASFigureGenerator(
        primary_df=jp_df,
        comparison_df=eu_df,
        primary_trait_name='Japanese High-IQ',
        comparison_trait_name='European Intelligence'
    )
    fig_generator.generate_all_figures(output_dir)

    # 他の解析（テーブル作成など）もこのdfから行う
    print("\n📋 Generating Summary Table...")
    top_variants = jp_df.sort_values('P').head(20)
    top_variants_path = output_dir / "Table1_Top_Variants_Japanese.csv"
    top_variants.to_csv(top_variants_path, index=False)
    print(f"✅ Top variants table saved to {top_variants_path}")

    print("\n🎉 Complete analysis pipeline finished!")
    print(f"📁 All results saved in: {output_dir}")

if __name__ == "__main__":
    main() 