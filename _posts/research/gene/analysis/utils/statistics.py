"""
Statistical analysis utilities for GWAS data
"""

import numpy as np
import pandas as pd
from scipy import stats
from scipy.stats import chi2
import warnings
warnings.filterwarnings('ignore')

def calculate_lambda_gc(p_values):
    """
    Calculate genomic inflation factor (lambda_GC)
    
    Parameters:
    p_values: array-like, P-values from GWAS
    
    Returns:
    float: Genomic inflation factor
    """
    p_values = np.array(p_values)
    p_values = p_values[~np.isnan(p_values)]
    p_values = p_values[p_values > 0]
    
    if len(p_values) == 0:
        return 1.0
    
    # Convert P-values to chi-squared statistics
    chi2_stats = chi2.ppf(1 - p_values, df=1)
    
    # Calculate lambda as median chi-squared / expected median
    lambda_gc = np.median(chi2_stats) / chi2.ppf(0.5, df=1)
    
    return lambda_gc

def calculate_heterogeneity(beta1, se1, beta2, se2):
    """
    Calculate I² heterogeneity statistic between two studies
    
    Parameters:
    beta1, se1: Effect size and standard error from study 1
    beta2, se2: Effect size and standard error from study 2
    
    Returns:
    dict: I² statistic, Q statistic, and P-value
    """
    # Fixed-effects meta-analysis weights
    w1 = 1 / (se1 ** 2)
    w2 = 1 / (se2 ** 2)
    
    # Pooled effect size
    beta_pooled = (w1 * beta1 + w2 * beta2) / (w1 + w2)
    
    # Q statistic
    Q = w1 * (beta1 - beta_pooled) ** 2 + w2 * (beta2 - beta_pooled) ** 2
    
    # I² statistic
    df = 1  # degrees of freedom for 2 studies
    I2 = max(0, (Q - df) / Q * 100) if Q > 0 else 0
    
    # P-value for heterogeneity
    p_het = 1 - chi2.cdf(Q, df) if Q > 0 else 1.0
    
    return {
        'I2': I2,
        'Q': Q,
        'p_heterogeneity': p_het,
        'significant_heterogeneity': I2 > 50 and p_het < 0.05
    }

def calculate_effect_correlation(beta1, beta2, se1=None, se2=None):
    """
    Calculate correlation between effect sizes from two studies
    
    Parameters:
    beta1, beta2: Effect sizes from studies 1 and 2
    se1, se2: Standard errors (optional, for weighted correlation)
    
    Returns:
    dict: Correlation coefficient and P-value
    """
    beta1 = np.array(beta1)
    beta2 = np.array(beta2)
    
    # Remove NaN values
    valid_idx = ~(np.isnan(beta1) | np.isnan(beta2))
    beta1_clean = beta1[valid_idx]
    beta2_clean = beta2[valid_idx]
    
    if len(beta1_clean) < 3:
        return {'correlation': np.nan, 'p_value': np.nan}
    
    # Calculate correlation
    if se1 is not None and se2 is not None:
        # Weighted correlation (inverse variance weighting)
        se1_clean = np.array(se1)[valid_idx]
        se2_clean = np.array(se2)[valid_idx]
        
        weights = 1 / (se1_clean ** 2 + se2_clean ** 2)
        weights = weights / np.sum(weights)
        
        mean1 = np.average(beta1_clean, weights=weights)
        mean2 = np.average(beta2_clean, weights=weights)
        
        cov = np.average((beta1_clean - mean1) * (beta2_clean - mean2), weights=weights)
        var1 = np.average((beta1_clean - mean1) ** 2, weights=weights)
        var2 = np.average((beta2_clean - mean2) ** 2, weights=weights)
        
        correlation = cov / np.sqrt(var1 * var2) if var1 > 0 and var2 > 0 else 0
        
        # Approximate p-value (not exact for weighted correlation)
        n_eff = len(beta1_clean)
        t_stat = correlation * np.sqrt((n_eff - 2) / (1 - correlation ** 2))
        p_value = 2 * (1 - stats.t.cdf(abs(t_stat), n_eff - 2))
        
    else:
        # Simple Pearson correlation
        correlation, p_value = stats.pearsonr(beta1_clean, beta2_clean)
    
    return {
        'correlation': correlation,
        'p_value': p_value,
        'n_variants': len(beta1_clean)
    }

