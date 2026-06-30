# cloud-itonami Integration

`cloud-itonami-*` repositories publish business models. `kotoba-industry`
declares what technology capabilities are required to run them.

Runtime flow:

```text
cloud-itonami-{isic}
        |
        v
kotoba.industry/execution-plan
        |
        v
kotoba.technology/stack
        |
        v
concrete repos and operator services
```

The business UI should show:

- operating states
- required technologies
- missing technologies
- available operator services
- proof and audit contracts

The business should not import a CFD or EDA implementation directly. It should
request the capability contract from `kotoba-technology`.
