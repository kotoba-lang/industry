import aiofiles
import asyncio
import os
import requests
import json
from datetime import datetime
from typing import Dict, Any, List
from temporalio import activity
from loguru import logger

from src.shared.models import VideoAnalysisRequest, HumeAIJobResponse, VideoEmotionResult, EmotionPrediction


class HumeAIAnalyzer:
    def __init__(self, api_key: str, base_url: str = "https://api.hume.ai"):
        self.api_key = api_key
        self.base_url = base_url
        self.headers = {
            "Authorization": f"Bearer {self.api_key}",
            "Content-Type": "application/json"
        }

    async def upload_video(self, video_path: str) -> str:
        """Upload video file to HumeAI and return media URL"""
        upload_url = f"{self.base_url}/v0/batch/media"

        async with aiofiles.open(video_path, 'rb') as f:
            video_data = await f.read()

        files = {
            'file': (video_path.split('/')[-1], video_data, 'video/webm')
        }

        response = requests.post(upload_url, headers=self.headers, files=files)

        if response.status_code != 200:
            raise Exception(f"Failed to upload video: {response.text}")

        result = response.json()
        return result['url']

    async def start_emotion_analysis(self, media_url: str, config: Dict[str, Any] = None) -> str:
        """Start emotion analysis job"""
        job_url = f"{self.base_url}/v0/batch/jobs"

        if config is None:
            config = {
                "models": {
                    "face": {
                        "facs": {},
                        "descriptions": {},
                        "identities": {}
                    },
                    "prosody": {
                        "granularities": [
                            {
                                "name": "utterance",
                                "length": 4.0
                            },
                            {
                                "name": "conversation",
                                "length": 10.0
                            }
                        ]
                    },
                    "language": {
                        "granularities": [
                            {
                                "name": "word",
                                "length": 1
                            },
                            {
                                "name": "sentence",
                                "length": 2
                            }
                        ]
                    },
                    "burst": {},
                    "ner": {}
                }
            }

        payload = {
            "urls": [media_url],
            "json": config
        }

        response = requests.post(job_url, headers=self.headers, json=payload)

        if response.status_code != 200:
            raise Exception(f"Failed to start analysis job: {response.text}")

        result = response.json()
        return result['job_id']

    async def check_job_status(self, job_id: str) -> Dict[str, Any]:
        """Check the status of an analysis job"""
        status_url = f"{self.base_url}/v0/batch/jobs/{job_id}"

        response = requests.get(status_url, headers=self.headers)

        if response.status_code != 200:
            raise Exception(f"Failed to check job status: {response.text}")

        return response.json()

    async def get_job_results(self, job_id: str) -> Dict[str, Any]:
        """Get the results of a completed analysis job"""
        results_url = f"{self.base_url}/v0/batch/jobs/{job_id}/results"

        response = requests.get(results_url, headers=self.headers)

        if response.status_code != 200:
            raise Exception(f"Failed to get job results: {response.text}")

        return response.json()

    async def wait_for_completion(self, job_id: str, timeout_seconds: int = 300) -> Dict[str, Any]:
        """Wait for job completion with polling"""
        start_time = datetime.now()

        while (datetime.now() - start_time).seconds < timeout_seconds:
            status_data = await self.check_job_status(job_id)

            if status_data['state'] == 'COMPLETED':
                return await self.get_job_results(job_id)
            elif status_data['state'] == 'FAILED':
                raise Exception(f"Analysis job failed: {status_data}")

            logger.info(f"Job {job_id} status: {status_data['state']}, waiting...")
            await asyncio.sleep(10)

        raise Exception(f"Job {job_id} timed out after {timeout_seconds} seconds")

    def parse_emotion_results(self, raw_results: Dict[str, Any]) -> List[EmotionPrediction]:
        """Parse HumeAI results into emotion predictions"""
        emotions = []

        # Parse face emotions
        if 'face' in raw_results:
            for prediction in raw_results['face'].get('predictions', []):
                for emotion_data in prediction.get('emotions', []):
                    emotions.append(EmotionPrediction(
                        emotion=emotion_data.get('name', ''),
                        score=emotion_data.get('score', 0.0),
                        confidence=emotion_data.get('confidence', 0.0)
                    ))

        # Parse prosody emotions
        if 'prosody' in raw_results:
            for prediction in raw_results['prosody'].get('predictions', []):
                for emotion_data in prediction.get('emotions', []):
                    emotions.append(EmotionPrediction(
                        emotion=emotion_data.get('name', ''),
                        score=emotion_data.get('score', 0.0),
                        confidence=emotion_data.get('confidence', 0.0)
                    ))

        return emotions


@activity.defn
async def analyze_video_emotions(request: VideoAnalysisRequest) -> VideoEmotionResult:
    """Temporal Activity to analyze video emotions using HumeAI"""
    logger.info(f"Starting emotion analysis for {request.video_filename}")

    # Get HumeAI API key from environment
    api_key = os.getenv('HUME_API_KEY')
    if not api_key:
        raise ValueError("HUME_API_KEY environment variable is required")

    analyzer = HumeAIAnalyzer(api_key)

    try:
        # Step 1: Upload video
        logger.info(f"Uploading video: {request.video_path}")
        media_url = await analyzer.upload_video(request.video_path)

        # Step 2: Start analysis job
        logger.info(f"Starting analysis job for media URL: {media_url}")
        job_id = await analyzer.start_emotion_analysis(media_url)

        # Step 3: Wait for completion
        logger.info(f"Waiting for job {job_id} to complete")
        raw_results = await analyzer.wait_for_completion(job_id)

        # Step 4: Parse results
        emotions = analyzer.parse_emotion_results(raw_results)

        result = VideoEmotionResult(
            session_id=request.session_id,
            video_filename=request.video_filename,
            job_id=job_id,
            emotions=emotions,
            metadata=raw_results,
            analyzed_at=datetime.now()
        )

        logger.info(f"Successfully analyzed {request.video_filename}, found {len(emotions)} emotion predictions")
        return result

    except Exception as e:
        logger.error(f"Failed to analyze video {request.video_filename}: {str(e)}")
        raise
