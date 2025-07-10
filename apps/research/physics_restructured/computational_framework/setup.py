#!/usr/bin/env python3
"""
Setup script for Generative Physics Cosmology Framework
"""

from setuptools import setup, find_packages
import os

# Read long description from README
with open("README_github.md", "r", encoding="utf-8") as fh:
    long_description = fh.read()

# Read requirements
with open("requirements.txt", "r", encoding="utf-8") as fh:
    requirements = [line.strip() for line in fh if line.strip() and not line.startswith("#")]

# Read version
version = "1.2.0"

setup(
    name="generative-physics-cosmology",
    version=version,
    author="Jun Kawasaki",
    author_email="root+physics@junkawasaki.com",
    description="A unified computational framework for cosmological evolution through information processing",
    long_description=long_description,
    long_description_content_type="text/markdown",
    url="https://github.com/junkawasaki/generative-physics-cosmology",
    project_urls={
        "Bug Reports": "https://github.com/junkawasaki/generative-physics-cosmology/issues",
        "Source": "https://github.com/junkawasaki/generative-physics-cosmology",
        "Documentation": "https://junkawasaki.com/generative-physics/docs",
        "ArXiv": "https://arxiv.org/abs/2501.XXXXX",
    },
    packages=find_packages(),
    classifiers=[
        "Development Status :: 4 - Beta",
        "Intended Audience :: Science/Research",
        "License :: OSI Approved :: MIT License",
        "Operating System :: OS Independent",
        "Programming Language :: Python :: 3",
        "Programming Language :: Python :: 3.8",
        "Programming Language :: Python :: 3.9",
        "Programming Language :: Python :: 3.10",
        "Programming Language :: Python :: 3.11",
        "Topic :: Scientific/Engineering :: Physics",
        "Topic :: Scientific/Engineering :: Astronomy",
        "Topic :: Scientific/Engineering :: Information Analysis",
        "Topic :: Scientific/Engineering :: Artificial Intelligence",
    ],
    python_requires=">=3.8",
    install_requires=requirements,
    extras_require={
        "gpu": [
            "tensorflow-gpu>=2.4.0",
            "torch>=1.7.0+cu111",
            "cupy>=9.0.0",
            "pycuda>=2020.1",
        ],
        "quantum": [
            "qiskit>=0.25.0",
            "cirq>=0.10.0",
            "pennylane>=0.15.0",
        ],
        "dev": [
            "pytest>=6.2.0",
            "pytest-cov>=2.11.0",
            "black>=21.1.0",
            "flake8>=3.8.0",
            "mypy>=0.812",
            "pre-commit>=2.10.0",
        ],
        "docs": [
            "sphinx>=3.5.0",
            "sphinx-rtd-theme>=0.5.0",
            "jupyter>=1.0.0",
            "nbconvert>=6.0.0",
        ],
        "all": [
            "tensorflow-gpu>=2.4.0",
            "torch>=1.7.0+cu111",
            "cupy>=9.0.0",
            "pycuda>=2020.1",
            "qiskit>=0.25.0",
            "cirq>=0.10.0",
            "pennylane>=0.15.0",
            "pytest>=6.2.0",
            "pytest-cov>=2.11.0",
            "black>=21.1.0",
            "flake8>=3.8.0",
            "mypy>=0.812",
            "pre-commit>=2.10.0",
            "sphinx>=3.5.0",
            "sphinx-rtd-theme>=0.5.0",
            "jupyter>=1.0.0",
            "nbconvert>=6.0.0",
        ],
    },
    entry_points={
        "console_scripts": [
            "generative-physics=generative_physics.cli:main",
            "solve-sigma8=generative_physics.cli:solve_sigma8",
            "run-cosmology=generative_physics.cli:run_cosmology",
            "new-physics-search=generative_physics.cli:new_physics_search",
        ],
    },
    include_package_data=True,
    package_data={
        "generative_physics": [
            "data/*.dat",
            "data/*.txt",
            "data/*.h5",
            "visualization/*.html",
            "visualization/*.js",
            "visualization/*.css",
        ],
    },
    keywords=[
        "cosmology",
        "physics",
        "information theory",
        "quantum gravity",
        "dark matter",
        "dark energy",
        "sigma8",
        "hubble tension",
        "machine learning",
        "artificial intelligence",
        "computational physics",
        "numerical simulation",
    ],
    zip_safe=False,
) 