# Spirit in Physics - Research Process v2.0

This process is for experimental research on spirituality, based on Jung's word association test and computational modeling.

## 🏗️ Architecture

This project implements **Hexagonal Architecture + CQRS** pattern with the following layered structure:

```
src/
├── 00_schema/            # zod等の型・定数（無依存）
├── 10_events/            # CMD_*/EV_* 列挙（有限語彙）
├── 20_ports/             # 抽象Port（ドメインが依存するだけ）
├── 30_fold/              # 純関数（MDAG -> 投影）※副作用禁止
├── 40_domain/            # xstate machines（UI非依存）
├── 50_adapters/          # RouteHandler/ServerActions/外部API実装
├── 60_projection/        # selectors/ViewModel（foldの薄ラッパ）
├── 70_supervisors/       # ルート単位の調停（invalidate/revalidate）
└── 80_app/               # app/(segments)/...（RSC & Client）
```

### Architecture Rules

- **Dependency Direction**: Higher layers can import from lower layers, but not vice versa
- **Layer Boundaries**: Each layer has a clear responsibility and dependency constraints
- **CQRS Pattern**: Commands and Events are clearly separated and enumerated
- **Pure Functions**: Fold functions are side-effect free and deterministic
- **Port/Adapter Pattern**: Domain depends only on abstract ports, not concrete implementations

### Layer Responsibilities

- **00_schema**: Type definitions, schemas, and constants (no dependencies)
- **10_events**: Command and event enumerations (finite vocabulary)
- **20_ports**: Abstract interfaces that domain depends on
- **30_fold**: Pure functions that project MerkleDAG to current state
- **40_domain**: XState machines for business logic (UI-independent)
- **50_adapters**: Concrete implementations of ports (API handlers, external services)
- **60_projection**: Selectors and ViewModels (thin wrappers around fold)
- **70_supervisors**: Route-level orchestration (cache invalidation/revalidation)
- **80_app**: Next.js application (RSC & Client components)

## 🚀 Vercel Deployment

This project is configured to deploy on Vercel with Blob Storage for artifact management.

### Prerequisites

- Vercel account
- Vercel CLI installed (`npm i -g vercel`)

### Deployment Steps

1. **Install dependencies:**
   ```bash
   npm install
   ```

2. **Create Vercel project:**
   ```bash
   vercel
   ```
   Follow the prompts to create a new project.

3. **Set up Blob Storage:**
   - Go to your Vercel dashboard
   - Navigate to your project settings
   - Go to "Storage" tab
   - Create a new Blob store
   - Copy the `BLOB_READ_WRITE_TOKEN`

4. **Set environment variables:**
   ```bash
   vercel env add BLOB_READ_WRITE_TOKEN
   ```
   Paste the token from step 3.

5. **Deploy:**
   ```bash
   vercel --prod
   ```

### Artifact Migration

If you have existing artifacts in `.artifacts_cache`, migrate them to Blob Storage:

```bash
npm run migrate-artifacts
```

### Environment Variables

- `BLOB_READ_WRITE_TOKEN`: Vercel Blob Storage token (automatically set via Vercel dashboard)

### Blob Storage Structure

Artifacts are stored with the following structure:
```
artifacts/
├── {participantId}/
│   ├── consent/
│   │   └── {id}-consent.json
│   ├── session_data/
│   │   └── {id}-session_data.json
│   ├── video/
│   │   └── {id}-session-{n}-video.webm
│   └── audio/
│       └── {id}-audio.mp3
```

### API Endpoints

- `POST /api/save-artifact`: Upload artifacts (videos, audio files)
- `POST /api/save-data`: Save structured data (consent, session data)

All data is automatically stored in Vercel Blob Storage.