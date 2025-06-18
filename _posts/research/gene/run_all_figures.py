#!/usr/bin/env python3
"""
Run All Figures - Molecular Psychiatry Paper

This script generates all figures for the paper:
"Population-specific genetic heterogeneity of intelligence"

Usage:
    python run_all_figures.py [--figures FIGURE_NUMBERS] [--output OUTPUT_DIR]

Example:
    python run_all_figures.py --figures 1,2,4,5 --output ./output

Author: AI Research Assistant
Date: 2024
"""

import sys
import os
import argparse
from pathlib import Path
import importlib.util

# Add project root to Python path
project_root = os.path.dirname(os.path.abspath(__file__))
sys.path.append(project_root)

class FigureRunner:
    """Orchestrate generation of all figures"""
    
    def __init__(self, output_dir='output'):
        """Initialize figure runner"""
        self.output_dir = Path(output_dir)
        self.output_dir.mkdir(exist_ok=True)
        
        # Figure configurations
        self.figure_configs = {
            '1': {
                'name': 'Manhattan & QQ Plot',
                'script': 'figures/figure1/generate_figure1.py',
                'description': 'Primary GWAS visualization with genomic inflation analysis'
            },
            '2': {
                'name': 'Cross-Population Comparison',
                'script': 'figures/figure2/generate_figure2.py', 
                'description': 'P-value correlation, effect sizes, and heterogeneity analysis'
            },
            '4': {
                'name': 'Cell-Type Enrichment',
                'script': 'figures/figure4/generate_figure4.py',
                'description': 'Brain cell-type specific enrichment analysis'
            },
            '5': {
                'name': 'Polygenic Score Analysis',
                'script': 'figures/figure5/generate_figure5.py',
                'description': 'Cross-population transferability and performance metrics'
            },
            'supp': {
                'name': 'Supplementary Pathway Analysis',
                'script': 'figures/supplementary/generate_supplementary.py',
                'description': 'Gene set and pathway enrichment analysis'
            }
        }
        
        self.table_configs = {
            '1': {
                'name': 'Top Variants Table',
                'script': 'tables/table1/generate_table1.py',
                'description': 'Summary of top intelligence-associated variants'
            }
        }
    
    def load_module_from_path(self, script_path):
        """Dynamically load a Python module from file path"""
        script_path = Path(script_path)
        
        if not script_path.exists():
            print(f"❌ Script not found: {script_path}")
            return None
        
        try:
            spec = importlib.util.spec_from_file_location(
                script_path.stem, script_path
            )
            module = importlib.util.module_from_spec(spec)
            spec.loader.exec_module(module)
            return module
        except Exception as e:
            print(f"❌ Error loading module {script_path}: {e}")
            return None
    
    def run_figure(self, figure_num):
        """Run a specific figure generation"""
        if figure_num not in self.figure_configs:
            print(f"❌ Unknown figure: {figure_num}")
            return False
        
        config = self.figure_configs[figure_num]
        print(f"\n{'='*60}")
        print(f"🎨 GENERATING FIGURE {figure_num}: {config['name']}")
        print(f"📄 {config['description']}")
        print(f"{'='*60}")
        
        # Load and run the figure script
        module = self.load_module_from_path(config['script'])
        
        if module is None:
            return False
        
        try:
            # Try to call main function
            if hasattr(module, 'main'):
                success = module.main()
                return success
            else:
                print(f"⚠️  No main() function found in {config['script']}")
                return False
                
        except Exception as e:
            print(f"❌ Error running figure {figure_num}: {e}")
            import traceback
            traceback.print_exc()
            return False
    
    def run_table(self, table_num):
        """Run a specific table generation"""
        if table_num not in self.table_configs:
            print(f"❌ Unknown table: {table_num}")
            return False
        
        config = self.table_configs[table_num]
        print(f"\n{'='*60}")
        print(f"📊 GENERATING TABLE {table_num}: {config['name']}")
        print(f"📄 {config['description']}")
        print(f"{'='*60}")
        
        # Load and run the table script
        module = self.load_module_from_path(config['script'])
        
        if module is None:
            return False
        
        try:
            if hasattr(module, 'main'):
                success = module.main()
                return success
            else:
                print(f"⚠️  No main() function found in {config['script']}")
                return False
                
        except Exception as e:
            print(f"❌ Error running table {table_num}: {e}")
            import traceback
            traceback.print_exc()
            return False
    
    def run_all_figures(self, figure_list=None):
        """Run all figures or specific subset"""
        if figure_list is None:
            figure_list = list(self.figure_configs.keys())
        
        print(f"\n🚀 STARTING FIGURE GENERATION")
        print(f"📁 Output directory: {self.output_dir.absolute()}")
        print(f"🎯 Figures to generate: {', '.join(figure_list)}")
        
        success_count = 0
        total_count = len(figure_list)
        
        for figure_num in figure_list:
            success = self.run_figure(figure_num)
            if success:
                success_count += 1
                print(f"✅ Figure {figure_num} completed successfully")
            else:
                print(f"❌ Figure {figure_num} failed")
        
        return success_count, total_count
    
    def run_all_tables(self, table_list=None):
        """Run all tables or specific subset"""
        if table_list is None:
            table_list = list(self.table_configs.keys())
        
        print(f"\n📋 STARTING TABLE GENERATION")
        
        success_count = 0
        total_count = len(table_list)
        
        for table_num in table_list:
            success = self.run_table(table_num)
            if success:
                success_count += 1
                print(f"✅ Table {table_num} completed successfully")
            else:
                print(f"❌ Table {table_num} failed")
        
        return success_count, total_count
    
    def check_requirements(self):
        """Check if required dependencies are available"""
        required_packages = [
            'pandas', 'numpy', 'matplotlib', 'seaborn', 'scipy', 'sklearn'
        ]
        
        missing_packages = []
        
        for package in required_packages:
            try:
                __import__(package)
            except ImportError:
                missing_packages.append(package)
        
        if missing_packages:
            print(f"❌ Missing packages: {missing_packages}")
            print(f"Install with: pip install {' '.join(missing_packages)}")
            return False
        
        print("✅ All required packages available")
        return True
    
    def check_data_files(self):
        """Check if required data files are present"""
        data_files = ['gwas-data.csv']
        missing_files = []
        
        for file_name in data_files:
            if not Path(file_name).exists():
                missing_files.append(file_name)
        
        if missing_files:
            print(f"⚠️  Missing data files: {missing_files}")
            print("Synthetic data will be generated for demonstration")
        else:
            print("✅ Data files found")
        
        return True  # Don't fail if data files missing - use synthetic data
    
    def print_summary(self, fig_success, fig_total, table_success, table_total):
        """Print final summary"""
        print(f"\n{'='*60}")
        print(f"📊 GENERATION SUMMARY")
        print(f"{'='*60}")
        print(f"📈 Figures: {fig_success}/{fig_total} successful")
        print(f"📋 Tables: {table_success}/{table_total} successful")
        
        total_success = fig_success + table_success
        total_items = fig_total + table_total
        
        if total_success == total_items:
            print(f"🎉 ALL ITEMS GENERATED SUCCESSFULLY!")
            print(f"📁 Output saved to: {self.output_dir.absolute()}")
        else:
            print(f"⚠️  {total_items - total_success} items failed")
        
        # List generated files
        output_files = list(self.output_dir.glob('*'))
        if output_files:
            print(f"\n📁 Generated files:")
            for file_path in sorted(output_files):
                size_mb = file_path.stat().st_size / (1024 * 1024)
                print(f"   • {file_path.name} ({size_mb:.1f} MB)")

