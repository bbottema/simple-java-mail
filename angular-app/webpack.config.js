var webpack = require('webpack');
var path = require('path');
var HtmlWebpackPlugin = require('html-webpack-plugin');

var PROD = false;

// Webpack Config
var webpackConfig = {
  entry: {
    'polyfills': './src/polyfills.browser.ts',
    'vendor': './src/vendor.ts',
    'external': './src/external.ts',
    'app': './src/main.browser.ts'
  },

  output: {
    publicPath: '',
    path: path.resolve(__dirname, './dist'),
  },

  plugins: (PROD ? [
    new webpack.optimize.UglifyJsPlugin({
      compress: {warnings: false},
      sourceMap: true
    })
  ] : []).concat([
    new webpack.ContextReplacementPlugin(
      // The (\\|\/) piece accounts for path separators in *nix and Windows
      /angular([\\/])core([\\/])@angular/,
      path.resolve(__dirname, './src'), {}
    ),
    new webpack.optimize.CommonsChunkPlugin({name: ['app', 'external', 'vendor', 'polyfills'], minChunks: Infinity}),
    new HtmlWebpackPlugin({
      template: 'src/index.html'
    })
  ]),

  module: {
    rules: [
      // .ts files for TypeScript
      {
        test: /\.ts$/,
        use: [
          { loader: 'awesome-typescript-loader' },
          { loader: 'angular2-template-loader' },
          { loader: 'angular2-router-loader' }
        ]
      },
      {test: /\.less$/, use: ['raw-loader', 'less-loader']},
      {test: /\.css$/, use: ['style-loader', 'css-loader']},
      {test: /\.(jpe?g|png|gif|svg)$/i, use: ['url-loader']},
      {test: /\.html$/, use: ['html-loader?-minimize']}
    ]
  }

};


// Our Webpack Defaults
var defaultConfig = {
  devtool: 'cheap-module-source-map',

  output: {
    filename: '[name].bundle.js',
    sourceMapFilename: '[name].map',
    chunkFilename: '[id].chunk.js'
  },

  resolve: {
    extensions: ['.ts', '.js'],
    modules: [ path.resolve(__dirname, 'node_modules') ]
  },

  devServer: {
    historyApiFallback: true,
    watchOptions: { aggregateTimeout: 300, poll: 1000 },
    headers: {
      "Access-Control-Allow-Origin": "*",
      "Access-Control-Allow-Methods": "GET, POST, PUT, DELETE, PATCH, OPTIONS",
      "Access-Control-Allow-Headers": "X-Requested-With, content-type, Authorization"
    }
  },

  node: {
    global: true,
    crypto: 'empty',
    __dirname: true,
    __filename: true,
    process: true,
    Buffer: false,
    clearImmediate: false,
    setImmediate: false
  }
};

var webpackMerge = require('webpack-merge');
module.exports = webpackMerge(defaultConfig, webpackConfig);
