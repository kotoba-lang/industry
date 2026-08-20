# Config
Switch the following files for each environment:
- src/constants/index.ts
- src/constants/interface.ts
- src/constants/tokens.ts
- src/token-config.json

For Mainnet Environment
```
cp env_specific_files/mainnet/constants/*.ts src/constants/
cp env_specific_files/mainnet/token-config.json src/
```

For Testnet Environment
```
cp env_specific_files/testnet/constants/*.ts src/constants/
cp env_specific_files/testnet/token-config.json src/
