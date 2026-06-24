# Transaction Hash and Signature

## Transaction Hash
We use the Ethereum compatible transaction data structure. Transaction hash will be calculated from the following items.

- nonce
- gasPrice
- gasLimit
- to
- value
- data
- chainId
- 0
- 0

Using RLP (Recursive Length Prefix) encoding, pack this structure into bytes. Hash the bytes value with `keccak256` function to get a hash value for signature. This hash is called Transaction Hash (or Transaction ID).

Sign the hash value using ECDSA (Elliptic Curve Digital Signature Algorithm) to derive a signature. This signature is represented as 3 values (`v`, `r`, `s`). The value `v` is calculated by the following formula. This will help avoiding "Replay Attach" by checking the value `v` is valid for the network or not.

```
v: chainId * 2 + 8 + v
```

Concatenate the value `v`, `r`, `s` to get the signature string.

### nonce
`nonce` is a positive integer value unique for the same transaction data from the same address. It's necessary to be unique, but not necessary to be consecutive (Ethereum doesn't allow skipping numbers for noce, but BURN does). `burnweb` uses system time in ms as a nonce of sending transaction.

### gasPrice, gasLimit
Ethereum compatible tools, such as MetaMask, specifies `gasPrice` and `gasLimit` parameters in the transaction data. To keep the compatibility with it, BURN includes these parameters in Transaction Hash deriving process. The transactions sent via `burnweb` or `BURN REST API` doesn't have `gasPrice` and `gasLimit` parameters, and BURN always regards them as `0`.

### to, value, data
Ethereum compatible tools handle the parameters `to` and `value` differently for transfering ETH or other ERC20 tokens.

- ETH (or a native token for the network):
  - to: The address to which a sender transfer the token.
  - value: The amount of ETH to transfer.
  - data: empty or '0x'
  > On Ethereum, a sender can specify `data` when sending ETH to a smart contract. BURN ignores `data` for processing token transfer, but uses `data` in Transaction Hash deriving, and signing process.

- ERC20 token:
  - to: ERC20 token smart contract address
  - value: 0
  > On Ethereum, a sender can transfer ETH at the same time by specifying `value`. BURN ignores `data` for processing token transfer, but uses `value` in Transaction Hash deriving, and signing process.
  - data: RLP encoded value of an array data ['a9059cbb', to address, amount]  
  > 'a9059cbb' is the hash value representing 'transfer(address,uin256)'

`burnweb` and `BURN REST API` take care of the `to` and `value` keeping compatibility with the above mechanism.

- Native token:
  - to: The address to which a sender transfer the token.
  - value: The amount of the native token to transfer.
  - data: '0x'
  > The native token is configured in .env, and internally it's represented as tokenId = '0x000000000000000000000000000000000000'

- Other tokens:
  - to: tokenId
  - value: 0
  - data: RLP encoded value of an array data ['a9059cbb', to address, amount]

Transactions to Create Token, Create BURN KVS, Set/Delete Key-Value use `to` and `value` as follows:
- to: Targetted token's tokenId or KVS's storeId
- value: 0
- data: RLP encoded value of parameters array according to each action

### chainId
Unique number (positive integer) for each blockchain. Ethereum Mainnet uses a chainId `1`. There are some well-known chainIds:

| chainId | Network Name |
| :--------- | :----------------------- |
| 1 | Mainnet |
| 3 | Ropsten |
| 4 | Rinkeby |
| 42 | Kovan |
| 61 | Ethereum Classic Mainnet |

In BURN, configure chainId other than above listed.

