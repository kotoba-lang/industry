import sys
from pathlib import Path
sys.path.append(str(Path(__file__).parent.parent / 'utils'))
from duckdb_manager import GWASDuckDBManager

def main():
    print("🧬 Manually importing Japanese High-IQ GWAS data...")
    manager = GWASDuckDBManager(dataset_path='../../dataset/')
    
    # 既存のデータをクリアして再インポート
    trait_id = 'Japanese_HighIQ_GWAS_2024'
    print(f"Force re-importing: {trait_id}")
    success = manager.import_trait_data(trait_id, force_reimport=True)
    
    if success:
        print("✅ Import successful!")
        db_info = manager.get_database_info()
        print(f"📊 DB now contains {db_info.get('traits_stored')} traits and {db_info.get('total_snps')} SNPs.")
    else:
        print("❌ Import failed. Please check logs.")

if __name__ == "__main__":
    main() 