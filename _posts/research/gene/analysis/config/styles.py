"""
Style configuration for Molecular Psychiatry paper figures
"""

import matplotlib.pyplot as plt
import seaborn as sns

# Publication-quality figure settings
FIGURE_STYLE = {
    # Font settings
    'font.family': 'Arial',
    'font.size': 12,
    'axes.titlesize': 14,
    'axes.labelsize': 12,
    'xtick.labelsize': 10,
    'ytick.labelsize': 10,
    'legend.fontsize': 10,
    'figure.titlesize': 16,
    
    # Line and marker settings
    'axes.linewidth': 1.2,
    'xtick.major.width': 1.2,
    'ytick.major.width': 1.2,
    'lines.linewidth': 1.5,
    'lines.markersize': 6,
    
    # Grid settings
    'axes.grid': True,
    'grid.alpha': 0.3,
    'grid.linewidth': 0.8,
    
    # Figure settings
    'figure.dpi': 300,
    'savefig.dpi': 300,
    'savefig.bbox': 'tight',
    'savefig.transparent': False,
    'savefig.facecolor': 'white',
    
    # Color cycle
    'axes.prop_cycle': plt.cycler('color', [
        '#2E86AB', '#A23B72', '#F18F01', '#C73E1D',
        '#4A90E2', '#7ED321', '#BD10E0', '#50C878'
    ])
}

# Figure sizes for different types of plots
FIGURE_SIZES = {
    'single_plot': (8, 6),
    'dual_horizontal': (15, 6),
    'dual_vertical': (8, 12),
    'quad_plot': (15, 12),
    'manhattan': (14, 8),
    'heatmap': (12, 8),
    'wide_plot': (16, 6),
    'square': (10, 10),
}

# Significance line styles
SIGNIFICANCE_LINES = {
    'genome_wide': {
        'y': -7.3,  # -log10(5e-8)
        'color': 'red',
        'linestyle': '--',
        'linewidth': 1.5,
        'alpha': 0.8,
        'label': 'Genome-wide significance (P = 5×10⁻⁸)'
    },
    'suggestive': {
        'y': -6.0,  # -log10(1e-6)
        'color': 'orange',
        'linestyle': '--',
        'linewidth': 1.5,
        'alpha': 0.8,
        'label': 'Suggestive significance (P = 1×10⁻⁶)'
    },
    'nominal': {
        'y': -1.3,  # -log10(0.05)
        'color': 'gray',
        'linestyle': ':',
        'linewidth': 1.0,
        'alpha': 0.6,
        'label': 'Nominal significance (P = 0.05)'
    }
}

# Scatter plot settings
SCATTER_SETTINGS = {
    'default': {
        's': 20,
        'alpha': 0.6,
        'edgecolors': 'none'
    },
    'highlighted': {
        's': 50,
        'alpha': 0.8,
        'edgecolors': 'black',
        'linewidth': 0.5
    },
    'large': {
        's': 100,
        'alpha': 0.7,
        'edgecolors': 'black',
        'linewidth': 0.8
    }
}

# Bar plot settings
BAR_SETTINGS = {
    'default': {
        'alpha': 0.7,
        'edgecolor': 'black',
        'linewidth': 0.5
    },
    'highlighted': {
        'alpha': 0.9,
        'edgecolor': 'black',
        'linewidth': 1.0
    }
}

# Heatmap settings
HEATMAP_SETTINGS = {
    'default': {
        'annot': True,
        'fmt': '.2f',
        'cbar_kws': {'shrink': 0.8},
        'linewidths': 0.5,
        'square': False
    },
    'correlation': {
        'annot': True,
        'fmt': '.3f',
        'vmin': -1,
        'vmax': 1,
        'center': 0,
        'cmap': 'RdBu_r',
        'square': True
    }
}

def setup_publication_style():
    """Apply publication-quality style settings"""
    # Set matplotlib style
    plt.style.use('default')
    plt.rcParams.update(FIGURE_STYLE)
    
    # Set seaborn style
    sns.set_style("whitegrid", {
        "axes.spines.left": True,
        "axes.spines.bottom": True,
        "axes.spines.top": False,
        "axes.spines.right": False,
        "grid.alpha": 0.3
    })

def get_figure_size(plot_type):
    """Get figure size for specific plot type"""
    return FIGURE_SIZES.get(plot_type, FIGURE_SIZES['single_plot'])

def add_significance_line(ax, line_type='genome_wide'):
    """Add significance line to plot"""
    settings = SIGNIFICANCE_LINES[line_type]
    ax.axhline(y=settings['y'], 
              color=settings['color'],
              linestyle=settings['linestyle'],
              linewidth=settings['linewidth'],
              alpha=settings['alpha'],
              label=settings['label'])

def apply_scatter_style(ax, style='default'):
    """Apply scatter plot style"""
    return SCATTER_SETTINGS[style]

def apply_bar_style(style='default'):
    """Apply bar plot style"""
    return BAR_SETTINGS[style]

def save_figure(fig, filename, output_dir='output'):
    """Save figure in multiple formats"""
    import os
    
    # Create output directory if it doesn't exist
    os.makedirs(output_dir, exist_ok=True)
    
    # Save in PNG format (high resolution)
    png_path = os.path.join(output_dir, f"{filename}.png")
    fig.savefig(png_path, dpi=300, bbox_inches='tight', 
                facecolor='white', transparent=False)
    
    # Save in PDF format (vector)
    pdf_path = os.path.join(output_dir, f"{filename}.pdf")
    fig.savefig(pdf_path, bbox_inches='tight', 
                facecolor='white', transparent=False)
    
    print(f"✅ Figure saved: {png_path} and {pdf_path}")
    
    return png_path, pdf_path 