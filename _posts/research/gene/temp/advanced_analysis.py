import pandas as pd
import numpy as np
import matplotlib.pyplot as plt
import seaborn as sns
import os

def advanced_concordance_analysis(our_gwas_path, euro_gwas_path, output_dir):
    """
    日本人GWASと欧州人GWASのデータの関連性を多角的に分析する。
    """
    try:
        output_dir = "temp/test_output"
        os.makedirs(output_dir, exist_ok=True)

        # --- データの読み込みと準備 ---
        df_our = pd.read_csv(our_gwas_path, sep='\\t', header=1)
        df_our.columns = [col.strip() for col in df_our.columns]
        df_our = df_our[['SNP', 'BETA', 'P']].rename(columns={'BETA': 'BETA_our', 'P': 'P_our'})
        df_our['SNP'] = df_our['SNP'].str.replace(r'\\n', '', regex=True).str.strip()

        df_euro = pd.read_csv(
            euro_gwas_path, sep='\\t', compression='gzip',
            usecols=['variant_id', 'beta', 'p_value']
        ).rename(columns={'variant_id': 'SNP', 'beta': 'BETA_euro', 'p_value': 'P_euro'})

        df_merged = pd.merge(df_our, df_euro, on='SNP', how='inner')
        df_merged.dropna(subset=['BETA_our', 'BETA_euro', 'P_our'], inplace=True)
        for col in ['BETA_our', 'BETA_euro', 'P_our', 'P_euro']:
            df_merged[col] = pd.to_numeric(df_merged[col], errors='coerce')
        df_merged.dropna(inplace=True)

        total_snps = len(df_merged)
        print(f"--- 高度な一致性分析 ---")
        print(f"解析対象となる共通SNP数: {total_snps}")

        # --- 1. 効果方向の一致率 (Sign Concordance Test) ---
        print("\\n--- 分析1: 効果方向の一致率 (Sign Concordance) ---")
        concordant_snps = np.sign(df_merged['BETA_our']) == np.sign(df_merged['BETA_euro'])
        sign_concordance_rate = (concordant_snps.sum() / total_snps) * 100
        print(f"効果の方向が一致したSNPの割合: {sign_concordance_rate:.2f}%")
        if sign_concordance_rate > 55:
            print("=> 50%を優位に超えており、両集団で弱いながらも共通の遺伝的影響があることを示唆します。")
        else:
            print("=> ほぼ50%であり、効果方向のランダムな一致と区別がつきません。")

        # --- 2. 効果量(BETA)の相関プロット ---
        print("\\n--- 分析2: 効果量(BETA)の相関プロット ---")
        correlation = df_merged['BETA_our'].corr(df_merged['BETA_euro'])
        print(f"日本人と欧州人のBETA値の相関係数: {correlation:.4f}")

        plt.figure(figsize=(8, 8))
        sample_size = min(50000, total_snps)
        sample_df = df_merged.sample(n=sample_size, random_state=1)
        sns.regplot(data=sample_df, x='BETA_our', y='BETA_euro',
                    scatter_kws={'alpha':0.1}, line_kws={'color': 'red'})
        plt.xlabel("Effect Size (BETA) in Japanese GWAS")
        plt.ylabel("Effect Size (BETA) in European GWAS (Savage et al. 2018)")
        plt.title(f"Effect Size Correlation (r = {correlation:.3f})")
        plt.grid(True)
        plot_path = os.path.join(output_dir, "beta_correlation_advanced.png")
        plt.savefig(plot_path)
        print(f"効果量の相関プロットを '{plot_path}' に保存しました。")
        if abs(correlation) > 0.1:
            print("=> 相関は低いものの、完全な無相関ではなく、何らかの弱い関連がある可能性を示しています。")
        else:
            print("=> 相関はほぼ0に近く、両集団の効果量に系統的な関連は見られません。")

        # --- 3. P値の閾値を緩和した再現率分析 ---
        print("\\n--- 分析3: P値の閾値を変えた場合の再現率分析 ---")
        thresholds = [1e-5, 1e-4, 1e-3, 1e-2, 0.05, 0.1, 0.5, 1.0]
        results_list = []
        for threshold in thresholds:
            top_our = df_merged[df_merged['P_our'] < threshold]
            if not top_our.empty:
                replicated = top_our[top_our['P_euro'] < 0.05]
                rate = len(replicated) / len(top_our) * 100
            else:
                rate = 0
            results_list.append({'Threshold_Japanese_P_value': f"< {threshold}", 'Replication_Rate_in_European (%)': rate})
            print(f"  日本人GWASのP値 < {threshold:g} の時、欧州人での再現率 (P < 0.05) は: {rate:.2f}%")
        
        pd.DataFrame(results_list).to_csv(os.path.join(output_dir, 'replication_by_threshold.csv'), index=False)
        print(f"閾値ごとの再現率データを 'temp/test_output/replication_by_threshold.csv' に保存しました。")

    except FileNotFoundError as e:
        print(f"エラー: ファイルが見つかりません。パスを確認してください: {e}")
    except Exception as e:
        print(f"解析中にエラーが発生しました: {e}")

if __name__ == "__main__":
    advanced_concordance_analysis(
        "manuscript/data/gwas-data.tsv",
        "manuscript/data/european_gwas_data.tsv.gz",
        "temp/test_output"
    ) 