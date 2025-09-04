from pydantic import BaseModel
from typing import List, Dict, Any, Optional
from datetime import datetime


class VideoAnalysisRequest(BaseModel):
    session_id: str
    video_path: str
    video_filename: str


class HumeAIJobResponse(BaseModel):
    job_id: str
    status: str
    created_at: datetime


class EmotionPrediction(BaseModel):
    emotion: str
    score: float
    confidence: float


class VideoEmotionResult(BaseModel):
    session_id: str
    video_filename: str
    job_id: str
    emotions: List[EmotionPrediction]
    metadata: Dict[str, Any]
    analyzed_at: datetime


class AnalysisResult(BaseModel):
    session_id: str
    results: List[VideoEmotionResult]
    status: str
    completed_at: Optional[datetime] = None
