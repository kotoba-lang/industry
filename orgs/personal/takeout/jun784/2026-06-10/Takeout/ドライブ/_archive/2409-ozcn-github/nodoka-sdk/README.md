## BurnWeb

burnweb is a library for interacting with BURN blockchain via REST API.

Read the [documentation](https://burnweb.readthedocs.io) for more details.

### Installation
```shell
npm install git+https://github.com/{your_repository}/burnweb.git
```

### Usage
```js
// For js
var BurnWeb = require('burnweb');
// For ts
import BurnWeb from 'burnweb';

// Set BURN endpoint URL to initialize
var burnweb = new BurnWeb('http://localhost:8545');

// or, specify private key at the same time
var burnweb = new BurnWeb('http://localhost:8545', privateKey);
```

### Test
```shell
npm install
npm run test
```