def calculate_qq_expected(n_tests):
    """
    Calculate expected P-values for QQ plot
    
    Parameters:
    n_tests: Number of tests
    
    Returns:
    array: Expected P-values under null hypothesis
    """
    expected_p = np.arange(1, n_tests + 1) / (n_tests + 1)
    return expected_p

def bonferroni_correction(p_values, alpha=0.05):
    """
    Apply Bonferroni correction for multiple testing
    
    Parameters:
    p_values: array-like, P-values to correct
    alpha: Significance level
    
    Returns:
    dict: Corrected alpha, significant indices
    """
    p_values = np.array(p_values)
    n_tests = len(p_values[~np.isnan(p_values)])
    
    corrected_alpha = alpha / n_tests
    significant = p_values < corrected_alpha
    
    return {
        'corrected_alpha': corrected_alpha,
        'significant_indices': np.where(significant)[0],
        'n_significant': np.sum(significant)
    }

def fdr_correction(p_values, alpha=0.05, method='bh'):
    """
    Apply False Discovery Rate correction
    
    Parameters:
    p_values: array-like, P-values to correct
    alpha: FDR level
    method: 'bh' for Benjamini-Hochberg
    
    Returns:
    dict: Adjusted P-values and significant indices
    """
    from scipy.stats import multipletests
    
    p_values = np.array(p_values)
    valid_p = p_values[~np.isnan(p_values)]
    
    if len(valid_p) == 0:
        return {
            'adjusted_p': p_values,
            'significant': np.zeros_like(p_values, dtype=bool),
            'n_significant': 0
        }
    
    # Apply FDR correction
    rejected, p_adjusted, _, _ = multipletests(valid_p, alpha=alpha, method=method)
    
    # Map back to original array
    adjusted_p_full = np.full_like(p_values, np.nan)
    significant_full = np.zeros_like(p_values, dtype=bool)
    
    valid_idx = ~np.isnan(p_values)
    adjusted_p_full[valid_idx] = p_adjusted
    significant_full[valid_idx] = rejected
    
    return {
        'adjusted_p': adjusted_p_full,
        'significant': significant_full,
        'n_significant': np.sum(rejected)
    }

def calculate_polygenic_score_r2(true_labels, scores):
    """
    Calculate R² for polygenic score performance
    
    Parameters:
    true_labels: array-like, True binary labels (0/1)
    scores: array-like, Polygenic scores
    
    Returns:
    dict: R², AUC, and other performance metrics
    """
    from sklearn.metrics import roc_auc_score, roc_curve
    from sklearn.linear_model import LogisticRegression
    
    true_labels = np.array(true_labels)
    scores = np.array(scores)
    
    # Remove NaN values
    valid_idx = ~(np.isnan(true_labels) | np.isnan(scores))
    y_clean = true_labels[valid_idx]
    x_clean = scores[valid_idx].reshape(-1, 1)
    
    if len(y_clean) < 10:
        return {
            'r2': np.nan,
            'auc': np.nan,
            'n_samples': len(y_clean)
        }
    
    # Fit logistic regression to calculate Nagelkerke R²
    lr = LogisticRegression()
    lr.fit(x_clean, y_clean)
    
    # Calculate log-likelihood
    y_pred_proba = lr.predict_proba(x_clean)[:, 1]
    log_likelihood = np.sum(y_clean * np.log(y_pred_proba + 1e-15) + 
                           (1 - y_clean) * np.log(1 - y_pred_proba + 1e-15))
    
    # Null model log-likelihood
    p_null = np.mean(y_clean)
    log_likelihood_null = np.sum(y_clean * np.log(p_null + 1e-15) + 
                                (1 - y_clean) * np.log(1 - p_null + 1e-15))
    
    # Nagelkerke R²
    n = len(y_clean)
    r2_cox_snell = 1 - np.exp(2 * (log_likelihood_null - log_likelihood) / n)
    r2_nagelkerke = r2_cox_snell / (1 - np.exp(2 * log_likelihood_null / n))
    
    # AUC
    auc = roc_auc_score(y_clean, scores[valid_idx])
    
    # ROC curve
    fpr, tpr, _ = roc_curve(y_clean, scores[valid_idx])
    
    return {
        'r2': r2_nagelkerke,
        'auc': auc,
        'fpr': fpr,
        'tpr': tpr,
        'n_samples': len(y_clean)
    }

