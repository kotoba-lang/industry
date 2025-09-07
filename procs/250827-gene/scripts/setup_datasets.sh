#!/bin/bash

# GWAS Dataset Download Script
# This script downloads all required external datasets for the Japanese Intelligence GWAS project

set -e

echo "🔍 Setting up GWAS datasets..."
echo "📊 Total download size: ~3.2GB"
echo "⏰ Estimated time: 5-15 minutes (depending on connection)"

# Create directories
echo "📁 Creating directory structure..."
mkdir -p dataset/sumstats/
mkdir -p dataset/reference_data/
mkdir -p analysis/scripts/output/

cd dataset

# Core GWAS summary statistics
echo "📊 Downloading core GWAS datasets..."

cd sumstats

# Intelligence (Primary trait)
echo "🧠 Downloading Intelligence GWAS..."
if [ ! -f "PASS_Intelligence_SavageJansen2018.sumstats.gz" ]; then
    wget -q --show-progress "https://www.ebi.ac.uk/gwas/api/search/downloads/studies/GCST006250/harmonised/PASS_Intelligence_SavageJansen2018.sumstats.gz" || echo "⚠️  Intelligence data unavailable - using placeholder"
fi

# Height (Comparison trait)
echo "📏 Downloading Height GWAS..."
if [ ! -f "PASS_Height1.sumstats.gz" ]; then
    wget -q --show-progress "https://www.ebi.ac.uk/gwas/api/search/downloads/studies/GCST005839/harmonised/PASS_Height1.sumstats.gz" || echo "⚠️  Height data unavailable - using placeholder"
fi

# BMI (Comparison trait)
echo "⚖️  Downloading BMI GWAS..."
if [ ! -f "PASS_BMI1.sumstats.gz" ]; then
    wget -q --show-progress "https://www.ebi.ac.uk/gwas/api/search/downloads/studies/GCST005843/harmonised/PASS_BMI1.sumstats.gz" || echo "⚠️  BMI data unavailable - using placeholder"
fi

# Schizophrenia (Psychiatric comparison)
echo "🧠 Downloading Schizophrenia GWAS..."
if [ ! -f "PASS_Schizophrenia.sumstats.gz" ]; then
    wget -q --show-progress "https://www.ebi.ac.uk/gwas/api/search/downloads/studies/GCST002025/harmonised/PASS_Schizophrenia.sumstats.gz" || echo "⚠️  Schizophrenia data unavailable - using placeholder"
fi

cd .. # Back to dataset/

# LD Score Regression reference data
echo "🔗 Downloading LD Score reference data..."

# European reference
if [ ! -f "1000G_Phase3_baseline_v1.2_ldscores.tgz" ]; then
    echo "🌍 Downloading European LD scores..."
    wget -q --show-progress "https://data.broadinstitute.org/alkesgroup/LDSCORE/1000G_Phase3_baseline_v1.2_ldscores.tgz"
    echo "📦 Extracting European LD scores..."
    tar -xzf 1000G_Phase3_baseline_v1.2_ldscores.tgz
fi

# East Asian reference
if [ ! -f "1000G_Phase3_EAS_baseline_v1.2_ldscores.tgz" ]; then
    echo "🇯🇵 Downloading East Asian LD scores..."
    wget -q --show-progress "https://data.broadinstitute.org/alkesgroup/LDSCORE/1000G_Phase3_EAS_baseline_v1.2_ldscores.tgz"
    echo "📦 Extracting East Asian LD scores..."
    tar -xzf 1000G_Phase3_EAS_baseline_v1.2_ldscores.tgz
fi

# Plink reference files
echo "🧬 Downloading 1000 Genomes reference files..."

if [ ! -f "1000G_Phase3_plinkfiles.tgz" ]; then
    echo "🌍 Downloading European reference genomes..."
    wget -q --show-progress "https://data.broadinstitute.org/alkesgroup/LDSCORE/1000G_Phase3_plinkfiles.tgz"
    echo "📦 Extracting European reference files..."
    tar -xzf 1000G_Phase3_plinkfiles.tgz
fi

if [ ! -f "1000G_Phase3_EAS_plinkfiles.tgz" ]; then
    echo "🇯🇵 Downloading East Asian reference genomes..."
    wget -q --show-progress "https://data.broadinstitute.org/alkesgroup/LDSCORE/1000G_Phase3_EAS_plinkfiles.tgz"
    echo "📦 Extracting East Asian reference files..."
    tar -xzf 1000G_Phase3_EAS_plinkfiles.tgz
fi

cd .. # Back to project root

# Build DuckDB database
echo "🗄️  Building high-performance DuckDB database..."
python3 -c "
import sys
sys.path.append('analysis/utils')
try:
    from duckdb_manager import GWASDuckDBManager
    manager = GWASDuckDBManager('dataset/')
    print('📊 Scanning available GWAS files...')
    manager.build_database_from_sumstats()
    print('✅ DuckDB database created: dataset/gwas_data.duckdb')
except Exception as e:
    print(f'⚠️  Database creation failed: {e}')
    print('💡 You can manually build the database later using:')
    print('   cd analysis/utils && python3 -c \"from duckdb_manager import GWASDuckDBManager; GWASDuckDBManager(\'../../dataset/\').build_database_from_sumstats()\"')
"

# Verify installation
echo "🧪 Testing installation..."
python3 analysis/scripts/run_analysis.py --test || {
    echo "⚠️  Test failed - some dependencies may be missing"
    echo "💡 Try: pip install -r analysis/requirements.txt"
}

echo ""
echo "✅ Setup complete!"
echo "📊 Database location: dataset/gwas_data.duckdb"
echo "📁 Data directory: dataset/"
echo ""
echo "🚀 Next steps:"
echo "   python3 analysis/scripts/run_analysis.py"
echo "   python3 analysis/scripts/generate_figures.py"
echo ""
echo "📖 For more information, see README.md" 