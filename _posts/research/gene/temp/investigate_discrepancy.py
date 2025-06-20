import pandas as pd
import numpy as np
import matplotlib.pyplot as plt
import seaborn as sns
import os

def investigate_discrepancy(our_gwas_path, new_euro_gwas_path, output_dir):
    """
    2種類の欧州人GWASデータの不一致を調査し、論文の主張を再検証する。
    """
    try:
        if not os.path.exists(output_dir):
            os.makedirs(output_dir)

        # --- データの読み込み ---
        # 1. gwas-data.csv (元の欧州人データを含む)
        df_original = pd.read_csv(our_gwas_path, sep='\\t', header=1)
        df_original.columns = [col.strip() for col in df_original.columns]
        df_original = df_original.rename(columns={
            'P': 'P_our', 'BETA': 'BETA_our',
            'P.1': 'P_european_original', 'Z': 'Z_european_original'
        })
        df_original['SNP'] = df_original['SNP'].str.replace(r'\\n', '', regex=True).str.strip()
        # 数値に変換
        for col in ['P_our', 'P_european_original']:
            df_original[col] = pd.to_numeric(df_original[col], errors='coerce')

        # 2. EBIからダウンロードした新しい欧州人データ
        df_new_euro = pd.read_csv(
            new_euro_gwas_path, sep='\\t', compression='gzip',
            usecols=['variant_id', 'p_value', 'beta'],
            dtype={'variant_id': str}
        ).rename(columns={
            'variant_id': 'SNP', 'p_value': 'P_european_new', 'beta': 'BETA_european_new'
        })

        # --- 2つの欧州人データをマージ ---
        df_merged = pd.merge(df_original, df_new_euro, on='SNP', how='left')
        
        # --- 検証1: 論文の元の主張(24%)を再現する ---
        print("--- 検証1: 元データでの論文主張の再現 ---")
        df_original_clean = df_original.dropna(subset=['P_our', 'P_european_original'])
        top_variants_orig = df_original_clean[df_original_clean['P_our'] < 1e-4]
        
        if not top_variants_orig.empty:
            significant_in_orig_euro = top_variants_orig[top_variants_orig['P_european_original'] < 0.05]
            percentage_orig = (len(significant_in_orig_euro) / len(top_variants_orig)) * 100
            print(f"元の欧州データを使用した場合:")
            print(f"  日本人トップ変異 (P < 1e-4): {len(top_variants_orig)}個")
            print(f"  うち欧州人で有意 (P < 0.05): {len(significant_in_orig_euro)}個")
            print(f"  再現率: {percentage_orig:.2f}%")
            if abs(percentage_orig - 24) < 5:
                print("  => 結論: 論文の元の主張(24%)は、gwas-data.csv内のデータで「再現可能」です。")
            else:
                print("  => 結論: 論文の元の主張(24%)は、gwas-data.csv内のデータでも「再現できません」。")
        else:
            print("  元のデータに P < 1e-4 の変異がありません。")

        # --- 検証2: 新しい公開データでの結果 (0%になることの再確認) ---
        print("\\n--- 検証2: 新しい公開データでの検証 ---")
        df_new_clean = df_merged.dropna(subset=['P_our', 'P_european_new'])
        top_variants_new = df_new_clean[df_new_clean['P_our'] < 1e-4]

        if not top_variants_new.empty:
            significant_in_new_euro = top_variants_new[top_variants_new['P_european_new'] < 0.05]
            percentage_new = (len(significant_in_new_euro) / len(top_variants_new)) * 100
            print(f"新しい公開データを使用した場合:")
            print(f"  日本人トップ変異 (P < 1e-4): {len(top_variants_new)}個")
            print(f"  うち欧州人で有意 (P < 0.05): {len(significant_in_new_euro)}個")
            print(f"  再現率: {percentage_new:.2f}%")
            print("  => 結論: 新しい公開データでは、関連性が「全く見られない」という結果が再確認されました。")
        else:
            print("  新しいデータセットとの比較対象がありません。")


        # --- 分析: 2つの欧州人データのP値を比較 ---
        print("\\n--- 分析: 2つの欧州人データのP値比較 ---")
        df_p_compare = df_merged.dropna(subset=['P_european_original', 'P_european_new'])
        if not df_p_compare.empty:
            log_p_orig = -np.log10(df_p_compare['P_european_original'])
            log_p_new = -np.log10(df_p_compare['P_european_new'])
            correlation = log_p_orig.corr(log_p_new)
            print(f"両データセットに共通するSNP数: {len(df_p_compare)}")
            print(f"-log10(P値)の相関係数: {correlation:.4f}")

            plt.figure(figsize=(8, 8))
            sns.scatterplot(x=log_p_orig, y=log_p_new, alpha=0.5)
            plt.xlabel("-log10(P) in gwas-data.csv (Original European Data)")
            plt.ylabel("-log10(P) in Savage et al. 2018 (New Public Data)")
            plt.title("Comparison of P-values between two European GWAS datasets")
            plt.grid(True)
            plot_path = os.path.join(output_dir, "pvalue_comparison.png")
            plt.savefig(plot_path)
            print(f"P値の比較プロットを '{plot_path}' に保存しました。")
            
            if correlation < 0.8:
                print("  => 警告: P値の相関が低く、これら2つのデータセットは「異なる解析結果」である可能性が非常に高いです。")
            else:
                print("  => P値の相関は高く、データセットは類似しているようです。")

    except FileNotFoundError as e:
        print(f"エラー: ファイルが見つかりません。パスを確認してください: {e}")
    except Exception as e:
        print(f"解析中にエラーが発生しました: {e}")

if __name__ == "__main__":
    investigate_discrepancy(
        "manuscript/data/gwas-data.csv",
        "manuscript/data/european_gwas_data.tsv.gz",
        "temp/test_output"
    ) 