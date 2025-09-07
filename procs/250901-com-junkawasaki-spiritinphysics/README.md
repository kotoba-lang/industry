# Spirit in Physics - Research Process v2.0

This process is for experimental research on spirituality, based on Jung's word association test and computational modeling.

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