# JSON-RPC Interface (Ethereum compatible)

BURN serves JSON-RPC interface compatible with Ethereum client like MetaMask.

## Endpoint
The JSON-RPC endpoint is serving at `http://{hostname}:{PORT}`, where `{hostname}` is the server hostname or IP address, and `{PORT}` is configured in .env.

## JSON-RPC methods
### net_version
Get current chain id. Ethereum client use this value in the signing process to avoid replay attack.
- Parameters: None
- Returns: CHAIN_ID configured in .env
- Example:

```
// Request
{
  "jsonrpc": "2.0",
  "id": 1,
  "method": "net_version",
  "params": []
}

// Result
{
    "jsonrpc": "2.0",
    "id": 1,
    "result": "4329815"
}
```

### eth_chainId
Get current chain id. Ethereum client use this value in the signing process to avoid replay attack.
- Parameters: None
- Returns: CHAIN_ID configured in .env
- Example:

```
// Request
{
  "jsonrpc": "2.0",
  "id": 1,
  "method": "eth_chainId",
  "params": []
}

// Result
{
    "jsonrpc": "2.0",
    "id": 1,
    "result": "0x421157" // 4329815
}
```

### eth_blockNumber
Get pseudo block number. This value is monitored by Ethereum client to see the blockchain is alive.
- Parameters: None
- Returns: Pseudo block number as:

```
(current sever time in ms - blockchain started time) / BLOCK_INTERVAL
```

- Example:

```
// Request
{
  "jsonrpc": "2.0",
  "id": 1,
  "method": "eth_blockNumber",
  "params": []
}

// Result
{
  "jsonrpc": "2.0",
  "id":1,
  "result": "0x4b7" // 1207
}
```

### eth_getBalance
Get the balance of the native token of the specified address.
- Parameters: Address to check balance.
- Returns: Balance in hex
- Example:

```
// Request
{
  "jsonrpc": "2.0",
  "id": 1,
  "method": "eth_getBalance",
  "params": [ "0x131D983EA00cb266342e713D6CF273E5bF82b3a4", "latest" ]
}

// Result
{
    "jsonrpc": "2.0",
    "id": 1,
    "result": "0xde0b6b3a7640000" // 1000000000000000000
}
```

### ethTransactionCount
Get pseudo count of transactions for the specified address. Ethereum client uses this value for deriving the next nonce for a sending transaction.
- Parameters: Address to check transaction count.
- Returns: Current server time in ms. It ensures the number keep increasing.

- Example:

```
// Request
{
  "jsonrpc": "2.0",
  "id": 1,
  "method": "eth_getTransactionCount",
  "params": [ "0x131D983EA00cb266342e713D6CF273E5bF82b3a4", "latest" ]
}

// Result
{
    "jsonrpc": "2.0",
    "id": 1,
    "result": "0x1604372040212" // 387317768651282
}
```

### eth_estimateGas
Get estimated gas amount for a transaction. BURN always returns 21000 for Ethereum client compatibility.
- Parameters: None (if specified, BURN ignores.)
- Returns: 0x5208 (21000)

- Example:

```
// Request
{
  "jsonrpc": "2.0",
  "id": 1,
  "method": "eth_getTransactionCount",
  "params": [ "0x131D983EA00cb266342e713D6CF273E5bF82b3a4", "latest" ]
}

// Result
{
    "jsonrpc": "2.0",
    "id": 1,
    "result": "0x1604372040212" // 387317768651282
}
```

### eth_call
Execute a message call without any mutation on the blockchain.
- Parameters:
  - to: The address the message call is directed to.
  - data: Hash of the method definition and encoded parameters.
  - tag: Ethereum client passes "null", "latest" or numbers. BURN ignores this parameter.
- Returns: The return value depends on the method called.

#### Token - name()
Get a token name.
- Parameters:
  - to: Token ID
  - data: The method hash `0x06fdde03` for `name()`
- Returns: RLP encoded value (decode hex to ASCII to get the token name in string)
- Example:

```
// Request
{
  "jsonrpc": "2.0",
  "id": 1,
  "method": "eth_call",
  "params":
   [ { "to": "0x8232875761b97a5242a4cffb94828dff5c101950",
       "data": "0x06fdde03" },
     "latest" ]
}

// Result
{
    "jsonrpc": "2.0",
    "id": 1,
    "result": "0x000000000000000000000000000000000000000000000000000000000000002000000000000000000000000000000000000000000000000000000000000000114255524e204e617469766520546f6b656e000000000000000000000000000000" // BURN Native Token
}
```

#### Token - symbol()
Get a token symbol.
- Parameters:
  - to: Token ID
  - data: The method hash `0x95d89b41` for `symbol()`
