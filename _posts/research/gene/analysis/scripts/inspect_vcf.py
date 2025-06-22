import gzip
from pathlib import Path

def main():
    """VCF.GZファイルのヘッダーと最初の数行を表示して内容を確認する"""
    
    # anlysis/scripts/からの相対パス
    file_path = Path(__file__).parent.parent.parent / 'reference_data' / 'Savage2018_Intelligence_GWAS_European_OpenGWAS.vcf.gz'
    
    print(f"🔬 Examining file: {file_path}")
    
    if not file_path.exists():
        print(f"❌ File not found at the specified path!")
        return
        
    try:
        with gzip.open(file_path, 'rt') as f:
            for i, line in enumerate(f):
                if i >= 200:
                    break
                print(line.strip())
    except Exception as e:
        print(f"An error occurred: {e}")

if __name__ == "__main__":
    main() 