def parse_arguments():
    """Parse command line arguments"""
    parser = argparse.ArgumentParser(
        description='Generate all figures for Molecular Psychiatry paper',
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
Examples:
  python run_all_figures.py                    # Generate all figures
  python run_all_figures.py --figures 1,2      # Generate only Figure 1 and 2
  python run_all_figures.py --tables 1         # Generate only Table 1
  python run_all_figures.py --output ./results # Save to custom directory
        """
    )
    
    parser.add_argument(
        '--figures', 
        type=str,
        help='Comma-separated list of figures to generate (1,2,4,5,supp)'
    )
    
    parser.add_argument(
        '--tables',
        type=str, 
        help='Comma-separated list of tables to generate (1)'
    )
    
    parser.add_argument(
        '--output',
        type=str,
        default='output',
        help='Output directory for generated files (default: output)'
    )
    
    parser.add_argument(
        '--check-only',
        action='store_true',
        help='Only check requirements and data files, do not generate'
    )
    
    return parser.parse_args()

def main():
    """Main function"""
    print("🧬 MOLECULAR PSYCHIATRY FIGURE GENERATION")
    print("Population-specific genetic heterogeneity of intelligence")
    print("="*60)
    
    # Parse arguments
    args = parse_arguments()
    
    # Initialize runner
    runner = FigureRunner(args.output)
    
    # Check requirements
    if not runner.check_requirements():
        return 1
    
    # Check data files
    runner.check_data_files()
    
    # If check-only mode, exit here
    if args.check_only:
        print("✅ Requirements check completed")
        return 0
    
    # Parse figure and table lists
    figure_list = None
    if args.figures:
        figure_list = [f.strip() for f in args.figures.split(',')]
        # Validate figure numbers
        invalid_figs = [f for f in figure_list if f not in runner.figure_configs]
        if invalid_figs:
            print(f"❌ Invalid figures: {invalid_figs}")
            print(f"Available figures: {list(runner.figure_configs.keys())}")
            return 1
    
    table_list = None
    if args.tables:
        table_list = [t.strip() for t in args.tables.split(',')]
        # Validate table numbers
        invalid_tables = [t for t in table_list if t not in runner.table_configs]
        if invalid_tables:
            print(f"❌ Invalid tables: {invalid_tables}")
            print(f"Available tables: {list(runner.table_configs.keys())}")
            return 1
    
    # Run generation
    try:
        # Generate figures
        if not args.tables:  # If not tables-only mode
            fig_success, fig_total = runner.run_all_figures(figure_list)
        else:
            fig_success, fig_total = 0, 0
        
        # Generate tables  
        if not args.figures or args.tables:  # If not figures-only mode
            table_success, table_total = runner.run_all_tables(table_list)
        else:
            table_success, table_total = 0, 0
        
        # Print summary
        runner.print_summary(fig_success, fig_total, table_success, table_total)
        
        # Return appropriate exit code
        total_success = fig_success + table_success
        total_items = fig_total + table_total
        
        return 0 if total_success == total_items else 1
        
    except KeyboardInterrupt:
        print(f"\n⏹️  Generation interrupted by user")
        return 1
    except Exception as e:
        print(f"\n❌ Unexpected error: {e}")
        import traceback
        traceback.print_exc()
        return 1

if __name__ == "__main__":
    exit_code = main()
    sys.exit(exit_code) 