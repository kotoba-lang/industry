Sūrya's Description Report

Files Description Table

| File Name                                                           | SHA-1 Hash                               |
| ------------------------------------------------------------------- | ---------------------------------------- |
| /Users/yamadakinya/truffle/XLE/contracts/token/ERC1404/IERC1404.sol | 169afe6beae8b5918eebee0a61d5a72cfde4b26e |
| /Users/yamadakinya/truffle/XLE/contracts/access/WhiteList.sol       | 703f346fdc17e6e3fa141872dd9f355499211ef0 |
| /Users/yamadakinya/truffle/XLE/contracts/access/Ownable.sol         | a8d8992afac22b07eb12c5038acf4087335cbd8b |
| /Users/yamadakinya/truffle/XLE/contracts/GSN/Context.sol            | cf387314a45d46468495dda572fa3005a34f41bb |
| /Users/yamadakinya/truffle/XLE/contracts/access/AccessControl.sol   | e983ec211693bb20e2535ebb7a01fe30784ee62e |
| /Users/yamadakinya/truffle/XLE/contracts/utils/EnumerableSet.sol    | 1e61fa3b4b64d916454696e2d8f9593d1e1b0fa9 |
| /Users/yamadakinya/truffle/XLE/contracts/utils/Address.sol          | dd9b39b93f934f62af46a3941c25783a078b17bf |
| /Users/yamadakinya/truffle/XLE/contracts/token/ERC20/ERC20.sol      | 8b41fd403d9c8f70e4b5cc6872b21f45ffcec6cf |
| /Users/yamadakinya/truffle/XLE/contracts/token/ERC20/IERC20.sol     | db73fd820142d6bbb59ac38796f9d946836a94bc |
| /Users/yamadakinya/truffle/XLE/contracts/math/SafeMath.sol          | 6f4bfe5933d90777e3fdb29ec31d1c38b8984afa |
| /Users/yamadakinya/truffle/XLE/contracts/token/TokenHolderList.sol  | b89802a566d1641cdfb07771cf3f76d653fce1f6 |

Contracts Description Table

