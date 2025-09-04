#!/usr/bin/env python3
"""
Figure Quality Check Script
生成された図の品質と内容をチェックします
"""

import pandas as pd
import numpy as np
from pathlib import Path
from PIL import Image
import matplotlib.pyplot as plt
import matplotlib.image as mpimg

def check_figure_quality():
    """生成された図の品質をチェック"""
    output_dir = Path('./output')
    
    print("🔍 Figure Quality Check")
    print("=" * 50)
    
    # 新しく生成された図をチェック
    figures_to_check = [
        'Figure_Supplementary_Pathway_Enrichment.png',
        'Figure5_Polygenic_Score.png'
    ]
    
    for fig_name in figures_to_check:
        fig_path = output_dir / fig_name
        
        if fig_path.exists():
            print(f"\n📊 {fig_name}")
            
            # 画像情報を取得
            with Image.open(fig_path) as img:
                width, height = img.size
                mode = img.mode
                
                print(f"  📐 Size: {width} x {height} pixels")
                print(f"  🎨 Mode: {mode}")
                
                # DPI情報（利用可能な場合）
                dpi = img.info.get('dpi', 'Unknown')
                print(f"  🔍 DPI: {dpi}")
                
                # ファイルサイズ
                file_size = fig_path.stat().st_size / 1024  # KB
                print(f"  💾 File size: {file_size:.1f} KB")
                
                # 品質チェック
                if width >= 800 and height >= 600:
                    print("  ✅ Resolution: Good")
                else:
                    print("  ⚠️ Resolution: May be too low for publication")
                
                if file_size > 50:  # 50KB以上
                    print("  ✅ File size: Appropriate")
                else:
                    print("  ⚠️ File size: May be too compressed")
        else:
            print(f"❌ {fig_name}: File not found")
    
    print("\n" + "=" * 50)

def check_data_consistency():
    """データの整合性をチェック"""
    output_dir = Path('./output')
    
    print("📈 Data Consistency Check")
    print("=" * 50)
    
    # パスウェイ解析データをチェック
    pathway_file = output_dir / 'pathway_enrichment_results.csv'
    if pathway_file.exists():
        df = pd.read_csv(pathway_file)
        print(f"\n🧬 Pathway Enrichment Results:")
        print(f"  📊 Number of pathways: {len(df)}")
        print(f"  🎯 Significant pathways (p<0.05): {(df['p_value'] < 0.05).sum()}")
        print(f"  📈 Max enrichment: {df['enrichment'].max():.2f}")
        print(f"  📉 Min p-value: {df['p_value'].min():.3f}")
        
        # 論理的整合性チェック
        if all(df['enrichment'] > 0):
            print("  ✅ Enrichment values: All positive")
        else:
            print("  ⚠️ Enrichment values: Contains non-positive values")
            
        if all((df['p_value'] >= 0) & (df['p_value'] <= 1)):
            print("  ✅ P-values: All in valid range [0,1]")
        else:
            print("  ⚠️ P-values: Contains invalid values")
            
        if all(df['significant_snps'] <= df['total_snps']):
            print("  ✅ SNP counts: Logical consistency maintained")
        else:
            print("  ⚠️ SNP counts: Significant > Total (inconsistent)")
    
    # PGS解析データをチェック
    pgs_file = output_dir / 'pgs_analysis_results.csv'
    if pgs_file.exists():
        df = pd.read_csv(pgs_file)
        print(f"\n🧮 PGS Analysis Results:")
        print(f"  📊 Number of populations: {len(df)}")
        
        if 'r2' in df.columns:
            print(f"  📈 Max R²: {df['r2'].max():.3f}")
            print(f"  📉 Min R²: {df['r2'].min():.3f}")
            
            # 転移性の計算をチェック
            if len(df) >= 2:
                original = df['r2'].iloc[0]
                transferred = df['r2'].iloc[1]
                reduction = (original - transferred) / original * 100
                print(f"  📊 Transferability reduction: {reduction:.1f}%")
                
                if 0 <= reduction <= 100:
                    print("  ✅ Transferability: Realistic reduction")
                else:
                    print("  ⚠️ Transferability: Unrealistic values")
            
            if all((df['r2'] >= 0) & (df['r2'] <= 1)):
                print("  ✅ R² values: All in valid range [0,1]")
            else:
                print("  ⚠️ R² values: Contains invalid values")
    
    print("\n" + "=" * 50)

def display_figure_previews():
    """図のプレビューを表示（テキストベース）"""
    output_dir = Path('./output')
    
    print("🖼️ Figure Content Preview")
    print("=" * 50)
    
    # データから図の内容を推測
    pathway_file = output_dir / 'pathway_enrichment_results.csv'
    if pathway_file.exists():
        df = pd.read_csv(pathway_file)
        print(f"\n📊 Pathway Enrichment Figure should show:")
        print(f"  • {len(df)} pathways")
        print(f"  • Enrichment range: {df['enrichment'].min():.1f} - {df['enrichment'].max():.1f}")
        print(f"  • Most significant: {df.loc[df['p_value'].idxmin(), 'pathway']} (p={df['p_value'].min():.3f})")
        print(f"  • Highest enrichment: {df.loc[df['enrichment'].idxmax(), 'pathway']} ({df['enrichment'].max():.1f}x)")
    
    pgs_file = output_dir / 'pgs_analysis_results.csv'
    if pgs_file.exists():
        df = pd.read_csv(pgs_file)
        print(f"\n📈 PGS Figure should show:")
        for idx, row in df.iterrows():
            print(f"  • {row['population']}: R² = {row['r2']:.3f}")
        
        if len(df) >= 2:
            reduction = (df['r2'].iloc[0] - df['r2'].iloc[1]) / df['r2'].iloc[0] * 100
            print(f"  • Clear visualization of {reduction:.1f}% reduction")
    
    print("\n" + "=" * 50)

def main():
    """メイン実行関数"""
    print("🔍 GWAS Figure Quality & Consistency Check")
    print("=" * 60)
    
    check_figure_quality()
    check_data_consistency()
    display_figure_previews()
    
    print("\n✅ Figure check completed!")
    print("📝 Review the output above for any warnings or issues.")

if __name__ == '__main__':
    main() 