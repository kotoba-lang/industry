import pandas as pd
import os

def process_chen_gwas(input_tsv, output_csv):
    """
    Processes the Chen et al. 2024 GWAS summary statistics into a standardized format.

    Args:
        input_tsv (str): Path to the input TSV file.
        output_csv (str): Path to the output standardized CSV file.
    """
    print(f"Reading GWAS data from {input_tsv}...")
    
    # Define column types to optimize memory usage
    dtype = {
        'chromosome': 'category',
        'beta': 'float32',
        'standard_error': 'float32',
        'effect_allele_frequency': 'float32',
        'p_value': 'float32',
    }
    
    try:
        df = pd.read_csv(
            input_tsv,
            sep='\t',
            dtype=dtype,
            usecols=[
                'chromosome', 'base_pair_location', 'rsid',
                'effect_allele', 'other_allele',
                'beta', 'standard_error', 'p_value'
            ],
            na_values=['#NA']
        )
        
        print(f"Successfully loaded {len(df)} variants.")
        
        # Rename columns to the standardized format
        df.rename(columns={
            'chromosome': 'CHR',
            'base_pair_location': 'BP',
            'rsid': 'SNP',
            'effect_allele': 'A1',
            'other_allele': 'A2',
            'beta': 'BETA',
            'standard_error': 'SE',
            'p_value': 'P'
        }, inplace=True)
        
        # Drop rows with missing essential data
        df.dropna(subset=['CHR', 'BP', 'SNP', 'P'], inplace=True)
        
        # Save to a new CSV file
        df.to_csv(output_csv, index=False)
        
        print(f"Standardized data saved to {output_csv}")
        print("\nPreview of the processed data:")
        print(df.head())
        
    except FileNotFoundError:
        print(f"Error: Input file not found at {input_tsv}")
    except Exception as e:
        print(f"An error occurred: {e}")

def main():
    """
    Main function to run the processing script.
    """
    script_dir = os.path.dirname(os.path.abspath(__file__))
    input_file = os.path.join(script_dir, '../../manuscript/data/EastAsian_EducationalAttainment_GWAS_Chen2024.tsv')
    output_file = os.path.join(script_dir, '../../manuscript/data/gwas_summary_stats_chen2024.csv')
    
    process_chen_gwas(input_file, output_file)

if __name__ == '__main__':
    main() 