|      Contract       |             Type              |         Bases          |                |               |
| :-----------------: | :---------------------------: | :--------------------: | :------------: | :-----------: |
|          └          |       **Function Name**       |     **Visibility**     | **Mutability** | **Modifiers** |
|                     |                               |                        |                |               |
|    **IERC1404**     |           Interface           |                        |                |               |
|          └          |   detectTransferRestriction   |      External ❗️      |                |     NO❗️     |
|          └          | messageForTransferRestriction |      External ❗️      |                |     NO❗️     |
|                     |                               |                        |                |               |
|    **WhiteList**    |        Implementation         | Ownable, AccessControl |                |               |
|          └          |     addAddressToWhiteList     |       Public ❗️       |       🛑       |   onlyOwner   |
|          └          |         isInWhiteList         |       Public ❗️       |                |     NO❗️     |
|          └          |    addAddressesToWhiteList    |       Public ❗️       |       🛑       |   onlyOwner   |
|          └          |  removeAddressFromWhiteList   |       Public ❗️       |       🛑       |   onlyOwner   |
|          └          | removeAddressesFromWhiteList  |       Public ❗️       |       🛑       |   onlyOwner   |
|                     |                               |                        |                |               |
|     **Ownable**     |        Implementation         |        Context         |                |               |
|          └          |         <Constructor>         |      Internal 🔒       |       🛑       |               |
|          └          |             owner             |       Public ❗️       |                |     NO❗️     |
|          └          |       renounceOwnership       |       Public ❗️       |       🛑       |   onlyOwner   |
|          └          |       transferOwnership       |       Public ❗️       |       🛑       |   onlyOwner   |
|                     |                               |                        |                |               |
|     **Context**     |        Implementation         |                        |                |               |
|          └          |          \_msgSender          |      Internal 🔒       |                |               |
|          └          |           \_msgData           |      Internal 🔒       |                |               |
|                     |                               |                        |                |               |
|  **AccessControl**  |        Implementation         |        Context         |                |               |
|          └          |            hasRole            |       Public ❗️       |                |     NO❗️     |
|          └          |      getRoleMemberCount       |       Public ❗️       |                |     NO❗️     |
|          └          |         getRoleMember         |       Public ❗️       |                |     NO❗️     |
|          └          |         getRoleAdmin          |       Public ❗️       |                |     NO❗️     |
|          └          |           grantRole           |       Public ❗️       |       🛑       |     NO❗️     |
|          └          |          revokeRole           |       Public ❗️       |       🛑       |     NO❗️     |
|          └          |         renounceRole          |       Public ❗️       |       🛑       |     NO❗️     |
|          └          |          \_setupRole          |      Internal 🔒       |       🛑       |               |
|          └          |        \_setRoleAdmin         |      Internal 🔒       |       🛑       |               |
|          └          |          \_grantRole          |       Private 🔐       |       🛑       |               |
|          └          |         \_revokeRole          |       Private 🔐       |       🛑       |               |
|                     |                               |                        |                |               |
|  **EnumerableSet**  |            Library            |                        |                |               |
|          └          |             \_add             |       Private 🔐       |       🛑       |               |
|          └          |           \_remove            |       Private 🔐       |       🛑       |               |
|          └          |          \_contains           |       Private 🔐       |                |               |
|          └          |           \_length            |       Private 🔐       |                |               |
|          └          |             \_at              |       Private 🔐       |                |               |
|          └          |              add              |      Internal 🔒       |       🛑       |               |
|          └          |            remove             |      Internal 🔒       |       🛑       |               |
|          └          |           contains            |      Internal 🔒       |                |               |
|          └          |            length             |      Internal 🔒       |                |               |
|          └          |              at               |      Internal 🔒       |                |               |
|          └          |              add              |      Internal 🔒       |       🛑       |               |
|          └          |            remove             |      Internal 🔒       |       🛑       |               |
|          └          |           contains            |      Internal 🔒       |                |               |
|          └          |            length             |      Internal 🔒       |                |               |
|          └          |              at               |      Internal 🔒       |                |               |
|                     |                               |                        |                |               |
|     **Address**     |            Library            |                        |                |               |
|          └          |          isContract           |      Internal 🔒       |                |               |
|          └          |           sendValue           |      Internal 🔒       |       🛑       |               |
|          └          |         functionCall          |      Internal 🔒       |       🛑       |               |
|          └          |         functionCall          |      Internal 🔒       |       🛑       |               |
|          └          |     functionCallWithValue     |      Internal 🔒       |       🛑       |               |
|          └          |     functionCallWithValue     |      Internal 🔒       |       🛑       |               |
|          └          |    \_functionCallWithValue    |       Private 🔐       |       🛑       |               |
|                     |                               |                        |                |               |
|      **ERC20**      |        Implementation         |    Context, IERC20     |                |               |
|          └          |         <Constructor>         |       Public ❗️       |       🛑       |     NO❗️     |
|          └          |             name              |       Public ❗️       |                |     NO❗️     |
|          └          |            symbol             |       Public ❗️       |                |     NO❗️     |
|          └          |           decimals            |       Public ❗️       |                |     NO❗️     |
|          └          |          totalSupply          |       Public ❗️       |                |     NO❗️     |
|          └          |           balanceOf           |       Public ❗️       |                |     NO❗️     |
|          └          |           transfer            |       Public ❗️       |       🛑       |     NO❗️     |
|          └          |           allowance           |       Public ❗️       |                |     NO❗️     |
|          └          |            approve            |       Public ❗️       |       🛑       |     NO❗️     |
|          └          |         transferFrom          |       Public ❗️       |       🛑       |     NO❗️     |
|          └          |       increaseAllowance       |       Public ❗️       |       🛑       |     NO❗️     |
|          └          |       decreaseAllowance       |       Public ❗️       |       🛑       |     NO❗️     |
|          └          |          \_transfer           |      Internal 🔒       |       🛑       |               |
|          └          |            \_mint             |      Internal 🔒       |       🛑       |               |
|          └          |            \_burn             |      Internal 🔒       |       🛑       |               |
|          └          |           \_approve           |      Internal 🔒       |       🛑       |               |
|          └          |        \_setupDecimals        |      Internal 🔒       |       🛑       |               |
|                     |                               |                        |                |               |
|     **IERC20**      |           Interface           |                        |                |               |
|          └          |          totalSupply          |      External ❗️      |                |     NO❗️     |
|          └          |           balanceOf           |      External ❗️      |                |     NO❗️     |
|          └          |           transfer            |      External ❗️      |       🛑       |     NO❗️     |
|          └          |           allowance           |      External ❗️      |                |     NO❗️     |
|          └          |            approve            |      External ❗️      |       🛑       |     NO❗️     |
|          └          |         transferFrom          |      External ❗️      |       🛑       |     NO❗️     |
|                     |                               |                        |                |               |
|    **SafeMath**     |            Library            |                        |                |               |
|          └          |              add              |      Internal 🔒       |                |               |
|          └          |              sub              |      Internal 🔒       |                |               |
|          └          |              sub              |      Internal 🔒       |                |               |
|          └          |              mul              |      Internal 🔒       |                |               |
|          └          |              div              |      Internal 🔒       |                |               |
|          └          |              div              |      Internal 🔒       |                |               |
|          └          |              mod              |      Internal 🔒       |                |               |
|          └          |              mod              |      Internal 🔒       |                |               |
|                     |                               |                        |                |               |
| **TokenHolderList** |        Implementation         |                        |                |               |
|          └          |    addAddressToHolderList     |       Public ❗️       |       🛑       |     NO❗️     |
|          └          |  removeAddressFromHolderList  |       Public ❗️       |       🛑       |     NO❗️     |
|          └          |   \_addAddressToHolderList    |      Internal 🔒       |       🛑       |               |
|          └          | \_removeAddressFromHolderList |      Internal 🔒       |       🛑       |               |
|          └          |     getAllHolderAddresses     |       Public ❗️       |                |     NO❗️     |
|          └          |           isHolder            |       Public ❗️       |                |     NO❗️     |
|          └          |         getHolderSize         |       Public ❗️       |                |     NO❗️     |

Legend

| Symbol | Meaning                   |
| :----: | ------------------------- |
|   🛑   | Function can modify state |
|   💵   | Function is payable       |