- Returns: RLP encoded value (decode hex to ASCII to get the token symbol in string)
- Example:

```
Request
{
  "jsonrpc": "2.0",
  "id": 1,
  "method": "eth_call",
  "params":
   [ { "to": "0x8232875761b97a5242a4cffb94828dff5c101950",
       "data": "0x95d89b41" },
     "latest" ]
}

Result
{
    "jsonrpc": "2.0",
    "id": 1,
    "result": "0x000000000000000000000000000000000000000000000000000000000000002000000000000000000000000000000000000000000000000000000000000000044255524e00000000000000000000000000000000000000000000000000000000" // BURN
}
```

#### Token - decimals()
Get a token decimals.
- Parameters:
  - to: Token ID
  - data: The method hash `0x313ce567` for `decimals()`
- Returns: RLP encoded value (decode hex to number to get decimals)
- Example:

```
Request
{
  "jsonrpc": "2.0",
  "id": 1,
  "method": "eth_estimateGas",
  "params":
  [ { "from": "0x131D983EA00cb266342e713D6CF273E5bF82b3a4",
      "value": "0x0",
      "data":
        "0xa9059cbb00000000000000000000000050b192630d0685570e1dbecaf045011bec139b140000000000000000000000000000000000000000000000000000000000000000",
      "to": "0x94eaf0f5c0952442c74c9af1e3bc068114987082",
      "gas": "0x904bc3"
    }
  ]
}

Result
{
    "jsonrpc": "2.0",
    "id": 1,
    "result": "0x5208" // 21000
}
```

### eth_sendRawTransaction
Send a signed transaction to the blockchain.
- Parameters: The signed transaction data (See `BURN_Signature_Spec.md`)
- Returns: The transaction hash (transaction ID, also see `BURN_Signature_Spec.md`)
- Example:
  - Transfer Native Token (regarded as `ETH` on Ethereum client side)

```
// Request
{
  "jsonrpc": "2.0",
  "id": 1,
  "method": "eth_sendRawTransaction",
  "params":[
    "0xf8768701604372956767850649534e008252089450b192630d0685570e1dbecaf045011bec139b14880de0b6b3a764000080838422d2a0635c6ce7e6c404cb76d62e3577d3a6cfd277496ccbb0302d90a803c0696cf616a01583077e56f2a27c7619e77fc807d55d2f33f376410fc6205e309e6b50f89c54"]
}

// Result
{
  "jsonrpc": "2.0",
  "id": 1,
  "result": "0x0548a8edde251705917cdca74e819ff61af7348565f8fb25775e8720dc8b96c6"
}
```

- Transfer Other Token (regarded as `ERC20 Token` on Ethereum client side)

```
// Request
{
  "jsonrpc": "2.0",
  "id": 1,
  "method": "eth_sendRawTransaction",
  "params":[
    "0xf8b387016043742125098506c088e200827b0c94b02d1e0a6680bb1f236acddc4d1797dad9e0041e80b844a9059cbb00000000000000000000000050b192630d0685570e1dbecaf045011bec139b140000000000000000000000000000000000000000000000000000000000000064838422d2a05ca5a3b7e4badf16e3bc430d9cc2013c04eeddd905856ebf6e07ad0cc0aaef57a005258addf8dbe747ef71e438a05d01bd23d3da21e204630282fa335f282a33af"]
}

// Result
{
  "jsonrpc": "2.0",
  "id": 1,
  "result": "0xcc32ca720e2262d4f48c1bbe68232fa8d78e6566cf5b3859f942df9343ac4779"
}
```

### eth_getTransactionReceipt
Get a transaction status.
- Parameters: The transaction hash (transaction ID)
- Returns: Details of the specified transaction in Object.
- Example:

```
// Request
{
  "jsonrpc": "2.0",
  "id": 1,
  "method": "eth_getTransactionReceipt",
  "params": [ "0xcc32ca720e2262d4f48c1bbe68232fa8d78e6566cf5b3859f942df9343ac4779" ]
}

// Result
{
    "jsonrpc": "2.0",
    "id": 1,
    "result": {
        "blockHash": "0xcc32ca720e2262d4f48c1bbe68232fa8d78e6566cf5b3859f942df9343ac4779",
        "blockNumber": 417111,
        "from": "0x2eb796323bdc987f48e7a1c8f0bbd922e5a34f1a",
        "to": "0x50b192630d0685570e1dbecaf045011bec139b14",
        "gasUsed": "0xa",
        "status": "0x1", // Always 0x1
        "transactionHash": "0xcc32ca720e2262d4f48c1bbe68232fa8d78e6566cf5b3859f942df9343ac4779",
        "transactionIndex": "0x1" // Always 0x1
    }
}
```

