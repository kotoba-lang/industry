# Gas Mechanism

## Gas for each operation
We define gas fee in static native token amount for each operation. We don't use Ethereum-like gasPrice and gasLimit style.

- GAS_CREATE_TOKEN
- GAS_MINT_TOKEN
- GAS_BURN_TOKEN
- GAS_TRANSFER_TOKEN
- GAS_CREATE_KVS
- GAS_SET_KEY_VALUE
- GAS_DELETE_KEY_VALUE

These values are configured in .env. At the first launch of the BURN network in the initialization process, these values are loaded from .env, and persisted. Collected gas will be transferred into NETWORK_OWNER also configured in .env.

## Token related gas

### Create/Mint/Burn Token
- When a new token is created, the sender of the transaction is charged GAS_CREATE_TOKEN amount of native token as a fee.
- The sender created the token is set as `Token Owner` of the created token.
- For each token, we can define `fee_token_id`, `fee`, and `fee_rate`.
- If the token is configured 'mintable', the `Token Owner` can mint (issue) token. The 'Token Owner' is charged GAS_MINT_TOKEN as a gas fee.
- If the token is configured 'burnable', the `Token Owner` can burn token. The 'Token Owner' is charged GAS_BURN_TOKEN as a gas fee.

### Transfer Token
- If `fee_token_id` is not set or set to '0x0000000000000000000000000000000000000000'
  - The token sender pays gas fee, with amount GAS_TRANSFER_TOKEN to NETWORK_OWNER.
- If `fee_token_id` is set other than the native token
  - The token sender pays gas in `fee_token_id` token to `Token Owner`, with amount max(transfer amount * `fee_rate` / 100000, `fee`). Also, `Token Owner` pays native token with amount GAS_TRANFER_TOKEN to NETWORK_OWNER.

## KVS related gas
- When a new KVS (Key-Value Store) is created, the sender of the transaction is charged GAS_CREATE_STORE amount of native token as a fee.
- The sender created the KVS is set as `Store Owner` of the created KVS.
- For each KVS, we can define `fee_token_id`, `fee`, and `fee_rate`.
- If `fee_token_id` is not set or set to '0x0000000000000000000000000000000000000000'
  - The Set/Delete transaction sender pays gas fee, with amount GAS_SET/DELETE_TOKEN to NETWORK_OWNER.
- If `fee_token_id` is set other than the native token
  - The Set/Delete transaction sender pays gas in `fee_token_id` token to `Store Owner`, with amount max(transfer amount * `fee_rate` / 100000, `fee`). Also, `Token Owner` pays native token with amount GAS_SET/DELETE_TOKEN to NETWORK_OWNER.

