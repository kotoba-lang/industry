"""
Color scheme configuration for Molecular Psychiatry paper figures
"""

# Primary color palette
COLORS = {
    # Population colors
    'japanese': '#2E86AB',      # Blue - Japanese population data
    'european': '#A23B72',      # Purple - European population data
    'convergent': '#F18F01',    # Orange - Shared/convergent findings
    
    # Significance levels
    'significant': '#C73E1D',   # Red - Highly significant results
    'suggestive': '#FF8C42',    # Orange - Suggestive significance
    'nonsignificant': '#CCCCCC', # Gray - Non-significant results
    
    # Cell type categories
    'neural': '#4A90E2',        # Blue - Neural cell types
    'glial': '#7ED321',         # Green - Glial cell types
    'gabaergic': '#F18F01',     # Orange - GABAergic neurons
    'other_cells': '#BD10E0',   # Purple - Other cell types
    
    # Chromosome alternating colors
    'chr_even': '#2E86AB',      # Blue for even chromosomes
    'chr_odd': '#A23B72',       # Purple for odd chromosomes
    
    # Additional analysis colors
    'pathway': '#50C878',       # Green - Pathway analysis
    'enrichment': '#FF6B6B',    # Red - Enrichment analysis
    'correlation': '#4ECDC4',   # Teal - Correlation analysis
}

# Color-blind friendly alternatives
COLORBLIND_COLORS = {
    'japanese': '#0173B2',      # Blue
    'european': '#DE8F05',      # Orange
    'convergent': '#CC78BC',    # Pink
    'significant': '#D55E00',   # Red-orange
    'neural': '#56B4E9',        # Sky blue
    'glial': '#009E73',         # Bluish green
}

# Gradient colors for heatmaps
GRADIENT_COLORS = {
    'blue_red': ['#2E86AB', '#FFFFFF', '#C73E1D'],
    'blue_white_red': ['#2E86AB', '#FFFFFF', '#A23B72'],
    'viridis': ['#440154', '#21908C', '#FDE725'],
    'plasma': ['#0D0887', '#CC4678', '#F0F921'],
}

# Transparency levels
ALPHA_LEVELS = {
    'high': 0.9,
    'medium': 0.7,
    'low': 0.5,
    'very_low': 0.3,
}

def get_chromosome_color(chr_num):
    """Get color for chromosome based on even/odd number"""
    return COLORS['chr_even'] if chr_num % 2 == 0 else COLORS['chr_odd']

def get_significance_color(p_value):
    """Get color based on p-value significance"""
    if p_value < 5e-8:
        return COLORS['significant']
    elif p_value < 1e-6:
        return COLORS['suggestive']
    else:
        return COLORS['nonsignificant']

def get_cell_type_color(cell_type):
    """Get color based on cell type category"""
    cell_type_lower = cell_type.lower()
    
    if any(term in cell_type_lower for term in ['pyramidal', 'hippocampal', 'cortical']):
        return COLORS['neural']
    elif any(term in cell_type_lower for term in ['gabaergic', 'gaba']):
        return COLORS['gabaergic']
    elif any(term in cell_type_lower for term in ['astrocyte', 'oligodendrocyte', 'microglia']):
        return COLORS['glial']
    else:
        return COLORS['other_cells'] 