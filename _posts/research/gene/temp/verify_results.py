import pandas as pd
import numpy as np

def verify_cross_population_results(file_path):
    """
    GWAS結果のCSVファイルを読み込み、論文の主張を検証します。
    """
    try:
        # データの読み込み（ヘッダーを2行目とし、列名を整形）
        df = pd.read_csv(file_path, header=1)
        df = df.rename(columns={
            'P': 'P_our',
            'P.1': 'P_european'
        })

        # データ型の変換（変換できない値はNaNにする）
        df['P_our'] = pd.to_numeric(df['P_our'], errors='coerce')
        df['P_european'] = pd.to_numeric(df['P_european'], errors='coerce')

        # 解析対象のデータをdropnaで欠損値除去
        df_clean = df.dropna(subset=['P_our', 'P_european'])

        # --- 論文の主張を検証 ---
        # "Of the top 50 Japanese-associated variants (P < 1×10⁻⁴), 
        #  only 12 (24%) showed nominal associations (P < 0.05) in European populations"

        # 1. 日本人トップ関連変異を抽出 (P < 1e-4)
        top_japanese_variants = df_clean[df_clean['P_our'] < 1e-4].copy()
        
        # 論文では "top 50" と言及されているが、実際のデータ数を使用する
        num_top_variants = len(top_japanese_variants)

        if num_top_variants == 0:
            print("検証対象データなし: 日本人のP値が1e-4未満の変異が見つかりませんでした。")
            return

        # 2. その中で、欧米人で名目的な有意性を持つものをカウント (P < 0.05)
        european_significant = top_japanese_variants[top_japanese_variants['P_european'] < 0.05]
        num_european_significant = len(european_significant)

        percentage = (num_european_significant / num_top_variants) * 100 if num_top_variants > 0 else 0

        # --- 検証結果の出力 ---
        print("--- 集団間比較の検証結果 ---")
        print(f"論文の主張: 日本人のトップ関連変異(P < 1e-4)のうち、24%が欧州人でも有意(P < 0.05)である。")
        print("-" * 30)
        print(f"データ中の該当する変異の総数 (日本人 P < 1e-4): {num_top_variants}個")
        print(f"うち、欧州人でも有意 (P < 0.05) な変異の数: {num_european_significant}個")
        print(f"割合: {percentage:.2f}%")
        
        # 論文の "top 50" という記述との比較
        if num_top_variants < 45 or num_top_variants > 55: # 50から少し離れていたら警告
             print(f"\n警告: データ中のトップ変異の数({num_top_variants}個)が、論文の言う「50個」と乖離しています。")

        # 結論
        # 論文の主張(24%)とデータの割合を比較
        if abs(percentage - 24) < 5: # 5%ポイント程度の差は許容
             print("\n結論: 論文の主張はデータと「おおむね一致」します。")
        else:
             print("\n結論: 論文の主張はデータと「一致しません」。")

    except Exception as e:
        print(f"エラーが発生しました: {e}")

if __name__ == "__main__":
    verify_cross_population_results("temp/test_output/GWAS結果.csv") 