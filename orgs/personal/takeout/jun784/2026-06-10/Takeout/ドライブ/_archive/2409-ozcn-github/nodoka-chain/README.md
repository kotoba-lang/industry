## BURN

Implementation of BURN Blockchain System

[API Reference](https://documenter.getpostman.com/view/4068273/TVCb4AoH)

## Install `burn`
### Prepare database
BURN uses MySQL as its backend data storage. Setup MySQL server somewhere accessible from the BURN server.
- Prepare MySQL server (installing on localhost, or setup on Cloud Service such as Amazon RDS.)
- Create a database on it and get `username`, `password`, and `database`.

### Install modules
```shell
$ npm i
```

### Building the source
```shell
$ npm run build
```

### Configure .env

#### Blockchain Config
| Parameter | Description |
| --------- | ---------------------------------------- |
| PORT | JSON-RPC endpoint listening port number |
| CHAIN_ID | Chain ID returned for 'net_info' |
| CHAIN_NAME | Chain Name returned for API 'Blockchain' |
| NETWORK_OWNER | Chain's owner address receiving all the collected gas fee |
| BLOCK_INTERVAL | Interval seconds to group up transactions in a block |
| BLOCK_PRODUCING_INTERVAL | Interval ms to run block producing batch process  |
| CHECKPOINT_INTERVAL | Number of blocks including in a checkpoint |
| CHECKPOINT_PRODUCING_INTERVAL | Interval ms to run checkpoint producing batch process  |
| CHECKPOINT_TX_WAIT_INTERVAL | Interval in ms to check transaction receipt for checkpoint transaction |
| CHECKPOINT_TX_MAX_WAIT_TIME | Max waiting time in ms for checkpoint transaction to be confirmed |

#### Chaindata Storage
| Parameter | Description |
| --------- | ---------------------------------------- |
| DB_TYPE | Currently only supports `mysql` |
| DB_HOST | MySQL database hostname |
| DB_USERNAME | MySQL database username |
| DB_PASSWORD | MySQL database pssword |
| DB_DATABASE | MySQL database name |
| DB_PORT | MySQL database port |

#### Transaction Fee
| Parameter | Description |
| --------- | ---------------------------------------- |
| GAS_CREATE_TOKEN | Gas consumption for creating a token |
| GAS_MINT_TOKEN | Gas consumption for minting (issuing) token balance |
| GAS_BURN_TOKEN | Gas consumption for burning token balance |
| GAS_TRANSFER_TOKEN | Gas consumption for transferring token balance |
| GAS_CREATE_KVS | Gas consumption for creating a Key-Value store |
| GAS_SET_KEY_VALUE | Gas consumption for setting a Key-Value data |
| GAS_DELETE_KEY_VALUE | Gas consumption for deleting a Key-Value data |

#### Ethereum Anchoring
| Parameter | Description |
| --------- | ---------------------------------------- |
| CONTRACT_ADDRESS | Checkpoint Smart Contract address on Ethereum |
| PROVIDER_URL | Ethereum node endpoint URL |
| ETH_GAS_API_KEY | Ethgasstation (Defipulse) API Key |
| ETH_ADDRESS | Ethereum address to send a checkpoint transaction |
| PRIVATE_KEY | Private key corresponding to the ETH_ADDRESS |
| WAIT_TX_INTERVAL | Timeout in seconds for getting transaction receipt |
| MAX_TX_INTERVAL_TIME | Timeout in seconds for waiting a confirmation |

#### Block Explorer
| Parameter | Description |
| --------- | ---------------------------------------- |
| BLOCK_EXPLORER_URL | Igniscan deployed domain and URL |
| BLOCK_EXPLORER_PORT | Igniscan deployed port number |

## Database setup

```shell
npm run typeorm migration:run
```

## Run `burn` process

```shell
npm start
```
or use `pm2` process manager
```shell
pm2 start ./dist/server.js burn
```
