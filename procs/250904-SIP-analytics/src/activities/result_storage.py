import json
import os
from datetime import datetime
from pathlib import Path
from typing import List
from temporalio import activity
from loguru import logger

from src.shared.models import VideoEmotionResult, AnalysisResult


@activity.defn
async def save_emotion_analysis_result(result: VideoEmotionResult) -> str:
    """Save individual video emotion analysis result to JSON file"""
    try:
        # Create results directory if it doesn't exist
        results_dir = Path(f"data/{result.session_id}/analysis_results")
        results_dir.mkdir(parents=True, exist_ok=True)

        # Generate filename with timestamp
        timestamp = result.analyzed_at.strftime("%Y%m%d_%H%M%S")
        filename = f"{results_dir}/{result.video_filename}_emotions_{timestamp}.json"

        # Convert result to dict for JSON serialization
        result_data = {
            "session_id": result.session_id,
            "video_filename": result.video_filename,
            "job_id": result.job_id,
            "analyzed_at": result.analyzed_at.isoformat(),
            "emotions": [
                {
                    "emotion": emotion.emotion,
                    "score": emotion.score,
                    "confidence": emotion.confidence
                }
                for emotion in result.emotions
            ],
            "metadata": result.metadata
        }

        # Save to JSON file
        with open(filename, 'w', encoding='utf-8') as f:
            json.dump(result_data, f, indent=2, ensure_ascii=False)

        logger.info(f"Saved emotion analysis result to {filename}")
        return filename

    except Exception as e:
        logger.error(f"Failed to save emotion analysis result: {str(e)}")
        raise


@activity.defn
async def save_batch_analysis_results(results: List[VideoEmotionResult], session_id: str) -> str:
    """Save batch analysis results to a summary JSON file"""
    try:
        # Create results directory
        results_dir = Path(f"data/{session_id}/analysis_results")
        results_dir.mkdir(parents=True, exist_ok=True)

        # Generate summary filename
        timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
        summary_filename = f"{results_dir}/batch_analysis_summary_{timestamp}.json"

        # Create summary data
        summary_data = {
            "session_id": session_id,
            "analysis_summary": {
                "total_videos": len(results),
                "completed_at": datetime.now().isoformat(),
                "videos": []
            }
        }

        # Add individual video results
        for result in results:
            video_summary = {
                "video_filename": result.video_filename,
                "job_id": result.job_id,
                "emotion_count": len(result.emotions),
                "analyzed_at": result.analyzed_at.isoformat(),
                "top_emotions": sorted(
                    [
                        {
                            "emotion": emotion.emotion,
                            "score": emotion.score,
                            "confidence": emotion.confidence
                        }
                        for emotion in result.emotions
                    ],
                    key=lambda x: x["score"],
                    reverse=True
                )[:5]  # Top 5 emotions
            }
            summary_data["analysis_summary"]["videos"].append(video_summary)

        # Save summary to JSON file
        with open(summary_filename, 'w', encoding='utf-8') as f:
            json.dump(summary_data, f, indent=2, ensure_ascii=False)

        logger.info(f"Saved batch analysis summary to {summary_filename}")
        return summary_filename

    except Exception as e:
        logger.error(f"Failed to save batch analysis results: {str(e)}")
        raise


@activity.defn
async def update_session_metadata(session_id: str, analysis_results: List[str]) -> None:
    """Update session metadata with analysis results information"""
    try:
        metadata_file = Path(f"data/{session_id}/session_data.json")

        if not metadata_file.exists():
            logger.warning(f"Session metadata file not found: {metadata_file}")
            return

        # Read existing metadata
        with open(metadata_file, 'r', encoding='utf-8') as f:
            metadata = json.load(f)

        # Update with analysis information
        if "analysis" not in metadata:
            metadata["analysis"] = {}

        metadata["analysis"].update({
            "last_analyzed": datetime.now().isoformat(),
            "result_files": analysis_results,
            "total_results": len(analysis_results)
        })

        # Save updated metadata
        with open(metadata_file, 'w', encoding='utf-8') as f:
            json.dump(metadata, f, indent=2, ensure_ascii=False)

        logger.info(f"Updated session metadata for {session_id}")

    except Exception as e:
        logger.error(f"Failed to update session metadata: {str(e)}")
        raise
