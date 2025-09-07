const path = require('path');

module.exports = {
  mode: 'production',
  entry: './src/index.ts',
  output: {
    path: path.resolve(__dirname, 'dist'),
    filename: 'index.esm.js',
    library: {
      type: 'module'
    },
    environment: {
      module: true
    },
    clean: false
  },
  experiments: {
    outputModule: true
  },
  target: ['web', 'es2020'],
  resolve: {
    extensions: ['.ts', '.js'],
    fallback: {
      "crypto": false,
      "stream": false,
      "util": false,
      "buffer": false,
      "process": false
    }
  },
  module: {
    rules: [
      {
        test: /\.ts$/,
        use: 'ts-loader',
        exclude: /node_modules/
      }
    ]
  },
  externals: {
    // peer dependencies
    'eventemitter3': 'eventemitter3'
  },
  optimization: {
    minimize: true,
    sideEffects: false
  },
  stats: {
    warnings: false
  }
}; 