def calculate_enrichment_score(gene_set_genes, background_genes, significant_genes):
    """
    Calculate gene set enrichment score
    
    Parameters:
    gene_set_genes: list, Genes in the gene set
    background_genes: list, All genes in background
    significant_genes: list, Significantly associated genes
    
    Returns:
    dict: Enrichment statistics
    """
    # Convert to sets for faster operations
    gene_set = set(gene_set_genes)
    background = set(background_genes)
    significant = set(significant_genes)
    
    # Overlap counts
    gene_set_in_bg = gene_set.intersection(background)
    sig_in_gene_set = significant.intersection(gene_set_in_bg)
    sig_not_in_gene_set = significant.intersection(background) - gene_set_in_bg
    
    # Contingency table
    a = len(sig_in_gene_set)  # significant & in gene set
    b = len(gene_set_in_bg) - a  # not significant & in gene set
    c = len(sig_not_in_gene_set)  # significant & not in gene set
    d = len(background) - a - b - c  # not significant & not in gene set
    
    # Fisher's exact test
    from scipy.stats import fisher_exact
    
    if a + b > 0 and c + d > 0:
        odds_ratio, p_value = fisher_exact([[a, b], [c, d]], alternative='greater')
        fold_enrichment = (a / (a + b)) / (c / (c + d)) if c + d > 0 else np.inf
    else:
        odds_ratio = np.nan
        p_value = 1.0
        fold_enrichment = np.nan
    
    return {
        'n_overlap': a,
        'n_gene_set': len(gene_set_in_bg),
        'n_significant': len(significant.intersection(background)),
        'fold_enrichment': fold_enrichment,
        'odds_ratio': odds_ratio,
        'p_value': p_value,
        'contingency': [[a, b], [c, d]]
    }

def meta_analysis_fixed_effects(betas, ses):
    """
    Fixed-effects meta-analysis
    
    Parameters:
    betas: array-like, Effect sizes
    ses: array-like, Standard errors
    
    Returns:
    dict: Meta-analysis results
    """
    betas = np.array(betas)
    ses = np.array(ses)
    
    # Remove NaN values
    valid_idx = ~(np.isnan(betas) | np.isnan(ses)) & (ses > 0)
    betas_clean = betas[valid_idx]
    ses_clean = ses[valid_idx]
    
    if len(betas_clean) < 2:
        return {
            'beta_meta': np.nan,
            'se_meta': np.nan,
            'p_meta': np.nan,
            'n_studies': len(betas_clean)
        }
    
    # Inverse variance weights
    weights = 1 / (ses_clean ** 2)
    
    # Meta-analyzed effect size
    beta_meta = np.sum(weights * betas_clean) / np.sum(weights)
    se_meta = 1 / np.sqrt(np.sum(weights))
    
    # Z-score and P-value
    z_meta = beta_meta / se_meta
    p_meta = 2 * (1 - stats.norm.cdf(abs(z_meta)))
    
    return {
        'beta_meta': beta_meta,
        'se_meta': se_meta,
        'z_meta': z_meta,
        'p_meta': p_meta,
        'n_studies': len(betas_clean)
    } 