import pandas as pd
import os

def convert_excel_to_csv(excel_path, output_dir):
    """
    Excelファイルの各シートをCSVファイルに変換します。

    Args:
        excel_path (str): 入力Excelファイルのパス。
        output_dir (str): CSVファイルを出力するディレクトリ。
    """
    try:
        if not os.path.exists(output_dir):
            os.makedirs(output_dir)

        xls = pd.ExcelFile(excel_path)
        for sheet_name in xls.sheet_names:
            df = pd.read_excel(xls, sheet_name=sheet_name)
            # シート名に使えない文字を置換
            safe_sheet_name = "".join([c if c.isalnum() else "_" for c in sheet_name])
            csv_path = os.path.join(output_dir, f"{safe_sheet_name}.csv")
            df.to_csv(csv_path, index=False)
            print(f"Successfully converted sheet '{sheet_name}' to '{csv_path}'")
    except Exception as e:
        print(f"Error reading or converting Excel file: {e}")

if __name__ == "__main__":
    convert_excel_to_csv("manuscript/data/データ解析結果_20250516 (1).xlsx", "temp/test_output") 