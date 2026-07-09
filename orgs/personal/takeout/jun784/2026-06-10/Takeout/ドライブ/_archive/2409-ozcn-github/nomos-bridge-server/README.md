## Description
Bridge program between two Ethereum compatible blockchains

## Prerequisite
Deploy the Bridge contract on the both chains.

## Configuration
.env
| Parameter | Description |
| --------- | ---------------------------------------- |
| MYSQL_HOST | MySQL hostname |
| DATABASE_TYPE | mysql |
| MYSQL_DATABASE | Database name |
| MYSQL_USER | Database username |
| MYSQL_PASSWORD | Database password |
| MYSQL_PORT | MySQL port (usually 3306) |
| BRIDGE_OWNER_PRIVATE_KEY | Bridge contract's owner address |
| CHAIN1_START_BLOCK_NUMBER | (optional) Event monitoring starts from this block number on the 1st chain |
| CHAIN2_START_BLOCK_NUMBER | (optional) Event monitoring starts from this block number on the 2nd chain |

src/bridge-config.json
| Parameter | Description |
| --------- | ---------------------------------------- |
| privateKey |  |
| interval | Event is monitored in this interval in seconds |
| chain1.rpcEndpoint | JSON-RPC endpoint for the 1st chain |
| chain1.contractAddress | Bridge contract address on the 1st chain |
| chain2.rpcEndpoint | JSON-RPC endpoint for the 2nd chain |
| chain2.contractAddress | Bridge contract address on the 2nd chain |

## Smart contract setup
Example use cases:

| Case | Primary chain | Primary token | Primary token type | Secondary chain | Secondary token | Secondary token type |
| --------- | --------- | --------- | --------- | --------- | --------- | --------- |
| 1 | Ethereum | ETH | Native | BURN | WETH | ERC20 |
| 2 | Ethereum | USDT | ERC20 | BURN | USDT | ERC20 |
| 3 | Ethereum | XXX | ERC20 | BURN | BURN | Native |

- Create the ERC20 token on the secondary chain as mintable & burnable
- Fund enough amount of native token on the Bridge contract on the secondary chain
- Call `setPair` function for each cases:
  - [Ethereum] setPair(`0x0000000000000000000000000000000000000000`, `false`, `false`, `WETH's contract address`)
  - [Ethereum] setPair(`USDT's contract address`, `false`, `false`, `USDT's contract address`)
  - [Ethereum] setPair(`XXX's contract address`, `false`, `false`, `0x0000000000000000000000000000000000000000`)
  - [BURN] setPair(`WETH's contract address`, `true`, `true`, `0x0000000000000000000000000000000000000000`)
  - [BURN] setPair(`USDT's contract address`, `true`, `true`, `USDT's contract address`)
  - [BURN] setPair(`0x0000000000000000000000000000000000000000`, `false`, `false`, `XXX's contract address`)

## Installation

```bash
$ yarn
$ yarn build
$ yarn typeorm migration:run
```

## Running the bridge

```bash
$ yarn start
```

