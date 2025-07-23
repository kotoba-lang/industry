# Use the official Node.js 20 image as base
FROM mcr.microsoft.com/devcontainers/typescript-node:1-20-bullseye

# Install Python 3.11 and development tools
RUN apt-get update && apt-get install -y \
    python3.11 \
    python3.11-dev \
    python3.11-venv \
    python3-pip \
    build-essential \
    curl \
    wget \
    git \
    vim \
    nano \
    htop \
    tree \
    jq \
    unzip \
    zip \
    ca-certificates \
    gnupg \
    lsb-release \
    && rm -rf /var/lib/apt/lists/*

# Create symlink for python3.11 as python
RUN ln -sf /usr/bin/python3.11 /usr/bin/python

# Install pnpm globally
RUN npm install -g pnpm@latest

# Install Python packages for development
RUN pip3 install --upgrade pip && \
    pip3 install \
    numpy \
    pandas \
    matplotlib \
    seaborn \
    scipy \
    scikit-learn \
    jupyter \
    ipython \
    black \
    flake8 \
    mypy \
    pytest \
    pytest-cov \
    pre-commit \
    jupyterlab

# Set up Python environment
ENV PYTHONPATH=/workspaces/apps/research
ENV PYTHONUNBUFFERED=1

# Create development directories
RUN mkdir -p /workspaces/.cache /workspaces/.pnpm-store

# Set up Git configuration
RUN git config --global init.defaultBranch main && \
    git config --global pull.rebase false

# Create useful aliases
RUN echo 'alias dev-webmaster="cd apps/webmaster && pnpm dev"' >> ~/.bashrc && \
    echo 'alias dev-cytoscaper="cd apps/cytoscaper && pnpm dev"' >> ~/.bashrc && \
    echo 'alias test-webmaster="cd apps/webmaster && pnpm test"' >> ~/.bashrc && \
    echo 'alias test-cytoscaper="cd apps/cytoscaper && pnpm test"' >> ~/.bashrc && \
    echo 'alias python-gene="cd apps/research/gene/analysis && python"' >> ~/.bashrc && \
    echo 'alias python-physics="cd apps/research/physics_restructured/computational_framework && python"' >> ~/.bashrc && \
    echo 'alias jupyter-gene="cd apps/research/gene/analysis && jupyter notebook"' >> ~/.bashrc && \
    echo 'alias jupyter-physics="cd apps/research/physics_restructured/computational_framework && jupyter notebook"' >> ~/.bashrc && \
    echo 'alias status="echo \"=== Project Status ===\" && echo \"Webmaster: \" && cd apps/webmaster && pnpm --version && cd ../.. && echo \"Cytoscaper: \" && cd apps/cytoscaper && pnpm --version && cd ../.. && echo \"Python: \" && python --version && echo \"Node: \" && node --version && echo \"pnpm: \" && pnpm --version"' >> ~/.bashrc

# Set working directory
WORKDIR /workspaces

# Expose ports
EXPOSE 1016 25720 3000 8000 8888

# Default command
CMD ["sleep", "infinity"] 